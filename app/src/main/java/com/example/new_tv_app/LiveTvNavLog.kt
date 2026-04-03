package com.example.new_tv_app

import android.util.Log

/**
 * Live TV D-pad / channel navigation tracing.
 *
 * **Tag:** `LiveTvNav` (search this exact string in Logcat).
 *
 * - Android Studio Logcat: dropdown **Edit Filter** → Log Tag = `LiveTvNav` (or Regex `LiveTvNav`),
 *   Log Level = **Info** or **Verbose**.
 * - adb: `adb logcat -s LiveTvNav:I`
 *
 * Note: [Log.d] is often hidden when the filter is set to **Info** or **Warning** only — this uses [Log.i].
 */
object LiveTvNavLog {
    const val TAG = "LiveTvNav"

    fun i(message: String) {
        Log.i(TAG, message)
    }
}
