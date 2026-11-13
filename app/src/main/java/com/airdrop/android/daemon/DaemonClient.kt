package com.airdrop.android.daemon

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DaemonClient(
    private val onConnected: (Boolean) -> Unit,
    private val onEvent: (Event) -> Unit,
    private val onRaw: ((String) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "airdrop-write").apply { isDaemon = true } }
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var heartbeat: Thread? = null
    private var lastProgressLog = 0L

    private class Pending(val cmd: JSONObject, val deadline: Long, val onDrop: (() -> Unit)?)
    private val pending = ArrayList<Pending>()

    val isConnected: Boolean get() = socket != null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::loop, "airdrop-read").apply { isDaemon = true; start() }
        heartbeat = Thread(::beat, "airdrop-beat").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        thread?.interrupt()
        heartbeat?.interrupt()
    }

    fun send(cmd: JSONObject, ttlMs: Long = DEFAULT_TTL_MS, onDrop: (() -> Unit)? = null) {
        val s = socket
        if (s == null) {
            if (ttlMs <= 0) { onDrop?.invoke(); return }
            synchronized(pending) {
                if (pending.size >= MAX_PENDING) pending.removeAt(0).onDrop?.invoke()
                pending += Pending(cmd, System.currentTimeMillis() + ttlMs, onDrop)
            }
            return
        }
        write(s, cmd, onDrop)
    }

    private fun write(s: LocalSocket, cmd: JSONObject, onDrop: (() -> Unit)?) {
        writer.execute {
            try {
                s.outputStream.apply { write((cmd.toString() + "\n").toByteArray()); flush() }
            } catch (t: Throwable) {
                Log.w(TAG, "write failed: $t")
                runCatching { s.close() }
                if (socket === s) socket = null
                onDrop?.invoke()
            }
        }
    }

    private fun flush(s: LocalSocket) {
        val now = System.currentTimeMillis()
        val ready = synchronized(pending) {
            val out = ArrayList<Pending>(pending); pending.clear(); out
        }
        for (p in ready) {
            if (p.deadline < now) { p.onDrop?.invoke(); continue }
            write(s, p.cmd, p.onDrop)
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        val dead = synchronized(pending) {
            val d = pending.filter { it.deadline < now }
            pending.removeAll(d.toSet()); d
        }
        dead.forEach { it.onDrop?.invoke() }
    }

    private fun beat() {
        while (running.get()) {
            try { Thread.sleep(HEARTBEAT_MS) } catch (_: InterruptedException) { return }
            expire()
            if (socket != null) send(Cmd.status(), ttlMs = 0)
        }
    }

    private fun loop() {
        var backoff = 500L
        while (running.get()) {
            var s: LocalSocket? = null
            var up = false
            try {
                s = LocalSocket()
                s.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                s.soTimeout = READ_TIMEOUT_MS
                socket = s
                backoff = 500L
                up = true
                onConnected(true)
                send(Cmd.status()); send(Cmd.inbox())
                flush(s)
                val r = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                while (running.get()) {
                    val line = try { r.readLine() } catch (_: SocketTimeoutException) {
                        Log.w(TAG, "daemon silent for ${READ_TIMEOUT_MS / 1000}s; reconnecting")
                        null
                    } ?: break
                    val progress = line.contains("\"state\":\"sending\"") || line.startsWith("{\"ev\":\"receiving\"")
                    val now = System.currentTimeMillis()
                    if (!progress || now - lastProgressLog > 1_000L) { Log.d(TAG, "<- $line"); if (progress) lastProgressLog = now }
                    runCatching { onRaw?.invoke(line) }
                    runCatching { onEvent(parseEvent(JSONObject(line))) }
                        .onFailure { Log.w(TAG, "bad event: $line ($it)") }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "no daemon: $t")
            }
            runCatching { s?.close() }
            socket = null
            if (running.get()) {
                if (up) onConnected(false)
                expire()
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
                backoff = (backoff * 2).coerceAtMost(3_000L)
            }
        }
    }

    private companion object {
        const val TAG = "AirDrop"
        const val DEFAULT_TTL_MS = 8_000L
        const val MAX_PENDING = 32
        const val HEARTBEAT_MS = 15_000L
        const val READ_TIMEOUT_MS = 50_000
    }
}
