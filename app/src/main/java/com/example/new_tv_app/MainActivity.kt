package com.example.new_tv_app

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.example.new_tv_app.iptv.IptvCredentials
import com.example.new_tv_app.ui.sidebar.IptvSidebarView
import java.util.Locale

/**
 * Shell with [IptvSidebarView] and main content [HomeContentFragment] / [ProfileFragment].
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val sidebar = findViewById<IptvSidebarView>(R.id.iptv_sidebar)
        val mainContent = findViewById<View>(R.id.main_content)
        val root = window.decorView.findViewById<View>(android.R.id.content)
        sidebar.attachAutoExpandCollapse(root, mainContent)
        val displayName = IptvCredentials.usernameRaw().trim().ifEmpty {
            getString(R.string.profile_display_name)
        }
        sidebar.setProfileDisplayName(displayName.uppercase(Locale.getDefault()))
        sidebar.setOnSearchClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, SearchFragment())
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnLiveClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, LiveTvFragment())
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnRecordsClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, RecordsFragment())
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnTvGuideClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, TvGuideFragment())
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnVodMoviesClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, VodBrowseFragment.newInstance(VodBrowseFragment.MODE_MOVIES))
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnVodSeriesClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, VodBrowseFragment.newInstance(VodBrowseFragment.MODE_SERIES))
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnProfileClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, ProfileFragment())
                .addToBackStack(null)
                .commit()
        }
        sidebar.setOnSettingsClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
        if (savedInstanceState == null) {
            val openLive = intent.getBooleanExtra(EXTRA_OPEN_LIVE, false)
            val initial = if (openLive) LiveTvFragment() else HomeContentFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, initial)
                .commitNow()
            if (openLive) {
                intent.removeExtra(EXTRA_OPEN_LIVE)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_LIVE = "open_live"
    }
}