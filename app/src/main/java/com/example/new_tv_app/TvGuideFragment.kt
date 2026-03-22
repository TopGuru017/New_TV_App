package com.example.new_tv_app

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.example.new_tv_app.iptv.IptvTimeUtils
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.XtreamLiveApi
import com.example.new_tv_app.reminder.Reminder
import com.example.new_tv_app.reminder.ReminderScheduler
import com.example.new_tv_app.reminder.ReminderStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV guide: left column = channels, right column = EPG for selected channel.
 * All times in Israel timezone. DPAD left/right switches columns.
 */
class TvGuideFragment : Fragment() {

    private var loadJob: Job? = null
    private var epgJob: Job? = null
    private var selectedStream: LiveStream? = null
    private var selectedChannelIndex = 0

    private lateinit var channelAdapter: TvGuideChannelAdapter
    private lateinit var programAdapter: TvGuideProgramAdapter
    private lateinit var reminderAdapter: TvGuideReminderAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_tv_guide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val loading = view.findViewById<ProgressBar>(R.id.tv_guide_loading)
        val error = view.findViewById<TextView>(R.id.tv_guide_error)
        val dateText = view.findViewById<TextView>(R.id.tv_guide_date)
        val channelsRv = view.findViewById<RecyclerView>(R.id.tv_guide_channels_list)
        val programsRv = view.findViewById<RecyclerView>(R.id.tv_guide_programs_list)
        val remindersRv = view.findViewById<RecyclerView>(R.id.tv_guide_reminders_list)
        val remindersEmpty = view.findViewById<TextView>(R.id.tv_guide_reminders_empty)

        dateText.text = IptvTimeUtils.formatDateIsrael(IptvTimeUtils.nowIsraelSeconds())

        channelAdapter = TvGuideChannelAdapter(
            onChannelSelected = { stream, index ->
                selectedStream = stream
                selectedChannelIndex = index
                loadEpg(stream, programsRv, loading, error)
            },
            sidebarRowId = R.id.row_tv_guide,
            programsListId = R.id.tv_guide_programs_list,
        )

        reminderAdapter = TvGuideReminderAdapter(
            onReminderClick = { reminder -> showDeleteReminderDialog(reminder, reminderAdapter, remindersRv, remindersEmpty) },
            programsListId = R.id.tv_guide_programs_list,
        )

        programAdapter = TvGuideProgramAdapter(
            channelIconUrl = { selectedStream?.iconUrl },
            channelName = { selectedStream?.name },
            channelsListId = R.id.tv_guide_channels_list,
            remindersColumnId = R.id.tv_guide_reminders_column,
            onDpadLeft = {
                val idx = selectedChannelIndex.coerceIn(0, channelAdapter.itemCount - 1)
                channelsRv.scrollToPosition(idx)
                channelsRv.post {
                    channelsRv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                }
            },
            onProgramSelected = { epg ->
                val stream = selectedStream ?: return@TvGuideProgramAdapter
                if (IptvTimeUtils.nowIsraelSeconds() >= epg.startUnix) return@TvGuideProgramAdapter
                val id = "${stream.streamId}_${epg.startUnix}"
                if (ReminderStore.contains(id)) return@TvGuideProgramAdapter
                val reminder = Reminder(
                    id = id,
                    streamId = stream.streamId,
                    channelName = stream.name,
                    channelIconUrl = stream.iconUrl,
                    programTitle = epg.title,
                    programDescription = epg.description,
                    startUnix = epg.startUnix,
                    endUnix = epg.endUnix,
                    epgImageUrl = epg.imageUrl,
                )
                ReminderStore.add(reminder)
                ReminderScheduler.schedule(requireContext(), reminder)
                reminderAdapter.submit(ReminderStore.getAll())
                refreshRemindersVisibility(reminderAdapter, remindersEmpty)
                android.widget.Toast.makeText(requireContext(), R.string.reminders_added, android.widget.Toast.LENGTH_SHORT).show()
            },
        )

        channelsRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        channelsRv.adapter = channelAdapter
        channelsRv.setHasFixedSize(true)
        channelsRv.itemAnimator = null

        programsRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        programsRv.adapter = programAdapter
        programsRv.setHasFixedSize(true)
        programsRv.itemAnimator = null

        remindersRv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        remindersRv.adapter = reminderAdapter
        remindersRv.setHasFixedSize(true)
        remindersRv.itemAnimator = null

