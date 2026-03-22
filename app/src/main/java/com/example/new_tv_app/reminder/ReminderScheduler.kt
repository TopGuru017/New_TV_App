package com.example.new_tv_app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

private const val NOTIFY_MINUTES_BEFORE = 3L

object ReminderScheduler {

    fun schedule(context: Context, reminder: Reminder) {
        val notifyAt = (reminder.startUnix - NOTIFY_MINUTES_BEFORE * 60) * 1000L
        if (notifyAt <= System.currentTimeMillis()) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
                putExtra(ReminderAlarmReceiver.EXTRA_TITLE, reminder.programTitle)
                putExtra(ReminderAlarmReceiver.EXTRA_CHANNEL, reminder.channelName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyAt, pi)
    }

    fun cancel(context: Context, reminderId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            Intent(context, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }
}
