package com.example.new_tv_app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.EpgListing
import com.example.new_tv_app.iptv.LiveCategory
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.XtreamLiveApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class LiveTvFragment : Fragment() {

    private var selectedCategoryId: String? = null
    private var streamsLoadedForCategoryId: String? = null
    private var loadStreamsJob: Job? = null
    private var epgFetchJob: Job? = null
    /** Prevents stale EPG from repainting after the user moved focus. */
    private var heroStreamId: String? = null
    /** After first stream list loads, move D-pad focus to channel index 0 (not the category row). */
    private var pendingInitialChannelFocus = true
    /** User pressed DPAD down from a category — focus channel 0 after streams are ready. */
    private var pendingFocusFirstAfterCategoryDown = false

    private lateinit var categoryAdapter: LiveCategoryAdapter
    private lateinit var channelAdapter: LiveChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_live_tv, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val loading = view.findViewById<ProgressBar>(R.id.live_loading)
        val error = view.findViewById<TextView>(R.id.live_error)

        val watermark = view.findViewById<ImageView>(R.id.live_hero_watermark)
        val heroLogo = view.findViewById<ImageView>(R.id.live_hero_channel_logo)
        val heroBackdrop = view.findViewById<ImageView>(R.id.live_hero_backdrop)
        val heroChannelName = view.findViewById<TextView>(R.id.live_hero_channel_name)
        val nowBadge = view.findViewById<TextView>(R.id.live_now_badge)
        val programmeTitle = view.findViewById<TextView>(R.id.live_programme_title)
        val genre = view.findViewById<TextView>(R.id.live_genre)
        val genreUnderline = view.findViewById<View>(R.id.live_genre_underline)
        val description = view.findViewById<TextView>(R.id.live_description)

        val categoriesRv = view.findViewById<RecyclerView>(R.id.live_categories_list)
        val channelsRv = view.findViewById<RecyclerView>(R.id.live_channels_list)
        val channelsEmpty = view.findViewById<TextView>(R.id.live_channels_empty)
        /** Left column only: DPAD left jumps to this focusable sidebar row (not the whole shell). */
        val sidebarFocusAnchorId = R.id.row_live

        fun loadIcon(url: String?, into: ImageView) {
            if (url.isNullOrBlank()) {
                Glide.with(into).clear(into)
                into.setImageDrawable(null)
            } else {
                Glide.with(into).load(url).fitCenter().into(into)
            }
        }

        fun bindHero(stream: LiveStream?, categoryName: String, epg: EpgListing?) {
            if (stream == null) {
                epgFetchJob?.cancel()
                Glide.with(watermark).clear(watermark)
                watermark.setImageDrawable(null)
                Glide.with(heroBackdrop).clear(heroBackdrop)
                heroBackdrop.setImageDrawable(null)
                loadIcon(null, heroLogo)
                heroChannelName.text = getString(R.string.live_empty_select)
                nowBadge.isVisible = false
                programmeTitle.text = ""
                genre.text = ""
                genreUnderline.isVisible = false
                description.text = ""
                return
            }

            val icon = stream.iconUrl
            loadIcon(icon, watermark)
            loadIcon(icon, heroLogo)
            loadIcon(icon, heroBackdrop)

            heroChannelName.text = stream.name

            if (epg != null) {
                nowBadge.isVisible = true
                nowBadge.text = getString(R.string.live_now_badge)
                programmeTitle.text = epg.title
                val genreLine = epg.category?.takeIf { it.isNotBlank() } ?: categoryName
                genre.text = genreLine
                genreUnderline.isVisible = genreLine.isNotBlank()
                description.text = epg.description.trim().ifBlank {
                    getString(R.string.live_no_description)
                }
            } else {
                nowBadge.isVisible = true
                nowBadge.text = getString(R.string.live_live_badge)
                programmeTitle.text = stream.name
                genre.text = categoryName
                genreUnderline.isVisible = categoryName.isNotBlank()
                description.text = getString(R.string.live_no_description)
            }
        }

        fun showDetail(stream: LiveStream?, categoryName: String) {
            heroStreamId = stream?.streamId
            bindHero(stream, categoryName, epg = null)
            if (stream == null) {
                epgFetchJob?.cancel()
                return
            }
            val sid = stream.streamId
            epgFetchJob?.cancel()
            epgFetchJob = viewLifecycleOwner.lifecycleScope.launch {
                val listings = XtreamLiveApi.fetchShortEpg(sid).getOrNull().orEmpty()
                if (!isAdded || heroStreamId != sid) return@launch
                val now = System.currentTimeMillis() / 1000L
                val current = listings.find { now >= it.startUnix && now < it.endUnix }
                    ?: listings.firstOrNull()
                bindHero(stream, categoryName, current)
            }
        }

        fun clearDetailPlaceholder(categoryName: String) {
            showDetail(null, categoryName)
        }

        val gridSpan = 6

        channelAdapter = LiveChannelAdapter(
            spanCount = gridSpan,
            sidebarFocusAnchorId = sidebarFocusAnchorId,
            categoriesRecyclerView = categoriesRv,
            selectedCategoryIndex = {
                categoryAdapter.indexOfCategoryId(selectedCategoryId)
            },
            categoryNameProvider = {
                val id = selectedCategoryId
                categoryAdapter.findName(id) ?: "—"
            },
            focusGridCellAt = { pos -> requestFocusGridCell(channelsRv, pos) },
            onChannelFocused = { stream, categoryName ->
                showDetail(stream, categoryName)
            },
            onChannelPlay = { stream, categoryName ->
                val url = IptvStreamUrls.liveStreamUrl(stream.streamId)
                val movie = Movie(
                    id = stream.streamId.hashCode().toLong(),
                    title = stream.name,
                    description = categoryName,
                    backgroundImageUrl = stream.iconUrl,
                    cardImageUrl = stream.iconUrl,
                    videoUrl = url,
                    studio = null,
                )
                val catId = stream.categoryId ?: selectedCategoryId
                startActivity(
                    Intent(requireContext(), PlaybackActivity::class.java).apply {
                        putExtra(DetailsActivity.MOVIE, movie)
                        if (!catId.isNullOrBlank()) {
                            putExtra(PlaybackActivity.LIVE_CATEGORY_ID, catId)
                            putExtra(PlaybackActivity.LIVE_STREAM_ID, stream.streamId)
                        }
                    }
                )
            },
        )

        fun requestFocusFirstChannel() {
            if (channelAdapter.itemCount <= 0) return
            // Do not clearFocus() on main_content: after clearing, the framework often
            // focuses the first focusable in the fragment (first category chip), which
            // triggers scheduleLoadStreams(first) and breaks the selected category.
            channelsRv.scrollToPosition(0)
            fun tryFocus(attempt: Int) {
                channelsRv.post {
                    val h = channelsRv.findViewHolderForAdapterPosition(0)
                    if (h != null) {
                        h.itemView.requestFocus()
                    } else if (attempt < 16) {
                        channelsRv.postDelayed({ tryFocus(attempt + 1) }, 24L)
                    }
                }
            }
            tryFocus(0)
        }

        fun scheduleLoadStreams(cat: LiveCategory) {
            val previousId = selectedCategoryId
            selectedCategoryId = cat.id
            // Avoid notifyDataSetChanged(): it rebinds every chip and drops D-pad focus on the category row.
            if (previousId != cat.id) {
                if (previousId != null) {
                    val pi = categoryAdapter.indexOfCategoryId(previousId)
                    if (pi >= 0) categoryAdapter.notifyItemChanged(pi)
                }
                val ni = categoryAdapter.indexOfCategoryId(cat.id)
                if (ni >= 0) categoryAdapter.notifyItemChanged(ni)
            }
            if (streamsLoadedForCategoryId == cat.id && channelAdapter.itemCount > 0) {
                channelAdapter.firstStream()?.let { showDetail(it, cat.name) }
                    ?: clearDetailPlaceholder(cat.name)
                when {
                    pendingFocusFirstAfterCategoryDown && channelAdapter.itemCount > 0 -> {
                        pendingFocusFirstAfterCategoryDown = false
                        requestFocusFirstChannel()
                    }
                    pendingInitialChannelFocus && channelAdapter.itemCount > 0 -> {
                        pendingInitialChannelFocus = false
                        requestFocusFirstChannel()
                    }
                }
                return
            }
            clearDetailPlaceholder(cat.name)
            loadStreamsJob?.cancel()
            loadStreamsJob = viewLifecycleOwner.lifecycleScope.launch {
                val result = XtreamLiveApi.fetchLiveStreams(cat.id)
                if (!isAdded) return@launch
                result.fold(
                    onSuccess = { streams ->
                        if (selectedCategoryId != cat.id) return@fold
                        streamsLoadedForCategoryId = cat.id
                        channelAdapter.submit(streams)
                        channelsEmpty.isVisible = streams.isEmpty()
                        if (streams.isNotEmpty()) {
                            showDetail(streams.first(), cat.name)
                            when {
                                pendingFocusFirstAfterCategoryDown -> {
                                    pendingFocusFirstAfterCategoryDown = false
                                    requestFocusFirstChannel()
                                }
                                pendingInitialChannelFocus -> {
                                    pendingInitialChannelFocus = false
                                    requestFocusFirstChannel()
                                }
                            }
                        } else {
                            clearDetailPlaceholder(cat.name)
                            pendingFocusFirstAfterCategoryDown = false
                        }
                    },
                    onFailure = {
                        if (selectedCategoryId != cat.id) return@fold
                        streamsLoadedForCategoryId = null
                        channelAdapter.submit(emptyList())
                        channelsEmpty.isVisible = true
                        clearDetailPlaceholder(cat.name)
                        pendingFocusFirstAfterCategoryDown = false
                    }
                )
            }
        }

        fun onCategoryDpadDown(cat: LiveCategory) {
            pendingFocusFirstAfterCategoryDown = true
            scheduleLoadStreams(cat)
        }

        categoryAdapter = LiveCategoryAdapter(
            selectedIdProvider = { selectedCategoryId },
            onItemFocused = { cat -> scheduleLoadStreams(cat) },
            onCategoryDpadDown = { cat -> onCategoryDpadDown(cat) }
        )

        categoriesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        categoriesRv.adapter = categoryAdapter
        categoriesRv.setHasFixedSize(true)
        categoriesRv.itemAnimator = null

        val spacing = resources.getDimensionPixelSize(R.dimen.live_grid_spacing)
        channelsRv.layoutManager = GridLayoutManager(requireContext(), gridSpan)
        channelsRv.adapter = channelAdapter
        channelsRv.addItemDecoration(GridSpacingItemDecoration(gridSpan, spacing))
        channelsRv.setHasFixedSize(true)
        channelsRv.itemAnimator = null

        val mainContent = requireActivity().findViewById<View>(R.id.main_content)
        val savedMainNextFocusLeft = mainContent.nextFocusLeftId
        mainContent.nextFocusLeftId = View.NO_ID
        viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    mainContent.nextFocusLeftId = savedMainNextFocusLeft
                }
            }
        )

        loading.isVisible = true
        error.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = XtreamLiveApi.fetchLiveCategories()
            loading.isVisible = false
            result.fold(
                onSuccess = { cats ->
                    if (cats.isEmpty()) {
                        error.text = getString(R.string.live_load_error)
                        error.isVisible = true
                        return@fold
                    }
                    categoryAdapter.submit(cats)
                    pendingInitialChannelFocus = true
                    scheduleLoadStreams(cats.first())
                },
                onFailure = {
                    error.text = getString(R.string.live_load_error)
                    error.isVisible = true
                }
            )
        }
    }

    override fun onDestroyView() {
        epgFetchJob?.cancel()
        loadStreamsJob?.cancel()
        super.onDestroyView()
    }

}

