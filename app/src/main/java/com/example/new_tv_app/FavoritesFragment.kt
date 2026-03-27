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

    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var sectionHeader: TextView

    private val adapter = FavoriteVodCardAdapter { movie ->
        startActivity(
            Intent(requireContext(), PlaybackActivity::class.java).apply {
                putExtra(DetailsActivity.MOVIE, movie)
            },
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_favorites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv = view.findViewById(R.id.favorites_rv)
        empty = view.findViewById(R.id.favorites_empty)
        sectionHeader = view.findViewById(R.id.favorites_section_header)

        rv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val gap = resources.getDimensionPixelSize(R.dimen.last_watch_row_item_gap)
        rv.addItemDecoration(FavoritesHorizontalSpacingDecoration(gap))
        rv.adapter = adapter

        view.findViewById<TextView>(R.id.favorites_edit).setOnClickListener {
            FavoriteVodStore.clear(requireContext())
            refreshList()
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        if (!isAdded) return
        val list = FavoriteVodStore.readAll(requireContext())
        adapter.submit(list)
        val has = list.isNotEmpty()
        rv.isVisible = has
        sectionHeader.isVisible = has
        empty.isVisible = !has
        if (has) {
            rv.post {
                rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
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
) : RecyclerView.Adapter<FavoriteVodCardAdapter.VH>() {

    private val items = mutableListOf<Movie>()

    fun submit(list: List<Movie>) {
        items.clear()
        items.addAll(list)
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
        holder.itemView.setOnClickListener { onOpen(m) }
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
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val bg: ImageView = itemView.findViewById(R.id.last_watch_vod_bg)
        val title: TextView = itemView.findViewById(R.id.last_watch_vod_title)
        val subtitle: TextView = itemView.findViewById(R.id.last_watch_vod_subtitle)
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
