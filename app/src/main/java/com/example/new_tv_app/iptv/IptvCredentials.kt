package com.example.new_tv_app.iptv

import android.content.Context
import com.example.new_tv_app.BuildConfig

/**
 * Xtream username/password after login. [BuildConfig] values are only used as URL fallback for server address.
 */
object IptvCredentials {

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val prefs
        get() = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    fun isLoggedIn(): Boolean =
        usernameRaw().isNotEmpty() && passwordRaw().isNotEmpty()

    fun usernameRaw(): String = prefs.getString(KEY_USERNAME, null)?.trim().orEmpty()

    fun passwordRaw(): String = prefs.getString(KEY_PASSWORD, null).orEmpty()

    /** Panel URL (still from build config until a server field is added on login). */
    fun baseUrl(): String = BuildConfig.IPTV_BASE_URL.trimEnd('/')

    /**
     * Last verified panel URL (scheme-sensitive) from runtime API checks.
     * If empty, falls back to [baseUrl].
     */
    fun preferredBaseUrl(): String =
        prefs.getString(KEY_LAST_WORKING_BASE_URL, null)?.trim()?.ifBlank { null } ?: baseUrl()

    fun markWorkingBaseUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_LAST_WORKING_BASE_URL, clean).apply()
    }

    /** Candidate URLs to try in order (preferred, configured, then scheme fallback). */
    fun candidateBaseUrls(): List<String> {
        val out = LinkedHashSet<String>()
        val preferred = preferredBaseUrl().trimEnd('/')
        val configured = baseUrl().trimEnd('/')
        if (preferred.isNotBlank()) out.add(preferred)
        if (configured.isNotBlank()) out.add(configured)
        for (u in listOf(preferred, configured)) {
            val alt = alternateScheme(u)
            if (alt != null) out.add(alt)
        }
        return out.toList()
    }

    private fun alternateScheme(url: String): String? {
        return when {
            url.startsWith("https://", ignoreCase = true) ->
                "http://${url.removePrefix("https://")}"
            url.startsWith("http://", ignoreCase = true) ->
                "https://${url.removePrefix("http://")}"
            else -> null
        }?.trimEnd('/')
    }

    private const val PREFS_NAME = "iptv_auth"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_LAST_WORKING_BASE_URL = "last_working_base_url"
}
