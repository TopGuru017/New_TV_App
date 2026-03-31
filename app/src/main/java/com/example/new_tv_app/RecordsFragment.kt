package com.example.new_tv_app

import android.graphics.Rect
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.new_tv_app.iptv.LiveStream
import com.example.new_tv_app.iptv.XtreamLiveApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * TV archive channel grid by category (Xtream `tv_archive`). Opens [RecordsDetailFragment] on select.
 */
class RecordsFragment : Fragment() {

    private var selectedCategoryId: String? = null
    private var archiveStreams: List<LiveStream> = emptyList()
    private var loadJob: Job? = null

    private lateinit var categoryAdapter: RecordsCategoryStripAdapter
    private lateinit var channelAdapter: RecordsChannelGridAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_records, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val loading = view.findViewById<ProgressBar>(R.id.records_loading)
        val error = view.findViewById<TextView>(R.id.records_error)
        val categoriesRv = view.findViewById<RecyclerView>(R.id.records_categories_list)
        val channelsRv = view.findViewById<RecyclerView>(R.id.records_channels_grid)
        val channelsEmpty = view.findViewById<TextView>(R.id.records_channels_empty)
        val sidebar = requireActivity().findViewById<com.example.new_tv_app.ui.sidebar.IptvSidebarView>(R.id.iptv_sidebar)
        val sidebarFocusAnchorId = R.id.row_records
        val gridSpan = 6

        var pendingFocusFirstAfterCategoryDown = false
        var pendingInitialChannelFocus = true

        channelAdapter = RecordsChannelGridAdapter(
            spanCount = gridSpan,
            sidebarFocusAnchorId = sidebarFocusAnchorId,
            categoriesRecyclerView = categoriesRv,
            selectedCategoryIndex = { categoryAdapter.indexOfCategoryId(selectedCategoryId) },
            onChannelOpen = { stream ->
                sidebar.lockExpand()
                sidebar.setExpanded(false)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.main_content, RecordsDetailFragment.newInstance(stream, selectedCategoryId))
                    .addToBackStack(null)
                    .commit()
            },
        )

        fun requestFocusFirstChannel() {
            if (channelAdapter.itemCount <= 0) return
            channelsRv.scrollToPosition(0)
            fun tryFocus(attempt: Int) {
                channelsRv.post {
                    val h = channelsRv.findViewHolderForAdapterPosition(0)
                    if (h != null) {
                        h.itemView.requestFocus()
                    } else if (attempt < 16) {
                        channelsRv.postDelayed({ tryFocus(attempt + 1) }, 24L)
                    }
                }
            }
            tryFocus(0)
        }

        lateinit var applyFilterAndShow: () -> Unit

        fun selectCategoryChip(categoryId: String?) {
            val prev = selectedCategoryId
            selectedCategoryId = categoryId
            if (prev != categoryId) {
                val pi = categoryAdapter.indexOfCategoryId(prev)
                if (pi >= 0) categoryAdapter.notifyItemChanged(pi)
                val ni = categoryAdapter.indexOfCategoryId(categoryId)
                if (ni >= 0) categoryAdapter.notifyItemChanged(ni)
            }
            applyFilterAndShow()
        }

        applyFilterAndShow = {
            val catId = selectedCategoryId
            val filtered = if (catId == null) {
                archiveStreams
            } else {
                archiveStreams.filter { it.categoryId == catId }
            }
            channelAdapter.submit(filtered)
            channelsEmpty.isVisible = filtered.isEmpty()
            when {
                pendingFocusFirstAfterCategoryDown && filtered.isNotEmpty() -> {
                    pendingFocusFirstAfterCategoryDown = false
                    requestFocusFirstChannel()
                }
                pendingInitialChannelFocus && filtered.isNotEmpty() -> {
                    pendingInitialChannelFocus = false
                    requestFocusFirstChannel()
                }
            }
        }

        categoryAdapter = RecordsCategoryStripAdapter(
            selectedIdProvider = { selectedCategoryId },
            onItemFocused = { id -> selectCategoryChip(id) },
            onCategoryDpadDown = { id ->
                pendingFocusFirstAfterCategoryDown = true
                selectCategoryChip(id)
            },
        )

        categoriesRv.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        categoriesRv.adapter = categoryAdapter
        categoriesRv.setHasFixedSize(true)
        categoriesRv.itemAnimator = null

        val spacing = resources.getDimensionPixelSize(R.dimen.live_grid_spacing)
        channelsRv.layoutManager = GridLayoutManager(requireContext(), gridSpan)
        channelsRv.adapter = channelAdapter
        channelsRv.addItemDecoration(RecordsGridSpacingItemDecoration(gridSpan, spacing))
        channelsRv.setHasFixedSize(true)
        channelsRv.itemAnimator = null

        val mainContent = requireActivity().findViewById<View>(R.id.main_content)
        val savedMainNextFocusLeft = mainContent.nextFocusLeftId
        mainContent.nextFocusLeftId = View.NO_ID
        viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    mainContent.nextFocusLeftId = savedMainNextFocusLeft
                }
            },
        )

        loading.isVisible = true
        error.isVisible = false

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val catsDef = async { XtreamLiveApi.fetchLiveCategories() }
            val archDef = async { XtreamLiveApi.fetchTvArchiveStreams() }
            val catsResult = catsDef.await()
            val archResult = archDef.await()
            loading.isVisible = false
            if (!isAdded) return@launch

            val archive = archResult.getOrNull().orEmpty()
            val allCats = catsResult.getOrNull().orEmpty()

            if (archive.isEmpty()) {
                if (archResult.isFailure) {
                    error.text = getString(R.string.records_load_error)
                    error.isVisible = true
                } else {
                    error.text = getString(R.string.records_empty_channels)
                    error.isVisible = true
                }
                categoryAdapter.submit(emptyList())
                archiveStreams = emptyList()
                channelAdapter.submit(emptyList())
                channelsEmpty.isVisible = true
                return@launch
            }

            archiveStreams = archive
            val usedCatIds = archive.mapNotNull { it.categoryId }.toSet()
            val strip = ArrayList<RecordsCategoryChip>()
            strip.add(RecordsCategoryChip(null, getString(R.string.records_category_all)))
            for (c in allCats) {
                if (c.id in usedCatIds) {
                    strip.add(RecordsCategoryChip(c.id, c.name))
                }
            }
            categoryAdapter.submit(strip)
            pendingInitialChannelFocus = true
            selectedCategoryId = null
            applyFilterAndShow()
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
    }
}

