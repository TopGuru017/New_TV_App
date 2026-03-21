package com.example.new_tv_app.iptv

import android.net.Uri
import com.example.new_tv_app.BuildConfig

/**
 * Xtream-style direct live URLs (same credentials as [XtreamLiveApi]).
 * Most panels serve **HLS** as `.m3u8`; raw `.ts` is less reliable on Android TV.
 */
object IptvStreamUrls {

    fun liveStreamUrl(streamId: String): String {
        val base = BuildConfig.IPTV_BASE_URL.trimEnd('/')
        val u = Uri.encode(BuildConfig.IPTV_USERNAME, "/")
        val p = Uri.encode(BuildConfig.IPTV_PASSWORD, "/")
        val id = streamId.trim().trimStart('/')
        return "$base/live/$u/$p/$id.m3u8"
    }

    fun vodMovieUrl(streamId: String, containerExtension: String): String {
        val base = BuildConfig.IPTV_BASE_URL.trimEnd('/')
        val u = Uri.encode(BuildConfig.IPTV_USERNAME, "/")
        val p = Uri.encode(BuildConfig.IPTV_PASSWORD, "/")
        val id = streamId.trim().trimStart('/')
        val ext = containerExtension.trim().removePrefix(".").ifBlank { "mp4" }
        return "$base/movie/$u/$p/$id.$ext"
    }

    fun seriesEpisodeUrl(episodeStreamId: String, containerExtension: String): String {
        val base = BuildConfig.IPTV_BASE_URL.trimEnd('/')
        val u = Uri.encode(BuildConfig.IPTV_USERNAME, "/")
        val p = Uri.encode(BuildConfig.IPTV_PASSWORD, "/")
        val id = episodeStreamId.trim().trimStart('/')
        val ext = containerExtension.trim().removePrefix(".").ifBlank { "mp4" }
        return "$base/series/$u/$p/$id.$ext"
    }
}