        reminderAdapter.submit(ReminderStore.getAll())
        refreshRemindersVisibility(reminderAdapter, remindersEmpty)
        remindersEmpty.nextFocusLeftId = R.id.tv_guide_programs_list
        remindersEmpty.nextFocusRightId = View.NO_ID

        loading.isVisible = true
        error.isVisible = false
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = XtreamLiveApi.fetchAllLiveStreams()
            loading.isVisible = false
            result.fold(
                onSuccess = { streams ->
                    if (streams.isEmpty()) {
                        error.text = getString(R.string.tv_guide_load_error)
                        error.isVisible = true
                        return@fold
                    }
                    error.isVisible = false
                    channelAdapter.submit(streams)
                    selectedStream = streams.firstOrNull()
                    selectedChannelIndex = 0
                    selectedStream?.let { loadEpg(it, programsRv, loading, error) }
                    channelsRv.post { channelsRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                },
                onFailure = {
                    error.text = getString(R.string.tv_guide_load_error)
                    error.isVisible = true
                },
            )
        }
    }

    private fun refreshRemindersVisibility(adapter: TvGuideReminderAdapter, emptyView: TextView) {
        val hasItems = adapter.itemCount > 0
        emptyView.isVisible = !hasItems
    }

    private fun showDeleteReminderDialog(
        reminder: Reminder,
        adapter: TvGuideReminderAdapter,
        remindersRv: RecyclerView,
        remindersEmpty: TextView,
    ) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.reminders_delete_confirm)
            .setPositiveButton(R.string.reminders_delete_yes) { _, _ ->
                ReminderStore.remove(reminder.id)
                ReminderScheduler.cancel(requireContext(), reminder.id)
                adapter.submit(ReminderStore.getAll())
                refreshRemindersVisibility(adapter, remindersEmpty)
                if (adapter.itemCount > 0) {
                    remindersRv.post { remindersRv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                } else {
                    remindersEmpty.requestFocus()
                }
            }
            .setNegativeButton(R.string.reminders_delete_no, null)
            .show()
    }

    private fun loadEpg(
        stream: LiveStream,
        programsRv: RecyclerView,
        loading: ProgressBar,
        error: TextView,
    ) {
        epgJob?.cancel()
        programAdapter.submit(emptyList())
        epgJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = XtreamLiveApi.fetchFullEpg(stream.streamId, limit = 50)
            if (selectedStream != stream) return@launch
            result.fold(
                onSuccess = { listings ->
                    programAdapter.submit(listings)
                },
                onFailure = { programAdapter.submit(emptyList()) },
            )
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        epgJob?.cancel()
        super.onDestroyView()
    }
}

private class TvGuideChannelAdapter(
    private val onChannelSelected: (LiveStream, Int) -> Unit,
    private val sidebarRowId: Int,
    private val programsListId: Int,
) : RecyclerView.Adapter<TvGuideChannelAdapter.VH>() {

    private val items = mutableListOf<LiveStream>()

    fun submit(list: List<LiveStream>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_guide_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stream = items[position]
        holder.name.text = stream.name
        val url = stream.iconUrl
        if (url.isNullOrBlank()) {
            Glide.with(holder.icon).clear(holder.icon)
            holder.icon.setImageDrawable(null)
        } else {
            Glide.with(holder.icon).load(url).fitCenter().into(holder.icon)
        }
        holder.itemView.nextFocusLeftId = sidebarRowId
        holder.itemView.nextFocusRightId = programsListId
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onChannelSelected(stream, position)
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onChannelSelected(stream, position)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.tv_guide_channel_icon)
        val name: TextView = itemView.findViewById(R.id.tv_guide_channel_name)
    }
}

