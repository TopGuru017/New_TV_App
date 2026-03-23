package com.example.new_tv_app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.SearchHistoryStore
import com.example.new_tv_app.iptv.SeriesShow
import com.example.new_tv_app.iptv.VodMovieItem
import com.example.new_tv_app.iptv.XtreamVodApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Locale

private const val SEARCH_GRID_SPAN = 6
private const val MAX_QUERY_LEN = 256
private const val MAX_RESULTS_PER_TYPE = 40

private sealed class SearchResultEntry {
    data class MovieHit(val item: VodMovieItem) : SearchResultEntry()
    data class SeriesHit(val item: SeriesShow) : SearchResultEntry()
}

/**
 * TV search: query field, single-row on-screen keyboard (globe / 123 / space / a–z / search / backspace),
 * results grid, history chips and clear (matches product layout).
 */
class SearchFragment : Fragment() {

    private val queryBuilder = StringBuilder()
    private var numericKeyboard = false
    private var upperCaseLetters = false

    private var allMovies: List<VodMovieItem>? = null
    private var allShows: List<SeriesShow>? = null

    private lateinit var searchQuery: TextView
    private lateinit var keyboardRow: LinearLayout
    private lateinit var historyRow: LinearLayout
    private lateinit var clearHistory: TextView
    private lateinit var resultsRv: RecyclerView
    private lateinit var resultsEmpty: TextView
    private lateinit var resultsHint: TextView
    private lateinit var catalogLoading: ProgressBar

