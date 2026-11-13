package com.airdrop.android

import android.app.Application
import com.airdrop.android.daemon.Hub

class AirDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}
