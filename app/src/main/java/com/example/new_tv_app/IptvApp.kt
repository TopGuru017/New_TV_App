package com.example.new_tv_app

import android.app.Application
import com.example.new_tv_app.iptv.IptvCredentials

class IptvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IptvCredentials.init(this)
    }
}
