package com.example.new_tv_app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.SearchHistoryStore
import com.example.new_tv_app.iptv.SeriesShow
import com.example.new_tv_app.iptv.VodMovieItem
import com.example.new_tv_app.iptv.XtreamLiveApi
import com.example.new_tv_app.iptv.XtreamVodApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Locale

private const val MAX_QUERY_LEN = 256
private const val MAX_RECORDINGS_ROW = 30
private const val MAX_VOD_ROW = 40

/**
 * TV search: keyboard, then horizontal rows — recordings (live channels), VOD series, VOD movies.
 */
class SearchFragment : Fragment() {

    private val queryBuilder = StringBuilder()
    private var numericKeyboard = false
    private var upperCaseLetters = false

    private var allLive: List<LiveStream>? = null
    private var allMovies: List<VodMovieItem>? = null
    private var allShows: List<SeriesShow>? = null

    private lateinit var searchQuery: TextView
    private lateinit var keyboardRow: LinearLayout
    private lateinit var historyRow: LinearLayout
    private lateinit var clearHistory: TextView
    private lateinit var resultsScroll: NestedScrollView
    private lateinit var resultsHint: TextView
    private lateinit var resultsEmpty: TextView
    private lateinit var headerRecordings: TextView
    private lateinit var headerSeries: TextView
    private lateinit var headerMovies: TextView
    private lateinit var rvRecordings: RecyclerView
    private lateinit var rvSeries: RecyclerView
    private lateinit var rvMovies: RecyclerView

    private val sidebarAnchorId = R.id.row_search

    private val recordingsAdapter = SearchRecordingAdapter(
        sidebarAnchorId = sidebarAnchorId,
        focusUpIdProvider = { keyboardLastView()?.id ?: View.NO_ID },
        queryProvider = { lastSearchQuery },
        onPlay = { playLive(it) },
        onRequestFocusNextRow = { focusFirstInRecyclerView(rvSeries) || focusFirstInRecyclerView(rvMovies) },
    )
    private val seriesAdapter = SearchVodStripAdapter<SeriesShow>(
        sidebarAnchorId = sidebarAnchorId,
        focusUpIdProvider = { keyboardLastView()?.id ?: View.NO_ID },
        queryProvider = { lastSearchQuery },
        loadPoster = { iv, url -> loadPoster(iv, url) },
        nameGetter = { it.name },
        posterGetter = { it.coverUrl },
        onPick = { playSeries(it) },
        onRequestFocusNextRow = { focusFirstInRecyclerView(rvMovies) },
    )
    private val moviesAdapter = SearchVodStripAdapter<VodMovieItem>(
        sidebarAnchorId = sidebarAnchorId,
        focusUpIdProvider = { keyboardLastView()?.id ?: View.NO_ID },
        queryProvider = { lastSearchQuery },
        loadPoster = { iv, url -> loadPoster(iv, url) },
        nameGetter = { it.name },
        posterGetter = { it.coverUrl },
        onPick = { playMovie(it) },
        onRequestFocusNextRow = { false },
    )

    private var lastSearchQuery: String = ""

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
        resultsScroll = view.findViewById(R.id.search_results_scroll)
        resultsHint = view.findViewById(R.id.search_results_hint)
        resultsEmpty = view.findViewById(R.id.search_results_empty)
        headerRecordings = view.findViewById(R.id.search_header_recordings)
        headerSeries = view.findViewById(R.id.search_header_series)
        headerMovies = view.findViewById(R.id.search_header_movies)
        rvRecordings = view.findViewById(R.id.search_rv_recordings)
        rvSeries = view.findViewById(R.id.search_rv_series)
        rvMovies = view.findViewById(R.id.search_rv_movies)
        val catalogLoading = view.findViewById<ProgressBar>(R.id.search_catalog_loading)

        val gap = resources.getDimensionPixelSize(R.dimen.live_grid_spacing)
        setupHorizontalRv(rvRecordings, recordingsAdapter, gap)
        setupHorizontalRv(rvSeries, seriesAdapter, gap)
        setupHorizontalRv(rvMovies, moviesAdapter, gap)

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
                KeyEvent.KEYCODE_DPAD_UP -> {
                    searchQuery.nextFocusUpId = sidebarAnchorId
                    false
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
            val dl = async { XtreamLiveApi.fetchAllLiveStreamsForSearch() }
            val dm = async { XtreamVodApi.fetchAllVodStreamsForSearch() }
            val ds = async { XtreamVodApi.fetchAllSeriesForSearch() }
            val lr = dl.await()
            val mr = dm.await()
            val sr = ds.await()
            if (!isAdded) return@launch
            catalogLoading.isVisible = false
            lr.fold(onSuccess = { allLive = it }, onFailure = { allLive = emptyList() })
            mr.fold(onSuccess = { allMovies = it }, onFailure = { allMovies = emptyList() })
            sr.fold(onSuccess = { allShows = it }, onFailure = { allShows = emptyList() })
            if (lr.isFailure || mr.isFailure || sr.isFailure) {
                Toast.makeText(requireContext(), R.string.search_error_catalog, Toast.LENGTH_SHORT).show()
            }
        }

