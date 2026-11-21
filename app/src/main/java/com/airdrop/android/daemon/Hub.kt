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
import com.airdrop.android.KeepAliveService
import com.airdrop.android.Notifier
import com.airdrop.android.R
import com.airdrop.android.Received
import com.airdrop.android.ble.BleWakeAdvertiser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject

const val ISLAND_STAMP_KEY = "airdrop_island_stamp"

const val SHARE_STATE_KEY = "oshare_state"

object Hub {
    private lateinit var ctx: Context
    private val client = DaemonClient(::onConnected, ::onEvent, detectStall = true)

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

    private val lsposedBusy = java.util.concurrent.atomic.AtomicBoolean()
    private val rootBusy = java.util.concurrent.atomic.AtomicBoolean()

    /** These are polled from the UI; a slow root shell must not pile up one blocked thread per poll. */
    private fun once(busy: java.util.concurrent.atomic.AtomicBoolean, name: String, work: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        Thread({ try { work() } finally { busy.set(false) } }, name).start()
    }

    fun refreshLsposed() = once(lsposedBusy, "airdrop-lsposed") { refreshLsposedNow() }

    @Volatile private var lsposedGrantTried = false

    /**
     * Reads the module's LSPosed state and, when LSPosed is there but the module is off or not scoped to the
     * share app, turns it on and scopes it (once per app run), then restarts the share app to load it.
     */
    private fun refreshLsposedNow() {
        var info = RadioInstaller.lsposed(ctx)
        if (info.dbFound && !(info.known && info.enabled && info.scoped) && !lsposedGrantTried) {
            lsposedGrantTried = true
            Log.i(TAG, "LSPosed: module known=${info.known} enabled=${info.enabled} scoped=${info.scoped}; granting")
            if (RadioInstaller.grantLsposed(ctx)) {
                info = RadioInstaller.lsposed(ctx)
                if (info.known && info.enabled && info.scoped) RadioInstaller.restartShareApp()
            }
        }
        _lsposed.value = info
    }

    private val _moduleVersion = MutableStateFlow<String?>(null)
    val moduleVersion: StateFlow<String?> = _moduleVersion

    fun refreshRoot() = once(rootBusy, "airdrop-root") {
        val ok = RadioInstaller.hasRoot()
        _rootOk.value = ok
        _moduleVersion.value = if (ok) RadioInstaller.installedVersion() else null
    }

    fun uninstallService(onDone: (String?) -> Unit) {
        Thread({
            val err = RadioInstaller.uninstall(ctx)
            if (err == null) {
                _airdrop.value = false
                _discovering.value = false
                _peers.value = emptyList()
                _status.value = Event.Status()
                syncKeepAlive()
                checkHealthNow()
            }
            onDone(err)
        }, "airdrop-uninstall").start()
    }

    private val _status = MutableStateFlow(Event.Status())

    val status: StateFlow<Event.Status> = _status

    private val _airdrop = MutableStateFlow(false)
    val airdrop: StateFlow<Boolean> = _airdrop

    enum class WifiPolicy { KeepWifi, Yield, StopWifi }

    private val _policy = MutableStateFlow(WifiPolicy.Yield)
    val policy: StateFlow<WifiPolicy> = _policy

    fun setPolicy(p: WifiPolicy) {
        _policy.value = p
        prefs.edit().putString(POLICY_KEY, p.name).apply()
        pushPolicy()
    }

    private fun pushPolicy() {
        askModule(Intent().putExtra("cmd", "policy").putExtra("yield", _policy.value == WifiPolicy.Yield))
        // Idle on 5 GHz channel 149 unless the user chose to keep Wi-Fi undisturbed. On 2.4 GHz channel 6 an
        // iPhone gives AirDrop one or two of its sixteen slots, and its request took 5-6 s to arrive instead of 1-2.
        Thread({ RadioInstaller.syncChannelMarker(_policy.value != WifiPolicy.KeepWifi) }, "airdrop-policy-marker").start()
    }

