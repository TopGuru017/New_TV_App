package com.example.new_tv_app.iptv

import android.net.Uri

/**
 * Xtream-style direct live URLs (same credentials as [XtreamLiveApi]).
 * Most panels serve **HLS** as `.m3u8`; raw `.ts` is less reliable on Android TV.
 */
object IptvStreamUrls {

    fun liveStreamUrl(streamId: String): String {
        val base = IptvCredentials.baseUrl()
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        return "$base/live/$u/$p/$id.m3u8"
    }

    fun vodMovieUrl(streamId: String, containerExtension: String): String {
        val base = IptvCredentials.baseUrl()
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        val ext = containerExtension.trim().removePrefix(".").ifBlank { "mp4" }
        return "$base/movie/$u/$p/$id.$ext"
    }

    fun seriesEpisodeUrl(episodeStreamId: String, containerExtension: String): String {
        val base = IptvCredentials.baseUrl()
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = episodeStreamId.trim().trimStart('/')
        val ext = containerExtension.trim().removePrefix(".").ifBlank { "mp4" }
        return "$base/series/$u/$p/$id.$ext"
    }

    /**
     * Xtream-style catch-up URL. [durationSeconds] is often required by panels (clip length).
     * If your panel expects minutes instead, adjust the caller.
     */
    fun timeshiftStreamUrl(streamId: String, startUnix: Long, endUnix: Long): String {
        val base = IptvCredentials.baseUrl().trimEnd('/')
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        val durationSec = (endUnix - startUnix).toInt().coerceIn(60, 8 * 3600)
        val startFmt = IptvTimeUtils.formatTimeshiftStartIsrael(startUnix)
        return "$base/timeshift/$u/$p/$durationSec/$startFmt/$id.m3u8"
    }
}
