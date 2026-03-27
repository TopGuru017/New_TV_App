package com.example.new_tv_app

import android.graphics.Rect
import android.os.Bundle
import android.content.Intent
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
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LastWatchStore
import com.example.new_tv_app.iptv.LastWatchStore.LastWatchEntry

/**
 * Placeholder for IPTV screens (live grid, VOD, etc.). Sidebar lives in [MainActivity].
 */
class HomeContentFragment : Fragment() {

    private fun openMovie(movie: Movie) {
        val url = movie.videoUrl.orEmpty().trim()
        if (url.isEmpty()) return
        startActivity(
            Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
            },
        )
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

    private lateinit var editBtn: TextView
    private lateinit var clearAllBtn: TextView
    private lateinit var empty: TextView
    private lateinit var recordsRv: RecyclerView
    private lateinit var seriesRv: RecyclerView
    private lateinit var moviesRv: RecyclerView

    private lateinit var recordsAdapter: LastWatchRecordsAdapter
    private lateinit var vodSeriesAdapter: LastWatchVodAdapter
    private lateinit var vodMoviesAdapter: LastWatchVodAdapter
    private var isEditMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home_content, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editBtn = view.findViewById(R.id.home_last_watch_edit)
        clearAllBtn = view.findViewById(R.id.home_last_watch_clear_all)
        empty = view.findViewById(R.id.home_last_watch_empty)

        recordsRv = view.findViewById(R.id.home_last_watch_records_rv)
        seriesRv = view.findViewById(R.id.home_last_watch_series_rv)
        moviesRv = view.findViewById(R.id.home_last_watch_movies_rv)

        recordsRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        seriesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        moviesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        val rowGap = resources.getDimensionPixelSize(R.dimen.last_watch_row_item_gap)
        val rowSpacing = LastWatchHorizontalSpacingDecoration(rowGap)
        recordsRv.addItemDecoration(rowSpacing)
        seriesRv.addItemDecoration(rowSpacing)
        moviesRv.addItemDecoration(rowSpacing)

        recordsAdapter = LastWatchRecordsAdapter(
            onOpen = { entry -> openMovie(entry.movie) },
            onRemove = { entry ->
                LastWatchStore.removeEntry(requireContext(), entry)
                loadLastWatch()
            },
            onDpadUp = {
                editBtn.requestFocus()
            },
            onDpadDown = {
                val seriesCount = seriesRv.adapter?.itemCount ?: 0
                if (seriesCount > 0) {
                    focusFirstItem(seriesRv)
                } else {
                    val moviesCount = moviesRv.adapter?.itemCount ?: 0
                    if (moviesCount > 0) focusFirstItem(moviesRv)
                }
            },
        )
        vodSeriesAdapter = LastWatchVodAdapter(
            onOpen = { entry -> openMovie(entry.movie) },
            onRemove = { entry ->
                LastWatchStore.removeEntry(requireContext(), entry)
                loadLastWatch()
            },
            onDpadUp = {
                when {
                    recordsRv.adapter?.itemCount ?: 0 > 0 -> focusFirstItem(recordsRv)
                    else -> editBtn.requestFocus()
                }
            },
            onDpadDown = {
                val moviesCount = moviesRv.adapter?.itemCount ?: 0
                if (moviesCount > 0) focusFirstItem(moviesRv)
            },
        )
        vodMoviesAdapter = LastWatchVodAdapter(
            onOpen = { entry -> openMovie(entry.movie) },
            onRemove = { entry ->
                LastWatchStore.removeEntry(requireContext(), entry)
                loadLastWatch()
            },
            onDpadUp = {
                when {
                    seriesRv.adapter?.itemCount ?: 0 > 0 -> focusFirstItem(seriesRv)
                    recordsRv.adapter?.itemCount ?: 0 > 0 -> focusFirstItem(recordsRv)
                    else -> editBtn.requestFocus()
                }
            },
        )

        recordsRv.adapter = recordsAdapter
        seriesRv.adapter = vodSeriesAdapter
        moviesRv.adapter = vodMoviesAdapter

        editBtn.setOnClickListener {
            isEditMode = !isEditMode
            applyEditModeUi()
        }
        clearAllBtn.setOnClickListener {
            LastWatchStore.clear(requireContext())
            loadLastWatch()
        }
        applyEditModeUi()
        loadLastWatch()
    }

    override fun onResume() {
        super.onResume()
        loadLastWatch()
    }

    private fun loadLastWatch() {
        if (!isAdded) return
        val records = LastWatchStore.readRecords(requireContext())
        val series = LastWatchStore.readVodSeries(requireContext())
        val movies = LastWatchStore.readVodMovies(requireContext())

        recordsAdapter.submit(records)
        vodSeriesAdapter.submit(series)
        vodMoviesAdapter.submit(movies)

        val hasAny = records.isNotEmpty() || series.isNotEmpty() || movies.isNotEmpty()
        empty.visibility = if (hasAny) View.GONE else View.VISIBLE

        val targetRv = when {
            records.isNotEmpty() -> recordsRv
            series.isNotEmpty() -> seriesRv
            movies.isNotEmpty() -> moviesRv
            else -> null
        }
        targetRv?.let { focusFirstItem(it) }
    }

    private fun applyEditModeUi() {
        editBtn.setText(if (isEditMode) R.string.last_watch_close_edit else R.string.last_watch_edit)
        clearAllBtn.isVisible = isEditMode
        recordsAdapter.setEditMode(isEditMode)
        vodSeriesAdapter.setEditMode(isEditMode)
        vodMoviesAdapter.setEditMode(isEditMode)
    }
}

