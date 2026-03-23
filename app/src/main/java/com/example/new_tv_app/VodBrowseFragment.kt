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
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.SeriesShow
import com.example.new_tv_app.iptv.VodMovieItem
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LastWatchStore
import com.example.new_tv_app.iptv.XtreamVodApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private data class VodCatalogChip(
    val id: String,
    val name: String,
)

/**
 * Movies or series browse: same layout rhythm as [LiveTvFragment], but the hero left column has **no** logo image — title text only.
 */
class VodBrowseFragment : Fragment() {

    private val mode: String by lazy { requireArguments().getString(ARG_MODE) ?: MODE_MOVIES }

    private var selectedCategoryId: String? = null
    private var itemsLoadedForCategoryId: String? = null
    private var loadItemsJob: Job? = null
    private var pendingInitialGridFocus = true
    private var pendingFocusFirstAfterCategoryDown = false

    private val movies = mutableListOf<VodMovieItem>()
    private val shows = mutableListOf<SeriesShow>()

    private lateinit var catalogAdapter: VodCatalogAdapter
    private lateinit var gridAdapter: VodGridAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_vod_browse, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val loading = view.findViewById<ProgressBar>(R.id.vod_loading)
        val error = view.findViewById<TextView>(R.id.vod_error)

        val watermark = view.findViewById<ImageView>(R.id.vod_hero_watermark)
        val backdrop = view.findViewById<ImageView>(R.id.vod_hero_backdrop)
        val leftTitle = view.findViewById<TextView>(R.id.vod_hero_left_title)
        val badge = view.findViewById<TextView>(R.id.vod_hero_badge)
        val title = view.findViewById<TextView>(R.id.vod_hero_title)
        val genre = view.findViewById<TextView>(R.id.vod_hero_genre)
        val genreUnderline = view.findViewById<View>(R.id.vod_hero_genre_underline)
        val description = view.findViewById<TextView>(R.id.vod_hero_description)

        val categoriesRv = view.findViewById<RecyclerView>(R.id.vod_categories_list)
        val itemsRv = view.findViewById<RecyclerView>(R.id.vod_items_list)
        val itemsEmpty = view.findViewById<TextView>(R.id.vod_items_empty)

        val sidebarFocusAnchorId =
            if (mode == MODE_SERIES) R.id.row_vod_series else R.id.row_vod_movies

        fun loadCover(url: String?, into: ImageView) {
            if (url.isNullOrBlank()) {
                Glide.with(into).clear(into)
                into.setImageDrawable(null)
            } else {
                Glide.with(into).load(url).fitCenter().into(into)
            }
        }

        fun bindHeroEmpty(categoryName: String) {
            Glide.with(watermark).clear(watermark)
            watermark.setImageDrawable(null)
            Glide.with(backdrop).clear(backdrop)
            backdrop.setImageDrawable(null)
            leftTitle.text = getString(R.string.vod_empty_select)
            badge.text = if (mode == MODE_SERIES) getString(R.string.vod_badge_series) else getString(R.string.vod_badge_movie)
            title.text = ""
            genre.text = categoryName
            genreUnderline.isVisible = categoryName.isNotBlank()
            description.text = ""
        }

        fun bindHeroMovie(item: VodMovieItem, categoryName: String) {
            loadCover(item.coverUrl, watermark)
            loadCover(item.coverUrl, backdrop)
            leftTitle.text = item.name
            badge.text = getString(R.string.vod_badge_movie)
            title.text = item.name
            genre.text = categoryName
            genreUnderline.isVisible = categoryName.isNotBlank()
            description.text = item.plot?.trim().orEmpty().ifBlank { getString(R.string.live_no_description) }
        }

        fun bindHeroSeries(show: SeriesShow, categoryName: String) {
            loadCover(show.coverUrl, watermark)
            loadCover(show.coverUrl, backdrop)
            leftTitle.text = show.name
            badge.text = getString(R.string.vod_badge_series)
            title.text = show.name
            genre.text = categoryName
            genreUnderline.isVisible = categoryName.isNotBlank()
            description.text = show.plot?.trim().orEmpty().ifBlank { getString(R.string.live_no_description) }
        }

        fun showMovieDetail(item: VodMovieItem?, categoryName: String) {
            if (item == null) {
                bindHeroEmpty(categoryName)
                return
            }
            bindHeroMovie(item, categoryName)
        }

