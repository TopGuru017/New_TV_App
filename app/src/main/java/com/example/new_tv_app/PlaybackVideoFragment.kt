package com.example.new_tv_app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageView.ScaleType
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.example.new_tv_app.iptv.EpgListing
import com.example.new_tv_app.iptv.FavoriteVodStore
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.XtreamLiveApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Plays VOD and live IPTV via ExoPlayer. No on-screen chrome while playing. Seekable VOD: DPAD left/right opens
 * the horizontal chapter strip; only then is the bottom overlay (times + progress) shown. Panel **live** URLs
 * ([IptvStreamUrls.isPanelLiveStreamUrl]): no chapter strip or seek — live is not movable.
 * Live channel picker ([PlaybackActivity.LIVE_CATEGORY_ID] + [PlaybackActivity.LIVE_STREAM_ID]): DPAD up/down
 * on the video toggles the column; inside the column, up/down moves between cards (no-op at first/last row),
 * OK switches stream, right/back dismisses.
 * Records catch-up (intent carries [PlaybackActivity.RECORDS_DAY_LISTINGS]): DPAD up/down on video toggles a bottom
 * overlay (VOD-style card + footer); DPAD up/down on the thumbnail browses listings (▲/▼ flash cyan). At the first
 * listing, up does nothing; at the last, down does nothing. OK on the thumbnail applies the selection (if changed)
 * and dismisses; DPAD left/back closes. Left/right chapter strip still applies when the stream is seekable.
 */
@UnstableApi
class PlaybackVideoFragment : Fragment() {

    private var mediaPlayer: ExoPlayer? = null
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var currentPlaybackUrl: String = ""
    private val attemptedPlaybackUrls = linkedSetOf<String>()

    private lateinit var videoLayout: PlayerView
    private lateinit var controlsOverlay: View
    private lateinit var trickRv: RecyclerView
    private lateinit var trickTimelineRow: View
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recordsInfoOverlay: View
    private lateinit var recordsColumnScrollUp: TextView
    private lateinit var recordsColumnScrollDown: TextView
    private lateinit var recordsColumnThumb: ImageView
    private lateinit var recordsInfoTitle: TextView
    private lateinit var recordsInfoProgress: ProgressBar
    private lateinit var recordsInfoTimeCurrent: TextView
    private lateinit var recordsInfoTimeTotal: TextView
    private lateinit var recordsInfoDescription: TextView
    private lateinit var recordsInfoMeta: TextView

    private val trickSlots = mutableListOf<TrickSlot>()
    private lateinit var trickAdapter: TrickStripAdapter
    private var posterUrl: String? = null
    /** Last duration used to build trick strip; rebuild when ExoPlayer reports a new length. */
    private var trickStripBuiltForLengthMs: Long = -1L
    /** Seek chapter cards visible (DPAD left/right on video); playback paused while visible. */
    private var trickStripUserVisible: Boolean = false
    private var selectedTrickIndex: Int = 0

    private var recordsArchiveStreamId: String? = null
    private val recordsDayListings = mutableListOf<EpgListing>()
    /** Records day panel visible (DPAD up/down on video); only when [recordsDayListings] non-empty. */
    private var recordsColumnUserVisible: Boolean = false
    private var currentArchiveListingId: Long = 0L
    /** Index in [recordsDayListings] for the panel (may differ from playback until user confirms). */
    private var recordsColumnListingIndex: Int = 0

    private val recordsArrowColorResetRunnable = Runnable {
        if (!isAdded || !recordsColumnUserVisible) return@Runnable
        resetRecordsArrowGlyphColorsAndDimming()
    }

    private lateinit var liveChannelColumnContainer: View
    private lateinit var liveChannelColumnRv: RecyclerView
    private lateinit var liveChannelColumnAdapter: LivePlaybackChannelColumnAdapter
    private var liveChannelColumnUserVisible: Boolean = false
    private var liveChannelsLoadJob: Job? = null
    private var liveCategoryId: String? = null
    private var livePlaybackStreamId: String? = null
    private lateinit var backCloseLiveChannelColumn: OnBackPressedCallback
    private lateinit var vodInfoBackCallback: OnBackPressedCallback

    private lateinit var playbackMovie: Movie

    private lateinit var vodInfoOverlay: View
    private lateinit var vodInfoThumb: ImageView
    private lateinit var vodInfoTitle: TextView
    private lateinit var vodInfoDescription: TextView
    private lateinit var vodInfoProgress: ProgressBar
    private lateinit var vodInfoTimeCurrent: TextView
    private lateinit var vodInfoTimeTotal: TextView
    private lateinit var vodInfoMeta: TextView
    private lateinit var vodInfoFavoriteBtn: TextView
    private var vodInfoWaitingForSecondDownFocus: Boolean = false

    private val progressTicker = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (!isAdded) return
            updatePlaybackControlsUi()
            updateVodInfoOverlayUi()
            updateRecordsInfoPlaybackUi()
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

        playbackMovie = movie

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

