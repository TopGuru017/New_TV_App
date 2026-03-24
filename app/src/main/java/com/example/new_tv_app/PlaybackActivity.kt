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