package com.example.new_tv_app

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/** Loads [PlaybackVideoFragment]. */
class PlaybackActivity : FragmentActivity() {

    companion object {
        /** Xtream stream id for archive URLs when playing catch-up from [RecordsDetailFragment]. */
        const val RECORDS_ARCHIVE_STREAM_ID = "records_archive_stream_id"
        /** Same order as the Records day list; enables day column UI during playback. */
        const val RECORDS_DAY_LISTINGS = "records_day_listings"
        /** Live TV: category id for the vertical channel picker (DPAD up/down during live). */
        const val LIVE_CATEGORY_ID = "live_category_id"
        /** Live TV: Xtream stream id currently playing (focus in picker). */
        const val LIVE_STREAM_ID = "live_stream_id"
        /** Live TV: whether this channel has tv_archive / catch-up enabled on the server. */
        const val LIVE_TV_ARCHIVE = "live_tv_archive"
        /** VOD: seek to this position (ms) once the player is ready. See [NO_INITIAL_POSITION]. */
        const val INITIAL_POSITION_MS = "initial_position_ms"
        /** Intent extra default: no resume seek. */
        const val NO_INITIAL_POSITION = -1L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PlaybackVideoFragment())
                .commit()
        }
    }
}