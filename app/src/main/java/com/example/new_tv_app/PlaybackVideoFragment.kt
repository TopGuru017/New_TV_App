package com.example.new_tv_app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.new_tv_app.playback.TimeshiftAwareDataSourceFactory
import androidx.media3.exoplayer.DefaultLoadControl
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
import androidx.lifecycle.lifecycleScope
import com.example.new_tv_app.iptv.EpgListing
import com.example.new_tv_app.iptv.FavoriteVodStore
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.XtreamLiveApi
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays VOD and live IPTV via ExoPlayer. No on-screen chrome while playing. Seekable VOD: DPAD left/right opens
 * the horizontal chapter strip; only then is the bottom overlay (times + progress) shown. Panel **live** URLs
 * ([IptvStreamUrls.isPanelLiveStreamUrl]): no chapter strip or seek — live is not movable.
 * Records catch-up (intent carries [PlaybackActivity.RECORDS_DAY_LISTINGS]): DPAD up/down on video toggles a bottom
 * overlay (VOD-style card + footer); DPAD up/down on the thumbnail browses listings (▲/▼ flash cyan). At the first
 * listing, up does nothing; at the last, down does nothing. OK on the thumbnail applies the selection (if changed)
 * and dismisses; DPAD left/back closes. Left/right chapter strip still applies when the stream is seekable.
 */
@UnstableApi
class PlaybackVideoFragment : Fragment() {

    private var mediaPlayer: ExoPlayer? = null
    private var timeshiftDataSourceFactory: TimeshiftAwareDataSourceFactory? = null
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
    /** Background job that loads EPG listings when entering basic timeshift mode (no intent extras). */
    private var recordsEpgLoadJob: Job? = null

    private val recordsArrowColorResetRunnable = Runnable {
        if (!isAdded || !recordsColumnUserVisible) return@Runnable
        resetRecordsArrowGlyphColorsAndDimming()
    }

    // ── Live info overlay ──────────────────────────────────────────────────────────
    private lateinit var liveInfoOverlay: View
    private lateinit var liveChannelName: TextView
    private lateinit var liveCurrentTime: TextView
    private lateinit var liveEpgRv: RecyclerView
    private lateinit var liveEpgAdapter: LiveEpgStripAdapter

    private val liveEpgListings = mutableListOf<EpgListing>()
    /** Currently displayed EPG listing index. –1 = loading / no data. */
    private var liveEpgIndex: Int = -1
    private var liveInfoVisible: Boolean = false
    private var liveEpgLoadJob: Job? = null
    private var liveStreamId: String? = null
    /** True only if the server has tv_archive (catch-up) enabled for this live channel. */
    private var liveTvArchive: Boolean = false

    // Channel list for DPAD up/down channel-switching while EPG overlay is open
    private var liveCategoryId: String? = null
    private val liveCategoryChannels = mutableListOf<LiveStream>()
    private var liveChannelIndex: Int = -1
    /** Index of the channel currently *previewed* in the overlay (may differ from [liveChannelIndex]).
     *  -1 means the overlay is showing the currently-playing channel. */
    private var livePreviewChannelIndex: Int = -1
    /** Display name shown in the overlay for the previewed (not yet playing) channel. */
    private var livePreviewStreamName: String? = null
    /** Icon URL shown in the overlay for the previewed channel. */
    private var livePreviewStreamIcon: String? = null
    /** Increments on each channel preview while loading EPG — stale responses are ignored. */
    private var liveEpgPreviewSeq: Int = 0
    private var liveCategoryLoadJob: Job? = null
    private lateinit var liveInfoBackCallback: OnBackPressedCallback

    private val liveArrowResetRunnable = Runnable {
        if (!isAdded || !liveInfoVisible) return@Runnable
        liveEpgAdapter.resetArrowColors(liveEpgRv)
    }
    // ──────────────────────────────────────────────────────────────────────────────

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