        fun showSeriesDetail(show: SeriesShow?, categoryName: String) {
            if (show == null) {
                bindHeroEmpty(categoryName)
                return
            }
            bindHeroSeries(show, categoryName)
        }

        fun startPlayback(titleStr: String, category: String, cover: String?, url: String) {
            val movie = Movie(
                id = titleStr.hashCode().toLong(),
                title = titleStr,
                description = category,
                backgroundImageUrl = cover,
                cardImageUrl = cover,
                videoUrl = url,
                studio = null,
            )

            val played = IptvTimeUtils.nowIsraelSeconds()
            if (mode == MODE_SERIES) {
                LastWatchStore.addVodSeries(
                    context = requireContext(),
                    playedUnixSeconds = played,
                    movie = movie,
                    imageUrl = cover,
                )
            } else {
                LastWatchStore.addVodMovies(
                    context = requireContext(),
                    playedUnixSeconds = played,
                    movie = movie,
                    imageUrl = cover,
                )
            }

            startActivity(
                Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, movie)
                },
            )
        }

        val gridSpan = 6

        gridAdapter = VodGridAdapter(
            mode = mode,
            spanCount = gridSpan,
            sidebarFocusAnchorId = sidebarFocusAnchorId,
            categoriesRecyclerView = categoriesRv,
            selectedCategoryIndex = { catalogAdapter.indexOfCategoryId(selectedCategoryId) },
            categoryNameProvider = {
                val id = selectedCategoryId
                catalogAdapter.findName(id) ?: "—"
            },
            movies = movies,
            shows = shows,
            onMovieFocused = { m, cat -> showMovieDetail(m, cat) },
            onSeriesFocused = { s, cat -> showSeriesDetail(s, cat) },
            onMoviePlay = { m, cat ->
                val url = IptvStreamUrls.vodMovieUrl(m.streamId, m.containerExtension)
                startPlayback(m.name, cat, m.coverUrl, url)
            },
            onSeriesPlay = { s, cat ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val r = XtreamVodApi.fetchFirstSeriesEpisode(s.seriesId)
                    if (!isAdded) return@launch
                    r.fold(
                        onSuccess = { (epId, ext) ->
                            val url = IptvStreamUrls.seriesEpisodeUrl(epId, ext)
                            startPlayback(s.name, cat, s.coverUrl, url)
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
            },
        )

        fun requestFocusFirstGridItem() {
            itemsRv.post {
                itemsRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: itemsRv.post {
                        itemsRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
            }
        }

        fun scheduleLoadItems(chip: VodCatalogChip) {
            val previousId = selectedCategoryId
            selectedCategoryId = chip.id
            if (previousId != chip.id) {
                if (previousId != null) {
                    val pi = catalogAdapter.indexOfCategoryId(previousId)
                    if (pi >= 0) catalogAdapter.notifyItemChanged(pi)
                }
                val ni = catalogAdapter.indexOfCategoryId(chip.id)
                if (ni >= 0) catalogAdapter.notifyItemChanged(ni)
            }

            if (itemsLoadedForCategoryId == chip.id && gridAdapter.itemCount > 0) {
                when (mode) {
                    MODE_MOVIES -> showMovieDetail(movies.firstOrNull(), chip.name)
                    else -> showSeriesDetail(shows.firstOrNull(), chip.name)
                }
                when {
                    pendingFocusFirstAfterCategoryDown && gridAdapter.itemCount > 0 -> {
                        pendingFocusFirstAfterCategoryDown = false
                        requestFocusFirstGridItem()
                    }
                    pendingInitialGridFocus && gridAdapter.itemCount > 0 -> {
                        pendingInitialGridFocus = false
                        requestFocusFirstGridItem()
                    }
                }
                return
            }

            when (mode) {
                MODE_MOVIES -> showMovieDetail(null, chip.name)
                else -> showSeriesDetail(null, chip.name)
            }
            loadItemsJob?.cancel()
            loadItemsJob = viewLifecycleOwner.lifecycleScope.launch {
                fun onLoadedOk() {
                    if (selectedCategoryId != chip.id) return
                    itemsLoadedForCategoryId = chip.id
                    gridAdapter.notifyDataSetChanged()
                    itemsEmpty.isVisible = gridAdapter.itemCount == 0
                    if (gridAdapter.itemCount > 0) {
                        when (mode) {
                            MODE_MOVIES -> showMovieDetail(movies.first(), chip.name)
                            else -> showSeriesDetail(shows.first(), chip.name)
                        }
                        when {
                            pendingFocusFirstAfterCategoryDown -> {
                                pendingFocusFirstAfterCategoryDown = false
                                requestFocusFirstGridItem()
                            }
                            pendingInitialGridFocus -> {
                                pendingInitialGridFocus = false
                                requestFocusFirstGridItem()
                            }
                        }
                    } else {
                        when (mode) {
                            MODE_MOVIES -> showMovieDetail(null, chip.name)
                            else -> showSeriesDetail(null, chip.name)
                        }
                        pendingFocusFirstAfterCategoryDown = false
                    }
                }

                fun onLoadedFail() {
                    if (selectedCategoryId != chip.id) return
                    itemsLoadedForCategoryId = null
                    movies.clear()
                    shows.clear()
                    gridAdapter.notifyDataSetChanged()
                    itemsEmpty.isVisible = true
                    when (mode) {
                        MODE_MOVIES -> showMovieDetail(null, chip.name)
                        else -> showSeriesDetail(null, chip.name)
                    }
                    pendingFocusFirstAfterCategoryDown = false
                }

                when (mode) {
                    MODE_MOVIES -> {
                        val result = XtreamVodApi.fetchVodStreams(chip.id)
                        if (!isAdded) return@launch
                        result.fold(
                            onSuccess = { list ->
                                movies.clear()
                                movies.addAll(list)
                                onLoadedOk()
                            },
                            onFailure = { onLoadedFail() },
                        )
                    }
                    else -> {
                        val result = XtreamVodApi.fetchSeries(chip.id)
                        if (!isAdded) return@launch
                        result.fold(
                            onSuccess = { list ->
                                shows.clear()
                                shows.addAll(list)
                                onLoadedOk()
                            },
                            onFailure = { onLoadedFail() },
                        )
                    }
                }
            }
        }

        fun onCatalogDpadDown(chip: VodCatalogChip) {
            pendingFocusFirstAfterCategoryDown = true
            scheduleLoadItems(chip)
        }

        catalogAdapter = VodCatalogAdapter(
            selectedIdProvider = { selectedCategoryId },
            onItemFocused = { scheduleLoadItems(it) },
            onCatalogDpadDown = { onCatalogDpadDown(it) },
        )

        categoriesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        categoriesRv.adapter = catalogAdapter
        categoriesRv.setHasFixedSize(true)
        categoriesRv.itemAnimator = null

        val spacing = resources.getDimensionPixelSize(R.dimen.live_grid_spacing)
        itemsRv.layoutManager = GridLayoutManager(requireContext(), gridSpan)
        itemsRv.adapter = gridAdapter
        itemsRv.addItemDecoration(VodGridSpacingItemDecoration(gridSpan, spacing))
        itemsRv.setHasFixedSize(true)
        itemsRv.itemAnimator = null

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

        loading.isVisible = true
        error.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = when (mode) {
                MODE_MOVIES -> XtreamVodApi.fetchVodCategories().map { list ->
                    list.map { VodCatalogChip(it.id, it.name) }
                }
                else -> XtreamVodApi.fetchSeriesCategories().map { list ->
                    list.map { VodCatalogChip(it.id, it.name) }
                }
            }
            loading.isVisible = false
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { chips ->
                    if (chips.isEmpty()) {
                        error.text = getString(R.string.vod_load_error)
                        error.isVisible = true
                        return@fold
                    }
                    catalogAdapter.submit(chips)
                    pendingInitialGridFocus = true
                    scheduleLoadItems(chips.first())
                },
                onFailure = {
                    error.text = getString(R.string.vod_load_error)
                    error.isVisible = true
                },
            )
        }
    }

    override fun onDestroyView() {
        loadItemsJob?.cancel()
        super.onDestroyView()
    }

    companion object {
        const val ARG_MODE = "vod_mode"
        const val MODE_MOVIES = "movies"
        const val MODE_SERIES = "series"

        fun newInstance(mode: String) = VodBrowseFragment().apply {
            arguments = bundleOf(ARG_MODE to mode)
        }
    }
}