private class TvGuideProgramAdapter(
    private val channelIconUrl: () -> String?,
    private val channelName: () -> String?,
    private val channelsListId: Int,
    private val remindersColumnId: Int,
    private val onDpadLeft: (() -> Unit)?,
    private val onProgramSelected: ((EpgListing) -> Unit)?,
) : RecyclerView.Adapter<TvGuideProgramAdapter.VH>() {

    private val items = mutableListOf<EpgListing>()
    private val nowSeconds = IptvTimeUtils.nowIsraelSeconds()

    fun submit(list: List<EpgListing>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_guide_program, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val epg = items[position]
        val ctx = holder.itemView.context

        holder.time.text = IptvTimeUtils.formatTimeRangeIsrael(epg.startUnix, epg.endUnix)
        holder.title.text = epg.title

        val isNow = nowSeconds >= epg.startUnix && nowSeconds < epg.endUnix
        holder.nowBadge.isVisible = isNow
        holder.progress.isVisible = isNow
        holder.icon.setImageResource(if (isNow) R.drawable.ic_tv_guide_clock else R.drawable.ic_tv_guide_bell)

        if (isNow) {
            val total = (epg.endUnix - epg.startUnix).toFloat()
            val elapsed = (nowSeconds - epg.startUnix).toFloat()
            holder.progress.max = 100
            holder.progress.progress = ((elapsed / total) * 100f).toInt().coerceIn(0, 100)
        }

        val thumbUrl = epg.imageUrl ?: channelIconUrl()
        if (thumbUrl.isNullOrBlank()) {
            Glide.with(holder.thumb).clear(holder.thumb)
            holder.thumb.setImageDrawable(null)
        } else {
            Glide.with(holder.thumb).load(thumbUrl).centerCrop().into(holder.thumb)
        }

        holder.itemView.nextFocusLeftId = channelsListId
        holder.itemView.nextFocusRightId = remindersColumnId
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    onDpadLeft?.invoke()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    onProgramSelected?.invoke(epg)
                    true
                }
                else -> false
            }
        }

        holder.desc.isVisible = false
        holder.footer.isVisible = false
        holder.desc.text = epg.description.ifBlank { ctx.getString(R.string.tv_guide_no_description) }
        holder.genre.text = epg.category?.let { ctx.getString(R.string.tv_guide_genre_fmt, it) } ?: ""
        val durationMin = ((epg.endUnix - epg.startUnix) / 60).toInt()
        holder.duration.text = ctx.getString(R.string.tv_guide_duration_fmt, formatDuration(durationMin))

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.desc.isVisible = hasFocus
            holder.footer.isVisible = hasFocus
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onProgramSelected?.invoke(epg)
        }
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "%02d:%02d:00".format(h, m) else "00:%02d:00".format(m)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.tv_guide_program_thumb)
        val progress: ProgressBar = itemView.findViewById(R.id.tv_guide_progress)
        val icon: ImageView = itemView.findViewById(R.id.tv_guide_program_icon)
        val time: TextView = itemView.findViewById(R.id.tv_guide_program_time)
        val nowBadge: TextView = itemView.findViewById(R.id.tv_guide_now_badge)
        val title: TextView = itemView.findViewById(R.id.tv_guide_program_title)
        val desc: TextView = itemView.findViewById(R.id.tv_guide_program_desc)
        val footer: View = itemView.findViewById(R.id.tv_guide_program_footer)
        val genre: TextView = itemView.findViewById(R.id.tv_guide_program_genre)
        val duration: TextView = itemView.findViewById(R.id.tv_guide_program_duration)
    }
}

private class TvGuideReminderAdapter(
    private val onReminderClick: (com.example.new_tv_app.reminder.Reminder) -> Unit,
    private val programsListId: Int,
) : RecyclerView.Adapter<TvGuideReminderAdapter.VH>() {

    private val items = mutableListOf<com.example.new_tv_app.reminder.Reminder>()

    fun submit(list: List<com.example.new_tv_app.reminder.Reminder>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_reminder, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.time.text = IptvTimeUtils.formatTimeRangeIsrael(r.startUnix, r.endUnix)
        holder.channel.text = r.channelName
        holder.title.text = r.programTitle
        holder.desc.text = r.programDescription.ifBlank { holder.itemView.context.getString(R.string.tv_guide_no_description) }

        val url = r.channelIconUrl
        if (url.isNullOrBlank()) {
            Glide.with(holder.icon).clear(holder.icon)
            holder.icon.setImageDrawable(null)
        } else {
            Glide.with(holder.icon).load(url).fitCenter().into(holder.icon)
        }

        holder.itemView.nextFocusLeftId = programsListId
        holder.itemView.nextFocusRightId = View.NO_ID
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                onReminderClick(r)
                true
            } else false
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onReminderClick(r)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.reminder_channel_icon)
        val time: TextView = itemView.findViewById(R.id.reminder_time)
        val channel: TextView = itemView.findViewById(R.id.reminder_channel)
        val title: TextView = itemView.findViewById(R.id.reminder_title)
        val desc: TextView = itemView.findViewById(R.id.reminder_desc)
    }
}
