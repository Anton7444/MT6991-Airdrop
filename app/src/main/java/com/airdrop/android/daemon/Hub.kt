package com.airdrop.android.daemon

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.airdrop.android.Notifier
import com.airdrop.android.R
import com.airdrop.android.Received
import com.airdrop.android.ble.BleWakeAdvertiser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

const val ISLAND_STAMP_KEY = "airdrop_island_stamp"

const val SHARE_STATE_KEY = "oshare_state"

object Hub {
    private lateinit var ctx: Context
    private val client = DaemonClient(::onConnected, ::onEvent)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _coexRequestFailed = MutableStateFlow(false)
    val coexRequestFailed: StateFlow<Boolean> = _coexRequestFailed

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val stagedOutgoing = ConcurrentHashMap<String, File>()
    private var lastFastMarkerUpdate = 0L
    private val _outgoing = MutableStateFlow<Map<String, Outgoing>>(emptyMap())
    val outgoing: StateFlow<Map<String, Outgoing>> = _outgoing

    private val _sent = MutableStateFlow<Map<String, SentItem>>(emptyMap())
    val sent: StateFlow<Map<String, SentItem>> = _sent

    data class SentItem(val names: List<String>, val uri: String?, val url: String?, val at: Long, val mime: String? = null)

    private val _incoming = MutableStateFlow<List<Incoming>>(emptyList())
    val incoming: StateFlow<List<Incoming>> = _incoming

    private val _inbox = MutableStateFlow<List<InboxFile>>(emptyList())
    val inbox: StateFlow<List<InboxFile>> = _inbox

    private val _rootOk = MutableStateFlow<Boolean?>(null)
    val rootOk: StateFlow<Boolean?> = _rootOk

    private val _lsposed = MutableStateFlow<RadioInstaller.Lsposed?>(null)
    val lsposed: StateFlow<RadioInstaller.Lsposed?> = _lsposed

    fun refreshLsposed() {
        Thread({ _lsposed.value = RadioInstaller.lsposed(ctx) }, "airdrop-lsposed").start()
    }

    private val _moduleVersion = MutableStateFlow<String?>(null)
    val moduleVersion: StateFlow<String?> = _moduleVersion

    fun refreshRoot() {
        Thread({
            val ok = RadioInstaller.hasRoot()
            _rootOk.value = ok
            _moduleVersion.value = if (ok) RadioInstaller.installedVersion() else null
        }, "airdrop-root").start()
    }

    fun uninstallService(onDone: (String?) -> Unit) {
        Thread({
            val err = RadioInstaller.uninstall(ctx)
            if (err == null) {
                _airdrop.value = false
                _discovering.value = false
                _peers.value = emptyList()
                _status.value = Event.Status()
            }
            onDone(err)
        }, "airdrop-uninstall").start()
    }

    private val _status = MutableStateFlow(Event.Status())

    val status: StateFlow<Event.Status> = _status

    private val _airdrop = MutableStateFlow(false)
    val airdrop: StateFlow<Boolean> = _airdrop

    enum class WifiPolicy { KeepWifi, Yield }

    private val _policy = MutableStateFlow(WifiPolicy.Yield)
    val policy: StateFlow<WifiPolicy> = _policy

    fun setPolicy(p: WifiPolicy) {
        _policy.value = p
        prefs.edit().putString(POLICY_KEY, p.name).apply()
        pushPolicy()
    }

    private fun pushPolicy() = askModule(Intent().putExtra("cmd", "policy").putExtra("yield", _policy.value == WifiPolicy.Yield))

    private val policyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun startPolicyEngine() {
        policyScope.launch {
            kotlinx.coroutines.flow.combine(_policy, _status, _airdrop, _connected) { p, st, on, up ->
                p == WifiPolicy.KeepWifi && on && up && st.coexAvailable && !st.coexWanted
            }.distinctUntilChanged().collect { needOn -> if (needOn) setCoex(true) }
        }
    }

    private const val POLICY_KEY = "wifi_policy"