    private val sidebarAnchorId = R.id.row_search
    private val resultsAdapter = SearchResultsAdapter(
        onMovie = { m -> playMovie(m) },
        onSeries = { s -> playSeries(s) },
        sidebarAnchorId = sidebarAnchorId,
        spanCount = SEARCH_GRID_SPAN,
        focusUpId = R.id.search_clear_history,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchQuery = view.findViewById(R.id.search_query)
        keyboardRow = view.findViewById(R.id.search_keyboard_row)
        historyRow = view.findViewById(R.id.search_history_row)
        clearHistory = view.findViewById(R.id.search_clear_history)
        resultsRv = view.findViewById(R.id.search_results_list)
        resultsEmpty = view.findViewById(R.id.search_results_empty)
        resultsHint = view.findViewById(R.id.search_results_hint)
        catalogLoading = view.findViewById(R.id.search_catalog_loading)

        val spacing = resources.getDimensionPixelSize(R.dimen.live_grid_spacing)
        resultsRv.layoutManager = GridLayoutManager(requireContext(), SEARCH_GRID_SPAN)
        resultsRv.adapter = resultsAdapter
        resultsRv.addItemDecoration(SearchGridSpacingItemDecoration(SEARCH_GRID_SPAN, spacing))
        resultsRv.setHasFixedSize(true)
        resultsRv.itemAnimator = null
        resultsRv.nextFocusDownId = clearHistory.id

        val mainContent = requireActivity().findViewById<View>(R.id.main_content)
        val savedMainNextFocusLeft = mainContent.nextFocusLeftId
        mainContent.nextFocusLeftId = View.NO_ID
        viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    mainContent.nextFocusLeftId = savedMainNextFocusLeft
                }
            },
        )

        searchQuery.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusFirstKeyboardKey()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    runSearch()
                    true
                }
                else -> false
            }
        }

        clearHistory.setOnClickListener {
            SearchHistoryStore.clear(requireContext())
            refreshHistoryStrip()
        }

        syncQueryDisplay()
        rebuildKeyboard()

        viewLifecycleOwner.lifecycleScope.launch {
            catalogLoading.isVisible = true
            val dm = async { XtreamVodApi.fetchAllVodStreamsForSearch() }
            val ds = async { XtreamVodApi.fetchAllSeriesForSearch() }
            val mr = dm.await()
            val sr = ds.await()
            if (!isAdded) return@launch
            catalogLoading.isVisible = false
            mr.fold(
                onSuccess = { allMovies = it },
                onFailure = {
                    allMovies = emptyList()
                    Toast.makeText(
                        requireContext(),
                        R.string.search_error_catalog,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            sr.fold(
                onSuccess = { allShows = it },
                onFailure = {
                    allShows = emptyList()
                    Toast.makeText(
                        requireContext(),
                        R.string.search_error_catalog,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        searchQuery.post { if (isAdded) searchQuery.requestFocus() }
    }

    private fun focusFirstKeyboardKey() {
        if (keyboardRow.childCount > 0) {
            keyboardRow.getChildAt(0).requestFocus()
        }
    }

    private fun appendChar(ch: String) {
        if (queryBuilder.length >= MAX_QUERY_LEN) return
        queryBuilder.append(ch)
        syncQueryDisplay()
    }

    private fun backspace() {
        if (queryBuilder.isEmpty()) return
        queryBuilder.deleteCharAt(queryBuilder.length - 1)
        syncQueryDisplay()
    }

    private fun syncQueryDisplay() {
        searchQuery.text = queryBuilder.toString()
    }

    private fun toggleCase() {
        if (numericKeyboard) return
        upperCaseLetters = !upperCaseLetters
        rebuildKeyboard()
        focusFirstKeyboardKey()
    }

    private fun toggle123() {
        numericKeyboard = !numericKeyboard
        rebuildKeyboard()
        focusFirstKeyboardKey()
    }

    private fun rebuildKeyboard() {
        keyboardRow.removeAllViews()
        val ctx = requireContext()
        val padCompact = resources.getDimensionPixelSize(R.dimen.search_keyboard_key_padding_h_compact)
        val padWide = resources.getDimensionPixelSize(R.dimen.search_keyboard_key_padding_h_wide)
        val iconPadV = (resources.displayMetrics.density * 4f).toInt().coerceAtLeast(4)

        data class Slot(val view: View, val weight: Float)
        val slots = mutableListOf<Slot>()

        slots += Slot(
            makeIconKey(
                ctx,
                R.drawable.ic_search_globe,
                R.string.search_cd_globe,
                padCompact,
                iconPadV,
            ) { toggleCase() },
            1f,
        )
        slots += Slot(
            makeLabelKey(ctx, getString(R.string.search_keyboard_123), padWide, 11.5f) { toggle123() },
            1.15f,
        )
        slots += Slot(
            makeLabelKey(ctx, getString(R.string.search_keyboard_space), padWide, 11f) { appendChar(" ") },
            2.4f,
        )

        if (numericKeyboard) {
            val nums = "1234567890.,-_ '\"!?"
            for (ch in nums) {
                slots += Slot(
                    makeLabelKey(ctx, ch.toString(), padCompact, 12f) { appendChar(ch.toString()) },
                    1f,
                )
            }
        } else {
            for (c in 'a'..'z') {
                val ch = if (upperCaseLetters) c.uppercaseChar() else c
                slots += Slot(
                    makeLabelKey(ctx, ch.toString(), padCompact, 12f) { appendChar(ch.toString()) },
                    1f,
                )
            }
        }

        slots += Slot(
            makeLabelKey(ctx, getString(R.string.search_keyboard_search), padWide, 11f) { runSearch() },
            2.2f,
        )
        slots += Slot(
            makeIconKey(
                ctx,
                R.drawable.ic_search_backspace,
                R.string.search_cd_backspace,
                padCompact,
                iconPadV,
            ) { backspace() },
            1.15f,
        )

        val keys = slots.map { it.view }
        slots.forEachIndexed { i, slot ->
            val v = slot.view
            v.id = View.generateViewId()
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, slot.weight)
            keyboardRow.addView(v, lp)
            v.nextFocusLeftId = if (i == 0) sidebarAnchorId else keys[i - 1].id
            v.nextFocusRightId = if (i == keys.lastIndex) v.id else keys[i + 1].id
            v.nextFocusUpId = searchQuery.id
        }

        searchQuery.nextFocusDownId = keys.first().id
        wireVerticalFocusFromKeyboard(keys.last())
    }

    private fun wireVerticalFocusFromKeyboard(lastKey: View) {
        refreshHistoryStrip(lastKeyboardKey = lastKey)
    }

    private fun makeLabelKey(
        ctx: android.content.Context,
        label: String,
        horizontalPaddingPx: Int,
        textSizeSp: Float,
        onActivate: () -> Unit,
    ): TextView {
        return TextView(ctx).apply {
            text = label
            isFocusable = true
            setBackgroundResource(R.drawable.bg_search_key)
            gravity = android.view.Gravity.CENTER
            textSize = textSizeSp
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(ctx, R.color.sidebar_text_primary))
            setPaddingRelative(horizontalPaddingPx, 0, horizontalPaddingPx, 0)
            setOnClickListener { onActivate() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    -> {
                        onActivate()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstHistoryOrClear()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun makeIconKey(
        ctx: android.content.Context,
        drawableRes: Int,
        contentDescRes: Int,
        horizontalPaddingPx: Int,
        verticalPaddingPx: Int,
        onActivate: () -> Unit,
    ): ImageView {
        return ImageView(ctx).apply {
            setImageResource(drawableRes)
            contentDescription = ctx.getString(contentDescRes)
            isFocusable = true
            setBackgroundResource(R.drawable.bg_search_key)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPaddingRelative(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
            setOnClickListener { onActivate() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    -> {
                        onActivate()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstHistoryOrClear()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun focusFirstHistoryOrClear() {
        if (historyRow.childCount > 0) {
            historyRow.getChildAt(0).requestFocus()
        } else {
            clearHistory.requestFocus()
        }
    }

    private fun refreshHistoryStrip(lastKeyboardKey: View? = null) {
        historyRow.removeAllViews()
        val items = SearchHistoryStore.readQueries(requireContext())
        val inflater = layoutInflater
        val chips = mutableListOf<View>()
        for (text in items) {
            val chip = inflater.inflate(R.layout.item_search_history, historyRow, false)
            chip.findViewById<TextView>(R.id.search_history_label).text = text
            chip.setOnClickListener {
                applyHistoryQuery(text)
            }
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    -> {
                        applyHistoryQuery(text)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        (lastKeyboardKey ?: keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1))?.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (resultsAdapter.itemCount > 0) {
                            resultsRv.scrollToPosition(0)
                            resultsRv.post {
                                resultsRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            chip.id = View.generateViewId()
            historyRow.addView(chip)
            chips.add(chip)
        }

        chips.forEachIndexed { i, chip ->
            chip.nextFocusLeftId = if (i == 0) {
                sidebarAnchorId
            } else {
                chips[i - 1].id
            }
            chip.nextFocusRightId = if (i == chips.lastIndex) clearHistory.id else chips[i + 1].id
        }

        clearHistory.nextFocusUpId =
            (lastKeyboardKey ?: keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1))?.id
                ?: searchQuery.id
        clearHistory.nextFocusLeftId =
            chips.lastOrNull()?.id
                ?: (lastKeyboardKey ?: keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1))?.id
                ?: searchQuery.id

        clearHistory.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    (lastKeyboardKey ?: keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1))?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (resultsAdapter.itemCount > 0) {
                        resultsRv.scrollToPosition(0)
                        resultsRv.post {
                            resultsRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val lastKey = lastKeyboardKey ?: keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1)
        if (lastKey != null) {
            lastKey.nextFocusDownId =
                if (chips.isNotEmpty()) chips.first().id else clearHistory.id
        }
    }

    private fun LinearLayout.getChildAtOrNull(index: Int): View? =
        if (index in 0 until childCount) getChildAt(index) else null

    private fun applyHistoryQuery(text: String) {
        queryBuilder.clear()
        queryBuilder.append(text.take(MAX_QUERY_LEN))
        syncQueryDisplay()
        runSearch()
    }

    private fun runSearch() {
        val q = queryBuilder.toString().trim()
        if (q.isEmpty()) {
            resultsAdapter.submit(emptyList())
            resultsRv.isVisible = false
            resultsEmpty.isVisible = false
            resultsHint.isVisible = true
            return
        }
        SearchHistoryStore.addQuery(requireContext(), q)
        refreshHistoryStrip(keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1))

        val ql = q.lowercase(Locale.getDefault())
        val movies = allMovies.orEmpty()
            .filter { it.name.lowercase(Locale.getDefault()).contains(ql) }
            .take(MAX_RESULTS_PER_TYPE)
            .map { SearchResultEntry.MovieHit(it) }
        val series = allShows.orEmpty()
            .filter { it.name.lowercase(Locale.getDefault()).contains(ql) }
            .take(MAX_RESULTS_PER_TYPE)
            .map { SearchResultEntry.SeriesHit(it) }

        val combined = movies + series
        resultsAdapter.submit(combined)
        resultsHint.isVisible = false
        resultsRv.isVisible = combined.isNotEmpty()
        resultsEmpty.isVisible = combined.isEmpty()
    }

    private fun playMovie(m: VodMovieItem) {
        val url = IptvStreamUrls.vodMovieUrl(m.streamId, m.containerExtension)
        val movie = Movie(
            id = m.streamId.hashCode().toLong(),
            title = m.name,
            description = getString(R.string.search_badge_movie),
            backgroundImageUrl = m.coverUrl,
            cardImageUrl = m.coverUrl,
            videoUrl = url,
            studio = null,
        )
        startActivity(
            Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
            },
        )
    }

    private fun playSeries(s: SeriesShow) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = XtreamVodApi.fetchFirstSeriesEpisode(s.seriesId)
            if (!isAdded) return@launch
            r.fold(
                onSuccess = { (epId, ext) ->
                    val url = IptvStreamUrls.seriesEpisodeUrl(epId, ext)
                    val movie = Movie(
                        id = s.seriesId.hashCode().toLong(),
                        title = s.name,
                        description = getString(R.string.search_badge_series),
                        backgroundImageUrl = s.coverUrl,
                        cardImageUrl = s.coverUrl,
                        videoUrl = url,
                        studio = null,
                    )
                    startActivity(
                        Intent(requireContext(), PlaybackActivity::class.java).apply {
                            putExtra(DetailsActivity.MOVIE, movie)
                        },
                    )
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        R.string.vod_series_episode_error,
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }
}

private class SearchGridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacingPx: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        val col = pos % spanCount
        outRect.left = spacingPx - col * spacingPx / spanCount
        outRect.right = (col + 1) * spacingPx / spanCount
        outRect.top = spacingPx / 2
        outRect.bottom = spacingPx / 2
    }
}

private class SearchResultsAdapter(
    private val onMovie: (VodMovieItem) -> Unit,
    private val onSeries: (SeriesShow) -> Unit,
    private val sidebarAnchorId: Int,
    private val spanCount: Int,
    private val focusUpId: Int,
) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

    private val items = mutableListOf<SearchResultEntry>()

    fun submit(list: List<SearchResultEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val col = position % spanCount
        holder.itemView.nextFocusLeftId = if (col == 0) sidebarAnchorId else View.NO_ID
        holder.itemView.nextFocusUpId = focusUpId
        when (val e = items[position]) {
            is SearchResultEntry.MovieHit -> {
                val m = e.item
                holder.name.text = m.name
                holder.name.append("\n")
                holder.name.append(holder.itemView.context.getString(R.string.search_badge_movie))
                loadIcon(holder.icon, m.coverUrl)
                holder.itemView.setOnClickListener { onMovie(m) }
                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        -> {
                            onMovie(m)
                            true
                        }
                        else -> false
                    }
                }
            }
            is SearchResultEntry.SeriesHit -> {
                val s = e.item
                holder.name.text = s.name
                holder.name.append("\n")
                holder.name.append(holder.itemView.context.getString(R.string.search_badge_series))
                loadIcon(holder.icon, s.coverUrl)
                holder.itemView.setOnClickListener { onSeries(s) }
                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        -> {
                            onSeries(s)
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    private fun loadIcon(icon: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(icon).clear(icon)
            icon.setImageDrawable(null)
        } else {
            Glide.with(icon).load(url).fitCenter().into(icon)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.live_channel_icon)
        val name: TextView = itemView.findViewById(R.id.live_channel_name)
    }
}
