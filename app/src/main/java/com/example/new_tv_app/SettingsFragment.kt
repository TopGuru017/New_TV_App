package com.example.new_tv_app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.new_tv_app.databinding.FragmentSettingsBinding
import com.example.new_tv_app.iptv.IptvCredentials
import com.example.new_tv_app.ui.sidebar.IptvSidebarView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var navRows: List<View>
    private lateinit var panels: List<ScrollView>
    private var selectedNavIndex = 0

    /** Restored when leaving Settings so the rail returns to “content” on RIGHT. */
    private var savedRowSettingsNextRight: Int = View.NO_ID

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rowSettings = requireActivity().findViewById<View>(R.id.row_settings)
        savedRowSettingsNextRight = rowSettings.nextFocusRightId

        val root = binding.root
        navRows = listOf(
            root.findViewById(R.id.nav_general),
            root.findViewById(R.id.nav_display),
            root.findViewById(R.id.nav_live),
            root.findViewById(R.id.nav_manage_devices),
            root.findViewById(R.id.nav_speed_test),
            root.findViewById(R.id.nav_live_support),
            root.findViewById(R.id.nav_log_out),
        )

        panels = listOf(
            binding.settingsPanelGeneral,
            binding.settingsPanelDisplay,
            binding.settingsPanelLive,
            binding.settingsPanelDevices,
            binding.settingsPanelSpeed,
            binding.settingsPanelSupport,
            binding.settingsPanelLogout,
        )

        bindNavRow(navRows[0], R.drawable.ic_settings_general, R.string.settings_nav_general)
        bindNavRow(navRows[1], R.drawable.ic_settings_display, R.string.settings_nav_display)
        bindNavRow(navRows[2], R.drawable.ic_settings_live, R.string.settings_nav_live)
        bindNavRow(navRows[3], R.drawable.ic_settings_devices, R.string.settings_nav_manage_devices)
        bindNavRow(navRows[4], R.drawable.ic_settings_speed, R.string.settings_nav_speed_test)
        bindNavRow(navRows[5], R.drawable.ic_settings_support, R.string.settings_nav_live_support)
        bindNavRow(navRows[6], R.drawable.ic_settings_logout, R.string.settings_nav_log_out)

        binding.settingsBuildInfo.text = getString(
            R.string.settings_build_fmt,
            SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()),
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        binding.settingsUpdateNotificationsSwitch.isChecked =
            prefs.getBoolean(KEY_UPDATE_NOTIFICATIONS, true)
        binding.settingsUpdateNotificationsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_UPDATE_NOTIFICATIONS, checked).apply()
        }

        refreshStorageSummary()
        binding.settingsClearCache.setOnClickListener { clearAppCache() }

        applyLanguageUi(prefs.getString(KEY_LANGUAGE, "he") ?: "he")
        binding.settingsLangHebrew.setOnClickListener {
            prefs.edit().putString(KEY_LANGUAGE, "he").apply()
            applyLanguageUi("he")
        }
        binding.settingsLangEnglish.setOnClickListener {
            prefs.edit().putString(KEY_LANGUAGE, "en").apply()
            applyLanguageUi("en")
        }

        applyRecordsOrderUi(prefs.getString(KEY_RECORDS_ORDER, "old_new") ?: "old_new")
        binding.settingsOrderNewOld.setOnClickListener {
            prefs.edit().putString(KEY_RECORDS_ORDER, "new_old").apply()
            applyRecordsOrderUi("new_old")
        }
        binding.settingsOrderOldNew.setOnClickListener {
            prefs.edit().putString(KEY_RECORDS_ORDER, "old_new").apply()
            applyRecordsOrderUi("old_new")
        }

        navRows.forEach { row -> row.nextFocusLeftId = R.id.row_settings }

        navRows.forEachIndexed { index, row ->
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectNav(index)
            }
            row.setOnClickListener { selectNav(index) }
            if (index == navRows.lastIndex) {
                row.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) {
                        showLogoutDialog()
                        true
                    } else {
                        false
                    }
                }
            }
        }

        binding.settingsPanelDisplay.getChildAt(0).isFocusable = true
        binding.settingsPanelLive.getChildAt(0).isFocusable = true
        binding.settingsPanelDevices.getChildAt(0).isFocusable = true
        binding.settingsPanelSpeed.getChildAt(0).isFocusable = true
        binding.settingsPanelSupport.getChildAt(0).isFocusable = true

        binding.settingsLogoutButton.setOnClickListener { showLogoutDialog() }
        binding.settingsLogoutButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                showLogoutDialog()
                true
            } else false
        }

        selectNav(savedInstanceState?.getInt(STATE_NAV_INDEX) ?: 0)
        navRows[0].post { navRows[0].requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV_INDEX, selectedNavIndex)
    }

    override fun onDestroyView() {
        activity?.findViewById<View>(R.id.row_settings)?.let { row ->
            row.nextFocusRightId = savedRowSettingsNextRight
        }
        super.onDestroyView()
        _binding = null
    }

    private fun bindNavRow(row: View, iconRes: Int, labelRes: Int) {
        row.findViewById<android.widget.ImageView>(R.id.settings_nav_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.settings_nav_label).setText(labelRes)
    }

    private fun firstFocusInActivePanel(): View = when (selectedNavIndex) {
        0 -> binding.settingsLangHebrew
        1 -> binding.settingsPanelDisplay.getChildAt(0) as TextView
        2 -> binding.settingsPanelLive.getChildAt(0) as TextView
        3 -> binding.settingsPanelDevices.getChildAt(0) as TextView
        4 -> binding.settingsPanelSpeed.getChildAt(0) as TextView
        5 -> binding.settingsPanelSupport.getChildAt(0) as TextView
        else -> binding.settingsLogoutButton
    }

    private fun updateHorizontalFocusChain() {
        val rowSettings = requireActivity().findViewById<View>(R.id.row_settings)
        rowSettings.nextFocusRightId = navRows[selectedNavIndex].id

        val contentFirst = firstFocusInActivePanel()
        navRows.forEach { it.nextFocusRightId = contentFirst.id }
    }

    private fun selectNav(index: Int) {
        selectedNavIndex = index.coerceIn(navRows.indices)
        navRows.forEachIndexed { i, row ->
            row.isActivated = i == selectedNavIndex
            row.findViewById<View>(R.id.settings_nav_indicator).isVisible = i == selectedNavIndex
        }
        panels.forEachIndexed { i, panel -> panel.isVisible = i == selectedNavIndex }

        val navId = navRows[selectedNavIndex].id
        binding.settingsPanelGeneral.nextFocusLeftId = navId
        binding.settingsPanelDisplay.nextFocusLeftId = navId
        binding.settingsPanelLive.nextFocusLeftId = navId
        binding.settingsPanelDevices.nextFocusLeftId = navId
        binding.settingsPanelSpeed.nextFocusLeftId = navId
        binding.settingsPanelSupport.nextFocusLeftId = navId
        binding.settingsPanelLogout.nextFocusLeftId = navId

        binding.settingsLangHebrew.nextFocusLeftId = navId
        binding.settingsLangEnglish.nextFocusLeftId = navId
        binding.settingsOrderNewOld.nextFocusLeftId = navId
        binding.settingsOrderOldNew.nextFocusLeftId = navId
        binding.settingsUpdateNotificationsSwitch.nextFocusLeftId = navId
        binding.settingsClearCache.nextFocusLeftId = navId

        (binding.settingsPanelDisplay.getChildAt(0) as TextView).nextFocusLeftId = navId
        (binding.settingsPanelLive.getChildAt(0) as TextView).nextFocusLeftId = navId
        (binding.settingsPanelDevices.getChildAt(0) as TextView).nextFocusLeftId = navId
        (binding.settingsPanelSpeed.getChildAt(0) as TextView).nextFocusLeftId = navId
        (binding.settingsPanelSupport.getChildAt(0) as TextView).nextFocusLeftId = navId
        binding.settingsLogoutButton.nextFocusLeftId = navId

        updateHorizontalFocusChain()
    }

    private fun applyLanguageUi(code: String) {
        val hebrewSelected = code == "he"
        binding.settingsLangHebrew.setBackgroundResource(
            if (hebrewSelected) R.drawable.bg_settings_option_selected else R.drawable.bg_settings_option_default,
        )
        binding.settingsLangEnglish.setBackgroundResource(
            if (!hebrewSelected) R.drawable.bg_settings_option_selected else R.drawable.bg_settings_option_default,
        )
        activity?.findViewById<IptvSidebarView>(R.id.iptv_sidebar)?.applyLanguage(code)
    }

    private fun applyRecordsOrderUi(order: String) {
        val newToOld = order == "new_old"
        binding.settingsOrderNewOld.setBackgroundResource(
            if (newToOld) R.drawable.bg_settings_option_selected else R.drawable.bg_settings_option_default,
        )
        binding.settingsOrderOldNew.setBackgroundResource(
            if (!newToOld) R.drawable.bg_settings_option_selected else R.drawable.bg_settings_option_default,
        )
    }

    private fun refreshStorageSummary() {
        val ctx = requireContext()
        binding.settingsCacheSize.text = getString(
            R.string.settings_cache_size_fmt,
            Formatter.formatFileSize(ctx, folderSize(ctx.cacheDir)),
        )
        val stat = StatFs(ctx.filesDir.path)
        val block = stat.blockSizeLong
        val total = block * stat.blockCountLong
        val free = block * stat.availableBlocksLong
        binding.settingsStorageSummary.text = getString(
            R.string.settings_storage_summary_fmt,
            Formatter.formatFileSize(ctx, total),
            Formatter.formatFileSize(ctx, free),
        )
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach { f ->
            size += if (f.isDirectory) folderSize(f) else f.length()
        }
        return size
    }

    private fun clearAppCache() {
        val ctx = requireContext()
        try {
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) { }
        refreshStorageSummary()
        android.widget.Toast.makeText(ctx, R.string.settings_cache_cleared, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_logout_confirm_title)
            .setMessage(R.string.settings_logout_confirm_message)
            .setPositiveButton(R.string.settings_logout_confirm_yes) { _, _ ->
                IptvCredentials.clear()
                startActivity(
                    Intent(requireContext(), LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    },
                )
                requireActivity().finish()
            }
            .setNegativeButton(R.string.settings_logout_confirm_cancel, null)
            .show()
    }

    companion object {
        private const val PREFS_NAME = "iptv_settings"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_RECORDS_ORDER = "records_order"
        private const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"
        private const val STATE_NAV_INDEX = "settings_nav_index"
    }
}