        liveCategoryId =
            requireActivity().intent.getStringExtra(PlaybackActivity.LIVE_CATEGORY_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        livePlaybackStreamId =
            requireActivity().intent.getStringExtra(PlaybackActivity.LIVE_STREAM_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        currentPlaybackUrl = url
        attemptedPlaybackUrls.clear()
        attemptedPlaybackUrls.add(url)
        logPlaybackTarget(url, movie.title)

        videoLayout = view.findViewById(R.id.vlc_video_layout)
        controlsOverlay = view.findViewById(R.id.playback_controls_overlay)
        trickRv = view.findViewById(R.id.playback_trick_rv)
        trickTimelineRow = view.findViewById(R.id.playback_timeline_row)
        timeCurrent = view.findViewById(R.id.playback_time_current)
        timeTotal = view.findViewById(R.id.playback_time_total)
        progressBar = view.findViewById(R.id.playback_progress)
        recordsInfoOverlay = view.findViewById(R.id.playback_records_info_overlay)
        recordsColumnScrollUp = view.findViewById(R.id.playback_records_scroll_up)
        recordsColumnScrollDown = view.findViewById(R.id.playback_records_scroll_down)
        recordsColumnThumb = view.findViewById(R.id.playback_records_col_thumb)
        recordsInfoTitle = view.findViewById(R.id.playback_records_info_title)
        recordsInfoProgress = view.findViewById(R.id.playback_records_info_progress)
        recordsInfoTimeCurrent = view.findViewById(R.id.playback_records_info_time_current)
        recordsInfoTimeTotal = view.findViewById(R.id.playback_records_info_time_total)
        recordsInfoDescription = view.findViewById(R.id.playback_records_info_description)
        recordsInfoMeta = view.findViewById(R.id.playback_records_info_meta)
        setupRecordsColumnPanelKeys()

        trickAdapter = TrickStripAdapter(
            initialPosterUrl = posterUrl,
            slots = trickSlots,
        )
        recordsInfoOverlay.isVisible = false

        liveChannelColumnContainer = view.findViewById(R.id.playback_live_channels_container)
        liveChannelColumnRv = view.findViewById(R.id.playback_live_channels_rv)
        backCloseLiveChannelColumn = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeLiveChannelColumnAndResume()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCloseLiveChannelColumn)

        vodInfoOverlay = view.findViewById(R.id.playback_vod_info_overlay)
        vodInfoThumb = view.findViewById(R.id.playback_vod_info_thumb)
        vodInfoTitle = view.findViewById(R.id.playback_vod_info_title)
        vodInfoDescription = view.findViewById(R.id.playback_vod_info_description)
        vodInfoProgress = view.findViewById(R.id.playback_vod_info_progress)
        vodInfoTimeCurrent = view.findViewById(R.id.playback_vod_info_time_current)
        vodInfoTimeTotal = view.findViewById(R.id.playback_vod_info_time_total)
        vodInfoMeta = view.findViewById(R.id.playback_vod_info_meta)
        vodInfoFavoriteBtn = view.findViewById(R.id.playback_vod_favorite_btn)
        vodInfoBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hideVodInfoOverlay()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, vodInfoBackCallback)

