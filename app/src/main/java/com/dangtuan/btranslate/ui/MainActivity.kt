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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dangtuan.btranslate.overlay.OverlayService

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var captureRequested = false

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
            status.text = "Bạn cần cho phép chụp màn hình để dịch chữ."
        }
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureRequested = intent?.action == ACTION_REQUEST_CAPTURE
        buildContent()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
    }

    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this)) {
            status.text = "B Dịch cần quyền “Hiển thị trên ứng dụng khác”."
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        status.text = "Nút B đang hoạt động. Bạn có thể đóng màn hình này."
        requestCaptureIfReady()
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
            text = "Đang chuẩn bị nút B…"
        }
        val permission = Button(this).apply {
            text = "Cho phép nút B nổi"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val open = Button(this).apply {
            text = "Bật nút B"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
                    finish()
                } else permission.performClick()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
            addView(TextView(this@MainActivity).apply { text = "B Dịch"; textSize = 30f })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(permission, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(open, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
        })
    }

    companion object { const val ACTION_REQUEST_CAPTURE = "com.dangtuan.btranslate.REQUEST_CAPTURE" }
}
