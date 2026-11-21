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
    /** Reconnect when a status request goes unanswered. Off for the module, where a reconnect fails pending sends. */
    private val detectStall: Boolean = false,
) {
    private val running = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "airdrop-write").apply { isDaemon = true } }
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var heartbeat: Thread? = null
    private var lastProgressLog = 0L
    private var lastLogged = ""

    /**
     * When the oldest unanswered status request was written, or 0. The daemon answers every one, so silence
     * means it has stopped talking to this connection (it does that to a client that was frozen in the
     * background and stopped reading) even though the socket still looks open.
     */
    @Volatile private var askedAt = 0L

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
        if (detectStall && askedAt == 0L && cmd.optString("cmd") == STATUS_CMD) askedAt = System.currentTimeMillis()
        write(s, cmd, onDrop)
    }

    /** Wakes a reader blocked on [s]; closing the descriptor alone does not always do that. */
    private fun drop(s: LocalSocket) {
        runCatching { s.shutdownInput() }
        runCatching { s.shutdownOutput() }
        runCatching { s.close() }
    }

    private fun write(s: LocalSocket, cmd: JSONObject, onDrop: (() -> Unit)?) {
        writer.execute {
            try {
                s.outputStream.apply { write((cmd.toString() + "\n").toByteArray()); flush() }
            } catch (t: Throwable) {
                Log.w(TAG, "write failed: $t")
                drop(s)
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
        var sinceBeat = 0L
        while (running.get()) {
            try { Thread.sleep(BEAT_TICK_MS) } catch (_: InterruptedException) { return }
            expire()
            val s = socket ?: continue
            val asked = askedAt
            if (asked != 0L && System.currentTimeMillis() - asked > STALL_MS) {
                Log.w(TAG, "the daemon stopped answering on this connection; reconnecting")
                askedAt = 0L
                drop(s)
                continue
            }
            sinceBeat += BEAT_TICK_MS
            if (sinceBeat >= HEARTBEAT_MS) { sinceBeat = 0L; send(Cmd.status(), ttlMs = 0) }
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
                askedAt = 0L
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
                    askedAt = 0L
                    val progress = line.contains("\"state\":\"sending\"") || line.startsWith("{\"ev\":\"receiving\"")
                    val now = System.currentTimeMillis()
                    // Status answers repeat every few seconds; log a line only when it says something new.
                    if (progress) {
                        if (now - lastProgressLog > 1_000L) { Log.d(TAG, "<- $line"); lastProgressLog = now }
                    } else if (line != lastLogged) {
                        lastLogged = line
                        Log.d(TAG, "<- " + if (line.length > LOG_MAX) line.take(LOG_MAX) + "…" else line)
                    }
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
                // A connection that was working is retried at once; only repeated failures back off.
                try { Thread.sleep(if (up) 100L else backoff) } catch (_: InterruptedException) { }
                if (!up) backoff = (backoff * 2).coerceAtMost(3_000L)
            }
        }
    }

    private companion object {
        const val TAG = "AirDrop"
        const val DEFAULT_TTL_MS = 8_000L
        const val MAX_PENDING = 32
        const val HEARTBEAT_MS = 15_000L
        const val BEAT_TICK_MS = 1_000L
        const val STALL_MS = 4_000L
        const val STATUS_CMD = "reconcile"
        const val READ_TIMEOUT_MS = 50_000
        const val LOG_MAX = 600
    }
}
