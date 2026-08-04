package com.dangtuan.btranslate.update

import com.dangtuan.btranslate.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

class UpdateChecker {
    fun check(): UpdateInfo? {
        val connection = (URL(BuildConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("Máy chủ cập nhật trả về mã ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val versionCode = json.getInt("versionCode")
            val versionName = json.getString("versionName").trim()
            require(versionName.isNotBlank()) { "Thiếu tên phiên bản" }
            if (versionCode <= BuildConfig.VERSION_CODE) return null
            val info = UpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = json.getString("apkUrl").trim(),
                sha256 = json.optString("sha256").trim().lowercase(),
                notes = json.optString("notes").trim()
            )
            require(info.apkUrl.startsWith("https://")) { "Đường dẫn APK không an toàn" }
            return info
        } finally {
            connection.disconnect()
        }
    }
}