        searchQuery.post { if (isAdded) searchQuery.requestFocus() }
    }

    private fun setupHorizontalRv(rv: RecyclerView, adapter: RecyclerView.Adapter<*>, gapPx: Int) {
        rv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        rv.itemAnimator = null
        rv.addItemDecoration(SearchHorizontalGapDecoration(gapPx))
    }

    private fun keyboardLastView(): View? =
        keyboardRow.getChildAtOrNull(keyboardRow.childCount - 1)

    private fun focusFirstKeyboardKey() {
        if (keyboardRow.childCount > 0) keyboardRow.getChildAt(0).requestFocus()
    }

    private fun focusFirstSearchResult(): Boolean =
        focusFirstInRecyclerView(rvRecordings) ||
            focusFirstInRecyclerView(rvSeries) ||
            focusFirstInRecyclerView(rvMovies)

    private fun focusFirstInRecyclerView(rv: RecyclerView): Boolean {
        if (!rv.isVisible || rv.adapter == null || rv.adapter!!.itemCount <= 0) return false
        rv.scrollToPosition(0)
        rv.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        return true
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
            makeIconKey(ctx, R.drawable.ic_search_globe, R.string.search_cd_globe, padCompact, iconPadV) { toggleCase() },
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
            makeIconKey(ctx, R.drawable.ic_search_backspace, R.string.search_cd_backspace, padCompact, iconPadV) { backspace() },
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
        recordingsAdapter.notifyDataSetChanged()
        seriesAdapter.notifyDataSetChanged()
        moviesAdapter.notifyDataSetChanged()
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
                        focusFirstSearchResult() || focusFirstHistoryOrClear()
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
                        focusFirstSearchResult() || focusFirstHistoryOrClear()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun focusFirstHistoryOrClear(): Boolean {
        if (historyRow.childCount > 0) historyRow.getChildAt(0).requestFocus()
        else clearHistory.requestFocus()
        return true
    }

    private fun refreshHistoryStrip(lastKeyboardKey: View? = null) {
        historyRow.removeAllViews()
        val items = SearchHistoryStore.readQueries(requireContext())
        val inflater = layoutInflater
        val chips = mutableListOf<View>()
        for (text in items) {
            val chip = inflater.inflate(R.layout.item_search_history, historyRow, false)
            chip.findViewById<TextView>(R.id.search_history_label).text = text
            chip.setOnClickListener { applyHistoryQuery(text) }
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
                        (lastKeyboardKey ?: keyboardLastView())?.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstSearchResult()
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
            chip.nextFocusLeftId = if (i == 0) sidebarAnchorId else chips[i - 1].id
            chip.nextFocusRightId = if (i == chips.lastIndex) clearHistory.id else chips[i + 1].id
        }

        clearHistory.nextFocusUpId = (lastKeyboardKey ?: keyboardLastView())?.id ?: searchQuery.id
        clearHistory.nextFocusLeftId =
            chips.lastOrNull()?.id ?: (lastKeyboardKey ?: keyboardLastView())?.id ?: searchQuery.id

        clearHistory.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    (lastKeyboardKey ?: keyboardLastView())?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusFirstSearchResult()
                    true
                }
                else -> false
            }
        }

        val lastKey = lastKeyboardKey ?: keyboardLastView()
        if (lastKey != null) {
            lastKey.nextFocusDownId = if (chips.isNotEmpty()) chips.first().id else clearHistory.id
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
        lastSearchQuery = q
        if (q.isEmpty()) {
            headerRecordings.isVisible = false
            headerSeries.isVisible = false
            headerMovies.isVisible = false
            rvRecordings.isVisible = false
            rvSeries.isVisible = false
            rvMovies.isVisible = false
            recordingsAdapter.submit(emptyList())
            seriesAdapter.submit(emptyList())
            moviesAdapter.submit(emptyList())
            resultsHint.isVisible = true
            resultsEmpty.isVisible = false
            return
        }
        SearchHistoryStore.addQuery(requireContext(), q)
        refreshHistoryStrip(keyboardLastView())

        val loc = Locale.getDefault()
        val ql = q.lowercase(loc)
        val recordings = allLive.orEmpty()
            .filter { it.name.lowercase(loc).contains(ql) }
            .take(MAX_RECORDINGS_ROW)
        val series = allShows.orEmpty()
            .filter { it.name.lowercase(loc).contains(ql) }
            .take(MAX_VOD_ROW)
        val movies = allMovies.orEmpty()
            .filter { it.name.lowercase(loc).contains(ql) }
            .take(MAX_VOD_ROW)

        recordingsAdapter.submit(recordings)
        seriesAdapter.submit(series)
        moviesAdapter.submit(movies)

        headerRecordings.text = getString(R.string.search_recordings_found, recordings.size)
        headerRecordings.isVisible = recordings.isNotEmpty()
        rvRecordings.isVisible = recordings.isNotEmpty()

        headerSeries.isVisible = series.isNotEmpty()
        rvSeries.isVisible = series.isNotEmpty()

        headerMovies.isVisible = movies.isNotEmpty()
        rvMovies.isVisible = movies.isNotEmpty()

        val any = recordings.isNotEmpty() || series.isNotEmpty() || movies.isNotEmpty()
        resultsHint.isVisible = false
        resultsEmpty.isVisible = !any
    }

    private fun loadPoster(iv: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(iv).clear(iv)
            iv.setImageDrawable(null)
        } else {
            Glide.with(iv).load(url).centerCrop().into(iv)
        }
    }

    private fun playLive(stream: LiveStream) {
        val url = IptvStreamUrls.liveStreamUrl(stream.streamId)
        val movie = Movie(
            id = stream.streamId.hashCode().toLong(),
            title = stream.name,
            description = getString(R.string.search_badge_live),
            backgroundImageUrl = stream.iconUrl,
            cardImageUrl = stream.iconUrl,
            videoUrl = url,
            studio = null,
        )
        startActivity(
            Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
            },
        )
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

