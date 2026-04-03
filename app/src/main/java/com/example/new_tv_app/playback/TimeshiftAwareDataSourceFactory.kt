package com.example.new_tv_app.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.new_tv_app.iptv.IptvStreamUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * DataSource.Factory that transparently keeps Xtream timeshift server sessions alive without
 * ever resetting the ExoPlayer instance (which causes a black screen).
 *
 * Problem: Xtream timeshift playlists are typically served as VOD-style HLS manifests that
 * ExoPlayer fetches once. The server ties a session to that first manifest request and expires
 * it after ~60 s of inactivity on the server side. When ExoPlayer later requests a segment that
 * falls outside the expired session window it receives HTTP 404, triggering the old workaround
 * of calling player.setMediaItem() + prepare() — which blanks the screen.
 *
 * Solution: [TimeshiftAwareDataSource] intercepts every manifest (*.m3u8) open from ExoPlayer
 * and calls [onManifestOpened]. This factory then runs a lightweight background coroutine that
 * silently re-GETs the manifest URL every [KEEP_ALIVE_INTERVAL_MS] milliseconds, renewing the
 * server session continuously. The player never has to be reset → no black screen.
 */
@UnstableApi
class TimeshiftAwareDataSourceFactory(
    context: Context,
    /** Exposed so [PlaybackVideoFragment] can still apply per-stream HTTP headers. */
    val httpDataSourceFactory: DefaultHttpDataSource.Factory,
    private val coroutineScope: CoroutineScope,
) : DataSource.Factory {

    private val innerFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
    private val activeUrl = AtomicReference<String?>(null)
    private var keepAliveJob: Job? = null

    /**
     * Called by [TimeshiftAwareDataSource] the moment ExoPlayer opens a timeshift manifest.
     * Also callable directly (e.g. from onResume) to restart the keepalive without waiting
     * for ExoPlayer to re-fetch the manifest.
     */
    fun startKeepAlive(url: String) {
        if (!IptvStreamUrls.isTimeshiftUrl(url)) return
        val previous = activeUrl.getAndSet(url)
        if (previous == url && keepAliveJob?.isActive == true) return
        keepAliveJob?.cancel()
        keepAliveJob = coroutineScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Timeshift keep-alive started for ${sanitize(url)}")
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                pingOnce(activeUrl.get() ?: break)
            }
            Log.d(TAG, "Timeshift keep-alive stopped")
        }
    }

    /**
     * Sends an immediate background ping to renew the server session — call this when a
     * 404 error is detected so the session is restored before ExoPlayer retries.
     */
    fun triggerImmediatePing() {
        val url = activeUrl.get() ?: return
        coroutineScope.launch(Dispatchers.IO) { pingOnce(url) }
    }

    /** Stops the keepalive coroutine (call from onStop / onDestroyView). */
    fun stop() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        activeUrl.set(null)
    }

    override fun createDataSource(): DataSource =
        TimeshiftAwareDataSource(innerFactory.createDataSource(), ::startKeepAlive)

    private fun pingOnce(url: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as? HttpURLConnection ?: return
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            IptvStreamUrls.simpleRefererOriginForStreamUrl(url)?.let { (referer, origin) ->
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Origin", origin)
            }
            conn.connect()
            // Read at least one byte so the server registers a complete request and creates
            // a fresh session; just connecting is not always sufficient.
            conn.inputStream.read()
            Log.d(TAG, "Keep-alive ping OK: ${sanitize(url)}")
        } catch (e: Exception) {
            Log.w(TAG, "Keep-alive ping failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun sanitize(url: String): String =
        url.substringAfter("://").substringAfter("/").take(50)

    companion object {
        private const val TAG = "TimeshiftKeepAlive"

        /**
         * Ping interval — must be shorter than the server's session TTL (~60 s on most
         * Xtream panels) so the session never actually expires.
         */
        const val KEEP_ALIVE_INTERVAL_MS = 50_000L
    }
}
