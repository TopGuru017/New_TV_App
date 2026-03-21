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

    private const val PREFS_NAME = "iptv_auth"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
}
