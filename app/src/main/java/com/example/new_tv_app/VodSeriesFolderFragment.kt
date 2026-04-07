package com.example.new_tv_app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.FavoriteVodStore
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LastWatchPlaybackPositionStore
import com.example.new_tv_app.iptv.LastWatchStore
import com.example.new_tv_app.iptv.SeriesDetails
import com.example.new_tv_app.iptv.SeriesEpisode
import com.example.new_tv_app.iptv.SeriesSeason
import com.example.new_tv_app.iptv.XtreamVodApi
import kotlinx.coroutines.launch
import java.util.Locale

class VodSeriesFolderFragment : Fragment() {

    private val seriesId: String by lazy { requireArguments().getString(ARG_SERIES_ID).orEmpty() }
    private val seriesName: String by lazy { requireArguments().getString(ARG_SERIES_NAME).orEmpty() }
    private val seriesCover: String? by lazy { requireArguments().getString(ARG_SERIES_COVER) }
    private val categoryName: String by lazy { requireArguments().getString(ARG_CATEGORY_NAME).orEmpty() }
    private val seriesPlotFromList: String? by lazy {
        requireArguments().getString(ARG_SERIES_PLOT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private var seriesPlotForHero: String = ""

    private lateinit var episodesAdapter: EpisodesAdapter
    private lateinit var seasonsAdapter: SeasonsRowAdapter
    private val shownEpisodes = mutableListOf<SeriesEpisode>()
    private var selectedSeasonNumber: Int? = null
    private var cachedDetails: SeriesDetails? = null
    private var sortDescending = true

    private lateinit var backdropImg: ImageView
    private lateinit var thumbnailImg: ImageView
    private lateinit var descriptionTv: TextView
    private lateinit var seasonsRv: RecyclerView
    private lateinit var episodesRv: RecyclerView
    private lateinit var episodesEmpty: TextView
    private lateinit var favoriteBtn: ImageView
    private lateinit var sortBtn: ImageView

    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_vod_series_folder, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backdropImg = view.findViewById(R.id.series_hero_backdrop)
        thumbnailImg = view.findViewById(R.id.series_hero_thumbnail)
        val badgeTv = view.findViewById<TextView>(R.id.series_hero_badge)
        val titleTv = view.findViewById<TextView>(R.id.series_hero_title)
        val genreTv = view.findViewById<TextView>(R.id.series_hero_genre)
        descriptionTv = view.findViewById(R.id.series_hero_description)
        val loading = view.findViewById<ProgressBar>(R.id.series_loading)
        val error = view.findViewById<TextView>(R.id.series_error)
        episodesRv = view.findViewById(R.id.series_episodes_list)
        episodesEmpty = view.findViewById(R.id.series_episodes_empty)
        seasonsRv = view.findViewById(R.id.series_seasons_list)
        favoriteBtn = view.findViewById(R.id.series_btn_favorite)
        sortBtn = view.findViewById(R.id.series_btn_sort)

        badgeTv.text = getString(R.string.vod_badge_series)
        titleTv.text = seriesName
        genreTv.text = categoryName
        descriptionTv.text = getString(R.string.vod_series_loading)

        loadImage(backdropImg, seriesCover)
        loadImage(thumbnailImg, seriesCover)

        refreshFavoriteIcon()
        favoriteBtn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bindSeriesHeroDescription()
        }
        favoriteBtn.setOnClickListener { toggleFavorite() }
        favoriteBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusSeasonChipForNavigation()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    toggleFavorite()
                    true
                }
                else -> false
            }
        }

        sortBtn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bindSeriesHeroDescription()
        }
        sortBtn.setOnClickListener { toggleSort() }
        sortBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusSeasonChipForNavigation()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    toggleSort()
                    true
                }
                else -> false
            }
        }

        seasonsAdapter = SeasonsRowAdapter(
            selectedSeasonProvider = { selectedSeasonNumber },
            onSeasonFocused = { season -> bindEpisodesForSeason(season.seasonNumber) },
            onDpadDown = { requestFocusFirstEpisode() },
        )
        seasonsRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        seasonsRv.adapter = seasonsAdapter
        seasonsRv.itemAnimator = null

        episodesAdapter = EpisodesAdapter(
            episodes = shownEpisodes,
            gridSpan = EPISODE_GRID_SPAN,
            fallbackCoverUrl = { seriesCover },
            onEpisodeFocused = { ep ->
                val epPlot = ep.plot?.trim().orEmpty()
                descriptionTv.text = epPlot.ifBlank { seriesPlotForHero }
                    .ifBlank { getString(R.string.live_no_description) }
                loadImage(backdropImg, ep.coverUrl?.takeIf { it.isNotBlank() } ?: seriesCover)
            },
            onEpisodePlay = { ep -> startEpisodePlayback(ep) },
            onFirstRowDpadUp = { focusSeasonChipForNavigation() },
        )
        episodesRv.layoutManager = GridLayoutManager(requireContext(), EPISODE_GRID_SPAN)
        episodesRv.adapter = episodesAdapter
        episodesRv.itemAnimator = null

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.setFragmentResult(
                    VodBrowseFragment.SERIES_FOLDER_RESULT_KEY,
                    Bundle().apply {
                        putString(VodBrowseFragment.SERIES_FOLDER_RESULT_SERIES_ID, seriesId)
                    },
                )
                parentFragmentManager.popBackStack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        loading.isVisible = true
        error.isVisible = false
        episodesEmpty.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = XtreamVodApi.fetchSeriesDetails(seriesId)
            if (!isAdded) return@launch
            loading.isVisible = false
            result.fold(
                onSuccess = { details ->
                    cachedDetails = details
                    seriesPlotForHero = mergeSeriesPlot(details)
                    bindSeriesHeroDescription()
                    bindSeriesDetails(details)
                },
                onFailure = {
                    error.text = getString(R.string.vod_series_episode_error)
                    error.isVisible = true
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::favoriteBtn.isInitialized) {
            refreshFavoriteIcon()
        }
        if (::episodesAdapter.isInitialized) {
            refreshEpisodeWatchStatus()
        }
    }

    private fun requestFocusFirstEpisode() {
        if (shownEpisodes.isEmpty()) return
        episodesRv.post {
            requestFocusEpisodeAt(0)
        }
    }

    /** Focus the season chip that matches the current season (or first), for vertical navigation. */
    private fun focusSeasonChipForNavigation() {
        if (!::seasonsRv.isInitialized || !seasonsRv.isVisible || seasonsAdapter.itemCount == 0) {
            return
        }
        val index = seasonsAdapter.adapterPositionForSelected(selectedSeasonNumber)
        seasonsRv.post { requestFocusSeasonAtAdapterIndex(index) }
    }

    private fun requestFocusSeasonAtAdapterIndex(index: Int) {
        if (index < 0 || index >= seasonsAdapter.itemCount) return
        val vh = seasonsRv.findViewHolderForAdapterPosition(index)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            seasonsRv.scrollToPosition(index)
            seasonsRv.post {
                seasonsRv.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    private fun requestFocusEpisodeAt(adapterPosition: Int) {
        if (adapterPosition < 0 || adapterPosition >= episodesAdapter.itemCount) return
        val vh = episodesRv.findViewHolderForAdapterPosition(adapterPosition)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            episodesRv.scrollToPosition(adapterPosition)
            episodesRv.post {
                episodesRv.findViewHolderForAdapterPosition(adapterPosition)?.itemView?.requestFocus()
            }
        }
    }

    private fun mergeSeriesPlot(details: SeriesDetails): String {
        val fromApi = details.plot?.trim().orEmpty()
        val fromList = seriesPlotFromList.orEmpty()
        return when {
            fromApi.isNotEmpty() && fromList.isNotEmpty() ->
                maxOf(fromApi, fromList, compareBy { it.length })
            fromApi.isNotEmpty() -> fromApi
            fromList.isNotEmpty() -> fromList
            else -> ""
        }
    }

    private fun bindSeriesHeroDescription() {
        descriptionTv.text = seriesPlotForHero.ifBlank { getString(R.string.live_no_description) }
        loadImage(backdropImg, seriesCover)
    }

    private fun loadImage(into: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            Glide.with(into).clear(into)
            into.setImageDrawable(null)
        } else {
            Glide.with(into).load(url).centerCrop().into(into)
        }
    }

    private fun refreshFavoriteIcon() {
        val fav = FavoriteVodStore.isSeriesFolderFavorite(requireContext(), seriesId)
        if (fav) {
            favoriteBtn.imageTintList = null
            favoriteBtn.clearColorFilter()
            favoriteBtn.setImageResource(R.drawable.ic_favorite_filled)
        } else {
            favoriteBtn.setImageResource(R.drawable.ic_sidebar_favorite)
            favoriteBtn.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.sidebar_text_primary),
            )
        }
        favoriteBtn.contentDescription =
            getString(if (fav) R.string.playback_remove_from_favorites else R.string.playback_add_to_favorites)
    }

    private fun toggleFavorite() {
        val fav = FavoriteVodStore.isSeriesFolderFavorite(requireContext(), seriesId)
        if (fav) {
            FavoriteVodStore.remove(requireContext(), seriesId)
        } else {
            val movie = Movie(
                id = seriesId.hashCode().toLong(),
                title = seriesName,
                description = categoryName,
                backgroundImageUrl = seriesCover,
                cardImageUrl = seriesCover,
                videoUrl = FavoriteVodStore.seriesFolderBookmarkUrl(seriesId),
                studio = null,
            )
            FavoriteVodStore.add(requireContext(), movie)
        }
        refreshFavoriteIcon()
    }

    private fun toggleSort() {
        sortDescending = !sortDescending
        reorderEpisodes()
        episodesAdapter.notifyDataSetChanged()
    }

    private fun reorderEpisodes() {
        if (sortDescending) {
            shownEpisodes.sortByDescending { it.episodeNumber }
        } else {
            shownEpisodes.sortBy { it.episodeNumber }
        }
    }

    private fun bindSeriesDetails(details: SeriesDetails) {
        val seasonsWithEpisodes = details.seasons.filter { season ->
            !details.episodesBySeason[season.seasonNumber].isNullOrEmpty()
        }
        seasonsAdapter.submit(seasonsWithEpisodes)
        seasonsRv.isVisible = seasonsWithEpisodes.isNotEmpty()
        if (seasonsWithEpisodes.isEmpty()) {
            selectedSeasonNumber = null
            shownEpisodes.clear()
            episodesAdapter.notifyDataSetChanged()
            episodesEmpty.isVisible = true
            return
        }
        val first =
            selectedSeasonNumber?.takeIf { num ->
                seasonsWithEpisodes.any { it.seasonNumber == num }
            } ?: seasonsWithEpisodes.first().seasonNumber
        bindEpisodesForSeason(first)
    }

    private fun bindEpisodesForSeason(seasonNumber: Int) {
        if (selectedSeasonNumber == seasonNumber && shownEpisodes.isNotEmpty()) return
        val previousSeason = selectedSeasonNumber
        selectedSeasonNumber = seasonNumber

        shownEpisodes.clear()
        shownEpisodes.addAll(cachedDetails?.episodesBySeason?.get(seasonNumber).orEmpty())
        reorderEpisodes()
        episodesAdapter.notifyDataSetChanged()
        episodesEmpty.isVisible = shownEpisodes.isEmpty()

        // Full notifyDataSetChanged() on seasons would rebind every chip and drop D-pad focus
        // (often to the next focusable, e.g. favorite). Only refresh selected styling.
        seasonsAdapter.refreshSelectionUi(previousSeason, seasonNumber)
        refreshEpisodeWatchStatus()
    }

    private fun refreshEpisodeWatchStatus() {
        val ctx = context ?: return
        val statuses = HashMap<String, EpisodeWatchStatus>()
        val vodSeriesResumeKeys = LastWatchStore.readVodSeries(ctx)
            .mapNotNull { LastWatchStore.resumeCacheKey(it) }
            .toHashSet()

        cachedDetails?.episodesBySeason
            ?.values
            ?.asSequence()
            ?.flatten()
            ?.forEach { ep ->
                val resumeKey = resumeKeyForEpisode(ep) ?: return@forEach
                val snapshot = LastWatchPlaybackPositionStore.read(ctx, resumeKey)
                if (snapshot != null && LastWatchPlaybackPositionStore.shouldOfferResume(snapshot)) {
                    val progressPercent = if (snapshot.durationMs > 0) {
                        ((snapshot.positionMs * 100L) / snapshot.durationMs).toInt().coerceIn(1, 99)
                    } else {
                        1
                    }
                    statuses[ep.episodeId] = EpisodeWatchStatus(progressPercent, isCompleted = false)
                } else if (vodSeriesResumeKeys.contains(resumeKey)) {
                    statuses[ep.episodeId] = EpisodeWatchStatus(100, isCompleted = true)
                }
            }
        episodesAdapter.updateWatchStatus(statuses)
    }

    private fun resumeKeyForEpisode(ep: SeriesEpisode): String? {
        val playbackMovie = buildEpisodeMovie(ep)
        return LastWatchStore.resumeCacheKeyForPlayback(playbackMovie)
    }

    private fun startEpisodePlayback(ep: SeriesEpisode) {
        val movie = buildEpisodeMovie(ep)
        val cacheKey = LastWatchStore.resumeCacheKeyForPlayback(movie)
        if (cacheKey != null) {
            val snap = LastWatchPlaybackPositionStore.read(requireContext(), cacheKey)
            if (snap != null && LastWatchPlaybackPositionStore.shouldOfferResume(snap)) {
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle(R.string.last_watch_resume_title)
                    .setMessage(R.string.last_watch_resume_message)
                    .setPositiveButton(R.string.last_watch_resume_resume) { _, _ ->
                        launchEpisodePlayback(ep, movie, snap.positionMs)
                    }
                    .setNegativeButton(R.string.last_watch_resume_restart) { _, _ ->
                        LastWatchPlaybackPositionStore.remove(requireContext(), cacheKey)
                        launchEpisodePlayback(ep, movie, null)
                    }
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                }
                dialog.show()
                return
            }
        }
        launchEpisodePlayback(ep, movie, null)
    }

    private fun launchEpisodePlayback(ep: SeriesEpisode, movie: Movie, initialPositionMs: Long?) {
        LastWatchStore.addVodSeries(
            context = requireContext(),
            playedUnixSeconds = IptvTimeUtils.nowIsraelSeconds(),
            movie = movie,
            imageUrl = ep.coverUrl ?: seriesCover,
        )
        startActivity(
            Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
                if (initialPositionMs != null) {
                    putExtra(PlaybackActivity.INITIAL_POSITION_MS, initialPositionMs)
                }
            },
        )
    }

    private fun buildEpisodeMovie(ep: SeriesEpisode): Movie {
        val url = IptvStreamUrls.seriesEpisodeUrl(ep.episodeId, ep.containerExtension)
        return Movie(
            id = "${seriesId}_${ep.episodeId}".hashCode().toLong(),
            title = "$seriesName - ${ep.title}",
            description = ep.plot ?: categoryName,
            backgroundImageUrl = ep.coverUrl ?: seriesCover,
            cardImageUrl = ep.coverUrl ?: seriesCover,
            videoUrl = url,
            studio = "Season ${ep.seasonNumber}",
        )
    }

    companion object {
        private const val EPISODE_GRID_SPAN = 4

        private const val ARG_SERIES_ID = "series_id"
        private const val ARG_SERIES_NAME = "series_name"
        private const val ARG_SERIES_COVER = "series_cover"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val ARG_SERIES_PLOT = "series_plot"

        fun newInstance(
            seriesId: String,
            seriesName: String,
            seriesCover: String?,
            categoryName: String,
            seriesPlotFromList: String? = null,
        ) = VodSeriesFolderFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERIES_ID, seriesId)
                putString(ARG_SERIES_NAME, seriesName)
                putString(ARG_SERIES_COVER, seriesCover)
                putString(ARG_CATEGORY_NAME, categoryName)
                if (!seriesPlotFromList.isNullOrBlank()) {
                    putString(ARG_SERIES_PLOT, seriesPlotFromList)
                }
            }
        }
    }
}

