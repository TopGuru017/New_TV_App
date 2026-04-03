package com.example.new_tv_app.iptv

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale

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
     * Prefer calling the overload that also accepts [startRaw]/[endRaw] whenever an
     * [EpgListing] is available — those are the raw datetime strings ("yyyy-MM-dd HH:mm:ss")
     * returned by the server and let us build the URL without any timezone arithmetic.
     *
     * This fallback overload always requests the maximum window (8 h = 480 min) because
     * numeric `stop_timestamp` values from many servers are incorrect (only 1–2 min after
     * start), and timezone-based corrections are also unreliable.
     */
    fun timeshiftStreamUrl(streamId: String, startUnix: Long, endUnix: Long): String {
        val base = IptvCredentials.preferredBaseUrl().trimEnd('/')
        val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
        val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
        val id = streamId.trim().trimStart('/')
        val durationMinutes = 8 * 60
        val startFmt = IptvTimeUtils.formatTimeshiftStartIsrael(startUnix)
        return "$base/timeshift/$u/$p/$durationMinutes/$startFmt/$id.m3u8"
    }

    /**
     * Preferred overload: builds the timeshift URL using the raw datetime strings the server
     * returned in the EPG ([startRaw] / [endRaw], e.g. `"2026-03-30 20:00:00"`).
     *
     * This avoids every timezone pitfall:
     * - The start segment is built by simple string reformatting ("2026-03-30 20:00:00"
     *   → "2026-03-30:20-00") — exactly what the server originally reported, zero conversion.
     * - The duration is the difference between the two raw strings parsed as plain numbers
     *   (both strings share the same timezone offset, so the difference is always correct).
     *
     * Falls back to the numeric overload when either raw string is null or unparseable.
     */
    fun timeshiftStreamUrl(
        streamId: String,
        startUnix: Long,
        endUnix: Long,
        startRaw: String?,
        endRaw: String?,
    ): String {
        val startFmt = startRaw?.let { rawToUrlSegment(it) }
        val durationMinutes = if (startFmt != null && endRaw != null) {
            rawDurationMinutes(startRaw!!, endRaw)
        } else null

        if (startFmt != null && durationMinutes != null) {
            val base = IptvCredentials.preferredBaseUrl().trimEnd('/')
            val u = Uri.encode(IptvCredentials.usernameRaw(), "/")
            val p = Uri.encode(IptvCredentials.passwordRaw(), "/")
            val id = streamId.trim().trimStart('/')
            return "$base/timeshift/$u/$p/$durationMinutes/$startFmt/$id.m3u8"
        }
        return timeshiftStreamUrl(streamId, startUnix, endUnix)
    }

    /**
     * Converts a server datetime string ("2026-03-30 20:00:00" or "2026-03-30 20:00")
     * to the Xtream URL segment format ("2026-03-30:20-00").
     * Returns null if the string does not match the expected shape.
     */
    private fun rawToUrlSegment(raw: String): String? {
        val s = raw.trim()
        val spaceIdx = s.indexOf(' ')
        if (spaceIdx < 1) return null
        val date = s.substring(0, spaceIdx)                     // "2026-03-30"
        val timePart = s.substring(spaceIdx + 1).take(5)        // "20:00"
        if (timePart.length < 5 || timePart[2] != ':') return null
        val hhmm = timePart.replace(':', '-')                   // "20-00"
        return "$date:$hhmm"
    }

    /**
     * Parses two server datetime strings as plain numbers (no timezone — both share the same
     * offset so it cancels in the subtraction) and returns the duration in minutes,
     * clamped to [1, 480].
     */
    private fun rawDurationMinutes(startRaw: String, endRaw: String): Int {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = IptvTimeUtils.ISRAEL_TZ
            }
            val tStart = fmt.parse(startRaw.trim())?.time ?: return 8 * 60
            val tEnd   = fmt.parse(endRaw.trim())?.time   ?: return 8 * 60
            if (tEnd <= tStart) return 8 * 60
            ((tEnd - tStart) / 60_000L).toInt().coerceIn(1, 8 * 60)
        } catch (_: Exception) {
            8 * 60
        }
    }
}
