package com.example.new_tv_app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.example.new_tv_app.iptv.FavoriteVodStore

/**
 * Favorites list — same horizontal card style as Last Watch VOD rows ([item_last_watch_vod_big]).
 */
class FavoritesFragment : Fragment() {

    private lateinit var seriesRv: RecyclerView
    private lateinit var moviesRv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var sectionHeaderSeries: TextView
    private lateinit var sectionHeaderMovies: TextView
    private lateinit var editBtn: TextView
    private lateinit var clearAllBtn: TextView
    private var isEditMode = false

    private val seriesAdapter = FavoriteVodCardAdapter(
        onOpen = { movie ->
            startActivity(
                Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, movie)
                },
            )
        },
        onRemove = { movie ->
            FavoriteVodStore.removeMovie(requireContext(), movie)
            refreshList()
        },
        onDpadUp = { editBtn.requestFocus() },
        onDpadDown = {
            val count = moviesRv.adapter?.itemCount ?: 0
            if (count > 0) focusFirstItem(moviesRv)
        },
    )
    private val moviesAdapter = FavoriteVodCardAdapter(
        onOpen = { movie ->
            startActivity(
                Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, movie)
                },
            )
        },
        onRemove = { movie ->
            FavoriteVodStore.removeMovie(requireContext(), movie)
            refreshList()
        },
        onDpadUp = {
            val count = seriesRv.adapter?.itemCount ?: 0
            if (count > 0) focusFirstItem(seriesRv) else editBtn.requestFocus()
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_favorites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        seriesRv = view.findViewById(R.id.favorites_series_rv)
        moviesRv = view.findViewById(R.id.favorites_movies_rv)
        empty = view.findViewById(R.id.favorites_empty)
        sectionHeaderSeries = view.findViewById(R.id.favorites_section_header_series)
        sectionHeaderMovies = view.findViewById(R.id.favorites_section_header_movies)
        editBtn = view.findViewById(R.id.favorites_edit)
        clearAllBtn = view.findViewById(R.id.favorites_clear_all)

        seriesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        moviesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val gap = resources.getDimensionPixelSize(R.dimen.last_watch_row_item_gap)
        seriesRv.addItemDecoration(FavoritesHorizontalSpacingDecoration(gap))
        moviesRv.addItemDecoration(FavoritesHorizontalSpacingDecoration(gap))
        seriesRv.adapter = seriesAdapter
        moviesRv.adapter = moviesAdapter

        editBtn.setOnClickListener {
            isEditMode = !isEditMode
            applyEditModeUi()
        }
        clearAllBtn.setOnClickListener {
            FavoriteVodStore.clear(requireContext())
            refreshList()
        }
        applyEditModeUi()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        if (!isAdded) return
        val list = FavoriteVodStore.readAll(requireContext())
        val (series, movies) = splitFavoritesByType(list)
        seriesAdapter.submit(series)
        moviesAdapter.submit(movies)

        val hasSeries = series.isNotEmpty()
        val hasMovies = movies.isNotEmpty()
        val has = hasSeries || hasMovies
        seriesRv.isVisible = hasSeries
        moviesRv.isVisible = hasMovies
        sectionHeaderSeries.isVisible = hasSeries
        sectionHeaderMovies.isVisible = hasMovies
        empty.isVisible = !has
        if (has) {
            when {
                hasSeries -> focusFirstItem(seriesRv)
                hasMovies -> focusFirstItem(moviesRv)
            }
        }
    }

    private fun applyEditModeUi() {
        editBtn.setText(if (isEditMode) R.string.favorites_close_edit else R.string.favorites_edit)
        clearAllBtn.isVisible = isEditMode
        seriesAdapter.setEditMode(isEditMode)
        moviesAdapter.setEditMode(isEditMode)
    }

    private fun focusFirstItem(rv: RecyclerView, attemptsRemaining: Int = 28) {
        rv.scrollToPosition(0)
        rv.post {
            if (!rv.isAttachedToWindow) return@post
            val vh = rv.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else if (attemptsRemaining > 0) {
                focusFirstItem(rv, attemptsRemaining - 1)
            }
        }
    }

    private fun splitFavoritesByType(all: List<Movie>): Pair<List<Movie>, List<Movie>> {
        val series = mutableListOf<Movie>()
        val movies = mutableListOf<Movie>()
        for (m in all) {
            val p = m.videoUrl.orEmpty()
            if (p.contains("/series/", ignoreCase = true)) {
                series.add(m)
            } else {
                movies.add(m)
            }
        }
        return series to movies
    }
}

private class FavoritesHorizontalSpacingDecoration(
    private val gapPx: Int,
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        if (pos > 0) {
            outRect.left = gapPx
        }
    }
}

private class FavoriteVodCardAdapter(
    private val onOpen: (Movie) -> Unit,
    private val onRemove: (Movie) -> Unit,
    private val onDpadUp: () -> Unit = {},
    private val onDpadDown: () -> Unit = {},
) : RecyclerView.Adapter<FavoriteVodCardAdapter.VH>() {

    private val items = mutableListOf<Movie>()
    private var editMode = false

    fun submit(list: List<Movie>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_last_watch_vod_big, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.title.text = m.title.orEmpty()
        holder.subtitle.text = m.description.orEmpty()
        val img = m.cardImageUrl?.takeIf { it.isNotBlank() }
            ?: m.backgroundImageUrl?.takeIf { it.isNotBlank() }
        loadFavoriteCardImage(holder.bg, img)
        holder.removeBadge.isVisible = editMode
        holder.itemView.setOnClickListener {
            if (editMode) onRemove(m) else onOpen(m)
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val parentRv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    val prev = pos - 1
                    parentRv.scrollToPosition(prev)
                    parentRv.post {
                        parentRv.findViewHolderForAdapterPosition(prev)?.itemView?.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    val next = pos + 1
                    parentRv.scrollToPosition(next)
                    parentRv.post {
                        parentRv.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    onDpadUp()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDpadDown()
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val bg: ImageView = itemView.findViewById(R.id.last_watch_vod_bg)
        val title: TextView = itemView.findViewById(R.id.last_watch_vod_title)
        val subtitle: TextView = itemView.findViewById(R.id.last_watch_vod_subtitle)
        val removeBadge: View = itemView.findViewById(R.id.last_watch_remove_badge)
    }
}

private fun loadFavoriteCardImage(target: ImageView, url: String?) {
    val radiusPx = target.resources.getDimensionPixelSize(R.dimen.last_watch_card_corner_radius)
    val opts = RequestOptions().transform(
        MultiTransformation(CenterCrop(), RoundedCorners(radiusPx)),
    )
    Glide.with(target).clear(target)
    if (url.isNullOrBlank()) {
        target.setImageDrawable(null)
        return
    }
    Glide.with(target).load(url).apply(opts).into(target)
}
