package com.example.new_tv_app.iptv

import java.io.Serializable

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
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class EpgListing(
    val title: String,
    val description: String,
    val category: String?,
    val startUnix: Long,
    val endUnix: Long,
    val imageUrl: String? = null,
    /** Raw datetime string from the server, e.g. "2026-03-30 20:00:00". Used to build
     *  timeshift URLs directly from the server's own time representation without any
     *  timezone conversion. Null when the server only provides a numeric timestamp. */
    val startRaw: String? = null,
    /** Raw datetime string for the programme end, same format as [startRaw]. */
    val endRaw: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }
}
