package com.example.new_tv_app.iptv

import com.example.new_tv_app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object PlayerApiFetcher {

    suspend fun fetchAccount(): Result<PlayerAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val base = BuildConfig.IPTV_BASE_URL.trimEnd('/')
            val u = URLEncoder.encode(BuildConfig.IPTV_USERNAME, StandardCharsets.UTF_8.name())
            val p = URLEncoder.encode(BuildConfig.IPTV_PASSWORD, StandardCharsets.UTF_8.name())
            val apiUrl = "$base/player_api.php?username=$u&password=$p"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()
            if (code !in 200..299) error("HTTP $code: ${text.take(200)}")
            parse(text)
        }
    }

    private fun parse(json: String): PlayerAccount {
        val root = JSONObject(json)
        val user = root.optJSONObject("user_info") ?: error("Missing user_info")
        if (user.optInt("auth", 0) != 1) {
            val msg = user.optString("message").ifBlank { "Login failed" }
            error(msg)
        }
        val server = root.optJSONObject("server_info") ?: JSONObject()

        val formatsJson = user.optJSONArray("allowed_output_formats")
        val formats = buildList {
            if (formatsJson != null) {
                for (i in 0 until formatsJson.length()) {
                    add(formatsJson.optString(i))
                }
            }
        }

        val serverUrl = server.optString("url", "").ifBlank {
            BuildConfig.IPTV_BASE_URL
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
        }

        return PlayerAccount(
            username = user.getString("username"),
            status = user.optString("status", "—"),
            expDateUnix = user.optString("exp_date", "").toLongOrNull(),
            serverNowUnix = server.optLong("timestamp_now", System.currentTimeMillis() / 1000L),
            isTrial = user.optString("is_trial", "0") == "1",
            maxConnections = user.optString("max_connections", "0").toIntOrNull() ?: 0,
            activeConnections = user.optString("active_cons", "0").toIntOrNull() ?: 0,
            allowedOutputFormats = formats,
            createdAtUnix = user.optString("created_at", "").toLongOrNull(),
            serverUrl = serverUrl,
            serverProtocol = server.optString("server_protocol", "https").ifBlank { "https" },
        )
    }
}