/**
 * Loads poster art clipped to the same corner radius as [R.dimen.last_watch_card_corner_radius]
 * so images do not show square corners outside the rounded cyan focus ring.
 */
private fun loadLastWatchCardImage(target: ImageView, url: String?) {
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

/** Horizontal gap between cards in last-watch rows (does not add trailing margin after last item). */
private class LastWatchHorizontalSpacingDecoration(
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

private class LastWatchRecordsAdapter(
    private val onOpen: (LastWatchEntry) -> Unit,
    private val onRemove: (LastWatchEntry) -> Unit,
    private val onDpadUp: () -> Unit,
    private val onDpadDown: () -> Unit,
) : RecyclerView.Adapter<LastWatchRecordsAdapter.VH>() {

    private val items = mutableListOf<LastWatchEntry>()
    private var editMode = false

    fun submit(list: List<LastWatchEntry>) {
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
            .inflate(R.layout.item_last_watch_record, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]

        holder.tag.text = e.tag?.uppercase() ?: ""
        holder.centerTitle.text = e.channelName?.uppercase() ?: ""
        holder.time.text = e.timeRange.orEmpty()

        val img = e.imageUrl?.takeIf { it.isNotBlank() }
            ?: e.movie.cardImageUrl?.takeIf { it.isNotBlank() }
            ?: e.movie.backgroundImageUrl?.takeIf { it.isNotBlank() }

        loadLastWatchCardImage(holder.bg, img)

        holder.bottomTitle.text = e.movie.title.orEmpty().ifBlank { e.channelName.orEmpty() }
        holder.bottomTime.text = if (e.playedUnixSeconds > 0) {
            IptvTimeUtils.formatDateIsrael(e.playedUnixSeconds, "dd/MM - EEEE")
        } else ""
        holder.removeBadge.isVisible = editMode

        holder.root.setOnClickListener {
            if (editMode) onRemove(e) else onOpen(e)
        }
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    val prev = pos - 1
                    rv?.scrollToPosition(prev)
                    rv?.post {
                        rv.findViewHolderForAdapterPosition(prev)?.itemView?.requestFocus()
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    val next = pos + 1
                    rv?.scrollToPosition(next)
                    rv?.post {
                        rv.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDpadDown()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    onDpadUp()
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: View = itemView.findViewById(R.id.last_watch_record_card)
        val bg: ImageView = itemView.findViewById(R.id.last_watch_record_bg)
        val tag: TextView = itemView.findViewById(R.id.last_watch_record_tag)
        val centerTitle: TextView = itemView.findViewById(R.id.last_watch_record_center_title)
        val time: TextView = itemView.findViewById(R.id.last_watch_record_time)
        val bottomTitle: TextView = itemView.findViewById(R.id.last_watch_record_bottom_title)
        val bottomTime: TextView = itemView.findViewById(R.id.last_watch_record_bottom_time)
        val removeBadge: View = itemView.findViewById(R.id.last_watch_remove_badge)
    }
}

private class LastWatchVodAdapter(
    private val onOpen: (LastWatchEntry) -> Unit,
    private val onRemove: (LastWatchEntry) -> Unit,
    private val onDpadUp: () -> Unit = {},
    private val onDpadDown: () -> Unit = {},
) : RecyclerView.Adapter<LastWatchVodAdapter.VH>() {

    private val items = mutableListOf<LastWatchEntry>()
    private var editMode = false

    fun submit(list: List<LastWatchEntry>) {
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
        val e = items[position]

        val img = e.imageUrl?.takeIf { it.isNotBlank() }
            ?: e.movie.cardImageUrl?.takeIf { it.isNotBlank() }
            ?: e.movie.backgroundImageUrl?.takeIf { it.isNotBlank() }

        loadLastWatchCardImage(holder.bg, img)

        holder.title.text = e.movie.title.orEmpty()
        holder.subtitle.text = e.movie.description.orEmpty()
        holder.removeBadge.isVisible = editMode

        holder.root.setOnClickListener {
            if (editMode) onRemove(e) else onOpen(e)
        }
        holder.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val rv = holder.root.parent as? RecyclerView ?: return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    val prev = pos - 1
                    rv.scrollToPosition(prev)
                    rv.post {
                        rv.findViewHolderForAdapterPosition(prev)?.itemView?.requestFocus()
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    val next = pos + 1
                    rv.scrollToPosition(next)
                    rv.post {
                        rv.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    onDpadUp()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDpadDown()
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: View = itemView
        val bg: ImageView = itemView.findViewById(R.id.last_watch_vod_bg)
        val title: TextView = itemView.findViewById(R.id.last_watch_vod_title)
        val subtitle: TextView = itemView.findViewById(R.id.last_watch_vod_subtitle)
        val removeBadge: View = itemView.findViewById(R.id.last_watch_remove_badge)
    }
}
