package com.airdrop.android

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.airdrop.android.daemon.Hub

/**
 * Keeps this process alive while AirDrop is on.
 *
 * Everything that makes the device reachable lives in this process: the Bluetooth wake advertisement, the
 * connection that hears incoming requests, the transfer watchdogs and the loop that restarts a dead radio.
 * As a plain background process it is frozen or killed within minutes of leaving the screen.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() obliges us to go foreground even if we are about to stop.
        if (!foreground()) { stopSelf(); return START_NOT_STICKY }
        if (!Hub.airdrop.value) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun foreground(): Boolean {
        val note = Notifier.keepAlive()
        for (type in types()) {
            try {
                startForeground(Notifier.KEEP_ALIVE_ID, note, type)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "foreground service (type $type) refused: $t")
            }
        }
        return false
    }

    private fun types(): List<Int> {
        val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT < 34) return listOf(connected)
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // From Android 14 the connected-device type needs a granted Bluetooth permission.
        val bluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return if (bluetooth) listOf(connected, special) else listOf(special)
    }

    companion object {
        private const val TAG = "AirDrop"

        fun sync(ctx: Context, on: Boolean) {
            val i = Intent(ctx, KeepAliveService::class.java)
            try {
                if (on) ctx.startForegroundService(i) else ctx.stopService(i)
            } catch (t: Throwable) {
                // Refused while in the background; retried when the app is exempt or next opened.
                Log.w(TAG, "keep-alive service not ${if (on) "started" else "stopped"}: $t")
            }
        }
    }
}

/** Brings the keep-alive service back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Hub.init(context)
                Hub.syncKeepAlive()
            }
        }
    }
}

/** The "Turn off" action on the keep-alive notification. */
class TurnOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        Hub.setAirDrop(false)
    }
}
