package com.airdrop.android.xposed

import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object WireProgress {
    private const val TX = "/sys/class/net/awdl0/statistics/tx_bytes"

    private const val OVERHEAD = 1.06

    private class Track(val base: Long, var shown: Long = 0)
    private val tracks = ConcurrentHashMap<String, Track>()

    private var ctx: android.content.Context? = null
    fun init(c: android.content.Context) { ctx = c }

    private fun tx(): Long {
        ctx?.let { c ->
            runCatching { android.provider.Settings.Global.getString(c.contentResolver, "airdrop_awdl_tx")?.toLong() }
                .getOrNull()?.let { return it }
        }
        return try { File(TX).readText().trim().toLong() } catch (_: Throwable) { -1L }
    }

    fun sent(mac: String, active: Boolean, sending: Boolean, sent: Long, total: Long): Long {
        if (!active) { tracks.remove(mac); return sent }
        if (!sending || total <= 0) {
            tx().takeIf { it >= 0 }?.let { tracks.putIfAbsent(mac, Track(it)) }
            return sent
        }
        val now = tx()
        if (now < 0) return sent
        val t = tracks.getOrPut(mac) { Track(now) }
        val wire = ((now - t.base) / OVERHEAD).toLong().coerceAtLeast(0)
        val real = minOf(wire, sent, total)
        if (real > t.shown) t.shown = real
        return t.shown
    }
}
