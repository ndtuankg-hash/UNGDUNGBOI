package com.dangtuan.btranslate.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.dangtuan.btranslate.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var captureRequested = false
    private var updateRequested = false
    private var updateDownloadStarted = false
    private var installPermissionOpened = false
    private var notificationPermissionPending = false
    private var updateUrl = ""
    private var updateVersion = ""
    private var updateSha256 = ""

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_START_CAPTURE)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            )
            finish()
        } else {
            captureRequested = false
            status.text = "Bạn cần cho phép chia sẻ màn hình để dịch chữ."
        }
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermissionPending = false
        if (Settings.canDrawOverlays(this) && !captureRequested && !updateRequested) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureRequested = intent?.action == ACTION_REQUEST_CAPTURE
        readUpdateRequest(intent)
        buildContent()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionPending = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_REQUEST_CAPTURE) {
            captureRequested = true
            requestCaptureIfReady()
        }
        readUpdateRequest(intent)
        if (intent.action == ACTION_INSTALL_UPDATE) continueUpdateIfReady()
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Nhấn “Cho phép nút BOI nổi” để cấp quyền hiển thị trên ứng dụng khác."
            return
        }
        if (updateRequested) {
            continueUpdateIfReady()
            return
        }
        if (captureRequested) {
            requestCaptureIfReady()
            return
        }

        // Khi đã có quyền phủ màn hình, mở thẳng bảng điều khiển BOI.
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_OPEN_PANEL))
        if (!notificationPermissionPending) finish()
    }

    private fun readUpdateRequest(source: Intent?) {
        if (source?.action != ACTION_INSTALL_UPDATE) return
        updateRequested = true
        updateUrl = source.getStringExtra(EXTRA_APK_URL).orEmpty()
        updateVersion = source.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        updateSha256 = source.getStringExtra(EXTRA_SHA256).orEmpty().lowercase()
    }

    private fun continueUpdateIfReady() {
        if (updateUrl.isBlank() || !updateUrl.startsWith("https://")) {
            status.text = "Đường dẫn cập nhật không hợp lệ."
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "Hãy bật “Cho phép từ nguồn này”, rồi quay lại BOI Dịch."
            if (!installPermissionOpened) {
                installPermissionOpened = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        if (!updateDownloadStarted) downloadAndInstallUpdate()
    }

    private fun downloadAndInstallUpdate() {
        updateDownloadStarted = true
        status.text = "Đang tải BOI Dịch $updateVersion…"
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { downloadUpdateApk() }
                status.text = "Đã tải xong. Hãy nhấn Cài đặt trên màn hình Android."
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", apk)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (error: Exception) {
                updateDownloadStarted = false
                status.text = "Chưa tải được bản cập nhật: ${error.message ?: "lỗi tải tệp"}"
            }
        }
    }

    private fun downloadUpdateApk(): File {
        val directory = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
        val output = File(directory, "BOI-Dich-Android-$updateVersion.apk")
        val connection = (URL(updateUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive")
        }
        try {
            if (connection.responseCode !in 200..299) error("máy chủ trả về mã ${connection.responseCode}")
            connection.inputStream.use { input -> output.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        require(output.length() > 0) { "tệp APK rỗng" }
        if (updateSha256.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            output.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == updateSha256) { "tệp tải về không đúng mã kiểm tra" }
        }
        return output
    }

    private fun requestCaptureIfReady() {
        if (!captureRequested || !Settings.canDrawOverlays(this)) return
        captureRequested = false
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }

    private fun buildContent() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            textSize = 18f
            text = "Đang chuẩn bị BOI…"
        }
        val permission = Button(this).apply {
            text = "Cho phép nút BOI nổi"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(com.dangtuan.btranslate.R.mipmap.ic_launcher)
                contentDescription = "Avatar BOI Dịch"
            }, LinearLayout.LayoutParams(padding * 4, padding * 4))
            addView(TextView(this@MainActivity).apply { text = "BOI Dịch"; textSize = 30f })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(permission, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
        })
    }

    companion object {
        const val ACTION_REQUEST_CAPTURE = "com.dangtuan.btranslate.REQUEST_CAPTURE"
        const val ACTION_INSTALL_UPDATE = "com.dangtuan.btranslate.INSTALL_UPDATE"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_VERSION_NAME = "version_name"
        const val EXTRA_SHA256 = "sha256"
    }
}
