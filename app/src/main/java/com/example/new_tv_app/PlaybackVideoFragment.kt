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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * Plays VOD and live IPTV via LibVLC. Bottom overlay: preview-style strip + progress for seekable content;
 * live / non-seekable shows timeline row only (no strip).
 * Seekable VOD: DPAD left/right on the video opens chapter cards; Enter closes the strip (or play/pause when hidden).
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

    private val trickSlots = mutableListOf<TrickSlot>()
    private lateinit var trickAdapter: TrickStripAdapter
    private var posterUrl: String? = null
    /** Last duration used to build trick strip; rebuild when LibVLC reports a new length. */
    private var trickStripBuiltForLengthMs: Long = -1L
    /** Seek chapter cards visible (DPAD left/right on video); playback paused while visible. */
    private var trickStripUserVisible: Boolean = false

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

        currentPlaybackUrl = url
        didCrossProtocolRetry = false
        logPlaybackTarget(url, movie.title)

        videoLayout = view.findViewById(R.id.vlc_video_layout)
        controlsOverlay = view.findViewById(R.id.playback_controls_overlay)
        trickRv = view.findViewById(R.id.playback_trick_rv)
        timeCurrent = view.findViewById(R.id.playback_time_current)
        timeTotal = view.findViewById(R.id.playback_time_total)
        progressBar = view.findViewById(R.id.playback_progress)

        trickAdapter = TrickStripAdapter(
            posterUrl = posterUrl,
            slots = trickSlots,
            onActivateCard = { ms -> activateTrickCardAndResume(ms) },
            onRequestFocusVideo = { videoLayout.requestFocus() },
        )
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
                    if (trickStripUserVisible && trickRv.isVisible && trickSlots.isNotEmpty()) {
                        trickStripUserVisible = false
                        trickRv.isVisible = false
                        if (!p.isPlaying) p.play()
                        updatePlaybackControlsUi()
                    } else {
                        if (p.isPlaying) p.pause() else p.play()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (trickRv.isVisible && trickAdapter.itemCount > 0) {
                        focusTrickNearCurrentTime()
                        true
                    } else {
                        false
                    }
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

    private fun rebuildTrickStripIfNeeded() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        val seekable = p.isSeekable && len > 1_000L

        controlsOverlay.isVisible = true

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
        timeCurrent.text = formatPlaybackTimeMs(cur)

        // Timeline + bar while playing, and while chapter cards are open (same seekable VOD).
        val seekable = p.isSeekable && (len > 1_000L || trickStripUserVisible)
        val showProgress = seekable && (len > 0L || trickStripUserVisible)
        progressBar.isVisible = showProgress
        if (showProgress && len > 0L) {
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

private class TrickStripAdapter(
    private val posterUrl: String?,
    private val slots: List<TrickSlot>,
    private val onActivateCard: (Long) -> Unit,
    private val onRequestFocusVideo: () -> Unit,
) : RecyclerView.Adapter<TrickStripAdapter.VH>() {

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