    fun init(app: Context) {
        if (::ctx.isInitialized) return
        ctx = app.applicationContext
        _policy.value = runCatching { WifiPolicy.valueOf(prefs.getString(POLICY_KEY, null) ?: "") }.getOrDefault(WifiPolicy.Yield)
        startPolicyEngine()
        if (!prefs.contains(AIRDROP_KEY)) {
            val already = runCatching { Settings.System.getInt(ctx.contentResolver, SHARE_STATE_KEY, -1) }.getOrDefault(-1) == 1
            prefs.edit().putBoolean(AIRDROP_KEY, already).apply()
        }
        _airdrop.value = prefs.getBoolean(AIRDROP_KEY, false)
        if (RadioInstaller.isMt6991()) fastSendMarker().delete()
        BleWakeAdvertiser.attach(ctx)
        Notifier.init(ctx)
        Thread({ RadioInstaller.provisionToken(ctx, Ipc.appToken(ctx)) }, "airdrop-token").start()
        refreshRoot()
        refreshLsposed()
        client.start()
        cleanStaleStaging()
        Thread {
            while (true) {
                try { Thread.sleep(WATCH_POLL_MS) } catch (_: InterruptedException) { return@Thread }
                runCatching { checkWatches() }.onFailure { Log.w(TAG, "watchdog failed: $it") }
            }
        }.apply { isDaemon = true; name = "airdrop-watchdog" }.start()
        if (prefs.getBoolean("radio_installed", false)) Thread {
            var lastTry = 0L
            while (true) {
                try { Thread.sleep(REVIVE_POLL_MS) } catch (_: InterruptedException) { return@Thread }
                val quiet = System.currentTimeMillis() - lastSeenAt
                if (!_connected.value && quiet > DAEMON_QUIET_MS && System.currentTimeMillis() - lastTry > REVIVE_GAP_MS) {
                    lastTry = System.currentTimeMillis()
                    Log.i(TAG, "nothing from the daemon for ${quiet / 1000}s; starting the stack again")
                    RadioInstaller.start(enableAirDrop = _airdrop.value)
                }
            }
        }.start()
    }

    private class SendWatch(
        val uris: List<Uri>, val text: String?,
        var lastEventAt: Long, var acked: Boolean = false, var retried: Boolean = false,
    )
    private val sendWatches = ConcurrentHashMap<String, SendWatch>()

    private class RecvWatch(var lastActivityAt: Long, var retried: Boolean = false)
    private val recvWatches = ConcurrentHashMap<Long, RecvWatch>()
    private val askedAt = ConcurrentHashMap<Long, Long>()

    private const val WATCH_POLL_MS = 2_000L
    private const val SEND_ACK_MS = 10_000L
    private const val SEND_RETRY_GIVE_UP_MS = 10_000L
    private const val ASK_ANSWER_MS = 90_000L
    private const val SEND_STALL_MS = 45_000L
    private const val RECV_START_MS = 8_000L
    private const val RECV_STALL_MS = 60_000L
    private const val INCOMING_ASK_MS = 5 * 60_000L

    private fun checkWatches() {
        val now = System.currentTimeMillis()
        for ((mac, w) in sendWatches) {
            val cur = _outgoing.value[mac]
            if (cur == null || !cur.active) { sendWatches.remove(mac); continue }
            val idle = now - w.lastEventAt
            when {
                !w.acked && !w.retried && idle > SEND_ACK_MS -> {
                    Log.w(TAG, "send to $mac: no sign of life after ${idle / 1000}s; trying once more")
                    w.retried = true; w.lastEventAt = now
                    wakeReceiver(wakeFor(mac))
                    dispatchSend(mac, w.uris, w.text.orEmpty())
                }
                !w.acked && w.retried && idle > SEND_RETRY_GIVE_UP_MS ->
                    failSend(mac, ctx.getString(R.string.err_send_noresponse))
                cur.state == SendState.Asking && w.acked && idle > ASK_ANSWER_MS ->
                    failSend(mac, ctx.getString(R.string.err_no_answer))
                cur.state == SendState.Sending && idle > SEND_STALL_MS ->
                    failSend(mac, ctx.getString(R.string.err_stalled))
            }
        }
        for ((id, w) in recvWatches) {
            val item = _incoming.value.firstOrNull { it.id == id }
            if (item == null || item.state != ReceiveState.Receiving) { recvWatches.remove(id); continue }
            val idle = now - w.lastActivityAt
            if (item.received == 0L && !w.retried && idle > RECV_START_MS) {
                Log.w(TAG, "accepted $id but no data after ${idle / 1000}s; answering again")
                w.retried = true
                sendAnswer(id, true)
            } else if (idle > RECV_STALL_MS || (item.received == 0L && w.retried && idle > RECV_START_MS * 3)) {
                Log.w(TAG, "receive $id stalled")
                recvWatches.remove(id)
                _incoming.update { l -> l.map { if (it.id == id) it.copy(state = ReceiveState.Failed, reason = ctx.getString(R.string.err_recv_not_started)) else it } }
                Notifier.cancelAsk(id)
            }
        }
        for ((id, at) in askedAt) {
            if (now - at < INCOMING_ASK_MS) continue
            askedAt.remove(id)
            val item = _incoming.value.firstOrNull { it.id == id }
            if (item != null && item.state == ReceiveState.Asking) {
                _incoming.update { l -> l.filterNot { it.id == id } }
                Notifier.cancelAsk(id)
            }
        }
    }