private class VodGridSpacingItemDecoration(
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

private class VodCatalogAdapter(
    private val selectedIdProvider: () -> String?,
    private val onItemFocused: (VodCatalogChip) -> Unit,
    private val onCatalogDpadDown: (VodCatalogChip) -> Unit,
) : RecyclerView.Adapter<VodCatalogAdapter.VH>() {

    private val items = mutableListOf<VodCatalogChip>()

    fun submit(list: List<VodCatalogChip>) {
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
        val chip = items[position]
        holder.text.text = chip.name.uppercase(Locale.getDefault())
        holder.text.isSelected = chip.id == selectedIdProvider()
        holder.text.nextFocusLeftId = View.NO_ID
        holder.text.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onCatalogDpadDown(items[pos])
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos + 1) != null) return@setOnKeyListener false
                    val next = pos + 1
                    rv.scrollToPosition(next)
                    requestFocusVodCategoryAfterScroll(rv, next)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos - 1) != null) return@setOnKeyListener false
                    val prev = pos - 1
                    rv.scrollToPosition(prev)
                    requestFocusVodCategoryAfterScroll(rv, prev)
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

private fun requestFocusVodCategoryAfterScroll(rv: RecyclerView, adapterPosition: Int) {
    rv.post {
        val h = rv.findViewHolderForAdapterPosition(adapterPosition)
        if (h != null) {
            h.itemView.requestFocus()
        } else {
            rv.postDelayed(
                {
                    rv.findViewHolderForAdapterPosition(adapterPosition)?.itemView?.requestFocus()
                },
                64L,
            )
        }
    }
}

