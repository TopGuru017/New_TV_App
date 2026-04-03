package com.example.new_tv_app.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide

/**
 * Clears on-disk caches used by playback (HLS/DASH manifests, segment buffers under [Context.getCacheDir]),
 * Glide image disk cache, and in-memory Glide bitmap pool.
 *
 * There is no separate persisted “EPG playlist version” store — EPG is loaded via API. Stale playlist
 * manifests from ExoPlayer / HTTP live under the app cache directory and are removed here.
 */
object AppSessionCleanup {

    /** Deletes [Context.getCacheDir] and [Context.getExternalCacheDir] contents (same as Settings → clear cache). */
    fun clearAppCacheDirectories(context: Context) {
        val app = context.applicationContext
        try {
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            app.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
        }
    }

    /**
     * Called on logout: wipe stream/video buffer dirs, then clear Glide memory (must run on main thread).
     * Disk cache is removed with [clearAppCacheDirectories]; [Glide.clearMemory] releases in-flight bitmaps.
     */
    fun clearCachesOnLogout(context: Context) {
        val app = context.applicationContext
        clearAppCacheDirectories(app)
        clearGlideMemoryMain(app)
    }

    private fun clearGlideMemoryMain(app: Context) {
        val run = Runnable {
            runCatching { Glide.get(app).clearMemory() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            Handler(Looper.getMainLooper()).post(run)
        }
    }
}
