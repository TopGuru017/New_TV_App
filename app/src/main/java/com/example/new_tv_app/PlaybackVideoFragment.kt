package com.example.new_tv_app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.EpgListing
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.IptvTimeUtils
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * Plays VOD and live IPTV via LibVLC. No on-screen chrome while playing. Seekable VOD: DPAD left/right opens
 * the horizontal chapter strip; only then is the bottom overlay (times + progress) shown.
 * Records catch-up (intent carries [PlaybackActivity.RECORDS_DAY_LISTINGS]): DPAD up/down toggles a vertical
 * column of that day’s programmes on the right; pick one to switch archive segment; left/right chapter strip
 * still applies when the stream is seekable.
 */
class PlaybackVideoFragment : Fragment() {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaybackUrl: String = ""
    private var didCrossProtocolRetry = false

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var controlsOverlay: View
    private lateinit var trickRv: RecyclerView
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recordsColumnContainer: View
    private lateinit var recordsColumnRv: RecyclerView

    private val trickSlots = mutableListOf<TrickSlot>()
    private lateinit var trickAdapter: TrickStripAdapter
    private var posterUrl: String? = null
    /** Last duration used to build trick strip; rebuild when LibVLC reports a new length. */
    private var trickStripBuiltForLengthMs: Long = -1L
    /** Seek chapter cards visible (DPAD left/right on video); playback paused while visible. */
    private var trickStripUserVisible: Boolean = false

    private var recordsArchiveStreamId: String? = null
    private val recordsDayListings = mutableListOf<EpgListing>()
    /** Vertical day list visible (DPAD up/down on video); only when [recordsDayListings] non-empty. */
    private var recordsColumnUserVisible: Boolean = false
    private var currentArchiveListingId: Long = 0L
    private lateinit var recordsColumnAdapter: RecordsColumnPlaybackAdapter

    private val progressTicker = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (!isAdded) return
            updatePlaybackControlsUi()
            v.postDelayed(this, 500L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_playback_video, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        val movie = IntentCompat.getSerializableExtra(
            requireActivity().intent,
            DetailsActivity.MOVIE,
            Movie::class.java,
        ) ?: run {
            Log.e(TAG, "no Movie in intent; finishing")
            Toast.makeText(requireContext(), R.string.playback_error_no_media, Toast.LENGTH_LONG).show()
            requireActivity().finish()
            return
        }

        val url = movie.videoUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            Log.e(TAG, "empty videoUrl; finishing")
            Toast.makeText(requireContext(), R.string.playback_error_no_media, Toast.LENGTH_LONG).show()
            requireActivity().finish()
            return
        }

        posterUrl = sequenceOf(movie.cardImageUrl, movie.backgroundImageUrl)
            .firstOrNull { !it.isNullOrBlank() }