private class VodGridAdapter(
    private val mode: String,
    private val spanCount: Int,
    private val sidebarFocusAnchorId: Int,
    private val categoriesRecyclerView: RecyclerView,
    private val selectedCategoryIndex: () -> Int,
    private val categoryNameProvider: () -> String,
    private val movies: MutableList<VodMovieItem>,
    private val shows: MutableList<SeriesShow>,
    private val onMovieFocused: (VodMovieItem, String) -> Unit,
    private val onSeriesFocused: (SeriesShow, String) -> Unit,
    private val onMoviePlay: (VodMovieItem, String) -> Unit,
    private val onSeriesPlay: (SeriesShow, String) -> Unit,
) : RecyclerView.Adapter<VodGridAdapter.VH>() {

    override fun getItemCount(): Int =
        if (mode == VodBrowseFragment.MODE_MOVIES) movies.size else shows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val col = position % spanCount
        holder.itemView.nextFocusLeftId =
            if (col == 0) sidebarFocusAnchorId else View.NO_ID
        holder.itemView.nextFocusUpId = View.NO_ID

        if (mode == VodBrowseFragment.MODE_MOVIES) {
            val m = movies[position]
            holder.name.text = m.name
            loadGridIcon(holder.icon, m.coverUrl)
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position < spanCount) {
                            val idx = selectedCategoryIndex()
                            categoriesRecyclerView.scrollToPosition(idx)
                            requestFocusVodCategoryAfterScroll(categoriesRecyclerView, idx)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onMoviePlay(m, categoryNameProvider())
                        true
                    }
                    else -> false
                }
            }
            holder.itemView.setOnClickListener {
                holder.itemView.requestFocus()
                onMoviePlay(m, categoryNameProvider())
            }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onMovieFocused(m, categoryNameProvider())
            }
        } else {
            val s = shows[position]
            holder.name.text = s.name
            loadGridIcon(holder.icon, s.coverUrl)
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position < spanCount) {
                            val idx = selectedCategoryIndex()
                            categoriesRecyclerView.scrollToPosition(idx)
                            requestFocusVodCategoryAfterScroll(categoriesRecyclerView, idx)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onSeriesPlay(s, categoryNameProvider())
                        true
                    }
                    else -> false
                }
            }
            holder.itemView.setOnClickListener {
                holder.itemView.requestFocus()
                onSeriesPlay(s, categoryNameProvider())
            }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onSeriesFocused(s, categoryNameProvider())
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.live_channel_icon)
        val name: TextView = itemView.findViewById(R.id.live_channel_name)
    }
}

private fun loadGridIcon(icon: ImageView, url: String?) {
    if (url.isNullOrBlank()) {
        Glide.with(icon).clear(icon)
        icon.setImageDrawable(null)
    } else {
        Glide.with(icon).load(url).fitCenter().into(icon)
    }
}