/** Horizontal season chips (same layout as VOD category strip). */
private class SeasonsRowAdapter(
    private val selectedSeasonProvider: () -> Int?,
    private val onSeasonFocused: (SeriesSeason) -> Unit,
    private val onDpadDown: () -> Unit,
) : RecyclerView.Adapter<SeasonsRowAdapter.VH>() {

    private val seasons = mutableListOf<SeriesSeason>()
    private var neighborScrollListener: RecyclerView.OnScrollListener? = null

    companion object {
        private val PAYLOAD_SELECTION = Any()
    }

    fun submit(list: List<SeriesSeason>) {
        seasons.clear()
        seasons.addAll(list)
        notifyDataSetChanged()
    }

    fun adapterPositionForSelected(seasonNumber: Int?): Int {
        if (seasons.isEmpty()) return 0
        if (seasonNumber == null) return 0
        val idx = seasons.indexOfFirst { it.seasonNumber == seasonNumber }
        return if (idx >= 0) idx else 0
    }

    /** Updates [isSelected] on chips only — avoids full rebind so focus stays on the focused chip. */
    fun refreshSelectionUi(previousSeasonNumber: Int?, newSeasonNumber: Int) {
        if (previousSeasonNumber != null && previousSeasonNumber != newSeasonNumber) {
            val oldIdx = seasons.indexOfFirst { it.seasonNumber == previousSeasonNumber }
            if (oldIdx >= 0) notifyItemChanged(oldIdx, PAYLOAD_SELECTION)
        }
        val newIdx = seasons.indexOfFirst { it.seasonNumber == newSeasonNumber }
        if (newIdx >= 0) notifyItemChanged(newIdx, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_season, parent, false) as TextView
        if (tv.id == View.NO_ID) {
            tv.id = View.generateViewId()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tv.defaultFocusHighlightEnabled = false
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = seasons.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (neighborScrollListener != null) return
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                wireSeasonHorizontalNeighbors(rv)
            }
        }
        neighborScrollListener = listener
        recyclerView.addOnScrollListener(listener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        neighborScrollListener?.let { recyclerView.removeOnScrollListener(it) }
        neighborScrollListener = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val season = seasons[position]
            holder.text.isSelected = selectedSeasonProvider() == season.seasonNumber
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val season = seasons[position]
        val selected = selectedSeasonProvider() == season.seasonNumber
        holder.text.text = season.title.uppercase(Locale.getDefault())
        holder.text.isSelected = selected
        holder.text.nextFocusUpId = R.id.series_btn_favorite
        holder.text.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onSeasonFocused(season)
        }
        holder.text.setOnClickListener {
            holder.text.requestFocus()
            onSeasonFocused(season)
        }
        // Do not handle DPAD_LEFT/RIGHT here — it blocks framework focus search between chips.
        holder.text.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDpadDown()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onSeasonFocused(season)
                    true
                }
                else -> false
            }
        }
        holder.itemView.post {
            val rv = holder.itemView.parent as? RecyclerView ?: return@post
            wireSeasonHorizontalNeighbors(rv)
        }
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    private fun wireSeasonHorizontalNeighbors(rv: RecyclerView) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        for (pos in first..last) {
            val vh = rv.findViewHolderForAdapterPosition(pos) ?: continue
            val v = vh.itemView
            val leftNeighbor = rv.findViewHolderForAdapterPosition(pos - 1)?.itemView
            val rightNeighbor = rv.findViewHolderForAdapterPosition(pos + 1)?.itemView
            v.nextFocusLeftId = leftNeighbor?.id?.takeIf { it != View.NO_ID } ?: View.NO_ID
            v.nextFocusRightId = rightNeighbor?.id?.takeIf { it != View.NO_ID } ?: View.NO_ID
            v.nextFocusUpId = R.id.series_btn_favorite
        }
    }
}