        // Bind live info overlay views
        liveInfoOverlay = view.findViewById(R.id.playback_live_info_overlay)
        liveChannelName = view.findViewById(R.id.playback_live_channel_name)
        liveCurrentTime = view.findViewById(R.id.playback_live_current_time)
        liveEpgRv = view.findViewById(R.id.playback_live_epg_rv)
        liveEpgAdapter = LiveEpgStripAdapter(nowSeconds = { IptvTimeUtils.nowIsraelSeconds() })
        liveEpgRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        liveEpgRv.setHasFixedSize(false)
        liveEpgRv.itemAnimator = null
        liveEpgRv.adapter = liveEpgAdapter
        liveInfoOverlay.isVisible = false
        liveStreamId = requireActivity().intent.getStringExtra(PlaybackActivity.LIVE_STREAM_ID)?.trim()
        liveTvArchive = requireActivity().intent.getBooleanExtra(PlaybackActivity.LIVE_TV_ARCHIVE, false)
        liveCategoryId = requireActivity().intent.getStringExtra(PlaybackActivity.LIVE_CATEGORY_ID)?.trim()
        liveInfoBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeLiveInfoOverlay() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, liveInfoBackCallback)

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
                    // Live stream: open EPG overlay, or preview adjacent channel while it is open
                    if (IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl)) {
                        if (liveInfoVisible) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                flashLiveArrow(LiveEpgStripAdapter.FLASH_UP)
                                livePreviewPrevChannel()
                            } else {
                                flashLiveArrow(LiveEpgStripAdapter.FLASH_DOWN)
                                livePreviewNextChannel()
                            }
                        } else {
                            openLiveInfoOverlay()
                        }
                        return@setOnKeyListener true
                    }
                    if (recordsDayListings.isNotEmpty() && !recordsArchiveStreamId.isNullOrEmpty() ||
                        IptvStreamUrls.isTimeshiftUrl(currentPlaybackUrl)
                    ) {
                        if (!recordsColumnUserVisible) {
                            openRecordsColumn()
                        } else {
                            // Overlay open; videoLayout may have regained focus – relay navigation
                            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                if (recordsDayListings.isNotEmpty() && recordsColumnListingIndex > 0) {
                                    flashRecordsScrollArrowUp()
                                    recordsBrowsePrev()
                                }
                            } else {
                                if (recordsDayListings.isNotEmpty() && recordsColumnListingIndex < recordsDayListings.lastIndex) {
                                    flashRecordsScrollArrowDown()
                                    recordsBrowseNext()
                                }
                            }
                            recordsColumnThumb.requestFocus()
                        }
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
                    // Live EPG strip open: left/right scroll the programme row
                    if (liveInfoVisible) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            flashLiveArrow(LiveEpgStripAdapter.FLASH_LEFT)
                            liveEpgBrowsePrev()
                        } else {
                            flashLiveArrow(LiveEpgStripAdapter.FLASH_RIGHT)
                            liveEpgBrowseNext()
                        }
                        return@setOnKeyListener true
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
                        if (!openTrickStripForSeeking(initialDelta = delta)) return@setOnKeyListener false
                    } else {
                        shiftSelectedTrick(delta)
                    }
                    val slot = trickSlots.getOrNull(selectedTrickIndex)
                    if (slot != null) seekTrickCardPaused(slot.startMs)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    if (liveInfoVisible) {
                        val previewIdx = livePreviewChannelIndex
                        if (previewIdx >= 0 && previewIdx != liveChannelIndex &&
                            liveCategoryChannels.isNotEmpty()
                        ) {
                            // User confirmed a different channel — switch now
                            livePreviewStreamName = null
                            livePreviewStreamIcon = null
                            liveChannelIndex = previewIdx
                            liveSwitchToChannel(liveCategoryChannels[previewIdx])
                            closeLiveInfoOverlay()
                        } else {
                            handleLiveCardSelect()
                        }
                        return@setOnKeyListener true
                    }
                    val p = mediaPlayer ?: return@setOnKeyListener false
                    if (recordsColumnUserVisible) {
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
        val tsFactory = TimeshiftAwareDataSourceFactory(
            requireContext(),
            httpFactory,
            viewLifecycleOwner.lifecycleScope,
        )
        timeshiftDataSourceFactory = tsFactory
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                120_000, // max 2 min — keeps enough segments pre-fetched for catch-up/timeshift
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
        val player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(DefaultMediaSourceFactory(tsFactory))
            .setLoadControl(loadControl)
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        mediaPlayer = player
        videoLayout.player = player

        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: code=${error.errorCodeName}", error)
                    // A 404 on a timeshift segment means the server's TTL window for this
                    // session has expired. Trigger an immediate URL refresh (same logic as the
                    // periodic proactive refresh) to obtain a new server session and resume
                    // playback from the saved position.
                    val isTimeshiftSegmentMissing =
                        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                            IptvStreamUrls.isTimeshiftUrl(currentPlaybackUrl)
                    if (isTimeshiftSegmentMissing) {
                        // Ping the manifest immediately to renew the server session, then
                        // recover the player after a short delay — no setMediaItem() call
                        // means no black screen.
                        Log.d(TAG, "Timeshift 404 → session keep-alive ping + soft recover")
                        timeshiftDataSourceFactory?.triggerImmediatePing()
                        val savedPos = mediaPlayer?.currentPosition?.coerceAtLeast(0) ?: 0L
                        view?.postDelayed({
                            if (!isAdded) return@postDelayed
                            mediaPlayer?.let { p ->
                                p.seekTo(savedPos)
                                p.prepare()
                                p.playWhenReady = true
                            }
                        }, 2_500L)
                        return
                    }
                    if (retryWithVodContainerFallbackIfNeeded()) return
                    if (retryWithAlternateSchemeIfNeeded()) return
                    view.post {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                playbackErrorMessage(),
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
                    if (playbackState == Player.STATE_ENDED) {
                        // Recordings are often served as live HLS (no #EXT-X-ENDLIST). When the
                        // server pushes new manifest segments after end-of-content, ExoPlayer can
                        // transition back to BUFFERING/READY and restart from position 0. Pausing
                        // on ENDED prevents that loop for timeshift and VOD content.
                        if (!IptvStreamUrls.isPanelLiveStreamUrl(currentPlaybackUrl)) {
                            mediaPlayer?.pause()
                        }
                    }
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
        // Restart background session keepalive if returning to a timeshift stream.
        // ExoPlayer won't re-fetch a VOD-style manifest on resume, so we kick the keepalive
        // manually here rather than waiting for TimeshiftAwareDataSource to notice.
        val url = currentPlaybackUrl
        if (IptvStreamUrls.isTimeshiftUrl(url)) {
            timeshiftDataSourceFactory?.startKeepAlive(url)
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop → pause()")
        view?.removeCallbacks(progressTicker)
        timeshiftDataSourceFactory?.stop()
        mediaPlayer?.pause()
        super.onStop()
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView → stop/release")
        view?.removeCallbacks(progressTicker)
        timeshiftDataSourceFactory?.stop()
        recordsEpgLoadJob?.cancel()
        if (::recordsColumnScrollUp.isInitialized) {
            recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        }
        liveEpgLoadJob?.cancel()
        liveCategoryLoadJob?.cancel()
        if (::liveEpgRv.isInitialized) {
            liveEpgRv.removeCallbacks(liveArrowResetRunnable)
        }
        mediaPlayer?.let { p ->
            p.stop()
            p.release()
        }
        mediaPlayer = null
        timeshiftDataSourceFactory = null
        videoLayout.player = null
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
        runCatching { p.seekTo(ms) }.onFailure { Log.w(TAG, "seek failed", it) }
        trickStripUserVisible = false
        trickRv.isVisible = false
        recordsColumnUserVisible = false
        recordsInfoOverlay.isVisible = false
        if (!p.isPlaying) p.play()
        videoLayout.requestFocus()
        updatePlaybackControlsUi()
    }

    /** Seeks to [ms] while keeping the trick strip open and the player paused. */
    private fun seekTrickCardPaused(ms: Long) {
        val p = mediaPlayer ?: return
        runCatching { p.seekTo(ms) }.onFailure { Log.w(TAG, "seek failed", it) }
        if (p.isPlaying) p.pause()
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
        if (!IptvStreamUrls.isTimeshiftUrl(currentPlaybackUrl) &&
            (recordsDayListings.isEmpty() || recordsArchiveStreamId.isNullOrEmpty())
        ) return
        trickStripUserVisible = false
        trickRv.isVisible = false
        recordsColumnUserVisible = true
        recordsInfoOverlay.isVisible = true
        syncRecordsColumnListingIndexWithPlayback()
        resetRecordsArrowGlyphColorsAndDimming()
        bindRecordsColumnPanelUi()
        loadRecordsEpgIfNeeded()
        mediaPlayer?.pause()
        recordsColumnThumb.requestFocus()
        updatePlaybackControlsUi()
    }

    private fun syncRecordsColumnListingIndexWithPlayback() {
        recordsColumnListingIndex = recordsDayListings.indexOfFirst { (it.startUnix xor it.endUnix) == currentArchiveListingId }
            .let { if (it >= 0) it else 0 }
    }

    /**
     * When the overlay is opened without day listings (e.g. via Last Watch → PlaybackActivity),
     * extract the stream ID from the timeshift URL, fetch the EPG for that day, and refresh the UI
     * so up/down navigation becomes available.
     */
    private fun loadRecordsEpgIfNeeded() {
        if (recordsDayListings.isNotEmpty()) return
        val sid = IptvStreamUrls.streamIdFromTimeshiftUrl(currentPlaybackUrl) ?: return
        recordsArchiveStreamId = sid
        recordsEpgLoadJob?.cancel()
        recordsEpgLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = XtreamLiveApi.fetchArchiveEpgTable(sid)
            result.onSuccess { allListings ->
                if (!isAdded || !recordsColumnUserVisible) return@onSuccess
                // Determine the recording's day from the timeshift URL start segment
                val recordingStartSec = IptvStreamUrls.startTimeSecondsFromTimeshiftUrl(currentPlaybackUrl)
                    ?: IptvTimeUtils.nowIsraelSeconds()
                val dayStart = IptvTimeUtils.startOfDayIsraelSeconds(recordingStartSec)
                val dayEnd = IptvTimeUtils.endOfDayIsraelSeconds(dayStart)
                val dayListings = allListings
                    .filter { it.startUnix >= dayStart && it.startUnix < dayEnd }
                    .sortedBy { it.startUnix }
                if (dayListings.isEmpty()) return@onSuccess
                recordsDayListings.clear()
                recordsDayListings.addAll(dayListings)
                // Try to pin currentArchiveListingId to the listing that covers the start time
                val anchor = allListings.find {
                    recordingStartSec >= it.startUnix && recordingStartSec < it.endUnix
                }
                if (anchor != null) {
                    currentArchiveListingId = anchor.startUnix xor anchor.endUnix
                }
                syncRecordsColumnListingIndexWithPlayback()
                if (recordsColumnUserVisible && isAdded) {
                    resetRecordsArrowGlyphColorsAndDimming()
                    bindRecordsColumnPanelUi()
                }
            }
        }
    }

    private fun bindRecordsColumnPanelUi() {
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex)

        val title: String
        val description: String
        val metaText: String
        val imgUrl: String?
        val thumbDesc: String

        if (listing != null) {
            val timeLabel = IptvTimeUtils.formatTimeRangeIsrael(listing.startUnix, listing.endUnix)
            title = listing.title
            description = listing.description.trim().ifBlank { getString(R.string.tv_guide_no_description) }
            metaText = formatRecordsMetaLine(listing)
            imgUrl = listing.imageUrl?.trim()?.takeIf { it.isNotEmpty() }
            thumbDesc = listing.title.ifBlank { listing.description.ifBlank { timeLabel } }
        } else {
            title = playbackMovie.title.orEmpty()
            description = playbackMovie.description.orEmpty()
            metaText = ""
            imgUrl = sequenceOf(playbackMovie.cardImageUrl, playbackMovie.backgroundImageUrl)
                .firstOrNull { !it.isNullOrBlank() }
            thumbDesc = title
        }

        recordsInfoTitle.text = title
        recordsInfoDescription.text = description
        recordsInfoMeta.text = metaText
        recordsColumnThumb.contentDescription = thumbDesc

        if (imgUrl.isNullOrEmpty()) {
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
                .load(imgUrl)
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
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex)
        val matchesPlayback = listing == null || (listing.startUnix xor listing.endUnix) == currentArchiveListingId
        recordsColumnThumb.setBackgroundResource(
            if (matchesPlayback) {
                R.drawable.bg_playback_records_thumb_border_cyan
            } else {
                R.drawable.bg_playback_records_thumb_border_muted
            },
        )
    }

    private fun applyRecordsScrollArrowDimming() {
        val noListings = recordsDayListings.isEmpty()
        val atTop = noListings || recordsColumnListingIndex <= 0
        val atBottom = noListings || recordsColumnListingIndex >= recordsDayListings.lastIndex
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
        val listing = recordsDayListings.getOrNull(recordsColumnListingIndex)
        val p = mediaPlayer
        if (listing == null) {
            // Basic mode (no day listings): show actual playback progress from player
            if (p != null) {
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
            }
            return
        }
        val listingId = listing.startUnix xor listing.endUnix
        val matchesPlayback = listingId == currentArchiveListingId
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
        if (recordsDayListings.isEmpty() || recordsColumnListingIndex <= 0) return
        recordsColumnListingIndex--
        bindRecordsColumnPanelUi()
    }

    private fun recordsBrowseNext() {
        if (recordsDayListings.isEmpty() || recordsColumnListingIndex >= recordsDayListings.lastIndex) return
        recordsColumnListingIndex++
        bindRecordsColumnPanelUi()
    }

    /**
     * Dismisses records overlay, shows horizontal chapter strip, and moves chapter selection by [initialDelta]
     * when requested (-1 left, +1 right). Returns false if stream is not seekable.
     */
    private fun openTrickStripForSeeking(initialDelta: Int = 0): Boolean {
        if (::recordsColumnScrollUp.isInitialized) {
            recordsColumnScrollUp.removeCallbacks(recordsArrowColorResetRunnable)
        }
        recordsColumnUserVisible = false
        recordsInfoOverlay.isVisible = false
        val p = mediaPlayer ?: return false
        val len = mediaLengthMs(p)
        val seekable = p.isCurrentMediaItemSeekable && len > 1_000L
        if (!seekable) return false
        if (trickSlots.isEmpty()) {
            rebuildTrickStripIfNeeded()
        }
        if (trickSlots.isEmpty()) return false
        trickStripUserVisible = true
        trickRv.isVisible = true
        if (p.isPlaying) p.pause()
        syncSelectedTrickToCurrentTime()
        if (initialDelta != 0) {
            shiftSelectedTrick(initialDelta)
        } else {
            centerSelectedTrickCard()
            updatePlaybackControlsUi()
        }
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
                    if (recordsDayListings.isEmpty() || recordsColumnListingIndex <= 0) {
                        true
                    } else {
                        flashRecordsScrollArrowUp()
                        recordsBrowsePrev()
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (recordsDayListings.isEmpty() || recordsColumnListingIndex >= recordsDayListings.lastIndex) {
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
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    if (openTrickStripForSeeking(initialDelta = delta)) {
                        val slot = trickSlots.getOrNull(selectedTrickIndex)
                        if (slot != null) seekTrickCardPaused(slot.startMs)
                    } else {
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
        val url = IptvStreamUrls.timeshiftStreamUrl(sid, listing.startUnix, listing.endUnix, listing.startRaw, listing.endRaw)
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
        recordsEpgLoadJob?.cancel()
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

    // ════════════════════════════════════════════════════════════════════════════
    // Live TV info overlay  (today's EPG strip for the current channel)
    // ════════════════════════════════════════════════════════════════════════════

    private fun openLiveInfoOverlay() {
        if (liveInfoVisible) return
        livePreviewChannelIndex = liveChannelIndex
        livePreviewStreamName = null
        livePreviewStreamIcon = null
        liveInfoVisible = true
        liveInfoOverlay.animate().cancel()
        liveInfoOverlay.alpha = 0f
        liveInfoOverlay.isVisible = true
        liveInfoOverlay.animate()
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        liveInfoBackCallback.isEnabled = true
        bindLiveInfoCard()
        videoLayout.requestFocus()   // keep focus on videoLayout; its key listener drives navigation
        loadLiveEpgIfNeeded()
        loadLiveCategoryIfNeeded()
    }

    private fun closeLiveInfoOverlay() {
        if (!liveInfoVisible) return
        liveEpgLoadJob?.cancel()
        liveEpgRv.removeCallbacks(liveArrowResetRunnable)
        liveInfoBackCallback.isEnabled = false
        liveEpgRv.animate().cancel()
        liveEpgRv.alpha = 1f
        liveInfoVisible = false
        liveInfoOverlay.animate().cancel()
        liveInfoOverlay.isVisible = false
        liveInfoOverlay.alpha = 1f
        livePreviewChannelIndex = liveChannelIndex
        livePreviewStreamName = null
        livePreviewStreamIcon = null
        videoLayout.requestFocus()
    }

    private fun loadLiveEpgIfNeeded() {
        val sid = liveStreamId ?: return
        if (liveEpgListings.isNotEmpty()) return
        liveEpgLoadJob?.cancel()
        liveEpgLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                XtreamLiveApi.fetchArchiveEpgTable(sid)
            }
            result.getOrNull()?.let { allListings ->
                val now = IptvTimeUtils.nowIsraelSeconds()
                val windowStart = now - 12 * 3600L  // past 12 hours
                val windowEnd   = now + 6  * 3600L  // next 6 hours
                val todayListings = allListings
                    .filter { it.endUnix > windowStart && it.startUnix < windowEnd }
                    .sortedBy { it.startUnix }
                if (todayListings.isNotEmpty()) {
                    liveEpgListings.clear()
                    liveEpgListings.addAll(todayListings)
                    liveEpgIndex = liveEpgListings.indexOfFirst {
                        it.startUnix <= now && now < it.endUnix
                    }.coerceAtLeast(0)
                    if (liveInfoVisible) bindLiveInfoCard()
                }
            }
        }
    }

    /** Loads all channels in [liveCategoryId] once; finds the current channel's index. */
    private fun loadLiveCategoryIfNeeded() {
        val catId = liveCategoryId ?: return
        if (liveCategoryChannels.isNotEmpty()) return
        liveCategoryLoadJob?.cancel()
        liveCategoryLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { XtreamLiveApi.fetchLiveStreams(catId) }
            result.onSuccess { channels ->
                if (!isAdded) return@onSuccess
                liveCategoryChannels.clear()
                liveCategoryChannels.addAll(channels)
                liveChannelIndex = channels.indexOfFirst { it.streamId == liveStreamId }
            }
        }
    }

    /** Move the overlay preview one channel up without switching playback. */
    private fun livePreviewPrevChannel() {
        val cur = livePreviewChannelIndex.coerceAtLeast(0)
        if (liveCategoryChannels.isEmpty() || cur <= 0) return
        livePreviewChannelIndex = cur - 1
        liveShowChannelPreview(liveCategoryChannels[livePreviewChannelIndex])
    }

    /** Move the overlay preview one channel down without switching playback. */
    private fun livePreviewNextChannel() {
        val cur = livePreviewChannelIndex.coerceAtLeast(0)
        if (liveCategoryChannels.isEmpty() || cur >= liveCategoryChannels.lastIndex) return
        livePreviewChannelIndex = cur + 1
        liveShowChannelPreview(liveCategoryChannels[livePreviewChannelIndex])
    }

    /**
     * Updates the EPG overlay to show [stream]'s info and EPG without switching the player.
     * The user must press OK to confirm and actually switch.
     */
    private fun liveShowChannelPreview(stream: LiveStream) {
        livePreviewStreamName = stream.name
        livePreviewStreamIcon = stream.iconUrl
        liveEpgLoadJob?.cancel()
        liveEpgRv.removeCallbacks(liveArrowResetRunnable)
        val seq = ++liveEpgPreviewSeq
        // Keep existing EPG cards visible while the new channel's schedule loads — avoids the
        // empty-strip flash. Dim the strip slightly until fresh data arrives.
        bindLiveInfoCard()
        liveEpgRv.animate().cancel()
        liveEpgRv.alpha = 0.42f
        liveEpgLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                XtreamLiveApi.fetchArchiveEpgTable(stream.streamId)
            }
            if (!isAdded || seq != liveEpgPreviewSeq) return@launch
            val allListings = result.getOrNull()
            if (allListings == null) {
                liveEpgRv.animate().alpha(1f).setDuration(180L).start()
                return@launch
            }
            val now = IptvTimeUtils.nowIsraelSeconds()
            val windowStart = now - 12 * 3600L
            val windowEnd = now + 6 * 3600L
            val todayListings = allListings
                .filter { it.endUnix > windowStart && it.startUnix < windowEnd }
                .sortedBy { it.startUnix }
            if (!isAdded || seq != liveEpgPreviewSeq) return@launch
            liveEpgListings.clear()
            liveEpgListings.addAll(todayListings)
            liveEpgIndex = if (todayListings.isNotEmpty()) {
                todayListings.indexOfFirst {
                    it.startUnix <= now && now < it.endUnix
                }.coerceAtLeast(0)
            } else {
                -1
            }
            if (liveInfoVisible) {
                bindLiveInfoCard()
                liveEpgRv.animate().cancel()
                liveEpgRv.alpha = 0.88f
                liveEpgRv.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun liveSwitchToChannel(stream: LiveStream) {
        liveEpgPreviewSeq++
        liveStreamId = stream.streamId
        liveTvArchive = stream.tvArchive

        // Update movie info for the new channel
        playbackMovie.title = stream.name
        playbackMovie.cardImageUrl = stream.iconUrl
        playbackMovie.backgroundImageUrl = stream.iconUrl

        // Switch player to new live stream
        val newUrl = IptvStreamUrls.liveStreamUrl(stream.streamId)
        val player = mediaPlayer ?: return
        trickStripUserVisible = false
        trickRv.isVisible = false
        runCatching { player.stop() }
        startPlayback(player, newUrl)

        // Reset EPG data and reload for the new channel
        liveEpgLoadJob?.cancel()
        liveEpgListings.clear()
        liveEpgIndex = -1
        liveEpgAdapter.submitList(emptyList())
        bindLiveInfoCard()
        loadLiveEpgIfNeeded()
    }

    private fun bindLiveInfoCard() {
        val now = IptvTimeUtils.nowIsraelSeconds()
        // While a preview channel is selected show its info; fall back to the playing channel
        liveChannelName.text = livePreviewStreamName ?: playbackMovie.title
        liveCurrentTime.text = IptvTimeUtils.formatTimeIsrael(now)
        liveEpgAdapter.channelIconUrl = livePreviewStreamIcon
            ?: sequenceOf(playbackMovie.cardImageUrl, playbackMovie.backgroundImageUrl)
                .firstOrNull { !it.isNullOrBlank() }
        if (liveEpgListings.isNotEmpty()) {
            liveEpgAdapter.submitList(liveEpgListings.toList(), liveEpgIndex)
            liveEpgRv.post { centerSelectedEpgCard() }
        } else {
            liveEpgAdapter.submitList(emptyList())
        }
    }

    /**
     * Snap the selected EPG card so that the previous card peeks by [R.dimen.playback_live_epg_left_peek]
     * on the left, and the next card naturally fills the remaining right space (more visible).
     * The asymmetry (left much smaller than right) creates the carousel depth effect.
     */
    private fun centerSelectedEpgCard() {
        if (liveEpgAdapter.size() == 0) return
        val lm = liveEpgRv.layoutManager as? LinearLayoutManager ?: return
        val leftPeek = resources.getDimensionPixelSize(R.dimen.playback_live_epg_left_peek)
        val cardMargin = (10 * resources.displayMetrics.density).toInt() // item_playback_live_epg_card marginEnd
        // Selected card left edge sits just after the left-peek portion + the gap between cards
        val offset = leftPeek + cardMargin
        lm.scrollToPositionWithOffset(liveEpgAdapter.getSelectedIndex(), offset)
        liveEpgRv.post {
            lm.scrollToPositionWithOffset(liveEpgAdapter.getSelectedIndex(), offset)
        }
    }

    private fun liveEpgBrowsePrev() {
        if (liveEpgListings.isEmpty() || liveEpgIndex <= 0) return
        liveEpgIndex--
        liveEpgAdapter.setSelectedIndex(liveEpgIndex)
        liveCurrentTime.text = IptvTimeUtils.formatTimeIsrael(IptvTimeUtils.nowIsraelSeconds())
        centerSelectedEpgCard()
    }

    private fun liveEpgBrowseNext() {
        if (liveEpgListings.isEmpty() || liveEpgIndex >= liveEpgListings.lastIndex) return
        liveEpgIndex++
        liveEpgAdapter.setSelectedIndex(liveEpgIndex)
        liveCurrentTime.text = IptvTimeUtils.formatTimeIsrael(IptvTimeUtils.nowIsraelSeconds())
        centerSelectedEpgCard()
    }

    private fun flashLiveArrow(direction: Int) {
        liveEpgRv.removeCallbacks(liveArrowResetRunnable)
        liveEpgAdapter.flashArrow(liveEpgRv, direction)
        liveEpgRv.postDelayed(liveArrowResetRunnable, 350L)
    }

    // Navigation is handled in videoLayout.setOnKeyListener so focus never leaves the player view.

    /** OK pressed on the live EPG card: play live if current, or switch to catch-up if past. */
    private fun handleLiveCardSelect() {
        val now = IptvTimeUtils.nowIsraelSeconds()
        val listing: EpgListing? = liveEpgListings.getOrNull(liveEpgIndex)
        if (listing == null || (listing.startUnix <= now && now < listing.endUnix)) {
            // Currently-airing: close overlay and keep watching live
            closeLiveInfoOverlay()
            return
        }
        if (listing.endUnix <= now) {
            // Past programme: open catch-up / records playback
            if (!liveTvArchive) {
                Toast.makeText(requireContext(), R.string.live_catchup_not_available, Toast.LENGTH_SHORT).show()
                return
            }
            val sid = liveStreamId ?: run { closeLiveInfoOverlay(); return }
            val timeshiftUrl = IptvStreamUrls.timeshiftStreamUrl(
                streamId = sid,
                startUnix = listing.startUnix,
                endUnix = listing.endUnix,
                startRaw = listing.startRaw,
                endRaw = listing.endRaw,
            )
            Log.d(TAG, "Opening catch-up: stream=$sid title=\"${listing.title}\" url=${sanitizeUrlForLog(timeshiftUrl)}")
            closeLiveInfoOverlay()
            val movie = Movie(
                id = listing.startUnix,
                title = listing.title,
                description = listing.description,
                backgroundImageUrl = listing.imageUrl ?: playbackMovie.backgroundImageUrl,
                cardImageUrl = listing.imageUrl ?: playbackMovie.cardImageUrl,
                videoUrl = timeshiftUrl,
                studio = null,
            )
            startActivity(
                android.content.Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, movie)
                    putExtra(PlaybackActivity.RECORDS_ARCHIVE_STREAM_ID, sid)
                }
            )
        }
        // Future programme: do nothing (already showing info)
    }

    // ════════════════════════════════════════════════════════════════════════════

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
        // Start (or restart) the background session keepalive for timeshift streams so the
        // server never expires the session mid-playback. For other stream types, stop any
        // previously active keepalive.
        if (IptvStreamUrls.isTimeshiftUrl(url)) {
            timeshiftDataSourceFactory?.startKeepAlive(url)
        } else {
            timeshiftDataSourceFactory?.stop()
        }
        Log.d(TAG, "Media set on ExoPlayer; url=${sanitizeUrlForLog(url)}")
        runCatching {
            player.setMediaItem(buildMediaItemForUrl(url))
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
                        playbackErrorMessage(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /**
     * Builds a [MediaItem] for the given URL. Timeshift URLs are served by many Xtream panels
     * as live HLS (no `#EXT-X-ENDLIST`) with a sliding segment window. Without special
     * configuration ExoPlayer would seek to the live edge (end of the recording) instead of
     * the beginning. Setting a very large [MediaItem.LiveConfiguration.targetOffsetMs] tells
     * ExoPlayer to position itself as far back from the live edge as possible — i.e. the
     * oldest available segment — so recordings start from the beginning. Locking
     * min/max playback speed to 1.0 prevents ExoPlayer from speeding up to "catch" the live
     * edge, which would cause 404s on segments that have already expired from the window.
     */
    private fun buildMediaItemForUrl(url: String): MediaItem {
        if (IptvStreamUrls.isTimeshiftUrl(url)) {
            return MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(TIMESHIFT_LIVE_TARGET_OFFSET_MS)
                        .setMaxOffsetMs(TIMESHIFT_LIVE_TARGET_OFFSET_MS)
                        .setMinPlaybackSpeed(1.0f)
                        .setMaxPlaybackSpeed(1.0f)
                        .build(),
                )
                .build()
        }
        return MediaItem.fromUri(Uri.parse(url))
    }

    private fun applyPanelFriendlyHttpOptions(streamUrl: String) {
        val headers = panelFriendlyHttpHeaders(streamUrl)
        timeshiftDataSourceFactory?.httpDataSourceFactory
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

    /** Returns a context-appropriate error message for a playback failure. */
    private fun playbackErrorMessage(): String =
        if (IptvStreamUrls.isTimeshiftUrl(currentPlaybackUrl))
            getString(R.string.live_catchup_record_unavailable)
        else
            getString(R.string.playback_error_message, getString(R.string.error_fragment_message))

    private companion object {
        const val TAG = "PlaybackExo"
        const val TRICK_SLOT_STEP_MS = 30_000L
        /** 24 h in ms — larger than any realistic recording, so ExoPlayer always starts at the
         *  oldest available segment of a live-type timeshift HLS stream rather than the live edge. */
        const val TIMESHIFT_LIVE_TARGET_OFFSET_MS = 24L * 60 * 60 * 1000

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
