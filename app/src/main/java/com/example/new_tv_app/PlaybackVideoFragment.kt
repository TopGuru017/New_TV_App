package com.example.new_tv_app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Plays VOD and live IPTV via LibVLC (handles redirects / HLS / odd servers better than MediaPlayer / ExoPlayer defaults).
 *
 * Many Xtream-style panels respond with **302** to a CDN/origin that checks **Referer** and/or rejects the default **VLC/x.x**
 * user-agent; the redirected request then returns **403** unless we send a panel **Referer** and a browser-like **User-Agent**.
 */
class PlaybackVideoFragment : Fragment() {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

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

        logPlaybackTarget(url, movie.title)

        val videoLayout = view.findViewById<VLCVideoLayout>(R.id.vlc_video_layout)
        videoLayout.doOnLayout { vl ->
            Log.d(
                TAG,
                "VLCVideoLayout laid out: w=${vl.width} h=${vl.height} " +
                    "visible=${vl.visibility == View.VISIBLE} isShown=${vl.isShown}",
            )
        }

        val options = ArrayList<String>().apply {
            add("--network-caching=2500")
            add("--http-reconnect")
            // Extra VLC engine lines in Logcat (tag often "VLC" / "vlc"); remove if too noisy.
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
            }
        }

        Log.d(TAG, "attachViews → VLCVideoLayout")
        player.attachViews(videoLayout, null, false, false)

        val media = Media(vlc, Uri.parse(url))
        applyPanelFriendlyHttpOptions(media, url)
        Log.d(TAG, "Media created, binding to player")
        player.media = media
        media.release()

        Log.d(TAG, "play() called")
        player.play()
    }

    override fun onStop() {
        Log.d(TAG, "onStop → pause()")
        mediaPlayer?.pause()
        super.onStop()
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView → stop/detach/release")
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

    private companion object {
        const val TAG = "PlaybackVLC"

        /**
         * Panel **Referer** (scheme + host) and a TV **Chrome** user-agent for HTTP(S), including after redirects.
         */
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

        /** Logs host + stream id only (path contains credentials for Xtream-style URLs). */
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
