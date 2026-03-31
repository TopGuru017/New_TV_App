package com.example.new_tv_app.iptv

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object IptvTimeUtils {

    /** Timezone used for server API calls (timeshift URLs, day boundaries). */
    val ISRAEL_TZ: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    /** Timezone used for all display — matches the device's local clock. */
    val DISPLAY_TZ: TimeZone = TimeZone.getDefault()

    fun formatTimeIsrael(unixSeconds: Long, pattern: String = "HH:mm"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = DISPLAY_TZ
        return sdf.format(Date(unixSeconds * 1000L))
    }

    fun formatTimeRangeIsrael(startUnix: Long, endUnix: Long): String =
        "${formatTimeIsrael(startUnix)} - ${formatTimeIsrael(endUnix)}"

    fun formatDateIsrael(unixSeconds: Long, pattern: String = "EEEE dd/MM/yyyy"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = DISPLAY_TZ
        return sdf.format(Date(unixSeconds * 1000L))
    }

    fun nowIsraelSeconds(): Long = System.currentTimeMillis() / 1000L

    /** Xtream timeshift path segment, e.g. `2026-03-22:04-20`. Uses server TZ. */
    fun formatTimeshiftStartIsrael(unixSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        sdf.timeZone = ISRAEL_TZ
        return sdf.format(Date(unixSeconds * 1000L))
    }

    /**
     * Parses a timeshift start segment (`2026-03-22:04-20`) back to Unix seconds.
     * Returns null if the segment cannot be parsed.
     */
    fun parseTimeshiftStartIsrael(startFmt: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            sdf.timeZone = ISRAEL_TZ
            sdf.parse(startFmt)?.time?.div(1000L)
        }.getOrNull()
    }

    fun startOfDayIsraelSeconds(unixSeconds: Long): Long {
        val cal = Calendar.getInstance(ISRAEL_TZ)
        cal.timeInMillis = unixSeconds * 1000L
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000L
    }

    fun endOfDayIsraelSeconds(dayStartUnix: Long): Long {
        val cal = Calendar.getInstance(ISRAEL_TZ)
        cal.timeInMillis = dayStartUnix * 1000L
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis / 1000L
    }

    /**
     * Last seven local calendar days: index 0 = today, then yesterday, …
     * [RecordsDaySlot.startUnix] is start-of-day in Israel (for server queries),
     * but the label is formatted in local time.
     */
    fun lastSevenDaySlotsIsrael(): List<RecordsDaySlot> {
        val cal = Calendar.getInstance(ISRAEL_TZ)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val out = ArrayList<RecordsDaySlot>(7)
        repeat(7) { i ->
            val start = cal.timeInMillis / 1000L
            val label = formatDateIsrael(start, "EEEE - dd/MM/yyyy")
            out.add(RecordsDaySlot(startUnix = start, label = label))
            if (i < 6) cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    fun formatDurationHms(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val sec = s % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    }
}

data class RecordsDaySlot(
    val startUnix: Long,
    val label: String,
)