/** Horizontal category list: next item may be off-screen; scroll then focus (DPAD hits focused child, not the RV). */
private fun requestFocusCategoryAfterScroll(rv: RecyclerView, adapterPosition: Int) {
    rv.post {
        val h = rv.findViewHolderForAdapterPosition(adapterPosition)
        if (h != null) {
            h.itemView.requestFocus()
        } else {
            rv.postDelayed(
                {
                    rv.findViewHolderForAdapterPosition(adapterPosition)?.itemView?.requestFocus()
                },
                64L
            )
        }
    }
}

/** Focus a grid cell after scrolling (DPAD row wrap across incomplete last row). */
private fun requestFocusGridCell(rv: RecyclerView, adapterPosition: Int, attemptsRemaining: Int = 24) {
    rv.scrollToPosition(adapterPosition)
    rv.post {
        val h = rv.findViewHolderForAdapterPosition(adapterPosition)
        if (h != null) {
            h.itemView.requestFocus()
        } else if (attemptsRemaining > 0) {
            rv.postDelayed({ requestFocusGridCell(rv, adapterPosition, attemptsRemaining - 1) }, 32L)
        }
    }
}

private class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacingPx: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
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

private class LiveCategoryAdapter(
    private val selectedIdProvider: () -> String?,
    private val onItemFocused: (LiveCategory) -> Unit,
    private val onCategoryDpadDown: (LiveCategory) -> Unit,
) : RecyclerView.Adapter<LiveCategoryAdapter.VH>() {

    private val items = mutableListOf<LiveCategory>()

    fun submit(list: List<LiveCategory>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun findName(categoryId: String?): String? {
        if (categoryId == null) return null
        return items.find { it.id == categoryId }?.name
    }

    fun indexOfCategoryId(categoryId: String?): Int {
        if (categoryId == null) return 0
        val i = items.indexOfFirst { it.id == categoryId }
        return if (i >= 0) i else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_category, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = items[position]
        holder.text.text = cat.name.uppercase(Locale.getDefault())
        holder.text.isSelected = cat.id == selectedIdProvider()
        holder.text.nextFocusLeftId = View.NO_ID
        holder.text.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onCategoryDpadDown(items[pos])
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos + 1) != null) {
                        return@setOnKeyListener false
                    }
                    val next = pos + 1
                    rv.scrollToPosition(next)
                    requestFocusCategoryAfterScroll(rv, next)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos - 1) != null) {
                        return@setOnKeyListener false
                    }
                    val prev = pos - 1
                    rv.scrollToPosition(prev)
                    requestFocusCategoryAfterScroll(rv, prev)
                    true
                }
                else -> false
            }
        }
    }

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
        init {
            text.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    text.requestFocus()
                    onItemFocused(items[pos])
                }
            }
            text.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemFocused(items[pos])
                    }
                }
            }
        }
    }
}