        currentArchiveListingId = movie.id
        recordsArchiveStreamId =
            requireActivity().intent.getStringExtra(PlaybackActivity.RECORDS_ARCHIVE_STREAM_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        recordsDayListings.clear()
        @Suppress("DEPRECATION")
        val rawList = requireActivity().intent.getSerializableExtra(PlaybackActivity.RECORDS_DAY_LISTINGS)
        if (rawList is ArrayList<*>) {
            for (e in rawList) {
                if (e is EpgListing) recordsDayListings.add(e)
            }
        }

        currentPlaybackUrl = url
        didCrossProtocolRetry = false
        logPlaybackTarget(url, movie.title)

        videoLayout = view.findViewById(R.id.vlc_video_layout)
        controlsOverlay = view.findViewById(R.id.playback_controls_overlay)
        trickRv = view.findViewById(R.id.playback_trick_rv)
        timeCurrent = view.findViewById(R.id.playback_time_current)
        timeTotal = view.findViewById(R.id.playback_time_total)
        progressBar = view.findViewById(R.id.playback_progress)
        recordsColumnContainer = view.findViewById(R.id.playback_records_column_container)
        recordsColumnRv = view.findViewById(R.id.playback_records_column_rv)

        trickAdapter = TrickStripAdapter(
            initialPosterUrl = posterUrl,
            slots = trickSlots,
            onActivateCard = { ms -> activateTrickCardAndResume(ms) },
            onRequestFocusVideo = { videoLayout.requestFocus() },
        )
        recordsColumnAdapter = RecordsColumnPlaybackAdapter(
            listings = recordsDayListings,
            onActivate = { listing -> activateRecordsListing(listing) },
            onCloseColumn = { closeRecordsColumnAndResume() },
            videoFocusId = R.id.vlc_video_layout,
        )
        recordsColumnRv.layoutManager = LinearLayoutManager(requireContext())
        recordsColumnRv.adapter = recordsColumnAdapter
        recordsColumnRv.itemAnimator = null
        recordsColumnContainer.isVisible = false
        trickRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        trickRv.setHasFixedSize(true)
        trickRv.itemAnimator = null
        trickRv.adapter = trickAdapter

        videoLayout.doOnLayout { vl ->
            Log.d(
                TAG,
                "VLCVideoLayout laid out: w=${vl.width} h=${vl.height} " +
                    "visible=${vl.visibility == View.VISIBLE} isShown=${vl.isShown}",
            )
        }

        videoLayout.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    if (recordsDayListings.isNotEmpty() && !recordsArchiveStreamId.isNullOrEmpty()) {
                        toggleRecordsColumn()
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (trickRv.isVisible && trickAdapter.itemCount > 0) {
                            focusTrickNearCurrentTime()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    val p = mediaPlayer ?: return@setOnKeyListener false
                    val len = mediaLengthMs(p)
                    val seekable = p.isSeekable && len > 1_000L && trickSlots.isNotEmpty()
                    if (!seekable) return@setOnKeyListener false
                    if (trickStripUserVisible || trickRv.isVisible) {
                        if (trickAdapter.itemCount > 0) {
                            focusTrickNearCurrentTime()
                            true
                        } else {
                            false
                        }
                    } else {
                        recordsColumnUserVisible = false
                        recordsColumnContainer.isVisible = false
                        trickStripUserVisible = true
                        trickRv.isVisible = true
                        if (p.isPlaying) p.pause()
                        focusTrickNearCurrentTime()
                        updatePlaybackControlsUi()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    val p = mediaPlayer ?: return@setOnKeyListener false
                    if (recordsColumnUserVisible) {
                        closeRecordsColumnAndResume()
                    } else if (trickStripUserVisible && trickRv.isVisible && trickSlots.isNotEmpty()) {
                        trickStripUserVisible = false
                        trickRv.isVisible = false
                        if (!p.isPlaying) p.play()
                        updatePlaybackControlsUi()
                    } else {
                        if (p.isPlaying) p.pause() else p.play()
                    }
                    true
                }
                else -> false
            }
        }

        val options = ArrayList<String>().apply {
            add("--network-caching=2500")
            add("--http-reconnect")
            add("-vv")
        }
        Log.d(TAG, "LibVLC options=$options")

        val vlc = LibVLC(requireContext(), options)
        libVLC = vlc
        val player = MediaPlayer(vlc)
        mediaPlayer = player

        player.setEventListener { event ->
            logVlcEvent(event)
            if (event.type == MediaPlayer.Event.EncounteredError) {
                if (retryWithAlternateSchemeIfNeeded()) return@setEventListener
                Log.e(TAG, "EncounteredError — check network / URL / codec (see VLC logs above)")
                view.post {
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.playback_error_message, getString(R.string.error_fragment_message)),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                return@setEventListener
            }
            when (event.type) {
                MediaPlayer.Event.LengthChanged,
                MediaPlayer.Event.SeekableChanged,
                -> view.post { rebuildTrickStripIfNeeded() }
                MediaPlayer.Event.Playing -> view.post {
                    rebuildTrickStripIfNeeded()
                    startProgressTicker()
                }
                else -> {}
            }
        }

        Log.d(TAG, "attachViews → VLCVideoLayout")
        player.attachViews(videoLayout, null, false, false)

        startPlayback(player, url)

        videoLayout.post { if (isAdded) videoLayout.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (mediaPlayer?.isPlaying == true) startProgressTicker()
    }

    override fun onStop() {
        Log.d(TAG, "onStop → pause()")
        view?.removeCallbacks(progressTicker)
        mediaPlayer?.pause()
        super.onStop()
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView → stop/detach/release")
        view?.removeCallbacks(progressTicker)
        mediaPlayer?.let { p ->
            p.stop()
            p.detachViews()
            p.release()
        }
        mediaPlayer = null
        libVLC?.release()
        libVLC = null
        super.onDestroyView()
    }

    private fun startProgressTicker() {
        val v = view ?: return
        v.removeCallbacks(progressTicker)
        v.post(progressTicker)
    }

    /** Seek to chapter, dismiss strip, resume playback, return focus to video. */
    private fun activateTrickCardAndResume(ms: Long) {
        val p = mediaPlayer ?: return
        runCatching { p.setTime(ms) }.onFailure { Log.w(TAG, "seek failed", it) }
        trickStripUserVisible = false
        trickRv.isVisible = false
        recordsColumnUserVisible = false
        recordsColumnContainer.isVisible = false
        if (!p.isPlaying) p.play()
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun focusTrickNearCurrentTime() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        if (len <= 0L || trickSlots.isEmpty()) return
        val t = p.time.coerceIn(0L, len)
        val n = trickSlots.size
        val idx = ((t * n) / len).toInt().coerceIn(0, n - 1)
        trickRv.scrollToPosition(idx)
        trickRv.post {
            trickRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
        }
    }

    private fun toggleRecordsColumn() {
        if (recordsDayListings.isEmpty() || recordsArchiveStreamId.isNullOrEmpty()) return
        if (recordsColumnUserVisible) closeRecordsColumnAndResume() else openRecordsColumn()
    }

    private fun openRecordsColumn() {
        if (recordsDayListings.isEmpty() || recordsArchiveStreamId.isNullOrEmpty()) return
        trickStripUserVisible = false
        trickRv.isVisible = false
        recordsColumnUserVisible = true
        recordsColumnContainer.isVisible = true
        recordsColumnAdapter.notifyDataSetChanged()
        mediaPlayer?.pause()
        val idx = recordsDayListings.indexOfFirst { (it.startUnix xor it.endUnix) == currentArchiveListingId }
            .let { if (it >= 0) it else 0 }
        recordsColumnRv.scrollToPosition(idx)
        requestFocusOnRecordsColumnPosition(idx)
        updatePlaybackControlsUi()
    }

    private fun requestFocusOnRecordsColumnPosition(adapterPosition: Int) {
        recordsColumnRv.post {
            val h = recordsColumnRv.findViewHolderForAdapterPosition(adapterPosition)
            if (h != null) {
                h.itemView.requestFocus()
            } else {
                recordsColumnRv.postDelayed(
                    {
                        recordsColumnRv.findViewHolderForAdapterPosition(adapterPosition)?.itemView?.requestFocus()
                    },
                    64L,
                )
            }
        }
    }

    private fun closeRecordsColumnAndResume() {
        if (!recordsColumnUserVisible) return
        recordsColumnUserVisible = false
        recordsColumnContainer.isVisible = false
        val p = mediaPlayer ?: return
        if (!p.isPlaying) p.play()
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun activateRecordsListing(listing: EpgListing) {
        val sid = recordsArchiveStreamId ?: return
        val player = mediaPlayer ?: return
        val url = IptvStreamUrls.timeshiftStreamUrl(sid, listing.startUnix, listing.endUnix)
        currentArchiveListingId = listing.startUnix xor listing.endUnix
        posterUrl = sequenceOf(listing.imageUrl, posterUrl).firstOrNull { !it.isNullOrBlank() }
        trickAdapter.setPosterUrl(posterUrl)
        trickStripBuiltForLengthMs = -1L
        trickStripUserVisible = false
        trickRv.isVisible = false
        recordsColumnUserVisible = false
        recordsColumnContainer.isVisible = false
        runCatching { player.stop() }
        startPlayback(player, url)
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun rebuildTrickStripIfNeeded() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        val seekable = p.isSeekable && len > 1_000L

        if (seekable) {
            if (len != trickStripBuiltForLengthMs) {
                trickStripBuiltForLengthMs = len
                trickSlots.clear()
                val step = (len / TRICK_SLOT_COUNT).coerceAtLeast(1L)
                for (i in 0 until TRICK_SLOT_COUNT) {
                    val start = (i * step).coerceAtMost((len - 1L).coerceAtLeast(0L))
                    trickSlots.add(TrickSlot(start, formatPlaybackTimeMs(start)))
                }
                trickAdapter.notifyDataSetChanged()
            }
            trickRv.isVisible = trickStripUserVisible && trickSlots.isNotEmpty()
            timeTotal.text = formatPlaybackTimeMs(len)
        } else {
            trickStripBuiltForLengthMs = -1L
            trickStripUserVisible = false
            trickRv.isVisible = false
            trickSlots.clear()
            trickAdapter.notifyDataSetChanged()
            timeTotal.text = getString(R.string.playback_live)
        }
        updatePlaybackControlsUi()
    }

    private fun updatePlaybackControlsUi() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        val cur = p.time.coerceAtLeast(0L)
        val stripShowing = trickStripUserVisible && trickRv.isVisible && trickSlots.isNotEmpty()
        val seekableVod = p.isSeekable && len > 1_000L
        val showChrome = stripShowing && seekableVod

        controlsOverlay.isVisible = showChrome
        if (!showChrome) {
            progressBar.isVisible = false
            progressBar.progress = 0
            return
        }

        timeCurrent.text = formatPlaybackTimeMs(cur)
        timeTotal.text = formatPlaybackTimeMs(len)
        progressBar.isVisible = len > 0L
        if (len > 0L) {
            val ratio = (cur * 1000L / len).toInt().coerceIn(0, 1000)
            progressBar.progress = ratio
        } else {
            progressBar.progress = 0
        }
    }

    private fun mediaLengthMs(p: MediaPlayer): Long {
        val l = p.length
        return if (l > 0L) l else 0L
    }

    private fun startPlayback(player: MediaPlayer, url: String) {
        currentPlaybackUrl = url
        val media = Media(libVLC ?: return, Uri.parse(url))
        applyPanelFriendlyHttpOptions(media, url)
        Log.d(TAG, "Media created, binding to player; url=${sanitizeUrlForLog(url)}")
        player.media = media
        media.release()
        Log.d(TAG, "play() called")
        player.play()
    }

    private fun retryWithAlternateSchemeIfNeeded(): Boolean {
        if (didCrossProtocolRetry) return false
        val alt = alternateScheme(currentPlaybackUrl) ?: return false
        val player = mediaPlayer ?: return false
        didCrossProtocolRetry = true
        Log.w(
            TAG,
            "Retrying playback with alternate scheme: from=${sanitizeUrlForLog(currentPlaybackUrl)} to=${sanitizeUrlForLog(alt)}",
        )
        startPlayback(player, alt)
        return true
    }

    private fun alternateScheme(url: String): String? = when {
        url.startsWith("https://", ignoreCase = true) ->
            "http://${url.removePrefix("https://")}"
        url.startsWith("http://", ignoreCase = true) ->
            "https://${url.removePrefix("http://")}"
        else -> null
    }

    private fun sanitizeUrlForLog(url: String): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.host}${u.path.orEmpty()}"
    }

    private companion object {
        const val TAG = "PlaybackVLC"
        const val TRICK_SLOT_COUNT = 18

        fun formatPlaybackTimeMs(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return if (h > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        fun applyPanelFriendlyHttpOptions(media: Media, streamUrl: String) {
            val u = Uri.parse(streamUrl)
            val scheme = u.scheme?.lowercase() ?: return
            val host = u.host ?: return
            if (scheme != "http" && scheme != "https") return
            val origin = "$scheme://$host/"
            media.addOption(":http-referrer=$origin")
            media.addOption(
                ":http-user-agent=Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
        }

        fun logPlaybackTarget(url: String, title: String?) {
            val u = Uri.parse(url)
            val last = u.lastPathSegment ?: "?"
            Log.i(TAG, "title=${title ?: "?"} host=${u.host} scheme=${u.scheme} lastSegment=$last (full URL omitted — contains credentials)")
        }

        fun logVlcEvent(event: MediaPlayer.Event) {
            val name = eventTypeName(event.type)
            val extra = when (event.type) {
                MediaPlayer.Event.Buffering -> " buffering=${event.buffering}"
                else -> ""
            }
            val level = when (event.type) {
                MediaPlayer.Event.EncounteredError -> Log.ERROR
                MediaPlayer.Event.Opening,
                MediaPlayer.Event.Playing,
                MediaPlayer.Event.Vout,
                -> Log.INFO
                else -> Log.DEBUG
            }
            Log.println(level, TAG, "event: $name$extra")
        }

        fun eventTypeName(type: Int): String = when (type) {
            MediaPlayer.Event.Opening -> "Opening"
            MediaPlayer.Event.Buffering -> "Buffering"
            MediaPlayer.Event.Playing -> "Playing"
            MediaPlayer.Event.Paused -> "Paused"
            MediaPlayer.Event.Stopped -> "Stopped"
            MediaPlayer.Event.EndReached -> "EndReached"
            MediaPlayer.Event.EncounteredError -> "EncounteredError"
            MediaPlayer.Event.TimeChanged -> "TimeChanged"
            MediaPlayer.Event.PositionChanged -> "PositionChanged"
            MediaPlayer.Event.SeekableChanged -> "SeekableChanged"
            MediaPlayer.Event.PausableChanged -> "PausableChanged"
            MediaPlayer.Event.LengthChanged -> "LengthChanged"
            MediaPlayer.Event.Vout -> "Vout"
            MediaPlayer.Event.MediaChanged -> "MediaChanged"
            MediaPlayer.Event.ESAdded -> "ESAdded"
            MediaPlayer.Event.ESDeleted -> "ESDeleted"
            else -> "Unknown($type)"
        }
    }
}

private data class TrickSlot(val startMs: Long, val label: String)

private class RecordsColumnPlaybackAdapter(
    private val listings: List<EpgListing>,
    private val onActivate: (EpgListing) -> Unit,
    private val onCloseColumn: () -> Unit,
    private val videoFocusId: Int,
) : RecyclerView.Adapter<RecordsColumnPlaybackAdapter.VH>() {

    override fun getItemCount(): Int = listings.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_records_column, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val listing = listings[position]
        val timeLabel = IptvTimeUtils.formatTimeRangeIsrael(listing.startUnix, listing.endUnix)
        holder.time.text = timeLabel
        holder.title.text = listing.title
        holder.itemView.contentDescription =
            holder.itemView.context.getString(R.string.playback_cd_trick_seek, timeLabel)

        val img = listing.imageUrl
        if (img.isNullOrBlank()) {
            Glide.with(holder.thumb).clear(holder.thumb)
            holder.thumb.setImageDrawable(null)
        } else {
            Glide.with(holder.thumb).load(img).centerCrop().into(holder.thumb)
        }

        holder.itemView.nextFocusLeftId = videoFocusId

        holder.itemView.setOnClickListener { onActivate(listing) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (pos == 0) {
                        onCloseColumn()
                        true
                    } else {
                        false
                    }
                KeyEvent.KEYCODE_BACK -> {
                    onCloseColumn()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos + 1) != null) return@setOnKeyListener false
                    val next = pos + 1
                    rv.scrollToPosition(next)
                    rv.post {
                        rv.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
                            ?: rv.postDelayed(
                                { rv.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() },
                                64L,
                            )
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onActivate(listing)
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.playback_records_col_thumb)
        val time: TextView = itemView.findViewById(R.id.playback_records_col_time)
        val title: TextView = itemView.findViewById(R.id.playback_records_col_title)
    }
}

private class TrickStripAdapter(
    initialPosterUrl: String?,
    private val slots: List<TrickSlot>,
    private val onActivateCard: (Long) -> Unit,
    private val onRequestFocusVideo: () -> Unit,
) : RecyclerView.Adapter<TrickStripAdapter.VH>() {

    private var posterUrl: String? = initialPosterUrl

    fun setPosterUrl(url: String?) {
        posterUrl = url
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = slots.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_trick_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val slot = slots[position]
        holder.time.text = slot.label
        holder.itemView.contentDescription =
            holder.itemView.context.getString(R.string.playback_cd_trick_seek, slot.label)

        val url = posterUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (url.isNullOrEmpty()) {
            Glide.with(holder.poster).clear(holder.poster)
            holder.poster.setImageDrawable(null)
        } else {
            Glide.with(holder.poster).load(url).centerCrop().into(holder.poster)
        }

        holder.itemView.nextFocusLeftId =
            if (position == 0) R.id.vlc_video_layout else View.NO_ID

        holder.itemView.setOnClickListener {
            onActivateCard(slot.startMs)
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onActivateCard(slot.startMs)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    onRequestFocusVideo()
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: ImageView = itemView.findViewById(R.id.playback_trick_poster)
        val time: TextView = itemView.findViewById(R.id.playback_trick_time)
    }
}
