package com.example.new_tv_app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.EpgListing
import com.example.new_tv_app.iptv.IptvStreamUrls
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LastWatchStore
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.RecordsDaySlot
import com.example.new_tv_app.iptv.XtreamLiveApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Horizontal [RecyclerView]: next item may be off-screen; scroll then focus (D-pad hits focused child). */
private fun requestFocusRecyclerChildAfterScroll(rv: RecyclerView, adapterPosition: Int) {
    rv.post {
        val h = rv.findViewHolderForAdapterPosition(adapterPosition)
        if (h != null) {
            h.itemView.requestFocus()
        } else {
            rv.postDelayed(
                {
                    rv.findViewHolderForAdapterPosition(adapterPosition)?.itemView?.requestFocus()
                },
                64L,
            )
        }
    }
}

/** Calendar days that have at least one catch-up row (same overlap + ended-before-now rules as the programme list). */
private fun daySlotsHavingCatchUp(
    calendarDays: List<RecordsDaySlot>,
    listings: List<EpgListing>,
    nowUnix: Long,
): List<RecordsDaySlot> =
    calendarDays.filter { slot ->
        val dayStart = slot.startUnix
        val dayEnd = IptvTimeUtils.endOfDayIsraelSeconds(dayStart)
        listings.any { listing ->
            listing.startUnix < dayEnd &&
                listing.endUnix > dayStart &&
                IptvTimeUtils.eligibleForRecordsList(listing.endUnix, nowUnix)
        }
    }

/** Catch-up UI: channel bar, date strip (only days that have catch-up), vertical programme list. */
class RecordsDetailFragment : Fragment() {

    private var barStreams: List<LiveStream> = emptyList()
    private var selectedStreamId: String = ""
    private var allListings: List<EpgListing> = emptyList()
    /** Fixed 7-day window used to bound the archive EPG request. */
    private lateinit var calendarDaySlots: List<RecordsDaySlot>
    /** Days shown in the date strip (subset of [calendarDaySlots] that have catch-up). */
    private var daySlots: List<RecordsDaySlot> = emptyList()
    private var selectedDayIndex: Int = 0

    private var epgJob: Job? = null
    private var archiveLoadJob: Job? = null

    private lateinit var channelBarAdapter: RecordsChannelBarAdapter
    private lateinit var datesAdapter: RecordsDatesAdapter
    private lateinit var programsAdapter: RecordsProgramsAdapter

