package com.example.new_tv_app.iptv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** All IPTV times use Israel timezone. */
object IptvTimeUtils {

    val ISRAEL_TZ: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

    fun formatTimeIsrael(unixSeconds: Long, pattern: String = "HH:mm"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = ISRAEL_TZ
        return sdf.format(Date(unixSeconds * 1000L))
    }

    fun formatTimeRangeIsrael(startUnix: Long, endUnix: Long): String =
        "${formatTimeIsrael(startUnix)} - ${formatTimeIsrael(endUnix)}"

    fun formatDateIsrael(unixSeconds: Long, pattern: String = "EEEE dd/MM/yyyy"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = ISRAEL_TZ
        return sdf.format(Date(unixSeconds * 1000L))
    }

    fun nowIsraelSeconds(): Long = System.currentTimeMillis() / 1000L
}
