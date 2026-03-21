package com.example.new_tv_app

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.example.new_tv_app.ui.sidebar.IptvSidebarView

/**
 * Shell with [IptvSidebarView] and main content [HomeContentFragment].
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val sidebar = findViewById<IptvSidebarView>(R.id.iptv_sidebar)
        val mainContent = findViewById<View>(R.id.main_content)
        val root = window.decorView.findViewById<View>(android.R.id.content)
        sidebar.attachAutoExpandCollapse(root, mainContent)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_content, HomeContentFragment())
                .commitNow()
        }
    }
}