    companion object {
        private const val RECORDS_PLAY_URL_LOG_TAG = "RecordsPlayUrl"
        private const val ARG_STREAM_ID = "stream_id"
        private const val ARG_STREAM_NAME = "stream_name"
        private const val ARG_ICON = "icon"
        private const val ARG_CATEGORY_ID = "category_id"
        /** When set (from [RecordsFragment]), channel bar is filled without a second full-panel fetch. */
        private const val ARG_PREFETCHED_BAR = "prefetched_bar_streams"
        private const val PREFS_IPTV = "iptv_settings"
        private const val KEY_RECORDS_ORDER = "records_order"

        fun newInstance(
            stream: LiveStream,
            categoryFilterId: String?,
            prefetchedBarStreams: ArrayList<LiveStream>? = null,
        ) =
            RecordsDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STREAM_ID, stream.streamId)
                    putString(ARG_STREAM_NAME, stream.name)
                    putString(ARG_ICON, stream.iconUrl)
                    if (categoryFilterId != null) {
                        putString(ARG_CATEGORY_ID, categoryFilterId)
                    }
                    if (prefetchedBarStreams != null) {
                        putSerializable(ARG_PREFETCHED_BAR, prefetchedBarStreams)
                    }
                }
            }
    }

    private val categoryFilterId: String?
        get() = arguments?.getString(ARG_CATEGORY_ID)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_records_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val channelBar = view.findViewById<RecyclerView>(R.id.records_detail_channel_bar)
        val datesRv = view.findViewById<RecyclerView>(R.id.records_detail_dates)
        val programsRv = view.findViewById<RecyclerView>(R.id.records_detail_programs)
        val headerTitle = view.findViewById<TextView>(R.id.records_detail_header_title)
        val headerLogo = view.findViewById<ImageView>(R.id.records_detail_header_logo)
        val loading = view.findViewById<ProgressBar>(R.id.records_detail_loading)
        val programsEmpty = view.findViewById<TextView>(R.id.records_detail_programs_empty)
        val error = view.findViewById<TextView>(R.id.records_detail_error)

        var lastProgramFocusIndex = 0
        var currentDayListings: List<EpgListing> = emptyList()

        fun focusRecyclerPosition(rv: RecyclerView, position: Int, attemptsRemaining: Int = 28) {
            if (position < 0 || rv.adapter == null || position >= rv.adapter!!.itemCount) return
            rv.scrollToPosition(position)
            rv.post {
                if (!rv.isAttachedToWindow) return@post
                val h = rv.findViewHolderForAdapterPosition(position)
                if (h != null) {
                    h.itemView.requestFocus()
                } else if (attemptsRemaining > 0) {
                    rv.postDelayed(
                        { focusRecyclerPosition(rv, position, attemptsRemaining - 1) },
                        20L,
                    )
                }
            }
        }

        fun focusSelectedChannelInBar() {
            val adapter = channelBar.adapter ?: return
            val count = adapter.itemCount
            if (count <= 0) return
            val idx = barStreams.indexOfFirst { it.streamId == selectedStreamId }
                .coerceIn(0, count - 1)
            channelBar.stopScroll()
            (channelBar.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(idx, 0)
            channelBar.post {
                if (!channelBar.isAttachedToWindow) return@post
                val vh = channelBar.findViewHolderForAdapterPosition(idx)
                if (vh != null) {
                    vh.itemView.requestFocus()
                } else {
                    focusRecyclerPosition(channelBar, idx, attemptsRemaining = 40)
                }
            }
        }

        calendarDaySlots = IptvTimeUtils.lastSevenDaySlotsIsrael()
        daySlots = emptyList()
        selectedStreamId = requireArguments().getString(ARG_STREAM_ID).orEmpty()

        fun selectedStream(): LiveStream? = barStreams.find { it.streamId == selectedStreamId }

        fun bindHeader() {
            val s = selectedStream()
            val name = s?.name ?: requireArguments().getString(ARG_STREAM_NAME).orEmpty()
            headerTitle.text = getString(R.string.records_header_fmt, name)
            val url = s?.iconUrl ?: requireArguments().getString(ARG_ICON)
            if (url.isNullOrBlank()) {
                Glide.with(headerLogo).clear(headerLogo)
                headerLogo.setImageDrawable(null)
            } else {
                Glide.with(headerLogo).load(url).fitCenter().into(headerLogo)
            }
        }

        fun newestFirst(): Boolean {
            val p = requireContext().getSharedPreferences(PREFS_IPTV, Context.MODE_PRIVATE)
            return p.getString(KEY_RECORDS_ORDER, "old_new") == "new_old"
        }

        fun refreshProgramList() {
            if (selectedDayIndex !in daySlots.indices) {
                currentDayListings = emptyList()
                programsAdapter.submit(emptyList())
                programsEmpty.isVisible = true
                return
            }
            val slot = daySlots[selectedDayIndex]
            val dayStart = slot.startUnix
            val dayEnd = IptvTimeUtils.endOfDayIsraelSeconds(dayStart)
            val now = IptvTimeUtils.nowIsraelSeconds()
            var list = allListings.filter { it.startUnix < dayEnd && it.endUnix > dayStart }
                .filter { IptvTimeUtils.eligibleForRecordsList(it.endUnix, now) }
            list = if (newestFirst()) {
                list.sortedByDescending { it.startUnix }
            } else {
                list.sortedBy { it.startUnix }
            }
            currentDayListings = list
            programsAdapter.submit(list)
            programsEmpty.isVisible = list.isEmpty()
        }

        fun playArchiveListing(listing: EpgListing) {
            val sid = selectedStreamId
            if (sid.isEmpty()) return
            val url = IptvStreamUrls.timeshiftStreamUrl(sid, listing.startUnix, listing.endUnix, listing.startRaw, listing.endRaw)
            Log.d(RECORDS_PLAY_URL_LOG_TAG, "Play records URL: $url")
            val channelName =
                barStreams.find { it.streamId == sid }?.name
            val timeRange = IptvTimeUtils.formatTimeRangeIsrael(listing.startUnix, listing.endUnix)
            val tag = listing.category
            val imageUrl = listing.imageUrl ?: barStreams.find { it.streamId == sid }?.iconUrl
            val movie = Movie(
                id = listing.startUnix xor listing.endUnix,
                title = listing.title,
                description = timeRange,
                backgroundImageUrl = imageUrl,
                cardImageUrl = imageUrl,
                videoUrl = url,
                studio = listing.category,
            )
            LastWatchStore.addRecords(
                context = requireContext(),
                playedUnixSeconds = IptvTimeUtils.nowIsraelSeconds(),
                channelName = channelName,
                tag = tag,
                timeRange = timeRange,
                imageUrl = imageUrl,
                movie = movie,
            )
            startActivity(
                Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(DetailsActivity.MOVIE, movie)
                    putExtra(PlaybackActivity.RECORDS_ARCHIVE_STREAM_ID, sid)
                    putExtra(PlaybackActivity.RECORDS_DAY_LISTINGS, ArrayList(currentDayListings))
                },
            )
        }

        fun loadEpg(streamId: String) {
            epgJob?.cancel()
            loading.isVisible = true
            error.isVisible = false
            programsAdapter.submit(emptyList())
            programsEmpty.isVisible = false
            epgJob = viewLifecycleOwner.lifecycleScope.launch {
                val result = XtreamLiveApi.fetchArchiveEpgTable(streamId)
                loading.isVisible = false
                if (!isAdded) return@launch
                result.fold(
                    onSuccess = { listings ->
                        val oldestDay = calendarDaySlots.last().startUnix
                        val newestEnd =
                            IptvTimeUtils.endOfDayIsraelSeconds(calendarDaySlots.first().startUnix)
                        allListings = listings.filter {
                            it.endUnix > oldestDay && it.startUnix < newestEnd
                        }
                        val now = IptvTimeUtils.nowIsraelSeconds()
                        daySlots = daySlotsHavingCatchUp(calendarDaySlots, allListings, now)
                        selectedDayIndex = 0
                        datesAdapter.submit(daySlots)
                        error.isVisible = false
                        refreshProgramList()
                    },
                    onFailure = {
                        allListings = emptyList()
                        daySlots = emptyList()
                        selectedDayIndex = 0
                        datesAdapter.submit(emptyList())
                        error.text = getString(R.string.records_epg_error)
                        error.isVisible = true
                        refreshProgramList()
                    },
                )
            }
        }

        fun onStreamPicked(stream: LiveStream) {
            val oldId = selectedStreamId
            val same = oldId == stream.streamId
            selectedStreamId = stream.streamId
            if (!same) {
                val oi = barStreams.indexOfFirst { it.streamId == oldId }
                val ni = barStreams.indexOfFirst { it.streamId == stream.streamId }
                if (oi >= 0) {
                    channelBarAdapter.notifyItemChanged(oi, RecordsChannelBarAdapter.PAYLOAD_SELECTION)
                }
                if (ni >= 0) {
                    channelBarAdapter.notifyItemChanged(ni, RecordsChannelBarAdapter.PAYLOAD_SELECTION)
                }
            }
            bindHeader()
            if (!same) {
                loadEpg(stream.streamId)
            } else if (allListings.isEmpty() && epgJob?.isActive != true) {
                loadEpg(stream.streamId)
            }
        }

        programsAdapter = RecordsProgramsAdapter(
            onProgramRowFocused = { pos ->
                lastProgramFocusIndex = pos
            },
            onDpadLeftFromProgram = {
                if (daySlots.isNotEmpty()) {
                    focusRecyclerPosition(
                        datesRv,
                        selectedDayIndex.coerceIn(0, daySlots.lastIndex),
                    )
                }
                true
            },
            onDpadUpFromFirstProgram = {
                focusSelectedChannelInBar()
                true
            },
            onProgramPlay = { listing -> playArchiveListing(listing) },
        )

        datesAdapter = RecordsDatesAdapter(
            selectedIndexProvider = { selectedDayIndex },
            onDayPicked = { idx ->
                if (selectedDayIndex == idx) return@RecordsDatesAdapter
                val oldIdx = selectedDayIndex
                selectedDayIndex = idx
                lastProgramFocusIndex = 0
                if (oldIdx in daySlots.indices) datesAdapter.notifyItemChanged(oldIdx)
                if (idx in daySlots.indices) datesAdapter.notifyItemChanged(idx)
                refreshProgramList()
            },
            onDpadRightFromDate = {
                val n = programsAdapter.itemCount
                if (n <= 0) return@RecordsDatesAdapter false
                val pos = lastProgramFocusIndex.coerceIn(0, n - 1)
                focusRecyclerPosition(programsRv, pos)
                true
            },
            onDpadUpFromFirstDate = {
                focusSelectedChannelInBar()
                true
            },
            sidebarFocusLeftId = R.id.row_records,
        )
        datesAdapter.submit(daySlots)

        fun focusFirstProgramOrDates() {
            val n = programsAdapter.itemCount
            if (n > 0) {
                lastProgramFocusIndex = 0
                programsRv.stopScroll()
                (programsRv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                programsRv.post {
                    if (!programsRv.isAttachedToWindow) return@post
                    val vh = programsRv.findViewHolderForAdapterPosition(0)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        focusRecyclerPosition(programsRv, 0, attemptsRemaining = 40)
                    }
                }
            } else if (daySlots.isNotEmpty()) {
                focusRecyclerPosition(
                    datesRv,
                    selectedDayIndex.coerceIn(0, daySlots.lastIndex),
                )
            }
        }

        var suppressFocusSwitch = true

        channelBarAdapter = RecordsChannelBarAdapter(
            nextFocusLeftOnFirst = R.id.row_records,
            selectedStreamIdProvider = { selectedStreamId },
            onStreamFocused = { stream -> if (!suppressFocusSwitch) onStreamPicked(stream) },
            /** DPAD left/right / click: always sync EPG, even while [suppressFocusSwitch] is true. */
            onExplicitChannelPick = { stream -> onStreamPicked(stream) },
            onDpadDownFromChannel = {
                focusFirstProgramOrDates()
                true
            },
        )

        channelBar.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        channelBar.adapter = channelBarAdapter
        channelBar.itemAnimator = null

        datesRv.layoutManager = LinearLayoutManager(requireContext())
        datesRv.adapter = datesAdapter
        datesRv.itemAnimator = null

        programsRv.layoutManager = LinearLayoutManager(requireContext())
        programsRv.adapter = programsAdapter
        programsRv.itemAnimator = null

        bindHeader()

        @Suppress("DEPRECATION")
        val prefetchedBar = arguments?.getSerializable(ARG_PREFETCHED_BAR) as? ArrayList<LiveStream>

        archiveLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val filterId = categoryFilterId
            val arch = when {
                !prefetchedBar.isNullOrEmpty() -> prefetchedBar
                else -> {
                    loading.isVisible = true
                    val result = XtreamLiveApi.fetchTvArchiveStreamsForCategory(filterId)
                    loading.isVisible = false
                    if (!isAdded) return@launch
                    result.getOrNull().orEmpty()
                }
            }
            if (!isAdded) return@launch
            barStreams = arch
            channelBarAdapter.submit(barStreams)
            val initial = barStreams.find { it.streamId == selectedStreamId }
                ?: barStreams.firstOrNull()
            if (initial != null) {
                selectedStreamId = initial.streamId
                bindHeader()
                loadEpg(initial.streamId)
                channelBar.post {
                    focusSelectedChannelInBar()
                    channelBar.post { suppressFocusSwitch = false }
                }
            } else {
                error.text = getString(R.string.records_empty_channels)
                error.isVisible = true
                loading.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        epgJob?.cancel()
        archiveLoadJob?.cancel()
        super.onDestroyView()
    }
}

