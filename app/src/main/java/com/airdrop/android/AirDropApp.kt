package com.airdrop.android

import android.app.Application
import com.airdrop.android.daemon.Hub
import com.airdrop.android.update.Updater

class AirDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Updater.init(this)
    }
}
