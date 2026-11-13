package com.airdrop.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.airdrop.android.daemon.Hub
import com.airdrop.android.daemon.Ipc

class RelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Ipc.ACTION_EVENT) return
        val json = intent.getStringExtra(Ipc.EXTRA_JSON)
        val accepted = fromShareApp(context, intent)
        val sender = if (Build.VERSION.SDK_INT >= 34) sentFromUid else INVALID_UID
        Log.i(TAG, "relay: sender uid=$sender accepted=$accepted json=$json")
        if (!accepted) return
        if (json == null) return
        Hub.init(context)
        Hub.onRelay(json)
    }
}

class ReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Ipc.ACTION_RECEIVED) return
        if (!fromShareApp(context, intent)) return
        val name = intent.getStringExtra(Ipc.EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: return
        Log.i(TAG, "a received file was announced by the module: $name")
        Hub.init(context)
        Hub.refreshInbox()
    }
}

private const val TAG = "AirDrop"

private fun BroadcastReceiver.fromShareApp(context: Context, intent: Intent): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    val shareUid = try {
        context.packageManager.getPackageUid(Ipc.SHARE_PKG, PackageManager.PackageInfoFlags.of(0))
    } catch (_: Exception) { -2 }
    if (sentFromUid == shareUid) return true
    val addressedToUs = intent.`package` == context.packageName
    val given = intent.getStringExtra(Ipc.EXTRA_TOKEN)
    val token = given != null && java.security.MessageDigest.isEqual(given.toByteArray(), Ipc.appToken(context).toByteArray())
    if (sentFromUid == INVALID_UID && addressedToUs && token) return true
    Log.w(TAG, "ignoring a relay: uid=$sentFromUid (share app is $shareUid) toUs=$addressedToUs token=$token")
    return false
}

private const val INVALID_UID = -1