    private val policyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun startPolicyEngine() {
        policyScope.launch {
            combine(_policy, _status, _airdrop, _connected) { p, st, on, up ->
                p == WifiPolicy.KeepWifi && on && up && st.coexAvailable && !st.coexWanted
            }.distinctUntilChanged().collect { needOn -> if (needOn) setCoex(true) }
        }
        policyScope.launch {
            combine(_policy, _incoming, _outgoing) { p, inc, out ->
                p == WifiPolicy.StopWifi &&
                    (inc.any { it.state == ReceiveState.Receiving } || out.values.any { it.state == SendState.Sending })
            }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    val wifiOn = runCatching { Settings.Global.getInt(ctx.contentResolver, "wifi_on", 0) }.getOrDefault(0) == 1
                    if (wifiOn) {
                        prefs.edit().putBoolean(WIFI_OFF_BY_US, true).apply()
                        RadioInstaller.setWifi(false)
                    }
                } else if (prefs.getBoolean(WIFI_OFF_BY_US, false)) {
                    delay(3_000)
                    prefs.edit().putBoolean(WIFI_OFF_BY_US, false).apply()
                    RadioInstaller.setWifi(true)
                }
            }
        }
        policyScope.launch {
            combine(_incoming, _outgoing) { inc, out ->
                inc.any { it.state == ReceiveState.Receiving } || out.values.any { it.active }
            }.distinctUntilChanged().collect { busy -> holdAwake(busy) }
        }
        policyScope.launch { _airdrop.collect { syncKeepAlive() } }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** A transfer must not stall because the screen went off and the CPU went to sleep. */
    @Synchronized private fun holdAwake(busy: Boolean) {
        runCatching {
            val lock = wakeLock ?: ctx.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AirDrop:transfer")
                .apply { setReferenceCounted(false) }.also { wakeLock = it }
            if (busy) lock.acquire(WAKE_LOCK_MAX_MS) else if (lock.isHeld) lock.release()
        }.onFailure { Log.w(TAG, "wake lock: $it") }
    }

    private const val WAKE_LOCK_MAX_MS = 2 * 60 * 60_000L

    /** Runs the foreground service exactly while AirDrop is on and there is a radio to look after. */
    fun syncKeepAlive() {
        if (!::ctx.isInitialized) return
        KeepAliveService.sync(ctx, _airdrop.value && prefs.getBoolean("radio_installed", false))
    }

    private const val POLICY_KEY = "wifi_policy"
    private const val WIFI_OFF_BY_US = "wifi_off_by_us"

    fun init(app: Context) {
        if (::ctx.isInitialized) return
        ctx = app.applicationContext
        _policy.value = runCatching { WifiPolicy.valueOf(prefs.getString(POLICY_KEY, null) ?: "") }.getOrDefault(WifiPolicy.Yield)
            // "Turn Wi-Fi off during transfers" is gone: turning Wi-Fi off mid-send took AirDrop's radio down with it.
            .let { if (it == WifiPolicy.StopWifi) WifiPolicy.Yield else it }
        startPolicyEngine()
        if (!prefs.contains(AIRDROP_KEY)) {
            val already = runCatching { Settings.System.getInt(ctx.contentResolver, SHARE_STATE_KEY, -1) }.getOrDefault(-1) == 1
            prefs.edit().putBoolean(AIRDROP_KEY, already).apply()
        }
        _airdrop.value = prefs.getBoolean(AIRDROP_KEY, false)
        if (RadioInstaller.isMt6991()) fastSendMarker().delete()
        BleWakeAdvertiser.attach(ctx)
        Notifier.init(ctx)
        Thread({
            // In this order: the foreground-service type depends on the Bluetooth permission, and a start
            // refused in the background is allowed once the app is exempt.
            runCatching { BleWakeAdvertiser.ensurePermissions(ctx) }
            runCatching { RadioInstaller.protectApp(ctx.packageName) }
            // The share app hosts the module that raises the receive card; keep the system from restricting it.
            runCatching { RadioInstaller.protectApp(Ipc.SHARE_PKG) }
            syncKeepAlive()
            runCatching { RadioInstaller.provisionToken(ctx, Ipc.appToken(ctx)) }
        }, "airdrop-setup").start()
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
        Thread {
            while (true) {
                try { Thread.sleep(SESSION_GUARD_MS) } catch (_: InterruptedException) { return@Thread }
                runCatching { guardSession() }.onFailure { Log.w(TAG, "session guard failed: $it") }
            }
        }.apply { isDaemon = true; name = "airdrop-session-guard" }.start()
        // Always running, whether AirDrop is on or off: the radio may be installed after this process started,
        // and a broken service should be found and fixed before the user turns AirDrop on, not after.
        Thread {
            try { Thread.sleep(HEALTH_FIRST_MS) } catch (_: InterruptedException) { return@Thread }
            while (true) {
                runCatching { healthPass() }.onFailure { Log.w(TAG, "health check failed: $it") }
                val gap = if (_airdrop.value) HEALTH_POLL_ON_MS else HEALTH_POLL_OFF_MS
                try { healthKick.tryAcquire(gap, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return@Thread }
                healthKick.drainPermits()
            }
        }.apply { isDaemon = true; name = "airdrop-health" }.start()
    }

    /** Whether everything a receive needs is in place, checked whether AirDrop is on or off. */
    enum class Health { Checking, Ready, Starting, NoRoot, NotInstalled, Outdated, RadioDown, ShareNotResponding }

    private val _health = MutableStateFlow(Health.Checking)
    val health: StateFlow<Health> = _health

    private val healthKick = java.util.concurrent.Semaphore(0)

    /** Runs a health pass now instead of at the next interval; called when the app comes to the foreground. */
    fun checkHealthNow() {
        if (::ctx.isInitialized) healthKick.release()
    }

    @Volatile private var startedAt = 0L
    @Volatile private var startAttempts = 0
    @Volatile private var lastSupervised = System.currentTimeMillis()

    private fun healthPass() {
        val root = _rootOk.value.takeIf { it == true } ?: RadioInstaller.hasRoot().also { _rootOk.value = it }
        if (!root) { _health.value = Health.NoRoot; return }

        if (!prefs.getBoolean("radio_installed", false)) {
            // Installed by an older build, or app data was cleared: the radio is there, the flag is not.
            if (RadioInstaller.installedVersion() != null) markRadioInstalled()
            else { _health.value = Health.NotInstalled; return }
        }

        val now = System.currentTimeMillis()
        if (!_connected.value) {
            // The watchdog rebuilds the stack by itself, and a toggle asks it for a full rebuild. Starting
            // the service on top of that runs two bring-ups at once, which fail each other and turn a few
            // seconds into minutes. Only a stack with no watchdog at all is started from here.
            val settling = now - lastToggleAt < TOGGLE_SETTLE_MS || now - lastSeenAt < DOWN_GRACE_MS
            if (settling || RadioInstaller.installing || RadioInstaller.stackAlive()) {
                _health.value = if (startAttempts > 2) Health.RadioDown else Health.Starting
                return
            }
            if (now - startedAt > START_GAP_MS) {
                startedAt = now
                lastSupervised = now
                startAttempts++
                Log.i(TAG, "the AirDrop service is not running; starting it (attempt $startAttempts)")
                _health.value = if (startAttempts > 2) Health.RadioDown else Health.Starting
                RadioInstaller.start(enableAirDrop = _airdrop.value)
                Thread({ Thread.sleep(START_SETTLE_MS); checkHealthNow() }, "airdrop-health-kick").start()
                return
            }
            _health.value = if (startAttempts > 2) Health.RadioDown else Health.Starting
            return
        }
        startAttempts = 0
        if (_airdrop.value && now - lastSupervised > SUPERVISE_GAP_MS) {
            // The daemon can outlive its watchdog; then nothing rebuilds the radio when it next drops.
            lastSupervised = now
            RadioInstaller.ensureSupervised()
        }

        val ok = if (radioOutdated()) Health.Outdated else Health.Ready
        if (moduleAnswers()) { _health.value = ok; return }
        // Killed, or frozen so deep the broadcast did not reach it: launch it as root and ask again.
        Log.w(TAG, "the share app did not answer; waking it")
        RadioInstaller.wakeShareApp()
        if (moduleAnswers(SHARE_WAKE_WAIT_MS)) { _health.value = ok; return }
        _health.value = Health.ShareNotResponding
    }

    private val shareRestartBusy = java.util.concurrent.atomic.AtomicBoolean()
    private val _restartingShare = MutableStateFlow(false)
    val restartingShare: StateFlow<Boolean> = _restartingShare

    /** Force-stops OnePlus Share and starts it again, so a freshly enabled or stuck module is loaded anew. */
    fun restartShareApp() = once(shareRestartBusy, "airdrop-share-restart") {
        _restartingShare.value = true
        try {
            Log.i(TAG, "restarting the share app on request")
            RadioInstaller.restartShareApp()
            moduleAnswers(SHARE_WAKE_WAIT_MS)
            refreshLsposedNow()
        } finally {
            _restartingShare.value = false
            checkHealthNow()
        }
    }

    @Volatile private var lastSessionRefresh = 0L
    @Volatile private var lastPeersAt = 0L
    @Volatile private var panelSearching = false
    @Volatile private var lastOutgoingAt = 0L
    private const val SEARCH_QUIET_MS = 60_000L

    /** Restarts the AirDrop session when it has gone stale, or as idle insurance; see [SessionGuard]. */
    private fun guardSession() {
        if (!_airdrop.value || !_connected.value || !_status.value.on) return
        val now = System.currentTimeMillis()
        // Our own search for iPhones (the share panel) and our own sends also bring iPhone data in, with no
        // lookup from the iPhone to answer; that is not a stale session.
        val searching = _discovering.value || (panelSearching && now - lastPeersAt < 10 * 60_000L) || now - lastPeersAt < SEARCH_QUIET_MS || now - lastOutgoingAt < SEARCH_QUIET_MS
        val busy = searching || _incoming.value.any { it.state == ReceiveState.Asking || it.state == ReceiveState.Receiving } ||
            _outgoing.value.values.any { it.active }
        val snap = SessionGuard.parse(RadioInstaller.daemonLogTail())
        val up = snap.sessionUpAt ?: return
        val (flowing, sinceData) = SessionGuard.dataFlow(snap.rxSamples, now)
        val action = SessionGuard.decide(
            busy = busy,
            sessionAgeMs = now - up,
            sinceRefreshMs = now - lastSessionRefresh,
            dataSinceMs = flowing,
            sinceSeenMs = snap.lastSeenAt?.let { now - it },
            sinceDataMs = sinceData,
        )
        if (action == SessionGuard.Action.None) return
        lastSessionRefresh = now
        lastToggleAt = now // the brief 0 is ours; the switch must not follow it
        Log.i(TAG, if (action == SessionGuard.Action.RefreshStale)
            "an iPhone is talking to us but its lookups go unanswered; restarting the session"
        else "refreshing the idle AirDrop session")
        RadioInstaller.cycleShareState()
    }

    /** Whether the running service is older than the one this app carries. */
    private fun radioOutdated(): Boolean {
        val bundled = RadioInstaller.bundledVersion(ctx) ?: return false
        // The installed package's version, as the status list shows it. The daemon's own status reports a
        // different number (its binary's), which never equals the package version.
        val running = RadioInstaller.installedVersion() ?: _status.value.version
        _moduleVersion.value = running
        return running.isNotBlank() && running != bundled
    }

    /** Pings the module and waits for its answer, which arrives as a relay. */
    private fun moduleAnswers(waitMs: Long = PING_WAIT_MS): Boolean {
        pingModule()
        val sent = pingSentAt
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            if (moduleSeenAt >= sent) return true
            Thread.sleep(100)
        }
        return moduleSeenAt >= sent
    }

    private const val SESSION_GUARD_MS = 5_000L
    private const val HEALTH_FIRST_MS = 1_500L
    private const val HEALTH_POLL_ON_MS = 30_000L
    private const val HEALTH_POLL_OFF_MS = 90_000L
    private const val START_GAP_MS = 30_000L
    private const val START_SETTLE_MS = 6_000L
    private const val PING_WAIT_MS = 2_500L
    private const val SHARE_WAKE_WAIT_MS = 6_000L
    private const val TOGGLE_SETTLE_MS = 90_000L
    private const val DOWN_GRACE_MS = 10_000L

    private class SendWatch(
        @Volatile var lastEventAt: Long, @Volatile var acked: Boolean = false,
        val preparation: SendPreparation = SendPreparation(),
    )
    private val sendWatches = ConcurrentHashMap<String, SendWatch>()

    private class RecvWatch(@Volatile var lastActivityAt: Long, @Volatile var retried: Boolean = false)
    private val recvWatches = ConcurrentHashMap<Long, RecvWatch>()
    private val askedAt = ConcurrentHashMap<Long, Long>()

    private const val WATCH_POLL_MS = 2_000L
    private const val INCOMING_ASK_MS = 5 * 60_000L

    @Synchronized private fun checkWatches() {
        val now = System.currentTimeMillis()
        for ((mac, w) in sendWatches) {
            val cur = _outgoing.value[mac]
            if (cur == null || !cur.active) { sendWatches.remove(mac)?.preparation?.cancel(); continue }
            if (w.preparation.dispatchedAt == null) continue
            when (Watchdog.sendTimeout(cur.state, w.acked, now - w.lastEventAt)) {
                Watchdog.SendTimeout.NoResponse -> failSend(mac, ctx.getString(R.string.err_send_noresponse))
                Watchdog.SendTimeout.NoAnswer -> failSend(mac, ctx.getString(R.string.err_no_answer))
                Watchdog.SendTimeout.Stalled -> failSend(mac, ctx.getString(R.string.err_stalled))
                null -> Unit
            }
        }
        for ((id, w) in recvWatches) {
            val item = _incoming.value.firstOrNull { it.id == id }
            if (item == null || item.state != ReceiveState.Receiving) { recvWatches.remove(id); continue }
            val idle = now - w.lastActivityAt
            when (Watchdog.recvAction(item.received, w.retried, idle)) {
                Watchdog.RecvAction.AnswerAgain -> {
                    Log.w(TAG, "accepted $id but no data after ${idle / 1000}s; answering again")
                    w.retried = true
                    sendAnswer(id, true, retry = true)
                }
                Watchdog.RecvAction.Fail -> {
                    Log.w(TAG, "receive $id stalled")
                    recvWatches.remove(id)
                    _incoming.update { l -> l.map { if (it.id == id) it.copy(state = ReceiveState.Failed, reason = ctx.getString(R.string.err_recv_not_started)) else it } }
                    Notifier.cancelAsk(id)
                }
                null -> Unit
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

    @Synchronized private fun failSend(mac: String, why: String) {
        Log.w(TAG, "send to $mac failed: $why")
        cancelledAt[mac] = System.currentTimeMillis()
        sendWatches.remove(mac)?.preparation?.cancel()
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

    private const val SUPERVISE_GAP_MS = 3 * 60_000L
    private const val TAG = "AirDrop"
    private const val AIRDROP_KEY = "airdrop_on"

    fun markRadioInstalled() {
        if (prefs.getBoolean("radio_installed", false)) return
        prefs.edit().putBoolean("radio_installed", true).apply()
        syncKeepAlive()
    }

    private val prefs get() = ctx.getSharedPreferences("airdrop", Context.MODE_PRIVATE)

    fun setAirDrop(on: Boolean) {
        lastToggleAt = System.currentTimeMillis()
        _airdrop.value = on
        prefs.edit().putBoolean(AIRDROP_KEY, on).apply()
        if (!on) discover(false)
        Thread({
            if (on) {
                // A forced restart always takes the slow (heavy) rebuild, about 30 s. A healthy stack comes up
                // by itself in a few seconds once the share mode is on, so only force one after a failure.
                if (_status.value.failed) RadioInstaller.requestRestart()
                RadioInstaller.start(enableAirDrop = true)
            } else RadioInstaller.setAirDropMode(false)
            refreshStatus()
            checkHealthNow()
        }, "airdrop-airdrop").start()
    }

    private fun islandModuleSeen(): Boolean =
        runCatching { Settings.Global.getString(ctx.contentResolver, ISLAND_STAMP_KEY) }
            .getOrNull()?.toLongOrNull() != null

    private fun islandSeen() = _status.value.moduleActive || islandModuleSeen()

    /**
     * Commands to the module are foreground broadcasts. The share app sits in the background almost all the
     * time; Android 14+ holds ordinary broadcasts to a cached process back for up to two minutes, while a
     * foreground one is delivered at once and thaws the process to do it.
     */
    private fun askModule(i: Intent) {
        ctx.sendBroadcast(
            i.setAction(Ipc.ACTION_REQUEST).setPackage(Ipc.SHARE_PKG)
                .putExtra("token", Ipc.appToken(ctx))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
    }

    /** Wakes the module: thaws the share app if it is frozen and makes it re-check its daemon connection. */
    private fun pingModule() {
        pingSentAt = System.currentTimeMillis()
        askModule(Intent().putExtra("cmd", "ping"))
    }

    @Volatile private var pingSentAt = 0L

    /**
     * When the module inside the share app last proved it is running. Commands to it are broadcasts to a
     * receiver that only exists while that process lives; if it is gone they vanish without an error.
     */
    @Volatile private var moduleSeenAt = 0L

    private fun moduleAlive(withinMs: Long = MODULE_ALIVE_MS) = System.currentTimeMillis() - moduleSeenAt < withinMs

    private const val MODULE_ALIVE_MS = 60_000L

    private const val DISCOVER_GAP_MS = 20_000L

    fun discover(on: Boolean, wake: String = WAKE_DISCOVER) {
        if (on) wakeReceiver(wake) else sleepReceiver(wake)
        val now = System.currentTimeMillis()
        if (on == lastDiscoverOn && now - lastDiscoverAt < DISCOVER_GAP_MS) return
        lastDiscoverAt = now
        lastDiscoverOn = on
        _discovering.value = on
        if (!on) _peers.value = emptyList()
        askDiscover(on)
    }

    /** Only the module may ask the daemon to discover; the daemon refuses ("a client may not ask Discover") the app. */
    private fun askDiscover(on: Boolean) {
        askModule(Intent().putExtra("cmd", "discover").putExtra("on", on))
    }

    fun rescan() {
        lastDiscoverAt = 0
        lastDiscoverOn = false
        discover(true)
    }

    private const val WAKE_DISCOVER = "discover"

    private fun wakeFor(mac: String) = "send:$mac"

    private fun fastSendMarker() = File(ctx.filesDir, "fast-send")

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

    private fun sendAnswer(id: Long, accept: Boolean, retry: Boolean = false) {
        // Only the share app's process may answer: the daemon refuses an Answer from this app
        // ("a client may not ask Answer"). The command reaches the module as a foreground broadcast, which
        // is delivered at once and thaws the share app if it was frozen.
        if (islandSeen()) askModule(Intent().putExtra("cmd", "answer").putExtra("id", id).putExtra("accept", accept))
        if (!islandSeen() || retry) client.send(Cmd.answer(id, accept))
    }

    fun refreshStatus() = client.send(Cmd.status())

    private val cancelledAt = ConcurrentHashMap<String, Long>()

    @Synchronized fun cancel(mac: String) {
        sendWatches.remove(mac)?.preparation?.cancel()
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

    @Synchronized fun send(mac: String, uris: List<Uri>, text: String?) {
        if (_outgoing.value[mac]?.active == true) return
        cancelledAt.remove(mac)
        val t = text?.trim().orEmpty()
        if (uris.isEmpty() && t.isEmpty()) return
        _sent.update {
            it + (mac to SentItem(
                names = uris.map { u -> u.lastPathSegment?.substringAfterLast('/').orEmpty() }.filter { n -> n.isNotEmpty() },
                uri = uris.lastOrNull()?.toString(),
                url = t.takeIf(::isWebUrl),
                at = System.currentTimeMillis(),
                mime = uris.lastOrNull()?.let { u -> runCatching { ctx.contentResolver.getType(u) }.getOrNull() },
            ))
        }
        _outgoing.update { it + (mac to Outgoing(mac, SendState.Asking)) }
        wakeReceiver(wakeFor(mac))
        val watch = SendWatch(0L)
        sendWatches[mac] = watch
        dispatchSend(mac, uris, t, watch)
    }

    private fun dispatchSend(mac: String, uris: List<Uri>, t: String, watch: SendWatch) {
        Thread({
            val box = File(ctx.cacheDir, "outgoing/${UUID.randomUUID()}")
            try {
                val staged = uris.mapIndexed { index, uri ->
                    watch.preparation.checkActive()
                    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { "shared-file" }
                    val dir = File(box, index.toString()).apply { mkdirs() }
                    val file = File(dir, name)
                    val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open shared file")
                    input.use { source -> file.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            watch.preparation.checkActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            watch.preparation.checkActive()
                            output.write(buffer, 0, count)
                        }
                    } }
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
                synchronized(this@Hub) {
                    if (sendWatches[mac] !== watch) {
                        box.deleteRecursively()
                        return@Thread
                    }
                    val published = watch.preparation.dispatch(System.currentTimeMillis()) {
                        watch.lastEventAt = watch.preparation.dispatchedAt!!
                        stagedOutgoing.put(mac, box)?.deleteRecursively()
                        askModule(i)
                    }
                    if (!published) box.deleteRecursively()
                }
            } catch (e: Exception) {
                box.deleteRecursively()
                synchronized(this@Hub) {
                    if (sendWatches[mac] === watch) {
                        Log.w(TAG, "could not prepare outgoing files", e)
                        sendWatches.remove(mac)?.preparation?.cancel()
                        stagedOutgoing.remove(mac, box)
                        _outgoing.update { it + (mac to Outgoing(mac, SendState.Failed, error = ctx.getString(R.string.err_read_file))) }
                        sleepReceiver(wakeFor(mac))
                    }
                }
            }
        }, "airdrop-prepare-send").start()
    }

    private const val COPY_BUFFER = 256 * 1024

    @Synchronized fun forget(mac: String) {
        sendWatches.remove(mac)?.preparation?.cancel()
        BleWakeAdvertiser.stop(wakeFor(mac))
        _outgoing.update { it - mac }
    }

    fun onRelay(json: String) {
        moduleSeenAt = System.currentTimeMillis()
        runCatching {
            val o = JSONObject(json)
            if (o.optString("ev") == "pong") return
            if (o.optString("ev") == "search") {
                panelSearching = o.optBoolean("on")
                lastPeersAt = System.currentTimeMillis()
                return
            }
            onEvent(parseEvent(o), relayed = true)
        }.onFailure { Log.w(TAG, "bad relayed event (${json.length} chars): $it") }
    }

    @Volatile private var linkGeneration = 0

    private fun onConnected(up: Boolean) {
        val generation = ++linkGeneration // only the reader thread calls this
        if (up) {
            _connected.value = true
            lastSeenAt = System.currentTimeMillis()
            markRadioInstalled()
            pushPolicy()
            if (_discovering.value) askDiscover(true)
        } else policyScope.launch {
            // A dropped connection is retried at once; only report the radio gone if that did not work.
            delay(DISCONNECT_GRACE_MS)
            if (linkGeneration != generation) return@launch
            _connected.value = false
            // Nothing below is known any more; showing the last answer would claim a radio that may be gone.
            _status.value = Event.Status()
            _peers.value = emptyList()
            modeOddSince = 0L
        }
    }

    private const val DISCONNECT_GRACE_MS = 1_500L

    /** Asks again for nearby devices while a screen is showing them; a single request can go stale. */
    fun refreshDiscovery() {
        if (_discovering.value) askDiscover(true)
    }

    private val meters = ConcurrentHashMap<String, SpeedMeter>()
    private fun meter(key: String) = meters.getOrPut(key) { SpeedMeter() }

    /** When the module last relayed send progress for a peer; its figure is what actually left the radio. */
    private val relayedProgressAt = ConcurrentHashMap<String, Long>()

    /** Receives that have ended. Events reach us twice (socket and module) and a late copy must not revive one. */
    private val ended = object : java.util.LinkedHashMap<Long, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 64
    }
    private fun end(id: Long) { synchronized(ended) { ended[id] = true }; meters.remove("in:$id") }
    private fun hasEnded(id: Long) = synchronized(ended) { ended.containsKey(id) }

    @Volatile private var lastToggleAt = 0L
    private var modeOddSince = 0L
    private val modeFixes = ArrayDeque<Long>()

    /**
     * Keeps the switch and the system's share mode telling the same story. The share app drops "everyone"
     * back to "contacts only" by itself, which silently takes AirDrop off the air while the switch stays on.
     */
    private fun keepMode(st: Event.Status) {
        val now = System.currentTimeMillis()
        while (modeFixes.isNotEmpty() && now - modeFixes.first() > ShareMode.FIX_WINDOW_MS) modeFixes.removeFirst()
        val odd = if (modeOddSince == 0L) null else now - modeOddSince
        when (ShareMode.decide(_airdrop.value, st.share, odd, now - lastToggleAt, modeFixes.size)) {
            ShareMode.Action.Agree -> modeOddSince = 0L
            ShareMode.Action.Wait -> if (modeOddSince == 0L) modeOddSince = now
            ShareMode.Action.FollowOn -> {
                modeOddSince = 0L
                Log.i(TAG, "the system share mode is 'everyone'; the switch follows it")
                _airdrop.value = true
                prefs.edit().putBoolean(AIRDROP_KEY, true).apply()
            }
            ShareMode.Action.FollowOff -> {
                modeOddSince = 0L
                Log.i(TAG, "sharing was turned off in the system; the switch follows it")
                _airdrop.value = false
                prefs.edit().putBoolean(AIRDROP_KEY, false).apply()
                discover(false)
            }
            ShareMode.Action.Restore -> {
                modeOddSince = 0L
                modeFixes.addLast(now)
                Log.i(TAG, "share mode fell back to ${st.share} while AirDrop is on; restoring 'everyone'")
                Thread({ RadioInstaller.setAirDropMode(true) }, "airdrop-mode").start()
            }
            ShareMode.Action.GiveUp -> {
                modeOddSince = 0L
                Log.w(TAG, "share mode keeps leaving 'everyone' (now ${st.share}); not forcing it again for a while")
            }
        }
    }

    private fun onEvent(e: Event, relayed: Boolean = false) {
        when (e) {
            is Event.Peers -> lastPeersAt = System.currentTimeMillis()
            is Event.Transfer -> lastOutgoingAt = System.currentTimeMillis()
            else -> Unit
        }
        lastSeenAt = System.currentTimeMillis()
        when (e) {
            is Event.Peers -> {
                if (e.peers.size != _peers.value.size || e.peers != _peers.value) {
                    Log.i(TAG, "device list: ${e.peers.size} near: " + e.peers.joinToString { "${it.name}/${it.mac}/${it.model}" })
                }
                _peers.value = e.peers
            }
            is Event.Transfer -> synchronized(this) {
                if (cancelledAt.containsKey(e.out.mac) && !sendWatches.containsKey(e.out.mac)) return
                if (sendWatches[e.out.mac]?.preparation?.dispatchedAt == null && sendWatches.containsKey(e.out.mac)) return
                sendWatches[e.out.mac]?.let { w -> w.lastEventAt = System.currentTimeMillis(); w.acked = true }
                val before = _outgoing.value[e.out.mac]
                if (before != null && !before.active && e.out.state == SendState.Asking) return
                val now = System.currentTimeMillis()
                if (e.out.active && now - (cancelledAt[e.out.mac] ?: 0L) < 4_000L) return
                var out = e.out
                if (out.state == SendState.Sending) {
                    // The same progress arrives from the socket and from the module with different byte
                    // counts; taking both makes the bar jump back and forth.
                    if (relayed) relayedProgressAt[out.mac] = now
                    else if (now - (relayedProgressAt[out.mac] ?: 0L) < 3_000L) return
                    if (before?.state == SendState.Sending && before.total == out.total && out.sent < before.sent) {
                        out = out.copy(sent = before.sent)
                    }
                    out = out.copy(speed = meter("out:${out.mac}").update(now, out.sent))
                } else {
                    meters.remove("out:${out.mac}")
                    relayedProgressAt.remove(out.mac)
                }
                _outgoing.update { it + (out.mac to out) }
                updateFastSendMarker()
                if (!e.out.active) {
                    sendWatches.remove(e.out.mac)?.preparation?.cancel()
                    BleWakeAdvertiser.stop(wakeFor(e.out.mac))
                    stagedOutgoing.remove(e.out.mac)?.deleteRecursively()
                }
            }
            is Event.IncomingAsk -> {
                if (hasEnded(e.incoming.id)) return
                // The second copy of a request must not put an accepted transfer back to "asking".
                if (_incoming.value.any { it.id == e.incoming.id && it.state != ReceiveState.Asking }) return
                // Heard here first: the share app may be frozen with the same request sitting unread in its
                // socket. Wake it now so the system card comes up together with this notification.
                if (!relayed && askedAt[e.incoming.id] == null) pingModule()
                askedAt.putIfAbsent(e.incoming.id, System.currentTimeMillis())
                _incoming.update { listOf(e.incoming) + it.filterNot { i -> i.id == e.incoming.id } }
                Notifier.ask(e.incoming)
            }
            is Event.Receiving -> {
                if (hasEnded(e.id)) return
                val now = System.currentTimeMillis()
                val speed = meter("in:${e.id}").update(now, e.received)
                // Accepted on the system card, or before this process existed: there is no entry yet.
                recvWatches.getOrPut(e.id) { RecvWatch(now) }.lastActivityAt = now
                askedAt.remove(e.id)
                // The second copy of a progress line changes nothing; skip the redraw and the notification.
                if (_incoming.value.any { it.id == e.id && it.state == ReceiveState.Receiving && it.received == e.received }) return
                _incoming.update { l ->
                    if (l.none { it.id == e.id }) {
                        listOf(Incoming(e.id, ctx.getString(R.string.apple_device), total = e.total, state = ReceiveState.Receiving, received = e.received, speed = speed)) + l
                    } else l.map {
                        if (it.id == e.id) it.copy(state = ReceiveState.Receiving, received = e.received, total = e.total.takeIf { t -> t > 0 } ?: it.total, speed = speed)
                        else it
                    }
                }
                _incoming.value.firstOrNull { it.id == e.id }?.let(Notifier::progress)
            }
            is Event.Received -> {
                end(e.id)
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
                end(e.id)
                recvWatches.remove(e.id); askedAt.remove(e.id)
                _incoming.update { l -> l.filterNot { it.id == e.id } }
                Notifier.cancelAsk(e.id)
                autoOpen(e.id) {
                    // Links come from a nearby device: only web links are ever opened, never other schemes.
                    val url = e.urls.firstOrNull(::isWebUrl)
                    if (url == null) {
                        Log.w(TAG, "received link is not a web address; not opening it")
                        return@autoOpen
                    }
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { ctx.startActivity(i) }.isFailure) RadioInstaller.viewAsRoot(url, "text/html")
                }
            }
            is Event.Withdrawn -> {
                end(e.id)
                recvWatches.remove(e.id); askedAt.remove(e.id)
                _incoming.update { l -> l.filterNot { it.id == e.id } }
                Notifier.cancelAsk(e.id)
            }
            is Event.Failed -> {
                end(e.id)
                recvWatches.remove(e.id); askedAt.remove(e.id)
                _incoming.update { l ->
                    l.map { if (it.id == e.id) it.copy(state = ReceiveState.Failed, reason = e.reason) else it }
                }
                Notifier.cancelAsk(e.id)
            }
            is Event.Status -> {
                _status.value = e
                keepMode(e)
            }
            is Event.Inbox -> _inbox.value = e.files.sortedByDescending { it.modified }
            is Event.Wake -> {
                Log.i(TAG, "the module asked for a receiver to be woken; advertising the AirDrop BLE wake")
                wakeReceiver("wake", WAKE_ASKED_MS)
            }
            Event.Unknown -> Unit
        }
    }

    private val opened = object : java.util.LinkedHashMap<Long, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 64
    }

    private fun autoOpen(id: Long, open: () -> Unit) {
        if (!autoOpenReceived || synchronized(opened) { opened.put(id, true) } != null) return
        Thread({ runCatching(open).onFailure { Log.w(TAG, "could not open what was received", it) } }, "airdrop-open").start()
    }

    var autoOpenReceived: Boolean
        get() = prefs.getBoolean("auto_open", true)
        set(v) = prefs.edit().putBoolean("auto_open", v).apply()
}