private class LiveChannelAdapter(
    private val spanCount: Int,
    private val sidebarFocusAnchorId: Int,
    private val categoriesRecyclerView: RecyclerView,
    private val selectedCategoryIndex: () -> Int,
    private val categoryNameProvider: () -> String,
    private val focusGridCellAt: (Int) -> Unit,
    private val onChannelFocused: (LiveStream, String) -> Unit,
    private val onChannelPlay: (LiveStream, String) -> Unit,
) : RecyclerView.Adapter<LiveChannelAdapter.VH>() {

    private val items = mutableListOf<LiveStream>()

    fun submit(list: List<LiveStream>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun firstStream(): LiveStream? = items.firstOrNull()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stream = items[position]
        holder.name.text = stream.name
        val col = position % spanCount
        val catIdx = selectedCategoryIndex()
        holder.itemView.nextFocusLeftId =
            if (col == 0 && catIdx <= 0) sidebarFocusAnchorId else View.NO_ID
        holder.itemView.nextFocusUpId = View.NO_ID
        val url = stream.iconUrl
        if (url.isNullOrBlank()) {
            Glide.with(holder.icon).clear(holder.icon)
            holder.icon.setImageDrawable(null)
        } else {
            Glide.with(holder.icon).load(url).fitCenter().into(holder.icon)
        }
        val rowStart = (position / spanCount) * spanCount
        val rowEnd = kotlin.math.min(rowStart + spanCount - 1, itemCount - 1)
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val idx = selectedCategoryIndex()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (position == rowStart) {
                        if (position > 0) {
                            focusGridCellAt(position - 1)
                            true
                        } else {
                            true
                        }
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (position == rowEnd) {
                        if (position < itemCount - 1) {
                            focusGridCellAt(position + 1)
                            true
                        } else {
                            true
                        }
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (position < spanCount) {
                        categoriesRecyclerView.scrollToPosition(idx)
                        requestFocusCategoryAfterScroll(categoriesRecyclerView, idx)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    onChannelPlay(stream, categoryNameProvider())
                    true
                }
                else -> false
            }
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onChannelPlay(stream, categoryNameProvider())
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                onChannelFocused(stream, categoryNameProvider())
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.live_channel_icon)
        val name: TextView = itemView.findViewById(R.id.live_channel_name)
    }
}