private class EpisodesAdapter(
    private val episodes: List<SeriesEpisode>,
    private val gridSpan: Int,
    private val fallbackCoverUrl: () -> String?,
    private val onEpisodeFocused: (SeriesEpisode) -> Unit,
    private val onEpisodePlay: (SeriesEpisode) -> Unit,
    private val onFirstRowDpadUp: () -> Unit,
) : RecyclerView.Adapter<EpisodesAdapter.VH>() {
    private var watchStatusByEpisodeId: Map<String, EpisodeWatchStatus> = emptyMap()

    fun updateWatchStatus(statusMap: Map<String, EpisodeWatchStatus>) {
        watchStatusByEpisodeId = statusMap
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_episode, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = episodes.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val episode = episodes[position]
        val inFirstRow = position < gridSpan
        holder.number.text = holder.itemView.context.getString(
            R.string.vod_series_episode_label, episode.episodeNumber,
        )
        holder.title.text = episode.title
        val thumbUrl = episode.coverUrl?.takeIf { it.isNotBlank() } ?: fallbackCoverUrl()
        if (thumbUrl.isNullOrBlank()) {
            Glide.with(holder.thumbnail).clear(holder.thumbnail)
            holder.thumbnail.setImageDrawable(null)
        } else {
            Glide.with(holder.thumbnail).load(thumbUrl).centerCrop().into(holder.thumbnail)
        }
        val watchStatus = watchStatusByEpisodeId[episode.episodeId]
        if (watchStatus == null) {
            holder.watchBadge.isVisible = false
            holder.watchProgress.isVisible = false
        } else {
            holder.watchBadge.isVisible = true
            holder.watchProgress.isVisible = true
            holder.watchProgress.progress = watchStatus.progressPercent.coerceIn(1, 100)
            holder.watchBadge.text = if (watchStatus.isCompleted) {
                holder.itemView.context.getString(R.string.vod_series_episode_watched)
            } else {
                "${watchStatus.progressPercent}%"
            }
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onEpisodeFocused(episode)
        }
        holder.itemView.setOnClickListener { onEpisodePlay(episode) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (inFirstRow) {
                        onFirstRowDpadUp()
                        true
                    } else {
                        false
                    }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onEpisodePlay(episode)
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        val number: TextView = itemView.findViewById(R.id.episode_number)
        val title: TextView = itemView.findViewById(R.id.episode_title)
        val watchBadge: TextView = itemView.findViewById(R.id.episode_watch_badge)
        val watchProgress: ProgressBar = itemView.findViewById(R.id.episode_watch_progress)
    }
}

private data class EpisodeWatchStatus(
    val progressPercent: Int,
    val isCompleted: Boolean,
)
