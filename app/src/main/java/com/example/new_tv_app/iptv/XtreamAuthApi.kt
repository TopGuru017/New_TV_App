package com.example.new_tv_app.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object XtreamAuthApi {

    /**
     * Validates credentials against Xtream [player_api.php] (no action — returns user_info / server_info).
     */
    suspend fun verify(baseUrl: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = baseUrl.trimEnd('/')
                val u = URLEncoder.encode(username.trim(), StandardCharsets.UTF_8.name())
                val p = URLEncoder.encode(password, StandardCharsets.UTF_8.name())
                val url = "$base/player_api.php?username=$u&password=$p"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 25_000
                conn.readTimeout = 25_000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                conn.disconnect()
                if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
                val root = JSONObject(text.trim())
                val user = root.optJSONObject("user_info")
                    ?: error(appStringFromJson(root) ?: "Invalid server response")
                if (user.optInt("auth", 0) != 1) {
                    error(user.optString("message").ifBlank { "Unauthorized" })
                }
            }
        }

    private fun appStringFromJson(root: JSONObject): String? {
        val ui = root.optJSONObject("user_info") ?: return null
        return ui.optString("message").takeIf { it.isNotBlank() }
    }
}
