package com.example.new_tv_app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.new_tv_app.iptv.IptvCredentials
import com.example.new_tv_app.iptv.XtreamAuthApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xtream-Code sign-in; on success opens [MainActivity] on Live TV.
 */
class LoginActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val username = findViewById<EditText>(R.id.login_username)
        val password = findViewById<EditText>(R.id.login_password)
        val loginBtn = findViewById<Button>(R.id.login_submit)
        val qrBtn = findViewById<Button>(R.id.login_qr)
        val resetBtn = findViewById<Button>(R.id.login_reset)
        val error = findViewById<TextView>(R.id.login_error)
        val progress = findViewById<ProgressBar>(R.id.login_progress)
        val langHe = findViewById<ImageButton>(R.id.login_lang_he)
        val langEn = findViewById<ImageButton>(R.id.login_lang_en)

        findViewById<TextView>(R.id.login_build).text = getString(
            R.string.login_build_fmt,
            SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()),
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        if (IptvCredentials.usernameRaw().isNotEmpty()) {
            username.setText(IptvCredentials.usernameRaw())
            password.setText(IptvCredentials.passwordRaw())
        }

        fun setLoading(loading: Boolean) {
            progress.isVisible = loading
            loginBtn.isEnabled = !loading
            qrBtn.isEnabled = !loading
            resetBtn.isEnabled = !loading
            langHe.isEnabled = !loading
            langEn.isEnabled = !loading
            username.isEnabled = !loading
            password.isEnabled = !loading
        }

        fun showError(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
        }

        fun goToApp() {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_LIVE, true)
                },
            )
            finish()
        }

        fun attemptLogin() {
            error.visibility = View.GONE
            val u = username.text?.toString().orEmpty().trim()
            val p = password.text?.toString().orEmpty()
            if (u.isEmpty() || p.isEmpty()) {
                showError(getString(R.string.login_error_empty))
                return
            }
            setLoading(true)
            lifecycleScope.launch {
                val result = XtreamAuthApi.verify(IptvCredentials.baseUrl(), u, p)
                setLoading(false)
                result.fold(
                    onSuccess = {
                        IptvCredentials.save(u, p)
                        goToApp()
                    },
                    onFailure = { e ->
                        showError(
                            e.message?.takeIf { it.isNotBlank() }
                                ?: getString(R.string.login_error_failed),
                        )
                    },
                )
            }
        }

        loginBtn.setOnClickListener { attemptLogin() }
        qrBtn.setOnClickListener {
            Toast.makeText(this, R.string.login_qr_toast, Toast.LENGTH_SHORT).show()
        }
        resetBtn.setOnClickListener {
            Toast.makeText(this, R.string.login_reset_toast, Toast.LENGTH_SHORT).show()
        }

        langHe.setOnClickListener {
            Toast.makeText(this, R.string.login_lang_hebrew, Toast.LENGTH_SHORT).show()
        }
        langEn.setOnClickListener {
            Toast.makeText(this, R.string.login_lang_english, Toast.LENGTH_SHORT).show()
        }

        if (savedInstanceState == null && IptvCredentials.isLoggedIn()) {
            setLoading(true)
            lifecycleScope.launch {
                val result = XtreamAuthApi.verify(
                    IptvCredentials.baseUrl(),
                    IptvCredentials.usernameRaw(),
                    IptvCredentials.passwordRaw(),
                )
                setLoading(false)
                if (result.isSuccess) {
                    goToApp()
                } else {
                    username.requestFocus()
                }
            }
        } else {
            username.post { username.requestFocus() }
        }
    }
}
