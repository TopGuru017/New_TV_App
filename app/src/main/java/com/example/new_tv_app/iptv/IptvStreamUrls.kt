package com.example.new_tv_app.iptv

import android.net.Uri

/**
 * Xtream-style direct live URLs (same credentials as [XtreamLiveApi]).
 * Most panels serve **HLS** as `.m3u8`; raw `.ts` is less reliable on Android TV.
 */
object IptvStreamUrls {

    fun liveStreamUrl(streamId: String): String {
        val base = IptvCredentials.preferredBaseUrl()
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        return "$base/live/$u/$p/$id.m3u8"
    }

    /** True for Xtream-style live URLs; used to disable seek / trick-play UI (live is not movable). */
    fun isPanelLiveStreamUrl(url: String): Boolean {
        val path = Uri.parse(url.trim()).path ?: return false
        return path.split('/').any { it.equals("live", ignoreCase = true) }
    }

    /** True for Xtream-style catch-up / timeshift URLs (`/timeshift/…`). */
    fun isTimeshiftUrl(url: String): Boolean {
        val path = Uri.parse(url.trim()).path ?: return false
        return path.split('/').any { it.equals("timeshift", ignoreCase = true) }
    }

    /**
     * Extracts the Xtream stream ID from a timeshift URL.
     *
     * URL format: `base/timeshift/user/pass/durationMin/startFmt/streamId.m3u8`
     * The stream ID is the last path segment, stripped of its extension.
     */
    fun streamIdFromTimeshiftUrl(url: String): String? {
        if (!isTimeshiftUrl(url)) return null
        val path = Uri.parse(url.trim()).path ?: return null
        val lastSegment = path.trimEnd('/').substringAfterLast('/').ifBlank { return null }
        val dot = lastSegment.lastIndexOf('.')
        return if (dot > 0) lastSegment.substring(0, dot) else lastSegment
    }

    /**
     * Returns the recording start time (Unix seconds, Israel tz) parsed from the timeshift URL
     * path segment `startFmt` (`yyyy-MM-dd:HH-mm`). Returns null on parse failure.
     */
    fun startTimeSecondsFromTimeshiftUrl(url: String): Long? {
        if (!isTimeshiftUrl(url)) return null
        val path = Uri.parse(url.trim()).path ?: return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        val timeshiftIdx = segments.indexOfFirst { it.equals("timeshift", ignoreCase = true) }
        if (timeshiftIdx < 0) return null
        // path after /timeshift: [user, pass, durationMin, startFmt, id.m3u8]
        val startFmt = segments.getOrNull(timeshiftIdx + 4) ?: return null
        return IptvTimeUtils.parseTimeshiftStartIsrael(startFmt)
    }

    fun vodMovieUrl(streamId: String, containerExtension: String): String {
        val base = IptvCredentials.preferredBaseUrl()
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        val ext = containerExtension.trim().removePrefix(".").ifBlank { "mp4" }
        return "$base/movie/$u/$p/$id.$ext"
    }

    fun seriesEpisodeUrl(episodeStreamId: String, containerExtension: String): String {
        val base = IptvCredentials.preferredBaseUrl()
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = episodeStreamId.trim().trimStart('/')
        val ext = containerExtension.trim().removePrefix(".").ifBlank { "mp4" }
        return "$base/series/$u/$p/$id.$ext"
    }

    /**
     * Xtream-style catch-up URL for recordings (timeshift).
     *
     * Many Xtream-based panels expect the "duration" path segment in **minutes** (clip length),
     * not seconds.
     */
    fun timeshiftStreamUrl(streamId: String, startUnix: Long, endUnix: Long): String {
        val base = IptvCredentials.preferredBaseUrl().trimEnd('/')
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        val durationMinutes = ((endUnix - startUnix) / 60L)
            .toInt()
            .coerceIn(1, 8 * 60) // 1..480 minutes
        val startFmt = IptvTimeUtils.formatTimeshiftStartIsrael(startUnix)
        return "$base/timeshift/$u/$p/$durationMinutes/$startFmt/$id.m3u8"
    }
}
