package com.example.new_tv_app.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.new_tv_app.iptv.IptvStreamUrls

/**
 * DataSource wrapper that intercepts Xtream timeshift manifest opens and notifies
 * [TimeshiftAwareDataSourceFactory] to start (or resume) its background session keep-alive.
 *
 * All actual data transfer is fully delegated to the wrapped [delegate] — this class adds
 * zero latency and changes nothing about how bytes are delivered to ExoPlayer.
 */
@UnstableApi
internal class TimeshiftAwareDataSource(
    private val delegate: DataSource,
    private val onManifestOpened: (url: String) -> Unit,
) : DataSource {

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        // Only HLS timeshift manifests (.m3u8) need the session keep-alive; raw MPEG-TS (.ts)
        // timeshift streams are delivered as a single progressive HTTP connection that ExoPlayer
        // keeps open, so no periodic ping is required.
        if (IptvStreamUrls.isTimeshiftUrl(url) && url.contains(".m3u8", ignoreCase = true)) {
            onManifestOpened(url)
        }
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri() = delegate.uri

    override fun close() = delegate.close()

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)
}
