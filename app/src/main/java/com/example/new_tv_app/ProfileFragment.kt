package com.example.new_tv_app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.new_tv_app.iptv.PlayerAccount
import com.example.new_tv_app.iptv.PlayerApiFetcher
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val progress = view.findViewById<ProgressBar>(R.id.profile_progress)
        val error = view.findViewById<TextView>(R.id.profile_error)

        val fullName = view.findViewById<TextView>(R.id.profile_full_name_value)
        val avatarInitial = view.findViewById<TextView>(R.id.profile_avatar_initial)
        val email = view.findViewById<TextView>(R.id.profile_email_value)
        val phone = view.findViewById<TextView>(R.id.profile_phone_value)
        val connections = view.findViewById<TextView>(R.id.profile_connections_value)
        val status = view.findViewById<TextView>(R.id.profile_subscription_status_value)
        val pkg = view.findViewById<TextView>(R.id.profile_existing_package_value)
        val validUntil = view.findViewById<TextView>(R.id.profile_valid_until_value)
        val daysRemaining = view.findViewById<TextView>(R.id.profile_days_remaining)
        val memberSince = view.findViewById<TextView>(R.id.profile_member_since_value)
        val siteUrl = view.findViewById<TextView>(R.id.profile_site_url)

        fun showLoading(loading: Boolean) {
            progress.isVisible = loading
            error.isVisible = false
        }

        fun showError(message: String) {
            error.text = message
            error.isVisible = true
            progress.isVisible = false
        }

        showLoading(true)
        lifecycleScope.launch {
            val result = PlayerApiFetcher.fetchAccount()
            showLoading(false)
            result.fold(
                onSuccess = { account -> bindAccount(view, account) },
                onFailure = {
                    showError(getString(R.string.profile_load_error))
                    val na = getString(R.string.profile_not_available)
                    fullName.text = na
                    avatarInitial.text = "?"
                    email.text = na
                    phone.text = na
                    connections.text = na
                    status.text = na
                    pkg.text = na
                    validUntil.text = na
                    daysRemaining.text = ""
                    memberSince.text = na
                    siteUrl.text = BuildConfig.IPTV_BASE_URL
                    siteUrl.setOnClickListener {
                        openUrl(BuildConfig.IPTV_BASE_URL)
                    }
                }
            )
        }
    }

    private fun bindAccount(root: View, account: PlayerAccount) {
        val ctx = requireContext()
        val na = getString(R.string.profile_not_available)

        val fullName = root.findViewById<TextView>(R.id.profile_full_name_value)
        val avatarInitial = root.findViewById<TextView>(R.id.profile_avatar_initial)
        fullName.text = account.username
        avatarInitial.text = firstLetterFromName(account.username)

        root.findViewById<TextView>(R.id.profile_email_value).text = na
        root.findViewById<TextView>(R.id.profile_phone_value).text = na

        root.findViewById<TextView>(R.id.profile_connections_value).text =
            getString(
                R.string.profile_connections_summary,
                account.activeConnections,
                account.maxConnections
            )

        val statusView = root.findViewById<TextView>(R.id.profile_subscription_status_value)
        statusView.text = account.status
        val active = account.status.equals("Active", ignoreCase = true)
        statusView.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (active) R.color.profile_status_active else R.color.profile_status_inactive
            )
        )

        val packageText = buildString {
            append(
                if (account.isTrial) {
                    getString(R.string.profile_package_trial)
                } else {
                    getString(R.string.profile_package_full)
                }
            )
            if (account.allowedOutputFormats.isNotEmpty()) {
                append(" — ")
                append(account.allowedOutputFormats.joinToString(", "))
            }
        }
        root.findViewById<TextView>(R.id.profile_existing_package_value).text = packageText

        val validUntilView = root.findViewById<TextView>(R.id.profile_valid_until_value)
        val exp = account.expDateUnix
        if (exp != null) {
            validUntilView.text = formatUnixSeconds(exp)
            root.findViewById<TextView>(R.id.profile_days_remaining).text =
                daysRemainingLabel(exp, account.serverNowUnix)
        } else {
            validUntilView.text = na
            root.findViewById<TextView>(R.id.profile_days_remaining).text = ""
        }

        val created = account.createdAtUnix
        root.findViewById<TextView>(R.id.profile_member_since_value).text =
            if (created != null) formatDateOnly(created) else na

        val site = "${account.serverProtocol}://${account.serverUrl}"
        val siteUrl = root.findViewById<TextView>(R.id.profile_site_url)
        siteUrl.text = site
        siteUrl.setOnClickListener { openUrl(site) }
    }

    private fun openUrl(url: String) {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
    }

    private fun formatUnixSeconds(tsSeconds: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss dd-MM-yyyy", Locale.getDefault())
        return sdf.format(Date(tsSeconds * 1000L))
    }

    private fun formatDateOnly(tsSeconds: Long): String {
        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        return sdf.format(Date(tsSeconds * 1000L))
    }

    private fun daysRemainingLabel(expSeconds: Long, nowSeconds: Long): String {
        val days = ((expSeconds - nowSeconds) / 86400L).toInt()
        return when {
            days < 0 -> getString(R.string.profile_expired)
            days == 0 -> getString(R.string.profile_expires_today)
            else -> getString(R.string.profile_days_remaining_template, days)
        }
    }

    private fun firstLetterFromName(name: String): String {
        for (ch in name) {
            if (ch.isLetter()) return ch.uppercaseChar().toString()
        }
        return "?"
    }
}