private fun buildHighlightedTitle(full: String, query: String, context: android.content.Context): CharSequence {
    if (query.isBlank()) return full
    val highlight = ContextCompat.getColor(context, R.color.search_query_highlight)
    val ss = SpannableString(full)
    val lowerFull = full.lowercase(Locale.getDefault())
    val q = query.lowercase(Locale.getDefault())
    var start = 0
    while (start <= lowerFull.length - q.length) {
        val i = lowerFull.indexOf(q, start)
        if (i < 0) break
        ss.setSpan(
            ForegroundColorSpan(highlight),
            i,
            i + q.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        start = i + q.length
    }
    return ss
}

private class SearchHorizontalGapDecoration(private val gapPx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        if (pos > 0) outRect.left = gapPx
    }
}

private class SearchRecordingAdapter(
    private val sidebarAnchorId: Int,
    private val focusUpIdProvider: () -> Int,
    private val queryProvider: () -> String,
    private val onPlay: (LiveStream) -> Unit,
    private val onRequestFocusNextRow: () -> Boolean,
) : RecyclerView.Adapter<SearchRecordingAdapter.VH>() {

    private val items = mutableListOf<LiveStream>()

    fun submit(list: List<LiveStream>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stream = items[position]
        holder.itemView.nextFocusLeftId = if (position == 0) sidebarAnchorId else View.NO_ID
        holder.itemView.nextFocusUpId = focusUpIdProvider()

        loadRecordingIcon(holder.icon, stream.iconUrl)
        holder.name.text = buildHighlightedTitle(stream.name, queryProvider(), holder.itemView.context)
        holder.idLine.text = holder.itemView.context.getString(R.string.search_result_id_fmt, stream.streamId)

        val activate = {
            onPlay(stream)
        }
        holder.itemView.setOnClickListener { activate() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    activate()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (position == items.lastIndex) onRequestFocusNextRow() else false
                }
                else -> false
            }
        }
    }

    private fun loadRecordingIcon(iv: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(iv).clear(iv)
            iv.setImageDrawable(null)
        } else {
            Glide.with(iv).load(url).fitCenter().into(iv)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.search_recording_icon)
        val name: TextView = itemView.findViewById(R.id.search_recording_name)
        val idLine: TextView = itemView.findViewById(R.id.search_recording_id)
    }
}

private class SearchVodStripAdapter<T : Any>(
    private val sidebarAnchorId: Int,
    private val focusUpIdProvider: () -> Int,
    private val queryProvider: () -> String,
    private val loadPoster: (ImageView, String?) -> Unit,
    private val nameGetter: (T) -> String,
    private val posterGetter: (T) -> String?,
    private val onPick: (T) -> Unit,
    private val onRequestFocusNextRow: () -> Boolean,
) : RecyclerView.Adapter<SearchVodStripAdapter.VH>() {

    private val items = mutableListOf<T>()

    fun submit(list: List<T>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_vod_strip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.itemView.nextFocusLeftId = if (position == 0) sidebarAnchorId else View.NO_ID
        holder.itemView.nextFocusUpId = focusUpIdProvider()

        val ctx = holder.itemView.context
        val q = queryProvider()
        val lastIndex = itemCount - 1
        val item = items[position]
        loadPoster(holder.poster, posterGetter(item))
        holder.title.text = buildHighlightedTitle(nameGetter(item), q, ctx)
        holder.itemView.setOnClickListener { onPick(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onPick(item)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (position == lastIndex) onRequestFocusNextRow() else false
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: ImageView = itemView.findViewById(R.id.search_vod_poster)
        val title: TextView = itemView.findViewById(R.id.search_vod_title)
    }
}
