package com.example.new_tv_app

import android.app.Application
import com.example.new_tv_app.iptv.IptvCredentials
import com.example.new_tv_app.reminder.ReminderStore

class IptvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IptvCredentials.init(this)
        ReminderStore.init(this)
    }
}
