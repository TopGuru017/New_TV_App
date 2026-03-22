package com.example.new_tv_app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.new_tv_app.MainActivity
import com.example.new_tv_app.R

class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: ""

        val channelId = "reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                context.getString(R.string.tv_guide_reminders),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (channel.isNotEmpty()) "$title — $channel" else title)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id.hashCode().and(0x7FFFFFFF), notification)
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
    }
}
