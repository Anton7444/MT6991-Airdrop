package com.airdrop.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.airdrop.android.daemon.Hub
import com.airdrop.android.daemon.Ipc

class RelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Ipc.ACTION_EVENT) return
        val json = intent.getStringExtra(Ipc.EXTRA_JSON)
        if (!fromShareApp(context, intent)) return
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
    val sender = if (Build.VERSION.SDK_INT >= 34) sentFromUid else INVALID_UID
    val shareUid = shareUid(context)
    val accepted = com.airdrop.android.daemon.RelayAuth.accepts(
        sender, shareUid, intent.`package` == context.packageName,
        intent.getStringExtra(Ipc.EXTRA_TOKEN), Ipc.appToken(context),
    )
    if (!accepted) Log.w(TAG, "ignoring relay from uid=$sender")
    return accepted
}

private const val INVALID_UID = -1

@Volatile private var cachedShareUid = -2
@Volatile private var shareUidAt = 0L

/** The share app's uid, looked up at most once a minute: relays arrive several times a second during a transfer. */
private fun shareUid(context: Context): Int {
    val now = android.os.SystemClock.elapsedRealtime()
    if (cachedShareUid >= 0 && now - shareUidAt < 60_000L) return cachedShareUid
    val uid = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageUid(Ipc.SHARE_PKG, 0)
    } catch (_: Exception) { -2 }
    cachedShareUid = uid; shareUidAt = now
    return uid
}