    private fun failSend(mac: String, why: String) {
        Log.w(TAG, "send to $mac failed: $why")
        sendWatches.remove(mac)
        askModule(Intent().putExtra("cmd", "cancel").putExtra("mac", mac))
        BleWakeAdvertiser.stop(wakeFor(mac))
        stagedOutgoing.remove(mac)?.deleteRecursively()
        _outgoing.update { it + (mac to Outgoing(mac, SendState.Failed, error = why)) }
        updateFastSendMarker()
    }

    private fun cleanStaleStaging() = runCatching {
        val cutoff = System.currentTimeMillis() - 6 * 3_600_000L
        File(ctx.cacheDir, "outgoing").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    @Volatile private var lastSeenAt = System.currentTimeMillis()

    private const val REVIVE_POLL_MS = 10_000L
    private const val DAEMON_QUIET_MS = 20_000L
    private const val REVIVE_GAP_MS = 60_000L
    private const val TAG = "AirDrop"
    private const val AIRDROP_KEY = "airdrop_on"

    fun markRadioInstalled() = prefs.edit().putBoolean("radio_installed", true).apply()

    private val prefs get() = ctx.getSharedPreferences("airdrop", Context.MODE_PRIVATE)

    fun setAirDrop(on: Boolean) {
        _airdrop.value = on
        prefs.edit().putBoolean(AIRDROP_KEY, on).apply()
        if (!on) discover(false)
        Thread({
            if (on) RadioInstaller.start(enableAirDrop = true) else RadioInstaller.setAirDropMode(false)
            refreshStatus()
        }, "airdrop-airdrop").start()
    }

    private fun islandModuleSeen(): Boolean =
        runCatching { Settings.Global.getString(ctx.contentResolver, ISLAND_STAMP_KEY) }
            .getOrNull()?.toLongOrNull() != null

    private fun islandSeen() = _status.value.moduleActive || islandModuleSeen()

    private fun askModule(i: Intent) {
        ctx.sendBroadcast(i.setAction(Ipc.ACTION_REQUEST).setPackage(Ipc.SHARE_PKG).putExtra("token", Ipc.appToken(ctx)))
    }

    private const val DISCOVER_GAP_MS = 20_000L

    fun discover(on: Boolean, wake: String = WAKE_DISCOVER) {
        if (on) wakeReceiver(wake) else sleepReceiver(wake)
        val now = System.currentTimeMillis()
        if (on == lastDiscoverOn && now - lastDiscoverAt < DISCOVER_GAP_MS) return
        lastDiscoverAt = now
        lastDiscoverOn = on
        _discovering.value = on
        if (!on) _peers.value = emptyList()
        askModule(Intent().putExtra("cmd", "discover").putExtra("on", on))
    }

    fun rescan() {
        lastDiscoverAt = 0
        lastDiscoverOn = false
        discover(true)
    }

    private const val WAKE_DISCOVER = "discover"

    private fun wakeFor(mac: String) = "send:$mac"

    private fun fastSendMarker() = File(ctx.filesDir, "pad2-fast-send")

    @Synchronized private fun updateFastSendMarker() {
        if (!RadioInstaller.isMt6991()) return
        val marker = fastSendMarker()
        val sending = _outgoing.value.values.any { it.state == SendState.Sending }
        if (!sending) {
            marker.delete()
            lastFastMarkerUpdate = 0L
        } else if (System.currentTimeMillis() - lastFastMarkerUpdate >= 5_000L || !marker.exists()) {
            runCatching { marker.writeText("sending\n") }
                .onFailure { Log.w(TAG, "could not update send-rate marker", it) }
            lastFastMarkerUpdate = System.currentTimeMillis()
        }
    }

    fun wakeReceiver(reason: String, holdMs: Long = 0) {
        BleWakeAdvertiser.start(reason, holdMs)
    }

    fun sleepReceiver(reason: String) {
        BleWakeAdvertiser.stop(reason)
    }

    private const val WAKE_ASKED_MS = 60_000L

    @Volatile private var lastDiscoverAt = 0L
    @Volatile private var lastDiscoverOn = false

    fun answer(id: Long, accept: Boolean) {
        sendAnswer(id, accept)
        askedAt.remove(id)
        if (accept) recvWatches[id] = RecvWatch(System.currentTimeMillis())
        _incoming.update { l ->
            l.map {
                if (it.id != id) it
                else if (accept) it.copy(state = ReceiveState.Receiving) else it.copy(state = ReceiveState.Declined)
            }
        }
        Notifier.cancelAsk(id)
    }

    private fun sendAnswer(id: Long, accept: Boolean) {
        if (islandSeen()) {
            askModule(Intent().putExtra("cmd", "answer").putExtra("id", id).putExtra("accept", accept))
        } else {
            client.send(Cmd.answer(id, accept))
        }
    }

    fun refreshStatus() = client.send(Cmd.status())

    private val cancelledAt = ConcurrentHashMap<String, Long>()

    fun cancel(mac: String) {
        sendWatches.remove(mac)
        BleWakeAdvertiser.stop(wakeFor(mac))
        askModule(Intent().putExtra("cmd", "cancel").putExtra("mac", mac))
        cancelledAt[mac] = System.currentTimeMillis()
        _outgoing.update { m -> if (m[mac]?.active == true) m + (mac to Outgoing(mac, SendState.Cancelled)) else m }
        stagedOutgoing.remove(mac)?.deleteRecursively()
        updateFastSendMarker()
    }

    fun refreshInbox() = client.send(Cmd.inbox())

    fun setCoex(on: Boolean) {
        _coexRequestFailed.value = false
        client.send(Cmd.setCoex(on)) { _coexRequestFailed.value = true }
        client.send(Cmd.status(), ttlMs = 0)
    }

    fun openShareSettings() = client.send(Cmd.openShareSettings())

    fun dismiss(id: Long) = _incoming.update { l -> l.filterNot { it.id == id } }

    fun send(mac: String, uris: List<Uri>, text: String?) {
        val t = text?.trim().orEmpty()
        if (uris.isEmpty() && t.isEmpty()) return
        _sent.update {
            it + (mac to SentItem(
                names = uris.map { u -> u.lastPathSegment?.substringAfterLast('/').orEmpty() }.filter { n -> n.isNotEmpty() },
                uri = uris.lastOrNull()?.toString(),
                url = t.takeIf { s -> Regex("^https?://\\S+$").matches(s) },
                at = System.currentTimeMillis(),
                mime = uris.lastOrNull()?.let { u -> runCatching { ctx.contentResolver.getType(u) }.getOrNull() },
            ))
        }
        _outgoing.update { it + (mac to Outgoing(mac, SendState.Asking)) }
        wakeReceiver(wakeFor(mac))
        sendWatches[mac] = SendWatch(uris, t.ifEmpty { null }, System.currentTimeMillis())
        dispatchSend(mac, uris, t)
    }

    private fun dispatchSend(mac: String, uris: List<Uri>, t: String) {
        Thread({
            val box = File(ctx.cacheDir, "outgoing/${UUID.randomUUID()}")
            try {
                val staged = uris.mapIndexed { index, uri ->
                    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { "shared-file" }
                    val dir = File(box, index.toString()).apply { mkdirs() }
                    val file = File(dir, name)
                    val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open shared file")
                    input.use { source -> file.outputStream().use { source.copyTo(it) } }
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.sharefiles", file)
                }
                val i = Intent().putExtra("cmd", "send").putExtra("mac", mac).putExtra("text", t)
                if (staged.isNotEmpty()) {
                    staged.forEach { ctx.grantUriPermission(Ipc.SHARE_PKG, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val clip = ClipData.newRawUri("files", staged.first())
                    staged.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    i.clipData = clip
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                stagedOutgoing.put(mac, box)?.deleteRecursively()
                askModule(i)
            } catch (e: Exception) {
                Log.w(TAG, "could not prepare outgoing files", e)
                box.deleteRecursively()
                sendWatches.remove(mac)
                _outgoing.update { it + (mac to Outgoing(mac, SendState.Failed, error = ctx.getString(R.string.err_read_file))) }
                sleepReceiver(wakeFor(mac))
            }
        }, "airdrop-prepare-send").start()
    }

    fun forget(mac: String) {
        sendWatches.remove(mac)
        BleWakeAdvertiser.stop(wakeFor(mac))
        _outgoing.update { it - mac }
    }

    fun onRelay(json: String) {
        Log.i(TAG, "relayed from the module: $json")
        runCatching { onEvent(parseEvent(JSONObject(json))) }
            .onFailure { Log.w(TAG, "bad relayed event: $json ($it)") }
    }

    private fun onConnected(up: Boolean) {
        _connected.value = up
        if (up) {
            lastSeenAt = System.currentTimeMillis()
            pushPolicy()
            if (_discovering.value) askModule(Intent().putExtra("cmd", "discover").putExtra("on", true))
        }
    }

    private fun onEvent(e: Event) {
        lastSeenAt = System.currentTimeMillis()
        when (e) {
            is Event.Peers -> {
                if (e.peers.size != _peers.value.size || e.peers != _peers.value) {
                    Log.i(TAG, "device list: ${e.peers.size} near: " + e.peers.joinToString { "${it.name}/${it.mac}/${it.model}" })
                }
                _peers.value = e.peers
            }
            is Event.Transfer -> {
                sendWatches[e.out.mac]?.let { w -> w.lastEventAt = System.currentTimeMillis(); w.acked = true }
                val before = _outgoing.value[e.out.mac]
                if (before != null && !before.active && e.out.state == SendState.Asking) return
                if (e.out.active && System.currentTimeMillis() - (cancelledAt[e.out.mac] ?: 0L) < 4_000L) return
                _outgoing.update { it + (e.out.mac to e.out) }
                updateFastSendMarker()
                if (!e.out.active) {
                    sendWatches.remove(e.out.mac)
                    BleWakeAdvertiser.stop(wakeFor(e.out.mac))
                    stagedOutgoing.remove(e.out.mac)?.deleteRecursively()
                }
            }
            is Event.IncomingAsk -> {
                askedAt.putIfAbsent(e.incoming.id, System.currentTimeMillis())
                _incoming.update { listOf(e.incoming) + it.filterNot { i -> i.id == e.incoming.id } }
                Notifier.ask(e.incoming)
            }
            is Event.Receiving -> {
                recvWatches[e.id]?.lastActivityAt = System.currentTimeMillis()
                _incoming.update { l ->
                    l.map {
                        if (it.id == e.id) it.copy(state = ReceiveState.Receiving, received = e.received, total = e.total.takeIf { t -> t > 0 } ?: it.total)
                        else it
                    }
                }
                _incoming.value.firstOrNull { it.id == e.id }?.let(Notifier::progress)
            }
            is Event.Received -> {
                recvWatches.remove(e.id); askedAt.remove(e.id)
                _incoming.update { l -> l.filterNot { it.id == e.id } }
                Notifier.cancelAsk(e.id)
                Log.i(TAG, "received: ${e.paths.size} file(s) ${e.paths.joinToString { it.substringAfterLast('/') }}")
                refreshInbox()
                autoOpen(e.id) {
                    if (e.paths.size == 1) Received.openReceived(ctx, e.paths[0], background = true) else Received.openFolder(ctx, background = true)
                }
            }
            is Event.ShowFile -> {
                Log.i(TAG, "the module asked for the file to be shown: ${e.path}")
                Received.openReceived(ctx, e.path)
            }
            is Event.UrlsReceived -> {
                _incoming.update { l -> l.filterNot { it.id == e.id } }
                Notifier.cancelAsk(e.id)
                autoOpen(e.id) {
                    val url = e.urls.firstOrNull() ?: return@autoOpen
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { ctx.startActivity(i) }.isFailure) RadioInstaller.viewAsRoot(url, "text/html")
                }
            }
            is Event.Withdrawn -> {
                recvWatches.remove(e.id); askedAt.remove(e.id)
                _incoming.update { l -> l.filterNot { it.id == e.id } }
                Notifier.cancelAsk(e.id)
            }
            is Event.Failed -> {
                recvWatches.remove(e.id); askedAt.remove(e.id)
                _incoming.update { l ->
                    l.map { if (it.id == e.id) it.copy(state = ReceiveState.Failed, reason = e.reason) else it }
                }
                Notifier.cancelAsk(e.id)
            }
            is Event.Status -> _status.value = e
            is Event.Inbox -> _inbox.value = e.files.sortedByDescending { it.modified }
            is Event.Wake -> {
                Log.i(TAG, "the module asked for a receiver to be woken; advertising the AirDrop BLE wake")
                wakeReceiver("wake", WAKE_ASKED_MS)
            }
            Event.Unknown -> Unit
        }
    }

    private val opened = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    private fun autoOpen(id: Long, open: () -> Unit) {
        if (!autoOpenReceived || !opened.add(id)) return
        Thread({ runCatching(open).onFailure { Log.w(TAG, "could not open what was received", it) } }, "airdrop-open").start()
    }

    var autoOpenReceived: Boolean
        get() = prefs.getBoolean("auto_open", true)
        set(v) = prefs.edit().putBoolean("auto_open", v).apply()

    private val URL = Regex("^https?://\\S+$")
}