        vodInfoFavoriteBtn.setOnClickListener {
            val sid = FavoriteVodStore.streamIdFromMovieUrl(playbackMovie.videoUrl) ?: return@setOnClickListener
            if (FavoriteVodStore.isFavorite(requireContext(), sid)) {
                FavoriteVodStore.remove(requireContext(), sid)
            } else {
                FavoriteVodStore.add(requireContext(), playbackMovie)
            }
            refreshVodFavoriteButton()
        }
        vodInfoFavoriteBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    videoLayout.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    hideVodInfoOverlay()
                    videoLayout.requestFocus()
                    true
                }
                else -> false
            }
        }

        liveChannelColumnAdapter = LivePlaybackChannelColumnAdapter(
            onStreamPicked = { stream -> switchLiveStreamFromColumn(stream) },
            onCloseColumn = { closeLiveChannelColumnAndResume() },
            videoFocusId = R.id.vlc_video_layout,
        )
        liveChannelColumnRv.layoutManager = LinearLayoutManager(requireContext())
        liveChannelColumnRv.adapter = liveChannelColumnAdapter
        liveChannelColumnRv.itemAnimator = null
        liveChannelColumnContainer.isVisible = false

        trickRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        trickRv.setHasFixedSize(true)
        trickRv.itemAnimator = null
        trickRv.adapter = trickAdapter

        videoLayout.doOnLayout { vl ->
            Log.d(
                TAG,
                "PlayerView laid out: w=${vl.width} h=${vl.height} " +
                    "visible=${vl.visibility == View.VISIBLE} isShown=${vl.isShown}",
            )
        }

        videoLayout.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    if (vodInfoOverlay.isVisible) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && vodInfoWaitingForSecondDownFocus) {
                            vodInfoWaitingForSecondDownFocus = false
                            vodInfoFavoriteBtn.requestFocus()
                            return@setOnKeyListener true
                        }
                        hideVodInfoOverlay()
                        videoLayout.requestFocus()
                        return@setOnKeyListener true
                    }
                    if (recordsDayListings.isNotEmpty() && !recordsArchiveStreamId.isNullOrEmpty()) {
                        toggleRecordsColumn()
                        return@setOnKeyListener true
                    }
                    if (IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl) && isLiveChannelPickerEligible()) {
                        toggleLiveChannelColumn()
                        return@setOnKeyListener true
                    }
                    if (isVodPlaybackMode()) {
                        if (trickStripUserVisible || (trickRv.isVisible && trickAdapter.itemCount > 0)) {
                            trickStripUserVisible = false
                            trickRv.isVisible = false
                            mediaPlayer?.play()
                            updatePlaybackControlsUi()
                        }
                        showVodInfoOverlay()
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (!IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl) &&
                            trickRv.isVisible && trickAdapter.itemCount > 0
                        ) {
                            focusTrickNearCurrentTime()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    if (vodInfoOverlay.isVisible) {
                        hideVodInfoOverlay()
                    }
                    if (IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl)) {
                        return@setOnKeyListener true
                    }
                    val p = mediaPlayer ?: return@setOnKeyListener false
                    val len = mediaLengthMs(p)
                    val seekable = p.isCurrentMediaItemSeekable && len > 1_000L && trickSlots.isNotEmpty()
                    if (!seekable) return@setOnKeyListener false
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    if (!trickStripUserVisible || !trickRv.isVisible) {
                        if (!openTrickStripForSeeking()) return@setOnKeyListener false
                        true
                    } else {
                        shiftSelectedTrick(delta)
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    val p = mediaPlayer ?: return@setOnKeyListener false
                    if (liveChannelColumnUserVisible) {
                        closeLiveChannelColumnAndResume()
                    } else if (recordsColumnUserVisible) {
                        closeRecordsColumnAndResume()
                    } else if (trickStripUserVisible && trickRv.isVisible && trickSlots.isNotEmpty()) {
                        val slot = trickSlots[selectedTrickIndex.coerceIn(0, trickSlots.lastIndex)]
                        activateTrickCardAndResume(slot.startMs)
                    } else {
                        if (p.isPlaying) p.pause() else p.play()
                    }
                    true
                }
                else -> false
            }
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        httpDataSourceFactory = httpFactory
        val dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpFactory)
        val player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        mediaPlayer = player
        videoLayout.player = player

        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: code=${error.errorCodeName}", error)
                    if (retryWithVodContainerFallbackIfNeeded()) return
                    if (retryWithAlternateSchemeIfNeeded()) return
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

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "isPlaying=$isPlaying")
                    if (isPlaying) {
                        view.post {
                            rebuildTrickStripIfNeeded()
                            startProgressTicker()
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val state = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($playbackState)"
                    }
                    Log.d(TAG, "state=$state")
                    view.post { rebuildTrickStripIfNeeded() }
                }
            },
        )

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
        Log.d(TAG, "onDestroyView → stop/release")
        liveChannelsLoadJob?.cancel()
        view?.removeCallbacks(progressTicker)
        if (::recordsColumnScrollUp.isInitialized) {
            recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        }
        mediaPlayer?.let { p ->
            p.stop()
            p.release()
        }
        mediaPlayer = null
        httpDataSourceFactory = null
        videoLayout.player = null
        super.onDestroyView()
    }

    private fun isLiveChannelPickerEligible(): Boolean =
        !liveCategoryId.isNullOrEmpty() && !livePlaybackStreamId.isNullOrEmpty()

    private fun toggleLiveChannelColumn() {
        if (liveChannelColumnUserVisible) {
            closeLiveChannelColumnAndResume()
        } else {
            openLiveChannelColumn()
        }
    }

    private fun openLiveChannelColumn() {
        val catId = liveCategoryId ?: return
        if (!IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl)) return
        liveChannelColumnUserVisible = true
        liveChannelColumnContainer.isVisible = true
        backCloseLiveChannelColumn.isEnabled = true
        mediaPlayer?.pause()
        liveChannelsLoadJob?.cancel()
        liveChannelsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = XtreamLiveApi.fetchLiveStreams(catId)
            if (!isAdded || !liveChannelColumnUserVisible) return@launch
            result.fold(
                onSuccess = { streams ->
                    liveChannelColumnAdapter.submit(streams)
                    val sid = livePlaybackStreamId
                    val idx = if (sid != null) {
                        streams.indexOfFirst { it.streamId == sid }.let { i -> if (i >= 0) i else 0 }
                    } else {
                        0
                    }
                    if (streams.isNotEmpty()) {
                        val safe = idx.coerceIn(0, streams.lastIndex)
                        liveChannelColumnRv.scrollToPosition(safe)
                        requestFocusLiveChannelPosition(safe)
                    }
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        R.string.live_load_error,
                        Toast.LENGTH_SHORT,
                    ).show()
                    closeLiveChannelColumnAndResume()
                },
            )
        }
    }

    private fun requestFocusLiveChannelPosition(adapterPosition: Int) {
        liveChannelColumnRv.post {
            val h = liveChannelColumnRv.findViewHolderForAdapterPosition(adapterPosition)
            if (h != null) {
                h.itemView.requestFocus()
            } else {
                liveChannelColumnRv.postDelayed(
                    {
                        liveChannelColumnRv.findViewHolderForAdapterPosition(adapterPosition)
                            ?.itemView
                            ?.requestFocus()
                    },
                    64L,
                )
            }
        }
    }

    private fun closeLiveChannelColumnAndResume() {
        if (!liveChannelColumnUserVisible) return
        liveChannelColumnUserVisible = false
        liveChannelColumnContainer.isVisible = false
        backCloseLiveChannelColumn.isEnabled = false
        liveChannelsLoadJob?.cancel()
        liveChannelsLoadJob = null
        mediaPlayer?.play()
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun switchLiveStreamFromColumn(stream: LiveStream) {
        livePlaybackStreamId = stream.streamId
        posterUrl = sequenceOf(stream.iconUrl, posterUrl).firstOrNull { !it.isNullOrBlank() }
        trickAdapter.setPosterUrl(posterUrl)
        trickStripBuiltForLengthMs = -1L
        trickStripUserVisible = false
        trickRv.isVisible = false
        liveChannelColumnUserVisible = false
        liveChannelColumnContainer.isVisible = false
        backCloseLiveChannelColumn.isEnabled = false
        liveChannelsLoadJob?.cancel()
        liveChannelsLoadJob = null
        val player = mediaPlayer ?: return
        runCatching { player.stop() }
        val newUrl = IptvStreamUrls.liveStreamUrl(stream.streamId)
        startPlayback(player, newUrl)
        videoLayout.requestFocus()
        player.play()
        updatePlaybackControlsUi()
    }

    private fun startProgressTicker() {
        val v = view ?: return
        v.removeCallbacks(progressTicker)
        v.post(progressTicker)
    }

    /** Seek to chapter, dismiss strip, resume playback, return focus to video. */
    private fun activateTrickCardAndResume(ms: Long) {
        val p = mediaPlayer ?: return
        runCatching { p.seekTo(ms) }.onFailure { Log.w(TAG, "seek failed", it) }
        trickStripUserVisible = false
        trickRv.isVisible = false
        recordsColumnUserVisible = false
        recordsInfoOverlay.isVisible = false
        if (!p.isPlaying) p.play()
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun focusTrickNearCurrentTime() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        if (len <= 0L || trickSlots.isEmpty()) return
        val t = p.currentPosition.coerceIn(0L, len)
        val n = trickSlots.size
        val idx = ((t * n) / len).toInt().coerceIn(0, n - 1)
        selectedTrickIndex = idx
        centerSelectedTrickCard()
    }

    private fun syncSelectedTrickToCurrentTime() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        if (len <= 0L || trickSlots.isEmpty()) return
        val t = p.currentPosition.coerceIn(0L, len)
        val n = trickSlots.size
        selectedTrickIndex = ((t * n) / len).toInt().coerceIn(0, n - 1)
        trickAdapter.setSelectedIndex(selectedTrickIndex)
    }

    private fun shiftSelectedTrick(delta: Int) {
        if (trickSlots.isEmpty()) return
        selectedTrickIndex = (selectedTrickIndex + delta).coerceIn(0, trickSlots.lastIndex)
        centerSelectedTrickCard()
        updatePlaybackControlsUi()
    }

    private fun centerSelectedTrickCard() {
        if (trickSlots.isEmpty()) return
        val lm = trickRv.layoutManager as? LinearLayoutManager ?: return
        trickAdapter.setSelectedIndex(selectedTrickIndex)
        val cardW = resources.getDimensionPixelSize(R.dimen.playback_trick_card_width)
        val offset = ((trickRv.width - cardW) / 2).coerceAtLeast(0)
        lm.scrollToPositionWithOffset(selectedTrickIndex, offset)
        trickRv.post {
            val postOffset = ((trickRv.width - cardW) / 2).coerceAtLeast(0)
            lm.scrollToPositionWithOffset(selectedTrickIndex, postOffset)
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
        recordsInfoOverlay.isVisible = true
        syncRecordsColumnListingIndexWithPlayback()
        resetRecordsArrowGlyphColorsAndDimming()
        bindRecordsColumnPanelUi()
        mediaPlayer?.pause()
        recordsColumnThumb.post {
            if (recordsColumnUserVisible && isAdded) recordsColumnThumb.requestFocus()
        }
        updatePlaybackControlsUi()
    }

    private fun syncRecordsColumnListingIndexWithPlayback() {
        recordsColumnListingIndex = recordsDayListings.indexOfFirst { (it.startUnix xor it.endUnix) == currentArchiveListingId }
            .let { if (it >= 0) it else 0 }
    }

    private fun bindRecordsColumnPanelUi() {
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex) ?: return
        val timeLabel = IptvTimeUtils.formatTimeRangeIsrael(listing.startUnix, listing.endUnix)
        recordsInfoTitle.text = listing.title
        recordsInfoDescription.text = listing.description.trim().ifBlank {
            getString(R.string.tv_guide_no_description)
        }
        recordsInfoMeta.text = formatRecordsMetaLine(listing)
        recordsColumnThumb.contentDescription = listing.title.ifBlank { listing.description.ifBlank { timeLabel } }

        val img = listing.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (img.isNullOrEmpty()) {
            Glide.with(recordsColumnThumb).clear(recordsColumnThumb)
            recordsColumnThumb.scaleType = ScaleType.CENTER_INSIDE
            recordsColumnThumb.setImageResource(R.drawable.ic_playback_timeslot_placeholder)
            recordsColumnThumb.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.sidebar_accent_cyan),
                PorterDuff.Mode.SRC_IN,
            )
        } else {
            recordsColumnThumb.clearColorFilter()
            recordsColumnThumb.scaleType = ScaleType.CENTER_CROP
            Glide.with(recordsColumnThumb)
                .load(img)
                .placeholder(R.drawable.ic_playback_timeslot_placeholder)
                .error(R.drawable.ic_playback_timeslot_placeholder)
                .centerCrop()
                .into(recordsColumnThumb)
        }

        applyRecordsScrollArrowDimming()
        applyRecordsThumbBorderState()
        updateRecordsInfoPlaybackUi()
    }

    private fun applyRecordsThumbBorderState() {
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex) ?: return
        val matchesPlayback = (listing.startUnix xor listing.endUnix) == currentArchiveListingId
        recordsColumnThumb.setBackgroundResource(
            if (matchesPlayback) {
                R.drawable.bg_playback_records_thumb_border_cyan
            } else {
                R.drawable.bg_playback_records_thumb_border_muted
            },
        )
    }

    private fun applyRecordsScrollArrowDimming() {
        val atTop = recordsColumnListingIndex <= 0
        val atBottom = recordsColumnListingIndex >= recordsDayListings.lastIndex
        recordsColumnScrollUp.alpha = if (atTop) 0.35f else 1f
        recordsColumnScrollDown.alpha = if (atBottom) 0.35f else 1f
    }

    private fun resetRecordsArrowGlyphColorsAndDimming() {
        val primary = ContextCompat.getColor(requireContext(), R.color.sidebar_text_primary)
        recordsColumnScrollUp.setTextColor(primary)
        recordsColumnScrollDown.setTextColor(primary)
        applyRecordsScrollArrowDimming()
    }

    private fun flashRecordsScrollArrowUp() {
        recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        val primary = ContextCompat.getColor(requireContext(), R.color.sidebar_text_primary)
        val cyan = ContextCompat.getColor(requireContext(), R.color.sidebar_accent_cyan)
        recordsColumnScrollDown.setTextColor(primary)
        recordsColumnScrollUp.setTextColor(cyan)
        recordsColumnScrollUp.postDelayed(recordsArrowColorResetRunnable, 220L)
    }

    private fun flashRecordsScrollArrowDown() {
        recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        val primary = ContextCompat.getColor(requireContext(), R.color.sidebar_text_primary)
        val cyan = ContextCompat.getColor(requireContext(), R.color.sidebar_accent_cyan)
        recordsColumnScrollUp.setTextColor(primary)
        recordsColumnScrollDown.setTextColor(cyan)
        recordsColumnScrollUp.postDelayed(recordsArrowColorResetRunnable, 220L)
    }

    private fun formatRecordsMetaLine(listing: EpgListing): String {
        val range = IptvTimeUtils.formatTimeRangeIsrael(listing.startUnix, listing.endUnix)
        val date = IptvTimeUtils.formatDateIsrael(listing.startUnix, "EEEE dd-MM-yyyy")
        val genre = listing.category?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
        val dur = IptvTimeUtils.formatDurationHms(listing.endUnix - listing.startUnix)
        return getString(R.string.playback_records_meta, range, date, genre, dur)
    }

    private fun updateRecordsInfoPlaybackUi() {
        if (!::recordsInfoOverlay.isInitialized || !recordsInfoOverlay.isVisible) return
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex) ?: return
        val listingId = listing.startUnix xor listing.endUnix
        val matchesPlayback = listingId == currentArchiveListingId
        val p = mediaPlayer
        if (matchesPlayback && p != null) {
            val len = mediaLengthMs(p)
            val cur = p.currentPosition.coerceAtLeast(0L)
            recordsInfoTimeCurrent.text = formatPlaybackTimeHms(cur)
            recordsInfoTimeTotal.text = formatPlaybackTimeHms(len)
            recordsInfoProgress.isVisible = len > 0L
            if (len > 0L) {
                recordsInfoProgress.progress = ((cur * 1000L) / len).toInt().coerceIn(0, 1000)
            } else {
                recordsInfoProgress.progress = 0
            }
        } else {
            val slotLenMs = (listing.endUnix - listing.startUnix).coerceAtLeast(0L) * 1000L
            recordsInfoTimeCurrent.text = formatPlaybackTimeHms(0L)
            recordsInfoTimeTotal.text = formatPlaybackTimeHms(slotLenMs.coerceAtLeast(0L))
            recordsInfoProgress.isVisible = slotLenMs > 0L
            recordsInfoProgress.progress = 0
        }
    }

    private fun recordsBrowsePrev() {
        if (recordsColumnListingIndex <= 0) return
        recordsColumnListingIndex--
        bindRecordsColumnPanelUi()
    }

    private fun recordsBrowseNext() {
        if (recordsColumnListingIndex >= recordsDayListings.lastIndex) return
        recordsColumnListingIndex++
        bindRecordsColumnPanelUi()
    }

    /**
     * Dismisses records overlay, shows horizontal chapter strip, pauses playback. Returns false if not seekable.
     */
    private fun openTrickStripForSeeking(): Boolean {
        if (::recordsColumnScrollUp.isInitialized) {
            recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        }
        recordsColumnUserVisible = false
        recordsInfoOverlay.isVisible = false
        val p = mediaPlayer ?: return false
        val len = mediaLengthMs(p)
        val seekable = p.isCurrentMediaItemSeekable && len > 1_000L && trickSlots.isNotEmpty()
        if (!seekable) return false
        trickStripUserVisible = true
        trickRv.isVisible = true
        if (p.isPlaying) p.pause()
        syncSelectedTrickToCurrentTime()
        centerSelectedTrickCard()
        updatePlaybackControlsUi()
        videoLayout.requestFocus()
        return true
    }

    private fun setupRecordsColumnPanelKeys() {
        val videoFocusId = R.id.vlc_video_layout
        recordsColumnThumb.nextFocusLeftId = videoFocusId

        recordsColumnThumb.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (recordsColumnListingIndex <= 0) {
                        true
                    } else {
                        flashRecordsScrollArrowUp()
                        recordsBrowsePrev()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (recordsColumnListingIndex >= recordsDayListings.lastIndex) {
                        true
                    } else {
                        flashRecordsScrollArrowDown()
                        recordsBrowseNext()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    if (!openTrickStripForSeeking()) {
                        closeRecordsColumnAndResume()
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK -> {
                    closeRecordsColumnAndResume()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    confirmRecordsSelectionAndClose()
                    true
                }
                else -> false
            }
        }
    }

    private fun confirmRecordsSelectionAndClose() {
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex) ?: run {
            closeRecordsColumnAndResume()
            return
        }
        val id = listing.startUnix xor listing.endUnix
        if (id != currentArchiveListingId) {
            applyRecordsStreamSwitch(listing)
        }
        closeRecordsColumnAndResume()
    }

    private fun applyRecordsStreamSwitch(listing: EpgListing) {
        val sid = recordsArchiveStreamId ?: return
        val player = mediaPlayer ?: return
        val url = IptvStreamUrls.timeshiftStreamUrl(sid, listing.startUnix, listing.endUnix)
        currentArchiveListingId = listing.startUnix xor listing.endUnix
        posterUrl = sequenceOf(listing.imageUrl, posterUrl).firstOrNull { !it.isNullOrBlank() }
        trickAdapter.setPosterUrl(posterUrl)
        trickStripBuiltForLengthMs = -1L
        trickStripUserVisible = false
        trickRv.isVisible = false
        runCatching { player.stop() }
        startPlayback(player, url)
        updatePlaybackControlsUi()
    }

    private fun closeRecordsColumnAndResume() {
        if (!recordsColumnUserVisible) return
        if (::recordsColumnScrollUp.isInitialized) {
            recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        }
        recordsColumnUserVisible = false
        recordsInfoOverlay.isVisible = false
        val p = mediaPlayer ?: return
        if (!p.isPlaying) p.play()
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun rebuildTrickStripIfNeeded() {
        val p = mediaPlayer ?: return
        if (IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl)) {
            trickStripBuiltForLengthMs = -1L
            trickStripUserVisible = false
            trickRv.isVisible = false
            trickSlots.clear()
            trickAdapter.notifyDataSetChanged()
            timeTotal.text = getString(R.string.playback_live)
            updatePlaybackControlsUi()
            return
        }
        val len = mediaLengthMs(p)
        val seekable = p.isCurrentMediaItemSeekable && len > 1_000L

        if (seekable) {
            if (len != trickStripBuiltForLengthMs) {
                trickStripBuiltForLengthMs = len
                trickSlots.clear()
                val step = TRICK_SLOT_STEP_MS
                val slotsCount = ((len + step - 1L) / step).toInt().coerceAtLeast(1)
                for (i in 0 until slotsCount) {
                    val start = (i * step).coerceAtMost((len - 1L).coerceAtLeast(0L))
                    val label = formatPlaybackTimeMs(start)
                    val thumb = thumbnailUrlForTrickSlot(start, step, len)
                    trickSlots.add(TrickSlot(start, label, thumb))
                }
                trickAdapter.notifyDataSetChanged()
            }
            trickRv.isVisible = trickStripUserVisible && trickSlots.isNotEmpty()
            if (trickRv.isVisible && trickSlots.isNotEmpty()) {
                selectedTrickIndex = selectedTrickIndex.coerceIn(0, trickSlots.lastIndex)
                centerSelectedTrickCard()
            }
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

    private fun currentArchiveAnchorListing(): EpgListing? {
        if (recordsArchiveStreamId.isNullOrEmpty() || recordsDayListings.isEmpty()) return null
        return recordsDayListings.find { (it.startUnix xor it.endUnix) == currentArchiveListingId }
    }

    /**
     * Thumbnail for a seek chapter: EPG image at that offset when archive day list is available,
     * else [posterUrl] (VOD / fallback).
     */
    private fun thumbnailUrlForTrickSlot(startOffsetMs: Long, stepMs: Long, mediaLenMs: Long): String? {
        val fallback = posterUrl?.trim()?.takeIf { it.isNotEmpty() }
        val anchor = currentArchiveAnchorListing() ?: return fallback
        val halfStep = (stepMs / 2L).coerceAtLeast(0L)
        val midOffset = (startOffsetMs + halfStep).coerceIn(0L, (mediaLenMs - 1L).coerceAtLeast(0L))
        val wallSec = anchor.startUnix + midOffset / 1000L
        val hit = recordsDayListings.find { wallSec >= it.startUnix && wallSec < it.endUnix }
        val fromListing = hit?.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: anchor.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        return fromListing ?: fallback
    }

    private fun updatePlaybackControlsUi() {
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        val cur = p.currentPosition.coerceAtLeast(0L)
        val stripShowing = trickStripUserVisible && trickRv.isVisible && trickSlots.isNotEmpty()
        val seekableVod = p.isCurrentMediaItemSeekable && len > 1_000L
        val showChrome = stripShowing && seekableVod

        controlsOverlay.isVisible = showChrome
        trickTimelineRow.isVisible = false
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

    private fun isVodPlaybackMode(): Boolean {
        if (!recordsArchiveStreamId.isNullOrEmpty()) return false
        return isVodOrSeriesUrl(currentPlaybackUrl)
    }

    private fun showVodInfoOverlay() {
        if (!isVodPlaybackMode()) return
        vodInfoTitle.text = playbackMovie.title.orEmpty()
        vodInfoDescription.text = playbackMovie.description.orEmpty()
        val thumb = sequenceOf(playbackMovie.cardImageUrl, playbackMovie.backgroundImageUrl)
            .firstOrNull { !it.isNullOrBlank() }
        if (!thumb.isNullOrBlank()) {
            Glide.with(vodInfoThumb).load(thumb).centerCrop().into(vodInfoThumb)
        } else {
            Glide.with(vodInfoThumb).clear(vodInfoThumb)
            vodInfoThumb.setImageDrawable(null)
        }
        refreshVodFavoriteButton()
        vodInfoOverlay.isVisible = true
        vodInfoBackCallback.isEnabled = true
        vodInfoWaitingForSecondDownFocus = true
        videoLayout.nextFocusDownId = R.id.playback_vod_favorite_btn
        vodInfoFavoriteBtn.nextFocusUpId = R.id.vlc_video_layout
        updateVodInfoOverlayUi()
        videoLayout.requestFocus()
    }

    private fun hideVodInfoOverlay() {
        if (!::vodInfoOverlay.isInitialized) return
        vodInfoOverlay.isVisible = false
        vodInfoBackCallback.isEnabled = false
        vodInfoWaitingForSecondDownFocus = false
        videoLayout.nextFocusDownId = View.NO_ID
        vodInfoFavoriteBtn.nextFocusUpId = View.NO_ID
    }

    private fun refreshVodFavoriteButton() {
        val sid = FavoriteVodStore.streamIdFromMovieUrl(playbackMovie.videoUrl) ?: return
        val fav = FavoriteVodStore.isFavorite(requireContext(), sid)
        vodInfoFavoriteBtn.setText(
            if (fav) R.string.playback_remove_from_favorites else R.string.playback_add_to_favorites,
        )
        val icon = ContextCompat.getDrawable(
            requireContext(),
            if (fav) R.drawable.ic_favorite_filled else R.drawable.ic_sidebar_favorite,
        )?.mutate()
        if (fav) {
            icon?.clearColorFilter()
        } else {
            icon?.setTint(ContextCompat.getColor(requireContext(), R.color.sidebar_text_primary))
        }
        vodInfoFavoriteBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    }

    private fun updateVodInfoOverlayUi() {
        if (!::vodInfoOverlay.isInitialized || !vodInfoOverlay.isVisible) return
        val p = mediaPlayer ?: return
        val len = mediaLengthMs(p)
        val cur = p.currentPosition.coerceAtLeast(0L)
        vodInfoTimeCurrent.text = formatPlaybackTimeHms(cur)
        vodInfoTimeTotal.text = formatPlaybackTimeHms(len)
        vodInfoProgress.isVisible = len > 0L
        if (len > 0L) {
            vodInfoProgress.progress = ((cur * 1000L) / len).toInt().coerceIn(0, 1000)
        } else {
            vodInfoProgress.progress = 0
        }
        vodInfoMeta.text = formatVodMetaLine(len)
    }

    private fun formatVodMetaLine(durationMs: Long): String {
        val genre = playbackMovie.studio?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
        val year = yearFromTitle(playbackMovie.title)
        val timeTotal = formatPlaybackTimeHms(durationMs)
        return getString(R.string.playback_vod_meta, genre, year, timeTotal)
    }

    private fun yearFromTitle(title: String?): String {
        if (title.isNullOrBlank()) return "—"
        val m = Regex("""\b(19|20)\d{2}\b""").find(title)
        return m?.value ?: "—"
    }

    private fun formatPlaybackTimeHms(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun mediaLengthMs(p: ExoPlayer): Long {
        val l = p.duration
        return if (l != C.TIME_UNSET && l > 0L) l else 0L
    }

    private fun startPlayback(player: ExoPlayer, url: String) {
        currentPlaybackUrl = url
        playbackMovie.videoUrl = url
        attemptedPlaybackUrls.add(url)
        applyPanelFriendlyHttpOptions(url)
        Log.d(TAG, "Media set on ExoPlayer; url=${sanitizeUrlForLog(url)}")
        runCatching {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            player.prepare()
            player.playWhenReady = true
        }.onFailure { err ->
            Log.e(TAG, "Failed to start playback for ${sanitizeUrlForLog(url)}", err)
            if (retryWithVodContainerFallbackIfNeeded()) return
            if (retryWithAlternateSchemeIfNeeded()) return
            view?.post {
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

    private fun applyPanelFriendlyHttpOptions(streamUrl: String) {
        val headers = panelFriendlyHttpHeaders(streamUrl)
        httpDataSourceFactory
            ?.setUserAgent("Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            ?.setDefaultRequestProperties(headers)
    }

    private fun retryWithVodContainerFallbackIfNeeded(): Boolean {
        if (!isVodOrSeriesUrl(currentPlaybackUrl)) return false
        val next = nextVodFallbackUrl(currentPlaybackUrl) ?: return false
        val player = mediaPlayer ?: return false
        Log.w(
            TAG,
            "Retrying playback with VOD container fallback: from=${sanitizeUrlForLog(currentPlaybackUrl)} to=${sanitizeUrlForLog(next)}",
        )
        startPlayback(player, next)
        return true
    }

    private fun retryWithAlternateSchemeIfNeeded(): Boolean {
        val alt = alternateScheme(currentPlaybackUrl)
            ?.takeIf { !attemptedPlaybackUrls.contains(it) }
            ?: return false
        val player = mediaPlayer ?: return false
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

    private fun isVodOrSeriesUrl(url: String): Boolean {
        val path = Uri.parse(url).path.orEmpty()
        return path.contains("/movie/", ignoreCase = true) || path.contains("/series/", ignoreCase = true)
    }

    private fun nextVodFallbackUrl(url: String): String? {
        val u = Uri.parse(url)
        val path = u.path ?: return null
        val file = path.substringAfterLast('/', "")
        val dot = file.lastIndexOf('.')
        if (dot <= 0 || dot >= file.lastIndex) return null
        val baseName = file.substring(0, dot)
        val currentExt = file.substring(dot + 1).lowercase(Locale.US)
        val fallbackOrder = listOf("mp4", "m3u8", "ts")
        for (ext in fallbackOrder) {
            if (ext == currentExt) continue
            val candidatePath = path.removeSuffix(file) + "$baseName.$ext"
            val candidate = u.buildUpon().path(candidatePath).build().toString()
            if (!attemptedPlaybackUrls.contains(candidate)) return candidate
        }
        return null
    }

    private fun sanitizeUrlForLog(url: String): String {
        val u = Uri.parse(url)
        return "${u.scheme}://${u.host}${u.path.orEmpty()}"
    }

    private companion object {
        const val TAG = "PlaybackExo"
        const val TRICK_SLOT_STEP_MS = 30_000L

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

        fun panelFriendlyHttpHeaders(streamUrl: String): Map<String, String> {
            val u = Uri.parse(streamUrl)
            val scheme = u.scheme?.lowercase() ?: return emptyMap()
            val host = u.host ?: return emptyMap()
            if (scheme != "http" && scheme != "https") return emptyMap()
            val origin = "$scheme://$host/"
            return mapOf(
                "Referer" to origin,
                "Origin" to origin,
            )
        }

        fun logPlaybackTarget(url: String, title: String?) {
            val u = Uri.parse(url)
            val last = u.lastPathSegment ?: "?"
            Log.i(TAG, "title=${title ?: "?"} host=${u.host} scheme=${u.scheme} lastSegment=$last (full URL omitted — contains credentials)")
        }

    }
}

private data class TrickSlot(
    val startMs: Long,
    val label: String,
    /** Poster / EPG still for this segment; null uses placeholder in UI. */
    val thumbnailUrl: String?,
)

/** Vertical channel cards for live playback (DPAD up/down in column). */
private class LivePlaybackChannelColumnAdapter(
    private val onStreamPicked: (LiveStream) -> Unit,
    private val onCloseColumn: () -> Unit,
    private val videoFocusId: Int,
) : RecyclerView.Adapter<LivePlaybackChannelColumnAdapter.VH>() {

    private val items = mutableListOf<LiveStream>()

    fun submit(list: List<LiveStream>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_live_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stream = items[position]
        holder.name.text = stream.name
        val iconUrl = stream.iconUrl
        if (iconUrl.isNullOrBlank()) {
            Glide.with(holder.icon).clear(holder.icon)
            holder.icon.setImageDrawable(null)
        } else {
            Glide.with(holder.icon).load(iconUrl).fitCenter().into(holder.icon)
        }
        holder.itemView.nextFocusRightId = videoFocusId
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onStreamPicked(stream)
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    when {
                        pos == 0 -> true
                        lm.findViewByPosition(pos - 1) != null -> false
                        else -> {
                            val prev = pos - 1
                            rv.scrollToPosition(prev)
                            rv.post {
                                val h = rv.findViewHolderForAdapterPosition(prev)
                                if (h != null) {
                                    h.itemView.requestFocus()
                                } else {
                                    rv.postDelayed(
                                        {
                                            rv.findViewHolderForAdapterPosition(prev)?.itemView?.requestFocus()
                                        },
                                        64L,
                                    )
                                }
                            }
                            true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (pos >= itemCount - 1) {
                        true
                    } else if (lm.findViewByPosition(pos + 1) != null) {
                        false
                    } else {
                        val next = pos + 1
                        rv.scrollToPosition(next)
                        rv.post {
                            val h = rv.findViewHolderForAdapterPosition(next)
                            if (h != null) {
                                h.itemView.requestFocus()
                            } else {
                                rv.postDelayed(
                                    {
                                        rv.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus()
                                    },
                                    64L,
                                )
                            }
                        }
                        true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    onCloseColumn()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    onStreamPicked(stream)
                    true
                }
                else -> false
            }
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.live_channel_icon)
        val name: TextView = itemView.findViewById(R.id.live_channel_name)
    }
}

private class TrickStripAdapter(
    initialPosterUrl: String?,
    private val slots: List<TrickSlot>,
) : RecyclerView.Adapter<TrickStripAdapter.VH>() {

    private var posterUrl: String? = initialPosterUrl
    private var selectedIndex: Int = 0

    fun setPosterUrl(url: String?) {
        posterUrl = url
        notifyDataSetChanged()
    }

    fun setSelectedIndex(index: Int) {
        if (slots.isEmpty()) {
            selectedIndex = 0
            return
        }
        val safe = index.coerceIn(0, slots.lastIndex)
        if (safe == selectedIndex) return
        val old = selectedIndex
        selectedIndex = safe
        if (old in slots.indices) notifyItemChanged(old)
        notifyItemChanged(selectedIndex)
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

        val url = slot.thumbnailUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: posterUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (url.isNullOrEmpty()) {
            Glide.with(holder.poster).clear(holder.poster)
            holder.poster.scaleType = ScaleType.CENTER_INSIDE
            holder.poster.setImageResource(R.drawable.ic_playback_timeslot_placeholder)
        } else {
            holder.poster.scaleType = ScaleType.CENTER_CROP
            val radius = holder.itemView.resources.getDimensionPixelSize(R.dimen.playback_trick_card_corner_radius)
            val opts = RequestOptions().transform(
                MultiTransformation(CenterCrop(), RoundedCorners(radius)),
            )
            Glide.with(holder.poster)
                .load(url)
                .placeholder(R.drawable.ic_playback_timeslot_placeholder)
                .error(R.drawable.ic_playback_timeslot_placeholder)
                .apply(opts)
                .into(holder.poster)
        }
        holder.itemView.isSelected = position == selectedIndex
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val poster: ImageView = itemView.findViewById(R.id.playback_trick_poster)
        val time: TextView = itemView.findViewById(R.id.playback_trick_time)
    }
}
