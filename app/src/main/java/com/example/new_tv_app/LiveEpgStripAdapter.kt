package com.example.new_tv_app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.EpgListing
import com.example.new_tv_app.iptv.IptvTimeUtils

class LiveEpgStripAdapter(
    private val nowSeconds: () -> Long,
) : RecyclerView.Adapter<LiveEpgStripAdapter.VH>() {

    private val listings = mutableListOf<EpgListing>()
    private var selectedIndex: Int = 0

    fun submitList(newList: List<EpgListing>, newSelected: Int = 0) {
        listings.clear()
        listings.addAll(newList)
        selectedIndex = newSelected.coerceIn(0, (newList.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun setSelectedIndex(idx: Int) {
        if (listings.isEmpty()) return
        val old = selectedIndex
        selectedIndex = idx.coerceIn(0, listings.lastIndex)
        if (old != selectedIndex) {
            notifyItemChanged(old)
            notifyItemChanged(selectedIndex)
        }
    }

    fun getSelectedIndex(): Int = selectedIndex
    fun getSelected(): EpgListing? = listings.getOrNull(selectedIndex)
    fun size(): Int = listings.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.live_card_thumb)
        val arrowUp: TextView = itemView.findViewById(R.id.live_card_arrow_up)
        val arrowDown: TextView = itemView.findViewById(R.id.live_card_arrow_down)
        val arrowLeft: TextView = itemView.findViewById(R.id.live_card_arrow_left)
        val arrowRight: TextView = itemView.findViewById(R.id.live_card_arrow_right)
        val badge: TextView = itemView.findViewById(R.id.live_card_badge)
        val time: TextView = itemView.findViewById(R.id.live_card_time)
        val title: TextView = itemView.findViewById(R.id.live_card_title)
        val description: TextView = itemView.findViewById(R.id.live_card_description)
        val genre: TextView = itemView.findViewById(R.id.live_card_genre)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_live_epg_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = listings.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val listing = listings[position]
        val now = nowSeconds()
        val selected = position == selectedIndex

        // Arrow visibility
        val arrowVis = if (selected) View.VISIBLE else View.INVISIBLE
        holder.arrowUp.visibility = arrowVis
        holder.arrowDown.visibility = arrowVis
        holder.arrowLeft.visibility = if (selected && position > 0) View.VISIBLE else View.INVISIBLE
        holder.arrowRight.visibility = if (selected && position < listings.lastIndex) View.VISIBLE else View.INVISIBLE

        // Arrow colour: default white (caller may tint for flash effect)
        val white = holder.itemView.context.getColor(android.R.color.white)
        holder.arrowUp.setTextColor(white)
        holder.arrowDown.setTextColor(white)
        holder.arrowLeft.setTextColor(white)
        holder.arrowRight.setTextColor(white)

        // Thumbnail border
        holder.thumb.setBackgroundResource(
            if (selected) R.drawable.bg_playback_records_thumb_border_cyan
            else R.drawable.bg_playback_records_thumb_border_muted
        )

        // Thumbnail image
        val imageUrl = listing.imageUrl
        if (!imageUrl.isNullOrBlank()) {
            Glide.with(holder.thumb).load(imageUrl).into(holder.thumb)
        } else {
            holder.thumb.setImageResource(0)
        }

        // Time
        val startLabel = IptvTimeUtils.formatTimeIsrael(listing.startUnix)
        val endLabel = IptvTimeUtils.formatTimeIsrael(listing.endUnix)
        holder.time.text = "$startLabel - $endLabel"

        // Title
        holder.title.text = listing.title

        // Description
        holder.description.text = listing.description.trim()

        // Genre
        val genre = listing.category?.trim().orEmpty()
        holder.genre.text = genre
        holder.genre.visibility = if (genre.isNotEmpty()) View.VISIBLE else View.GONE

        // Badge
        when {
            listing.startUnix <= now && now < listing.endUnix -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = holder.itemView.context.getString(R.string.playback_live_badge_live)
                holder.badge.setBackgroundResource(R.drawable.bg_playback_live_badge_live)
            }
            listing.endUnix <= now -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = holder.itemView.context.getString(R.string.playback_live_badge_records)
                holder.badge.setBackgroundResource(R.drawable.bg_playback_live_badge_records)
            }
            else -> {
                holder.badge.visibility = View.GONE
            }
        }
    }

    /** Flash arrow colours at [position]. Colours are reset by caller after a delay. */
    fun flashArrow(rv: RecyclerView, direction: Int) {
        val vh = rv.findViewHolderForAdapterPosition(selectedIndex) as? VH ?: return
        val cyan = rv.context.getColor(R.color.sidebar_accent_cyan)
        when (direction) {
            FLASH_LEFT -> vh.arrowLeft.setTextColor(cyan)
            FLASH_RIGHT -> vh.arrowRight.setTextColor(cyan)
            FLASH_UP -> vh.arrowUp.setTextColor(cyan)
            FLASH_DOWN -> vh.arrowDown.setTextColor(cyan)
        }
    }

    /** Reset arrow colours on the selected card to white. */
    fun resetArrowColors(rv: RecyclerView) {
        val vh = rv.findViewHolderForAdapterPosition(selectedIndex) as? VH ?: return
        val white = rv.context.getColor(android.R.color.white)
        vh.arrowUp.setTextColor(white)
        vh.arrowDown.setTextColor(white)
        vh.arrowLeft.setTextColor(white)
        vh.arrowRight.setTextColor(white)
    }

    companion object {
        const val FLASH_LEFT = 0
        const val FLASH_RIGHT = 1
        const val FLASH_UP = 2
        const val FLASH_DOWN = 3
    }
}
