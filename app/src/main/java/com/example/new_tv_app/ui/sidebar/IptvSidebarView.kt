package com.example.new_tv_app.ui.sidebar

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.new_tv_app.R
import com.example.new_tv_app.iptv.IptvTimeUtils
import java.util.Locale

/**
 * IPTV navigation rail: expands while focused, collapses to icons when focus is in main content.
 */
class IptvSidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val expandedWidthPx =
        resources.getDimensionPixelSize(R.dimen.sidebar_width_expanded)
    private val collapsedWidthPx =
        resources.getDimensionPixelSize(R.dimen.sidebar_width_collapsed)
    private val horizontalPadPx =
        resources.getDimensionPixelSize(R.dimen.sidebar_horizontal_padding)
    private val logoSlotSizePx =
        resources.getDimensionPixelSize(R.dimen.sidebar_logo_size)

    private var expanded = true
    private var vodMenuOpen = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockText()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private lateinit var logoRow: LinearLayout
    private lateinit var logoBrandSdarot: TextView
    private lateinit var logoSlot: FrameLayout
    private lateinit var appLogo: ImageView
    private lateinit var profileRow: LinearLayout
    private lateinit var profileName: TextView
    private lateinit var profileInitial: TextView
    private lateinit var vodChevron: ImageView
    private lateinit var vodSubmenu: View
    private lateinit var footerRow: LinearLayout
    private lateinit var clockView: TextView

    private lateinit var rowSearch: LinearLayout
    private lateinit var rowFavorites: LinearLayout
    private lateinit var rowLastWatch: LinearLayout
    private lateinit var rowTvGuide: LinearLayout
    private lateinit var rowLive: LinearLayout
    private lateinit var rowLiveLabel: TextView
    private lateinit var rowRecords: LinearLayout
    private lateinit var rowVod: LinearLayout
    private lateinit var rowSettings: LinearLayout

    private val navRows: List<LinearLayout> by lazy {
        listOf(
            rowSearch,
            rowFavorites,
            rowLastWatch,
            rowTvGuide,
            rowLive,
            rowRecords,
            rowVod
        )
    }

    private val vodSubRows: List<TextView> by lazy {
        listOf(
            findViewById(R.id.row_vod_series),
            findViewById(R.id.row_vod_movies)
        )
    }

    private var focusRoot: View? = null
    private var globalFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var mainContentView: View? = null

    init {
        setBackgroundResource(R.color.sidebar_background)
        LayoutInflater.from(context).inflate(R.layout.view_iptv_sidebar_inner, this, true)
        bindViews()
        profileName.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }

                override fun afterTextChanged(s: Editable?) {
                    refreshProfileInitial()
                }
            }
        )
        refreshProfileInitial()
        rowVod.setOnClickListener {
            if (!expanded) return@setOnClickListener
            vodMenuOpen = !vodMenuOpen
            syncVodSubmenu()
        }
        applyVisualState(animateWidth = false)
        updateClockText()
    }

    fun setOnProfileClickListener(listener: OnClickListener?) {
        profileRow.setOnClickListener(listener)
    }

    fun setOnSearchClickListener(listener: OnClickListener?) {
        rowSearch.setOnClickListener(listener)
    }

    fun setOnLiveClickListener(listener: OnClickListener?) {
        rowLive.setOnClickListener(listener)
    }

    fun setOnRecordsClickListener(listener: OnClickListener?) {
        rowRecords.setOnClickListener(listener)
    }

    fun setOnTvGuideClickListener(listener: OnClickListener?) {
        rowTvGuide.setOnClickListener(listener)
    }

    fun setOnSettingsClickListener(listener: OnClickListener?) {
        rowSettings.setOnClickListener(listener)
    }

    fun setOnVodMoviesClickListener(listener: OnClickListener?) {
        findViewById<TextView>(R.id.row_vod_movies).setOnClickListener(listener)
    }

    fun setOnVodSeriesClickListener(listener: OnClickListener?) {
        findViewById<TextView>(R.id.row_vod_series).setOnClickListener(listener)
    }

    /** Sets the label under the avatar (e.g. IPTV username from BuildConfig). */
    fun setProfileDisplayName(name: CharSequence) {
        profileName.text = name
        refreshProfileInitial()
    }

    private fun bindViews() {
        logoRow = findViewById(R.id.sidebar_logo_row)
        logoBrandSdarot = findViewById(R.id.sidebar_brand_sdarot)
        logoSlot = findViewById(R.id.sidebar_logo_slot)
        appLogo = findViewById(R.id.sidebar_app_logo)
        profileRow = findViewById(R.id.sidebar_profile_row)
        profileName = findViewById(R.id.sidebar_profile_name)
        profileInitial = findViewById(R.id.sidebar_profile_initial)
        vodChevron = findViewById(R.id.sidebar_vod_chevron)
        vodSubmenu = findViewById(R.id.sidebar_vod_submenu)
        footerRow = findViewById(R.id.sidebar_footer_row)
        clockView = findViewById(R.id.sidebar_clock)

        rowSearch = findViewById(R.id.row_search)
        rowFavorites = findViewById(R.id.row_favorites)
        rowLastWatch = findViewById(R.id.row_last_watch)
        rowTvGuide = findViewById(R.id.row_tv_guide)
        rowLive = findViewById(R.id.row_live)
        rowLiveLabel = findViewById(R.id.row_live_label)
        rowRecords = findViewById(R.id.row_records)
        rowVod = findViewById(R.id.row_vod)
        rowSettings = findViewById(R.id.row_settings)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clockHandler.post(clockRunnable)
    }

    override fun onDetachedFromWindow() {
        clockHandler.removeCallbacks(clockRunnable)
        removeGlobalFocusListener()
        clearMainContentInset()
        mainContentView = null
        super.onDetachedFromWindow()
    }

    /**
     * Call from [android.app.Activity.onCreate] after [android.app.Activity.setContentView].
     *
     * @param mainContent Usually the [android.widget.FrameLayout] that hosts your IPTV fragments
     * (sibling of this sidebar, not a descendant — pass the view explicitly).
     */
    fun attachAutoExpandCollapse(root: View, mainContent: View) {
        removeGlobalFocusListener()
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) return@OnGlobalFocusChangeListener
            val onSidebar = newFocus.isDescendantOfSidebar(this)
            setExpanded(onSidebar)
        }
        globalFocusListener = listener
        focusRoot = root
        root.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        val contentId = mainContent.id
        wireFocusRightToContent(contentId)
        mainContent.nextFocusLeftId = profileRow.id
        mainContentView = mainContent
        applySidebarWidthAndContentInset(animate = false)
    }

    private fun removeGlobalFocusListener() {
        val root = focusRoot
        val listener = globalFocusListener
        if (root != null && listener != null) {
            root.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
        }
        focusRoot = null
        globalFocusListener = null
    }

    private fun View.isDescendantOfSidebar(sidebar: IptvSidebarView): Boolean {
        var v: View? = this
        while (v != null) {
            if (v === sidebar) return true
            val parent = v.parent
            v = parent as? View
        }
        return false
    }

    private fun wireFocusRightToContent(contentId: Int) {
        val chain: List<View> =
            listOf(profileRow) + navRows + vodSubRows + rowSettings
        chain.forEach { it.nextFocusRightId = contentId }
    }

    fun setExpanded(expand: Boolean, animate: Boolean = true) {
        if (expand == expanded) return
        expanded = expand
        if (!expand) vodMenuOpen = false
        applyVisualState(animate)
    }

    private fun applyVisualState(animateWidth: Boolean) {
        applySidebarWidthAndContentInset(animateWidth)

        logoRow.setPaddingRelative(
            horizontalPadPx,
            logoRow.paddingTop,
            horizontalPadPx,
            logoRow.paddingBottom
        )
        logoBrandSdarot.isVisible = expanded
        val slotLp = logoSlot.layoutParams as LinearLayout.LayoutParams
        val minLogoSlotW =
            resources.getDimensionPixelSize(R.dimen.sidebar_logo_image_min_width)
        if (expanded) {
            slotLp.width = 0
            slotLp.weight = 1f
            slotLp.height = logoSlotSizePx
            logoSlot.minimumWidth = minLogoSlotW
        } else {
            slotLp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            slotLp.weight = 0f
            slotLp.height = logoSlotSizePx
            logoSlot.minimumWidth = logoSlotSizePx
        }
        logoSlot.layoutParams = slotLp
        logoRow.gravity = rowGravityForExpandedState()

        profileRow.setPaddingRelative(
            horizontalPadPx,
            profileRow.paddingTop,
            horizontalPadPx,
            profileRow.paddingBottom
        )
        profileRow.gravity = rowGravityForExpandedState()

        applyRowLayout(profileRow)

        navRows.forEach { applyRowLayout(it) }
        applyRowLayout(rowSettings)

        vodChevron.isVisible = expanded
        syncVodSubmenu()

        footerRow.setPaddingRelative(
            horizontalPadPx,
            footerRow.paddingTop,
            horizontalPadPx,
            footerRow.paddingBottom
        )
        if (expanded) {
            footerRow.orientation = LinearLayout.HORIZONTAL
            footerRow.gravity = Gravity.CENTER_VERTICAL
            (clockView.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT
                weight = 0f
            }
            clockView.gravity = Gravity.START
        } else {
            footerRow.orientation = LinearLayout.VERTICAL
            footerRow.gravity = Gravity.CENTER_HORIZONTAL
            (clockView.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                weight = 0f
            }
            clockView.gravity = Gravity.CENTER_HORIZONTAL
        }
        updateClockText()
    }

    private fun rowGravityForExpandedState(): Int =
        if (expanded) Gravity.CENTER_VERTICAL or Gravity.START
        else Gravity.CENTER

    private fun applyRowLayout(row: LinearLayout) {
        row.setPaddingRelative(horizontalPadPx, 0, horizontalPadPx, 0)
        when (row.id) {
            R.id.row_live -> rowLiveLabel.isVisible = expanded
            R.id.row_vod -> row.getChildAt(1).isVisible = expanded
            else -> if (row.childCount >= 2) row.getChildAt(1).isVisible = expanded
        }
        row.gravity = rowGravityForExpandedState()
    }

    private fun syncVodSubmenu() {
        vodSubmenu.isVisible = expanded && vodMenuOpen
        vodChevron.setImageResource(
            if (vodMenuOpen) R.drawable.ic_sidebar_chevron_up
            else R.drawable.ic_sidebar_chevron_down
        )
    }

    /**
     * Main content always reserves [collapsedWidthPx] on the start edge so its width stays fixed.
     * Only the sidebar width animates; expanded rail draws over the left part of that region.
     * Minimized rail: icons centered horizontally in the column ([Gravity.CENTER]).
     */
    private fun applySidebarWidthAndContentInset(animate: Boolean) {
        val targetSidebarW = if (expanded) expandedWidthPx else collapsedWidthPx
        val slp = layoutParams as? FrameLayout.LayoutParams ?: return
        mainContentView?.let { content ->
            val clp = content.layoutParams as? FrameLayout.LayoutParams ?: return@let
            if (clp.marginStart != collapsedWidthPx) {
                clp.marginStart = collapsedWidthPx
                content.layoutParams = clp
            }
        }

        val fromW = if (width > 0) width else slp.width
        if (!animate || fromW == targetSidebarW) {
            slp.width = targetSidebarW
            layoutParams = slp
            return
        }
        ValueAnimator.ofInt(fromW, targetSidebarW).apply {
            duration = 220
            addUpdateListener { anim ->
                slp.width = anim.animatedValue as Int
                layoutParams = slp
            }
        }.start()
    }

    private fun clearMainContentInset() {
        val content = mainContentView ?: return
        val clp = content.layoutParams as? FrameLayout.LayoutParams ?: return
        clp.marginStart = 0
        content.layoutParams = clp
    }

    private fun updateClockText() {
        val now = IptvTimeUtils.nowIsraelSeconds()
        val time = IptvTimeUtils.formatTimeIsrael(now)
        val day = IptvTimeUtils.formatDateIsrael(now, "EEEE")
        clockView.text = if (expanded) "$time $day" else time
    }

    private fun refreshProfileInitial() {
        profileInitial.text = firstLetterFromDisplayName(profileName.text?.toString().orEmpty())
    }

    private fun firstLetterFromDisplayName(name: String): String {
        for (ch in name) {
            if (ch.isLetter()) return ch.uppercaseChar().toString()
        }
        return "?"
    }
}
