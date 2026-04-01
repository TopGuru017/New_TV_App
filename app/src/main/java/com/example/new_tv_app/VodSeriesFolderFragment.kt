package com.example.new_tv_app

import android.content.Intent
import android.content.res.ColorStateList
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
import androidx.core.view.doOnLayout
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
import com.example.new_tv_app.iptv.LastWatchStore
import com.example.new_tv_app.iptv.SeriesDetails
import com.example.new_tv_app.iptv.SeriesEpisode
import com.example.new_tv_app.iptv.SeriesSeason
import com.example.new_tv_app.iptv.XtreamVodApi
import kotlinx.coroutines.launch

/** Row view that currently has (or contains) focus; avoids [RecyclerView.findFocusedChild] API differences. */
private fun RecyclerView.focusedRowOrNull(): View? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.hasFocus()) return child
    }
    return null
}

class VodSeriesFolderFragment : Fragment() {

    private val seriesId: String by lazy { requireArguments().getString(ARG_SERIES_ID).orEmpty() }
    private val seriesName: String by lazy { requireArguments().getString(ARG_SERIES_NAME).orEmpty() }
    private val seriesCover: String? by lazy { requireArguments().getString(ARG_SERIES_COVER) }
    private val categoryName: String by lazy { requireArguments().getString(ARG_CATEGORY_NAME).orEmpty() }
    private val seriesPlotFromList: String? by lazy {
        requireArguments().getString(ARG_SERIES_PLOT)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Merged series description (API + list); used for hero and as fallback when an episode has no plot. */
    private var seriesPlotForHero: String = ""

    private lateinit var episodesAdapter: EpisodesAdapter
    private lateinit var seasonDropdownAdapter: SeasonDropdownAdapter
    private val shownEpisodes = mutableListOf<SeriesEpisode>()
    private var allSeasons = listOf<SeriesSeason>()
    private var selectedSeasonNumber: Int? = null
    private var cachedDetails: SeriesDetails? = null
    private var sortDescending = true   // newest (highest episode number) first

    private lateinit var backdropImg: ImageView
    private lateinit var thumbnailImg: ImageView
    private lateinit var descriptionTv: TextView
    private lateinit var seasonBtn: TextView
    private lateinit var seasonDropdownOverlay: View
    private lateinit var episodesEmpty: TextView
    private lateinit var favoriteBtn: ImageView
    private var seasonDropdownOpen = false

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
        val episodesRv = view.findViewById<RecyclerView>(R.id.series_episodes_list)
        episodesEmpty = view.findViewById(R.id.series_episodes_empty)
        seasonBtn = view.findViewById(R.id.series_season_btn)
        seasonDropdownOverlay = view.findViewById(R.id.series_season_dropdown)
        val seasonDropdownRv = view.findViewById<RecyclerView>(R.id.series_season_dropdown_list)
        favoriteBtn = view.findViewById(R.id.series_btn_favorite)
        val sortBtn = view.findViewById<ImageView>(R.id.series_btn_sort)

        // Static hero bindings
        badgeTv.text = getString(R.string.vod_badge_series)
        titleTv.text = seriesName
        genreTv.text = categoryName
        descriptionTv.text = getString(R.string.vod_series_loading)

        loadImage(backdropImg, seriesCover)
        loadImage(thumbnailImg, seriesCover)

        // ── Favorites button ──────────────────────────────────────────────────
        refreshFavoriteIcon()
        favoriteBtn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bindSeriesHeroDescription()
        }
        favoriteBtn.setOnClickListener { toggleFavorite() }
        favoriteBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                toggleFavorite(); true
            } else false
        }

        // ── Sort button (↑↓ icon, top-right) ─────────────────────────────────
        sortBtn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bindSeriesHeroDescription()
        }
        sortBtn.setOnClickListener { toggleSort() }
        sortBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                toggleSort(); true
            } else false
        }

        // ── Season dropdown ───────────────────────────────────────────────────
        seasonDropdownAdapter = SeasonDropdownAdapter(
            selectedSeasonProvider = { selectedSeasonNumber },
            onSeasonPicked = { season ->
                closeSeasonDropdown()
                if (selectedSeasonNumber != season.seasonNumber) {
                    bindEpisodesForSeason(season.seasonNumber)
                }
                seasonBtn.requestFocus()
            },
            onDpadUpExitDropdown = {
                closeSeasonDropdown()
                favoriteBtn.requestFocus()
            },
        )
        seasonDropdownRv.layoutManager = LinearLayoutManager(requireContext())
        seasonDropdownRv.adapter = seasonDropdownAdapter
        seasonDropdownRv.itemAnimator = null

        // DPAD_UP with focus on the list but no focused child (edge case) → still exit to heart
        seasonDropdownRv.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && seasonDropdownRv.focusedRowOrNull() == null) {
                closeSeasonDropdown()
                favoriteBtn.requestFocus()
                true
            } else {
                false
            }
        }

        seasonBtn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bindSeriesHeroDescription()
        }
        seasonBtn.setOnClickListener { toggleSeasonDropdown(view) }
        seasonBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    toggleSeasonDropdown(view); true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // From the season chip (dropdown closed or open): UP always goes to favorite
                    if (seasonDropdownOpen) closeSeasonDropdown()
                    favoriteBtn.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (seasonDropdownOpen) {
                        seasonDropdownRv.requestFocus()
                        seasonDropdownRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        true
                    } else false
                }
                else -> false
            }
        }

        // ── Episodes grid ─────────────────────────────────────────────────────
        episodesAdapter = EpisodesAdapter(
            episodes = shownEpisodes,
            fallbackCoverUrl = { seriesCover },
            onEpisodeFocused = { ep ->
                val epPlot = ep.plot?.trim().orEmpty()
                descriptionTv.text = epPlot.ifBlank { seriesPlotForHero }
                    .ifBlank { getString(R.string.live_no_description) }
                loadImage(backdropImg, ep.coverUrl?.takeIf { it.isNotBlank() } ?: seriesCover)
            },
            onEpisodePlay = { ep -> startEpisodePlayback(ep) },
        )
        val gridSpan = 4
        episodesRv.layoutManager = GridLayoutManager(requireContext(), gridSpan)
        episodesRv.adapter = episodesAdapter
        episodesRv.itemAnimator = null

        // Back: close dropdown first, otherwise pop back stack
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (seasonDropdownOpen) {
                    closeSeasonDropdown()
                    seasonBtn.requestFocus()
                } else {
                    parentFragmentManager.setFragmentResult(
                        VodBrowseFragment.SERIES_FOLDER_RESULT_KEY,
                        Bundle().apply {
                            putString(VodBrowseFragment.SERIES_FOLDER_RESULT_SERIES_ID, seriesId)
                        },
                    )
                    parentFragmentManager.popBackStack()
                }
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
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private fun toggleSeasonDropdown(rootView: View) {
        if (seasonDropdownOpen) {
            closeSeasonDropdown()
        } else {
            openSeasonDropdown(rootView)
        }
    }

    private fun openSeasonDropdown(rootView: View) {
        if (allSeasons.isEmpty()) return
        seasonDropdownOpen = true
        seasonBtn.text = buildSeasonLabel(selectedSeasonNumber, open = true)
        seasonDropdownOverlay.isVisible = true

        // Position overlay below the controls bar
        seasonBtn.doOnLayout {
            val location = IntArray(2)
            seasonBtn.getLocationInWindow(location)
            val rootLocation = IntArray(2)
            rootView.getLocationInWindow(rootLocation)
            val topPx = location[1] - rootLocation[1] + seasonBtn.height + 4
            val lp = seasonDropdownOverlay.layoutParams as ViewGroup.LayoutParams
            if (seasonDropdownOverlay.layoutParams is ViewGroup.MarginLayoutParams) {
                val mlp = seasonDropdownOverlay.layoutParams as ViewGroup.MarginLayoutParams
                mlp.topMargin = topPx
                seasonDropdownOverlay.layoutParams = mlp
            }
        }
    }

    private fun closeSeasonDropdown() {
        seasonDropdownOpen = false
        seasonDropdownOverlay.isVisible = false
        seasonBtn.text = buildSeasonLabel(selectedSeasonNumber, open = false)
    }

    private fun buildSeasonLabel(seasonNumber: Int?, open: Boolean): String {
        val arrow = if (open) "  ▲" else "  ▼"
        if (seasonNumber == null) return getString(R.string.vod_series_season_label, "—") + arrow
        val season = allSeasons.find { it.seasonNumber == seasonNumber }
        val label = season?.title ?: getString(R.string.vod_series_season_label, seasonNumber.toString())
        return "$label$arrow"
    }

    private fun bindSeriesDetails(details: SeriesDetails) {
        allSeasons = details.seasons
        seasonDropdownAdapter.submit(details.seasons)
        if (details.seasons.isEmpty()) {
            selectedSeasonNumber = null
            seasonBtn.text = buildSeasonLabel(null, open = false)
            episodesEmpty.isVisible = true
            return
        }
        val first = selectedSeasonNumber ?: details.seasons.first().seasonNumber
        bindEpisodesForSeason(first)
    }

    private fun bindEpisodesForSeason(seasonNumber: Int) {
        selectedSeasonNumber = seasonNumber
        seasonBtn.text = buildSeasonLabel(seasonNumber, open = false)
        seasonDropdownAdapter.notifyDataSetChanged()

        shownEpisodes.clear()
        shownEpisodes.addAll(cachedDetails?.episodesBySeason?.get(seasonNumber).orEmpty())
        reorderEpisodes()
        episodesAdapter.notifyDataSetChanged()
        episodesEmpty.isVisible = shownEpisodes.isEmpty()
    }

    private fun startEpisodePlayback(ep: SeriesEpisode) {
        val url = IptvStreamUrls.seriesEpisodeUrl(ep.episodeId, ep.containerExtension)
        val movie = Movie(
            id = "${seriesId}_${ep.episodeId}".hashCode().toLong(),
            title = "$seriesName - ${ep.title}",
            description = ep.plot ?: categoryName,
            backgroundImageUrl = ep.coverUrl ?: seriesCover,
            cardImageUrl = ep.coverUrl ?: seriesCover,
            videoUrl = url,
            studio = "Season ${ep.seasonNumber}",
        )
        LastWatchStore.addVodSeries(
            context = requireContext(),
            playedUnixSeconds = IptvTimeUtils.nowIsraelSeconds(),
            movie = movie,
            imageUrl = ep.coverUrl ?: seriesCover,
        )
        startActivity(
            Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
            },
        )
    }

    companion object {
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

// ── Season dropdown adapter ────────────────────────────────────────────────────

private class SeasonDropdownAdapter(
    private val selectedSeasonProvider: () -> Int?,
    private val onSeasonPicked: (SeriesSeason) -> Unit,
    /** DPAD_UP from any row while the season menu is open — exit to favorite. */
    private val onDpadUpExitDropdown: () -> Unit,
) : RecyclerView.Adapter<SeasonDropdownAdapter.VH>() {

    private val seasons = mutableListOf<SeriesSeason>()

    fun submit(list: List<SeriesSeason>) {
        seasons.clear()
        seasons.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(
                (14 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
            )
            setTextColor(ContextCompat.getColor(context, R.color.sidebar_text_primary))
            textSize = 13f
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_series_season_item)
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = seasons.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val season = seasons[position]
        val selected = selectedSeasonProvider() == season.seasonNumber
        holder.tv.text = season.title
        holder.tv.isSelected = selected
        if (selected) {
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, R.color.sidebar_accent_cyan))
        } else {
            holder.tv.setTextColor(ContextCompat.getColor(holder.tv.context, R.color.sidebar_text_primary))
        }
        holder.tv.setOnClickListener { onSeasonPicked(season) }
        holder.tv.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val pos = holder.bindingAdapterPosition
                    if (pos == 0) {
                        onDpadUpExitDropdown()
                        true
                    } else if (pos > 0) {
                        val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
                        val prev = pos - 1
                        val target = rv.findViewHolderForAdapterPosition(prev)?.itemView
                        if (target != null) {
                            target.requestFocus()
                        } else {
                            rv.scrollToPosition(prev)
                            rv.post {
                                rv.findViewHolderForAdapterPosition(prev)?.itemView?.requestFocus()
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onSeasonPicked(season)
                    true
                }
                else -> false
            }
        }
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
}

// ── Episodes grid adapter ─────────────────────────────────────────────────────

private class EpisodesAdapter(
    private val episodes: List<SeriesEpisode>,
    private val fallbackCoverUrl: () -> String?,
    private val onEpisodeFocused: (SeriesEpisode) -> Unit,
    private val onEpisodePlay: (SeriesEpisode) -> Unit,
) : RecyclerView.Adapter<EpisodesAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_episode, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = episodes.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val episode = episodes[position]
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
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onEpisodeFocused(episode)
        }
        holder.itemView.setOnClickListener { onEpisodePlay(episode) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                onEpisodePlay(episode); true
            } else false
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        val number: TextView = itemView.findViewById(R.id.episode_number)
        val title: TextView = itemView.findViewById(R.id.episode_title)
    }
}
