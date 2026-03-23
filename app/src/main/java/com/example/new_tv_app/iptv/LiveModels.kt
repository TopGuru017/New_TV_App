package com.example.new_tv_app.iptv

data class LiveCategory(
    val id: String,
    val name: String,
)

data class LiveStream(
    val streamId: String,
    val name: String,
    val iconUrl: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    /** Xtream `tv_archive` — channel offers catch-up / TV archive. */
    val tvArchive: Boolean = false,
)

data class EpgListing(
    val title: String,
    val description: String,
    val category: String?,
    val startUnix: Long,
    val endUnix: Long,
    val imageUrl: String? = null,
)