private data class RecordsCategoryChip(
    val categoryId: String?,
    val label: String,
)

private fun requestFocusCategoryAfterScroll(rv: RecyclerView, adapterPosition: Int) {
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

private class RecordsGridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacingPx: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        val col = pos % spanCount
        outRect.left = spacingPx - col * spacingPx / spanCount
        outRect.right = (col + 1) * spacingPx / spanCount
        outRect.top = spacingPx / 2
        outRect.bottom = spacingPx / 2
    }
}

private class RecordsCategoryStripAdapter(
    private val selectedIdProvider: () -> String?,
    private val onItemFocused: (String?) -> Unit,
    private val onCategoryDpadDown: (String?) -> Unit,
) : RecyclerView.Adapter<RecordsCategoryStripAdapter.VH>() {

    private val items = mutableListOf<RecordsCategoryChip>()

    fun submit(list: List<RecordsCategoryChip>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun indexOfCategoryId(categoryId: String?): Int {
        val i = items.indexOfFirst { it.categoryId == categoryId }
        return if (i >= 0) i else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_category, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = items[position]
        holder.text.text = chip.label.uppercase(Locale.getDefault())
        holder.text.isSelected = chip.categoryId == selectedIdProvider()
        holder.text.nextFocusLeftId = View.NO_ID
        holder.text.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
            val rv = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener false
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onCategoryDpadDown(items[pos].categoryId)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos >= itemCount - 1) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos + 1) != null) {
                        return@setOnKeyListener false
                    }
                    val next = pos + 1
                    rv.scrollToPosition(next)
                    requestFocusCategoryAfterScroll(rv, next)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (pos <= 0) return@setOnKeyListener false
                    if (lm.findViewByPosition(pos - 1) != null) {
                        return@setOnKeyListener false
                    }
                    val prev = pos - 1
                    rv.scrollToPosition(prev)
                    requestFocusCategoryAfterScroll(rv, prev)
                    true
                }
                else -> false
            }
        }
    }

    inner class VH(val text: TextView) : RecyclerView.ViewHolder(text) {
        init {
            text.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    text.requestFocus()
                    onItemFocused(items[pos].categoryId)
                }
            }
            text.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemFocused(items[pos].categoryId)
                    }
                }
            }
        }
    }
}

private class RecordsChannelGridAdapter(
    private val spanCount: Int,
    private val sidebarFocusAnchorId: Int,
    private val categoriesRecyclerView: RecyclerView,
    private val selectedCategoryIndex: () -> Int,
    private val onChannelOpen: (LiveStream) -> Unit,
) : RecyclerView.Adapter<RecordsChannelGridAdapter.VH>() {

    private val items = mutableListOf<LiveStream>()

    fun submit(list: List<LiveStream>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_records_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val stream = items[position]
        holder.name.text = stream.name
        val col = position % spanCount
        holder.itemView.nextFocusLeftId =
            if (col == 0) sidebarFocusAnchorId else View.NO_ID
        holder.itemView.nextFocusUpId = View.NO_ID
        val url = stream.iconUrl
        if (url.isNullOrBlank()) {
            Glide.with(holder.icon).clear(holder.icon)
            holder.icon.setImageDrawable(null)
        } else {
            Glide.with(holder.icon).load(url).fitCenter().into(holder.icon)
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (position < spanCount) {
                        val idx = selectedCategoryIndex()
                        categoriesRecyclerView.scrollToPosition(idx)
                        requestFocusCategoryAfterScroll(categoriesRecyclerView, idx)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    onChannelOpen(stream)
                    true
                }
                else -> false
            }
        }
        holder.itemView.setOnClickListener {
            holder.itemView.requestFocus()
            onChannelOpen(stream)
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.records_channel_icon)
        val name: TextView = itemView.findViewById(R.id.records_channel_name)
    }
}
