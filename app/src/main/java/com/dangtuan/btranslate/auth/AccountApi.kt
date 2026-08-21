package com.dangtuan.btranslate.auth

import com.dangtuan.btranslate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AuthResult(
    val success: Boolean,
    val message: String,
    val sessionToken: String = "",
    val username: String = "",
    val status: String = ""
)

class AccountApi {
    suspend fun login(username: String, password: String) = authenticate("login", username, password)
    suspend fun register(username: String, password: String) = authenticate("register", username, password)

    suspend fun checkSession(token: String): AuthResult = post(
        JSONObject().put("action", "session").put("session_token", token)
    )

    private suspend fun authenticate(action: String, username: String, password: String): AuthResult = post(
        JSONObject()
            .put("action", action)
            .put("username", username.trim().lowercase())
            .put("password", password)
    )

    private suspend fun post(body: JSONObject): AuthResult = withContext(Dispatchers.IO) {
        if (BuildConfig.ACCOUNT_API_URL.isBlank()) {
            return@withContext AuthResult(false, "Chưa cấu hình ACCOUNT_API_URL trong local.properties.")
        }
        try {
            val connection = (URL(BuildConfig.ACCOUNT_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val json = JSONObject(stream?.bufferedReader()?.use { it.readText() }.orEmpty().ifBlank { "{}" })
            if (connection.responseCode == 401 && json.optString("code") == "UNAUTHORIZED_NO_AUTH_HEADER") {
                return@withContext AuthResult(
                    false,
                    "Máy chủ đăng nhập chưa được cấu hình đúng. Vui lòng cập nhật BOI Dịch lên bản mới nhất."
                )
            }
            val account = json.optJSONObject("account")
            AuthResult(
                success = json.optBoolean("success"),
                message = json.optString("message", if (connection.responseCode in 200..299) "Thành công." else "Yêu cầu thất bại."),
                sessionToken = json.optString("session_token"),
                username = account?.optString("username") ?: json.optString("username"),
                status = account?.optString("status") ?: json.optString("status")
            )
        } catch (error: Exception) {
            AuthResult(false, "Không kết nối được máy chủ tài khoản: ${error.message ?: "lỗi mạng"}")
        }
    }
}