private class RecordsChannelBarAdapter(
    private val nextFocusLeftOnFirst: Int,
    private val selectedStreamIdProvider: () -> String,
    private val onStreamFocused: (LiveStream) -> Unit,
    private val onExplicitChannelPick: (LiveStream) -> Unit,
    private val onDpadDownFromChannel: () -> Boolean,
) : RecyclerView.Adapter<RecordsChannelBarAdapter.VH>() {

    companion object {
        const val PAYLOAD_SELECTION = "records_channel_selection"
    }

    private val items = mutableListOf<LiveStream>()

    fun submit(list: List<LiveStream>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_records_channel_bar, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindFull(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            bindFull(holder, position)
        } else {
            bindSelectionAndScale(holder, position)
        }
    }

    private fun bindSelectionAndScale(holder: VH, position: Int) {
        if (position !in items.indices) return
        val stream = items[position]
        val selected = stream.streamId == selectedStreamIdProvider()
        val focused = holder.itemView.isFocused
        holder.itemView.isSelected = selected
        holder.itemView.scaleX = if (selected || focused) 1.06f else 1f
        holder.itemView.scaleY = if (selected || focused) 1.06f else 1f
    }

    private fun bindFull(holder: VH, position: Int) {
        val stream = items[position]
        holder.name.text = stream.name
        holder.itemView.post {
            holder.itemView.pivotX = holder.itemView.width / 2f
            holder.itemView.pivotY = 0f
        }
        holder.itemView.nextFocusDownId = View.NO_ID
        holder.itemView.nextFocusLeftId =
            if (position == 0) nextFocusLeftOnFirst else View.NO_ID
        bindSelectionAndScale(holder, position)
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDownFromChannel()
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    val next = pos + 1
                    onExplicitChannelPick(items[next])
                    if (lm.findViewByPosition(next) != null) return@setOnKeyListener false
                    rv.scrollToPosition(next)
                    requestFocusRecyclerChildAfterScroll(rv, next)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    val prev = pos - 1
                    onExplicitChannelPick(items[prev])
                    if (lm.findViewByPosition(prev) != null) return@setOnKeyListener false
                    rv.scrollToPosition(prev)
                    requestFocusRecyclerChildAfterScroll(rv, prev)
                    true
                }
                else -> false
            }
        }
        val url = stream.iconUrl
        if (url.isNullOrBlank()) {
            Glide.with(holder.icon).clear(holder.icon)
            holder.icon.setImageDrawable(null)
        } else {
            Glide.with(holder.icon).load(url).fitCenter().into(holder.icon)
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                onStreamFocused(stream)
            }
            val selNow = stream.streamId == selectedStreamIdProvider()
            v.isSelected = selNow
            v.scaleX = if (hasFocus || selNow) 1.06f else 1f
            v.scaleY = if (hasFocus || selNow) 1.06f else 1f
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onExplicitChannelPick(stream)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.records_bar_icon)
        val name: TextView = itemView.findViewById(R.id.records_bar_name)
    }
}

