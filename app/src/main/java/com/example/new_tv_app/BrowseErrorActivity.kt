package com.example.new_tv_app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.example.new_tv_app.ui.sidebar.IptvSidebarView

/**
 * BrowseErrorActivity shows how to use ErrorFragment.
 */
class BrowseErrorActivity : FragmentActivity() {

    private lateinit var mErrorFragment: ErrorFragment
    private lateinit var mSpinnerFragment: SpinnerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val sidebar = findViewById<IptvSidebarView>(R.id.iptv_sidebar)
        val mainContent = findViewById<View>(R.id.main_content)
        val root = window.decorView.findViewById<View>(android.R.id.content)
        sidebar.attachAutoExpandCollapse(root, mainContent)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content, MainFragment())
                .commitNow()
        }
        testError()
    }

    private fun testError() {
        mErrorFragment = ErrorFragment()
        supportFragmentManager
            .beginTransaction()
            .add(R.id.main_content, mErrorFragment)
            .commit()

        mSpinnerFragment = SpinnerFragment()
        supportFragmentManager
            .beginTransaction()
            .add(R.id.main_content, mSpinnerFragment)
            .commit()

        val handler = Handler(Looper.myLooper()!!)
        handler.postDelayed({
            supportFragmentManager
                .beginTransaction()
                .remove(mSpinnerFragment)
                .commit()
            mErrorFragment.setErrorContent()
        }, TIMER_DELAY)
    }

    class SpinnerFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            val progressBar = ProgressBar(container?.context)
            if (container is FrameLayout) {
                val layoutParams =
                    FrameLayout.LayoutParams(SPINNER_WIDTH, SPINNER_HEIGHT, Gravity.CENTER)
                progressBar.layoutParams = layoutParams
            }
            return progressBar
        }
    }

    companion object {
        private val TIMER_DELAY = 3000L
        private val SPINNER_WIDTH = 100
        private val SPINNER_HEIGHT = 100
    }
}