package com.dangtuan.btranslate.overlay

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dangtuan.btranslate.BuildConfig
import com.dangtuan.btranslate.R
import com.dangtuan.btranslate.auth.AccountApi
import com.dangtuan.btranslate.auth.TokenStore
import com.dangtuan.btranslate.translation.LanguageCatalog
import com.dangtuan.btranslate.translation.LanguageOption
import com.dangtuan.btranslate.translation.ScreenCaptureController
import com.dangtuan.btranslate.translation.TranslatedLine
import com.dangtuan.btranslate.translation.TranslationEngine
import com.dangtuan.btranslate.ui.MainActivity
import com.dangtuan.btranslate.update.UpdateChecker
import com.dangtuan.btranslate.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
class OverlayService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var bubble: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private var translationLayer: FrameLayout? = null
    private var captureController: ScreenCaptureController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val accountApi = AccountApi()
    private lateinit var tokenStore: TokenStore
    private val translator = TranslationEngine()
    private val updateChecker = UpdateChecker()
    private var authenticated = false
    private var checkingSession = false
    private var continuous = false
    private var continuousJob: Job? = null
    private var translating = false
    private var waitingAnimator: ValueAnimator? = null
    private var latestUpdate: UpdateInfo? = null
    private var updateChecked = false
    private var source = LanguageCatalog.sources.first()
    private var target = LanguageCatalog.targets.first { it.code == "vi" }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        windows = getSystemService(WindowManager::class.java)
        tokenStore = TokenStore(this)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showBubble()
        validateSavedSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START_CAPTURE -> {
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data != null) startCapture(resultCode, data)
            }
            ACTION_SHOW, null -> if (!::bubble.isInitialized && Settings.canDrawOverlays(this)) showBubble()
        }
        // Nếu Android đã dọn tiến trình thì quyền chụp màn hình cũ cũng không còn.
        // Không tự dựng lại một nút B không thể dịch được.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = screenSize()
        captureController?.resize(w, h)
        if (::bubbleParams.isInitialized) {
            bubbleParams.x = bubbleParams.x.coerceIn(0, max(0, w - dp(58)))
            bubbleParams.y = bubbleParams.y.coerceIn(0, max(0, h - dp(58)))
            runCatching { windows.updateViewLayout(bubble, bubbleParams) }
        }
        removeTranslationLayer()
        panel?.let {
            closePanel()
            if (authenticated) showControlPanel() else showAuthPanel()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopWaitingAnimation(showB = false)
        continuousJob?.cancel()
        captureController?.close()
        captureController = null
        closePanel()
        removeTranslationLayer()
        if (::bubble.isInitialized) runCatching { windows.removeView(bubble) }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showBubble() {
        if (::bubble.isInitialized) return
        bubble = TextView(this).apply {
            text = "B"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(8).toFloat()
            background = rounded(Color.rgb(43, 103, 246), dp(29).toFloat())
            setOnTouchListener(BubbleTouch())
        }
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        bubbleParams = WindowManager.LayoutParams(
            dp(58), dp(58), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", dp(12))
            y = prefs.getInt("bubble_y", dp(160))
        }
        windows.addView(bubble, bubbleParams)
        scheduleFade()
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressTriggered = false
        private val longPress = Runnable {
            if (!moved) {
                longPressTriggered = true
                if (panel == null) {
                    removeTranslationLayer()
                    openPanel()
                } else {
                    closePanel()
                }
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacksAndMessages(FADE_TOKEN)
                    bubble.alpha = 1f
                    downX = event.rawX; downY = event.rawY
                    startX = bubbleParams.x; startY = bubbleParams.y
                    moved = false
                    longPressTriggered = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        val (w, h) = screenSize()
                        bubbleParams.x = (startX + dx.toInt()).coerceIn(0, max(0, w - bubble.width))
                        bubbleParams.y = (startY + dy.toInt()).coerceIn(0, max(0, h - bubble.height))
                        windows.updateViewLayout(bubble, bubbleParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    stopWaitingAnimation()
                    if (moved) {
                        snapBubbleToEdge()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !longPressTriggered) {
                        closePanel()
                        startWaitingAnimation()
                        translateOnce()
                    }
                    scheduleFade()
                    return true
                }
            }
            return false
        }
    }

    private fun snapBubbleToEdge() {
        val (w, _) = screenSize()
        bubbleParams.x = if (bubbleParams.x + bubble.width / 2 < w / 2) dp(6) else max(dp(6), w - bubble.width - dp(6))
        windows.updateViewLayout(bubble, bubbleParams)
        getSharedPreferences("overlay", MODE_PRIVATE).edit()
            .putInt("bubble_x", bubbleParams.x).putInt("bubble_y", bubbleParams.y).apply()
    }

    private fun scheduleFade() {
        handler.removeCallbacksAndMessages(FADE_TOKEN)
        handler.postAtTime({ if (::bubble.isInitialized) bubble.animate().alpha(0.38f).setDuration(250).start() }, FADE_TOKEN, android.os.SystemClock.uptimeMillis() + 2_000)
    }

    private fun startWaitingAnimation() {
        waitingAnimator?.cancel()
        waitingAnimator = ValueAnimator.ofInt(0, WAITING_DOT_LIFTS.lastIndex).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                if (!::bubble.isInitialized) return@addUpdateListener
                val lifts = WAITING_DOT_LIFTS[animator.animatedValue as Int]
                bubble.text = SpannableString(". . .").apply {
                    DOT_POSITIONS.forEachIndexed { index, position ->
                        val lift = lifts[index]
                        if (lift > 0) {
                            setSpan(
                                LiftSpan(dp(lift)),
                                position,
                                position + 1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
            }
            start()
        }
    }

    private fun stopWaitingAnimation(showB: Boolean = true) {
        waitingAnimator?.cancel()
        waitingAnimator = null
        if (showB && ::bubble.isInitialized) bubble.text = "B"
    }

    private fun openPanel() {
        if (panel == null) {
            if (authenticated) showControlPanel() else showAuthPanel()
        }
    }

    private fun showAuthPanel() {
        val root = panelRoot()
        val title = title("Đăng nhập B Dịch")
        val username = EditText(this).apply { hint = "Tên tài khoản"; isSingleLine = true }
        val password = EditText(this).apply {
            hint = "Mật khẩu"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val status = TextView(this).apply { setTextColor(Color.DKGRAY); textSize = 14f }
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        val login = Button(this).apply { text = "Đăng nhập" }
        val register = Button(this).apply { text = "Đăng ký" }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(login, LinearLayout.LayoutParams(0, -2, 1f))
            addView(register, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }
        fun submit(isRegister: Boolean) {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) { status.text = "Vui lòng nhập tài khoản và mật khẩu."; return }
            login.isEnabled = false; register.isEnabled = false; progress.visibility = View.VISIBLE
            serviceScope.launch {
                val result = if (isRegister) accountApi.register(name, pass) else accountApi.login(name, pass)
                if (result.success && result.sessionToken.isNotBlank()) {
                    tokenStore.save(result.sessionToken)
                    authenticated = true
                    closePanel()
                    showControlPanel()
                    toast(result.message)
                } else {
                    status.text = result.message
                    login.isEnabled = true; register.isEnabled = true; progress.visibility = View.GONE
                }
            }
        }
        login.setOnClickListener { submit(false) }
        register.setOnClickListener { submit(true) }
        root.addView(title)
        root.addView(username)
        root.addView(password)
        root.addView(status, margins(top = 4))
        root.addView(progress, centered())
        root.addView(actions, margins(top = 8))
        root.addView(closePanelButton(), margins(top = 10))
        root.addView(exitButton(), margins(top = 10))
        showPanel(root)
    }

    private fun showControlPanel() {
        val root = panelRoot()
        val prefs = getSharedPreferences("translation", MODE_PRIVATE)
        source = LanguageCatalog.sources[prefs.getInt("source", 0).coerceIn(LanguageCatalog.sources.indices)]
        target = LanguageCatalog.targets[prefs.getInt("target", 1).coerceIn(LanguageCatalog.targets.indices)]
        val sourceSpinner = languageSpinner(LanguageCatalog.sources, LanguageCatalog.sources.indexOf(source)) { index ->
            source = LanguageCatalog.sources[index]; prefs.edit().putInt("source", index).apply(); removeTranslationLayer()
        }
        val targetSpinner = languageSpinner(LanguageCatalog.targets, LanguageCatalog.targets.indexOf(target)) { index ->
            target = LanguageCatalog.targets[index]; prefs.edit().putInt("target", index).apply(); removeTranslationLayer()
        }
        val continuousSwitch = Switch(this).apply {
            text = "Dịch liên tục"
            isChecked = continuous
            setOnCheckedChangeListener { _, checked ->
                continuous = checked
                if (checked) {
                    closePanel()
                    startContinuous()
                } else continuousJob?.cancel()
            }
        }
        root.addView(title("Cài đặt dịch"))
        root.addView(label("Ngôn ngữ cần dịch"), margins(top = 8))
        root.addView(sourceSpinner)
        root.addView(label("Ngôn ngữ sử dụng"), margins(top = 8))
        root.addView(targetSpinner)
        root.addView(continuousSwitch, margins(top = 10))
        root.addView(TextView(this).apply {
            text = "Chạm B: dịch một lần.\nGiữ B: mở hoặc đóng bảng.\nGiữ rồi kéo: di chuyển nút B."
            textSize = 13f; setTextColor(Color.DKGRAY)
        }, margins(top = 4))
        val updateStatus = TextView(this).apply {
            textSize = 13f; setTextColor(Color.DKGRAY)
        }
        val updateButton = Button(this).apply {
            text = "Kiểm tra phiên bản mới"
            setOnClickListener {
                checkForUpdate(this, updateStatus, showCurrentToast = true)
            }
        }
        showUpdateStatus(updateStatus, latestUpdate)
        root.addView(updateStatus, margins(top = 12))
        root.addView(updateButton, margins(top = 4))
        root.addView(closePanelButton(), margins(top = 12))
        root.addView(exitButton(), margins(top = 12))
        showPanel(root)
        if (!updateChecked) checkForUpdate(updateButton, updateStatus, showCurrentToast = false)
    }

    private fun checkForUpdate(button: Button, status: TextView, showCurrentToast: Boolean) {
        button.isEnabled = false
        button.text = "Đang kiểm tra…"
        serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { updateChecker.check() }
                updateChecked = true
                latestUpdate = result
                if (!button.isAttachedToWindow) return@launch
                button.isEnabled = true
                button.text = "Kiểm tra phiên bản mới"
                showUpdateStatus(status, result)
                if (result == null && showCurrentToast) toast("Bạn đang dùng bản mới nhất.")
            } catch (error: Exception) {
                if (!button.isAttachedToWindow) return@launch
                button.isEnabled = true
                button.text = "Kiểm tra phiên bản mới"
                showUpdateStatus(status, latestUpdate)
                if (showCurrentToast) toast(error.message ?: "Lỗi kiểm tra cập nhật")
            }
        }
    }

    private fun showUpdateStatus(status: TextView, update: UpdateInfo?) {
        status.setTextColor(if (update == null) Color.DKGRAY else Color.rgb(0, 102, 204))
        status.text = if (update == null) {
            "Phiên bản hiện tại: ${BuildConfig.VERSION_NAME}"
        } else {
            "Đã có phiên bản mới"
        }
        status.isClickable = update != null
        status.setOnClickListener(if (update == null) null else View.OnClickListener { startUpdate(update) })
    }

    private fun startUpdate(info: UpdateInfo) {
        closePanel()
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_INSTALL_UPDATE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_APK_URL, info.apkUrl)
            putExtra(MainActivity.EXTRA_VERSION_NAME, info.versionName)
            putExtra(MainActivity.EXTRA_SHA256, info.sha256)
        })
    }

    private fun validateSavedSession() {
        val token = tokenStore.load()
        if (token.isBlank()) return
        checkingSession = true
        serviceScope.launch {
            val result = accountApi.checkSession(token)
            checkingSession = false
            authenticated = result.success
            if (!result.success) tokenStore.clear()
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
        captureController?.close()
        val (w, h) = screenSize()
        captureController = ScreenCaptureController(
            this,
            resultCode,
            data,
            w,
            h,
            resources.displayMetrics.densityDpi
        ) {
            handler.post { stopBecauseCaptureDisconnected() }
        }
        toast("Đã bật quyền dịch màn hình.")
        if (continuous) startContinuous() else translateOnce()
    }

    private fun ensureCapture(): Boolean {
        if (captureController != null) return true
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_REQUEST_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        toast("Cho phép chụp màn hình để bắt đầu dịch.")
        return false
    }

    private fun translateOnce() {
        if (!authenticated) { showAuthPanel(); return }
        if (translating || !ensureCapture()) return
        translating = true
        serviceScope.launch {
            try {
                translationLayer?.visibility = View.GONE
                bubble.alpha = 0f
                delay(80)
                val controller = captureController ?: return@launch
                val bitmap = withTimeout(CAPTURE_TIMEOUT_MS) { controller.capture() }
                bubble.alpha = 1f
                val result = withContext(Dispatchers.Default) { translator.translate(bitmap, source, target) }
                bitmap.recycle()
                renderTranslations(result)
            } catch (_: TimeoutCancellationException) {
                // Sau khi tắt màn hình lâu, MediaProjection đôi khi không báo onStop
                // nhưng cũng không cấp ảnh mới. Khi đó nút B phải biến mất thay vì
                // mắc kẹt ở dấu ba chấm.
                stopBecauseCaptureDisconnected()
            } catch (error: Exception) {
                toast("Chưa dịch được: ${error.message ?: "lỗi xử lý"}")
            } finally {
                translating = false
                stopWaitingAnimation()
                bubble.alpha = 1f
                scheduleFade()
            }
        }
    }

    private fun stopBecauseCaptureDisconnected() {
        if (!::bubble.isInitialized) return
        stopWaitingAnimation(showB = false)
        continuous = false
        continuousJob?.cancel()
        continuousJob = null
        captureController?.close()
        captureController = null
        stopSelf()
    }

    private fun startContinuous() {
        if (!ensureCapture()) return
        continuousJob?.cancel()
        continuousJob = serviceScope.launch {
            while (isActive && continuous) {
                translateOnce()
                delay(1_400)
            }
        }
    }

    private fun renderTranslations(lines: List<TranslatedLine>) {
        removeTranslationLayer()
        if (lines.isEmpty()) return
        val layer = FrameLayout(this).apply {
            addView(TranslationCanvasView(lines), FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            ))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layer.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) removeTranslationLayer()
            true
        }
        windows.addView(layer, params)
        translationLayer = layer
        runCatching {
            windows.removeView(bubble)
            windows.addView(bubble, bubbleParams)
        }
    }

    private fun removeTranslationLayer() {
        translationLayer?.let { layer ->
            (layer.getChildAt(0) as? TranslationCanvasView)?.release()
            runCatching { windows.removeView(layer) }
        }
        translationLayer = null
    }

    /**
     * Vẽ tất cả kết quả trên một canvas toàn màn hình. Mỗi TextLine có đúng một
     * nền đã tái tạo và một lần vẽ chữ, nên không còn các TextView nhỏ bị lệch.
     */
    private inner class TranslationCanvasView(
        private val lines: List<TranslatedLine>
    ) : View(this@OverlayService) {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.DEFAULT
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            lines.forEach { line ->
                if (line.background.isRecycled) return@forEach
                canvas.drawBitmap(line.background, null, RectF(line.backgroundBox), backgroundPaint)
                drawFittedLine(canvas, line)
            }
        }

        private fun drawFittedLine(canvas: Canvas, line: TranslatedLine) {
            val box = RectF(line.box)
            val availableWidth = max(1f, box.width() - max(2f, box.height() * 0.08f))
            val availableHeight = max(1f, box.height() * 0.90f)
            textPaint.color = line.textColor
            textPaint.textSize = fittedTextSize(line.text, availableWidth, availableHeight)

            val metrics = textPaint.fontMetrics
            val baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(line.text, box.left, baseline, textPaint)
        }

        private fun fittedTextSize(text: String, availableWidth: Float, availableHeight: Float): Float {
            var low = max(5f, availableHeight * 0.28f)
            var high = max(low, availableHeight)
            repeat(12) {
                val candidate = (low + high) / 2f
                textPaint.textSize = candidate
                if (textPaint.measureText(text) <= availableWidth) low = candidate else high = candidate
            }
            return low
        }

        fun release() {
            lines.forEach { line ->
                if (!line.background.isRecycled) line.background.recycle()
            }
        }
    }

    private fun showPanel(content: View) {
        closePanel()
        val (screenW, screenH) = screenSize()
        val scroll = ScrollView(this).apply { addView(content) }
        val params = WindowManager.LayoutParams(
            min(screenW - dp(24), dp(370)), min(screenH - dp(36), dp(580)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windows.addView(scroll, params)
        panel = scroll
    }

    private fun closePanel() {
        panel?.let { runCatching { windows.removeView(it) } }
        panel = null
    }

    private fun panelRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = rounded(Color.rgb(248, 249, 252), dp(18).toFloat())
    }

    private fun title(value: String) = TextView(this).apply { text = value; textSize = 22f; setTextColor(Color.BLACK) }
    private fun label(value: String) = TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.DKGRAY) }

    private fun languageSpinner(options: List<LanguageOption>, selected: Int, changed: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
        setSelection(selected)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = changed(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun exitButton() = Button(this).apply {
        text = "Tắt B"
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(190, 35, 46), dp(10).toFloat())
        setOnClickListener { stopSelf() }
    }

    private fun closePanelButton() = Button(this).apply {
        text = "Đóng bảng"
        setOnClickListener { closePanel() }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius }
    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun centered() = LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = DisplayMetrics()
            windows.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ).build()

    companion object {
        const val ACTION_SHOW = "com.dangtuan.btranslate.SHOW"
        const val ACTION_STOP = "com.dangtuan.btranslate.STOP"
        const val ACTION_START_CAPTURE = "com.dangtuan.btranslate.START_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "b_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private val FADE_TOKEN = Any()
        private val DOT_POSITIONS = intArrayOf(0, 2, 4)
        private val WAITING_DOT_LIFTS = arrayOf(
            intArrayOf(0, 0, 0),
            intArrayOf(6, 0, 0),
            intArrayOf(2, 6, 0),
            intArrayOf(0, 2, 6),
            intArrayOf(0, 0, 2),
            intArrayOf(0, 0, 0)
        )
    }

    private class LiftSpan(private val liftPx: Int) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = kotlin.math.ceil(paint.measureText(text, start, end).toDouble()).toInt()

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            canvas.drawText(text, start, end, x, y - liftPx.toFloat(), paint)
        }
    }
}