private class RecordsDatesAdapter(
    private val selectedIndexProvider: () -> Int,
    private val onDayPicked: (Int) -> Unit,
    private val onDpadRightFromDate: () -> Boolean,
    private val onDpadUpFromFirstDate: () -> Boolean,
    private val sidebarFocusLeftId: Int,
) : RecyclerView.Adapter<RecordsDatesAdapter.VH>() {

    private val items = mutableListOf<RecordsDaySlot>()

    fun submit(list: List<RecordsDaySlot>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_records_date, parent, false) as FrameLayout
        return VH(root)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val day = items[position]
        holder.text.text = day.label
        val selected = position == selectedIndexProvider()
        holder.itemView.isSelected = selected
        holder.itemView.nextFocusRightId = View.NO_ID
        holder.itemView.nextFocusUpId = View.NO_ID
        holder.itemView.nextFocusLeftId =
            if (position == 0) sidebarFocusLeftId else View.NO_ID
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> onDpadRightFromDate()
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (pos == 0) onDpadUpFromFirstDate() else false
                else -> false
            }
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDayPicked(pos)
                }
            }
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onDayPicked(pos)
            }
        }
    }

    class VH(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val text: TextView = root.findViewById(R.id.records_date_label)
    }
}

private class RecordsProgramsAdapter(
    private val onProgramRowFocused: (Int) -> Unit,
    private val onDpadLeftFromProgram: () -> Boolean,
    private val onDpadUpFromFirstProgram: () -> Boolean,
    private val onProgramPlay: (EpgListing) -> Unit,
) : RecyclerView.Adapter<RecordsProgramsAdapter.VH>() {

    private val items = mutableListOf<EpgListing>()

    fun submit(list: List<EpgListing>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_records_program, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val listing = items[position]
        holder.time.text = IptvTimeUtils.formatTimeRangeIsrael(listing.startUnix, listing.endUnix)
        holder.title.text = listing.title
        holder.desc.text = listing.description.trim().ifBlank {
            holder.itemView.context.getString(R.string.tv_guide_no_description)
        }
        val genreLine = listing.category?.trim().orEmpty()
        holder.genre.text = if (genreLine.isNotEmpty()) genreLine else "\u2014"
        val dur = listing.endUnix - listing.startUnix
        holder.duration.text = IptvTimeUtils.formatDurationHms(dur)
        val img = listing.imageUrl
        if (img.isNullOrBlank()) {
            Glide.with(holder.thumb).clear(holder.thumb)
            holder.thumb.setImageDrawable(null)
        } else {
            Glide.with(holder.thumb).load(img).centerCrop().into(holder.thumb)
        }
        holder.itemView.nextFocusLeftId = View.NO_ID
        holder.itemView.nextFocusUpId = View.NO_ID
        holder.itemView.nextFocusDownId = View.NO_ID
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.playOverlay.isVisible = hasFocus
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onProgramRowFocused(pos)
                }
            }
        }
        holder.playOverlay.isVisible = holder.itemView.isFocused
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (pos >= itemCount - 1) {
                        true
                    } else {
                        val next = pos + 1
                        if (lm.findViewByPosition(next) != null) {
                            val nh = rv.findViewHolderForAdapterPosition(next)
                            if (nh != null) {
                                nh.itemView.requestFocus()
                            } else {
                                requestFocusRecyclerChildAfterScroll(rv, next)
                            }
                        } else {
                            rv.scrollToPosition(next)
                            requestFocusRecyclerChildAfterScroll(rv, next)
                        }
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> onDpadLeftFromProgram()
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (pos == 0) onDpadUpFromFirstProgram() else false
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    onProgramPlay(listing)
                    true
                }
                else -> false
            }
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onProgramPlay(listing)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.records_prog_thumb)
        val playOverlay: ImageView = itemView.findViewById(R.id.records_prog_play_overlay)
        val time: TextView = itemView.findViewById(R.id.records_prog_time)
        val title: TextView = itemView.findViewById(R.id.records_prog_title)
        val desc: TextView = itemView.findViewById(R.id.records_prog_desc)
        val genre: TextView = itemView.findViewById(R.id.records_prog_genre)
        val duration: TextView = itemView.findViewById(R.id.records_prog_duration)
    }
}
