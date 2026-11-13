package com.airdrop.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.util.Base64
import androidx.core.content.ContextCompat
import com.airdrop.android.daemon.Hub
import com.airdrop.android.daemon.Incoming
import com.airdrop.android.ui.label

object Notifier {
    private const val ASK = "airdrop_ask"
    private const val PROGRESS = "airdrop_progress"
    private lateinit var ctx: Context
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    fun init(context: Context) {
        ctx = context
        nm.createNotificationChannel(NotificationChannel(ASK, ctx.getString(R.string.ch_ask), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(PROGRESS, ctx.getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW))
    }

    private fun allowed() = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun nid(id: Long) = (id % Int.MAX_VALUE).toInt()

    private fun answer(id: Long, accept: Boolean): PendingIntent = PendingIntent.getBroadcast(
        ctx, nid(id) * 2 + if (accept) 1 else 0,
        Intent(ctx, AnswerReceiver::class.java).putExtra("id", id).putExtra("accept", accept),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun open(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun saved(): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Received.folderIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun received(name: String) {
        if (!allowed()) return
        val openIt = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra("open_file", name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            nid(name.hashCode().toLong()),
            Notification.Builder(ctx, PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ctx.getString(R.string.notif_received, name))
                .setContentText(ctx.getString(R.string.notif_tap_to_open))
                .setAutoCancel(true)
                .setContentIntent(openIt)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.open_folder), saved()).build())
                .build(),
        )
    }

    fun ask(i: Incoming) {
        if (!allowed()) return
        val b = Notification.Builder(ctx, ASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.notif_wants, i.from))
            .setContentText("${i.label(ctx)} · ${Formatter.formatShortFileSize(ctx, i.total)}")
            .setContentIntent(open())
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.decline), answer(i.id, false)).build())
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.accept), answer(i.id, true)).build())
            .setOngoing(true)
        if (i.thumbnailB64.isNotEmpty()) runCatching {
            val bytes = Base64.decode(i.thumbnailB64, Base64.DEFAULT)
            b.setLargeIcon(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        nm.notify(nid(i.id), b.build())
    }

    fun cancelAsk(id: Long) = nm.cancel(nid(id))

    fun progress(i: Incoming) {
        if (!allowed()) return
        nm.notify(
            nid(i.id),
            Notification.Builder(ctx, PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ctx.getString(R.string.in_receiving, i.label(ctx)))
                .setContentText(ctx.getString(R.string.progress_of, Formatter.formatShortFileSize(ctx, i.received), Formatter.formatShortFileSize(ctx, i.total)))
                .setProgress(100, (i.fraction * 100).toInt(), i.total <= 0)
                .setOngoing(true).setOnlyAlertOnly()
                .setContentIntent(open())
                .build(),
        )
    }

    fun done(i: Incoming) {
        if (!allowed()) return
        nm.notify(
            nid(i.id),
            Notification.Builder(ctx, PROGRESS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(ctx.getString(R.string.in_done, i.label(ctx)))
                .setContentText(ctx.getString(R.string.notif_saved))
                .setAutoCancel(true)
                .setContentIntent(saved())
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.open_folder), saved()).build())
                .build(),
        )
    }

    private fun Notification.Builder.setOnlyAlertOnly() = setOnlyAlertOnce(true)
}

class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Hub.init(context)
        Hub.answer(intent.getLongExtra("id", -1), intent.getBooleanExtra("accept", false))
    }
}
