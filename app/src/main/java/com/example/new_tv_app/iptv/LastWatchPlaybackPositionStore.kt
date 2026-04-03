package com.example.new_tv_app.iptv

import android.content.Context
import androidx.media3.common.C

/**
 * Persists last playback position (ms) per VOD resume key ([LastWatchStore.resumeCacheKey]).
 * Used when reopening from Last Watch (resume vs restart) and updated during normal VOD playback.
 */
object LastWatchPlaybackPositionStore {

    private const val PREFS = "iptv_last_watch_playback_positions"
    private const val MIN_RESUME_MS = 10_000L

    data class Snapshot(
        val positionMs: Long,
        val durationMs: Long,
    )

    fun read(context: Context, resumeKey: String): Snapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(resumeKey, null) ?: return null
        val parts = raw.split(',')
        if (parts.size < 2) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: 0L
        return Snapshot(pos, dur)
    }

    fun remove(context: Context, resumeKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(resumeKey)
            .apply()
    }

    fun save(context: Context, resumeKey: String, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_RESUME_MS) {
            remove(context, resumeKey)
            return
        }
        val dur = if (durationMs > 0 && durationMs != C.TIME_UNSET) durationMs else 0L
        if (dur > 0 && positionMs >= (dur * NEAR_END_FRACTION).toLong()) {
            remove(context, resumeKey)
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(resumeKey, "${positionMs},${dur}")
            .apply()
    }

    fun shouldOfferResume(snapshot: Snapshot): Boolean {
        if (snapshot.positionMs < MIN_RESUME_MS) return false
        if (snapshot.durationMs > 0 && snapshot.positionMs >= (snapshot.durationMs * NEAR_END_FRACTION).toLong()) {
            return false
        }
        return true
    }

    private const val NEAR_END_FRACTION = 0.92
}
