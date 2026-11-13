package com.airdrop.android.xposed

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.airdrop.android.daemon.Cmd
import com.airdrop.android.daemon.DaemonClient
import com.airdrop.android.daemon.Event
import com.airdrop.android.daemon.ISLAND_STAMP_KEY
import com.airdrop.android.daemon.Incoming
import com.airdrop.android.daemon.Outgoing
import com.airdrop.android.daemon.SendState
import com.airdrop.android.daemon.Ipc
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object Island {
    private const val ACTIVITY = "com.oplus.oshare.ui.ReceiveDialogActivity"

    private const val S_ASK = 0
    private const val S_PROGRESS = 1
    private const val S_DONE = 2
    private const val S_CANCELLED = 4
    private const val S_FAILED = 5

    private lateinit var module: Module
    private lateinit var app: Application
    private val live = ConcurrentHashMap<Long, Incoming>()
    @Volatile private var current: Long = -1
    @Volatile private var lastPercent = -1
    private lateinit var client: DaemonClient

    @Volatile private var sendCard: SendCard? = null
    private class SendTrack(val task: String, var lastPct: Int = -1)
    private val sendTracks = ConcurrentHashMap<String, SendTrack>()
    private val peerNames = ConcurrentHashMap<String, String>()

    private fun onSendEvent(o: Outgoing) {
        val c = sendCard ?: return
        val track = sendTracks[o.mac]
        when {
            o.active -> {
                activeSeen = true
                if (track == null) {
                    val t = SendTrack("airdrop-send-${o.mac}-${System.currentTimeMillis()}")
                    sendTracks[o.mac] = t
                    c.begin(app, t.task, peerNames[o.mac] ?: o.mac, 1)
                    module.log(android.util.Log.INFO, Module.TAG, "send card up for ${o.mac}", null)
                } else if (o.state == SendState.Sending && o.total > 0) {
                    val pct = (o.sent * 100 / o.total).toInt().coerceIn(0, 100)
                    if (pct != track.lastPct) { track.lastPct = pct; c.progress(app, track.task, pct) }
                }
            }
            track != null -> {
                sendTracks.remove(o.mac)
                when (o.state) {
                    SendState.Delivered -> { c.progress(app, track.task, 100); c.finished(app, track.task) }
                    SendState.Declined -> c.declined(app, track.task)
                    SendState.Cancelled -> c.cancelled(app, track.task)
                    else -> c.failed(app, track.task)
                }
            }
        }
    }

    @Volatile private var yieldSends = true
    @Volatile private var coexWantedNow = false
    @Volatile private var yielded = false
    @Volatile private var restoreAt = 0L
    @Volatile private var statusAt = 0L
    @Volatile private var activeSeen = false
    @Volatile var flipping = false
        private set
    @Volatile private var dispatchedAt = 0L
    @Volatile private var sawDownSince = 0L

    private fun setYield(on: Boolean) {
        yieldSends = on
        runCatching { app.getSharedPreferences("airdrop_bridge", Context.MODE_PRIVATE).edit().putBoolean("yield", on).apply() }
    }

    fun dispatchSend(mac: String, paths: List<String>, urls: List<String>, allowYield: Boolean = true, onUnreachable: () -> Unit) {
        if (allowYield && yieldSends && coexWantedNow) {
            try {
                module.log(android.util.Log.INFO, Module.TAG, "Wi-Fi steps aside for this send", null)
                yielded = true
                flipping = true
                activeSeen = false
                dispatchedAt = System.currentTimeMillis()
                client.send(Cmd.setCoex(false), ttlMs = 10_000L)
                val t0 = System.currentTimeMillis()
                sawDownSince = 0L
                while (System.currentTimeMillis() - t0 < 45_000L) {
                    val waited = System.currentTimeMillis() - t0
                    if (statusAt > t0 && sessionOn == true && !coexWantedNow && (sawDownSince >= t0 || waited > 15_000L)) break
                    client.send(Cmd.status(), ttlMs = 0)
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
                try { Thread.sleep(4_000) } catch (_: InterruptedException) { }
                flipping = false

            } finally { flipping = false }
        }
        client.send(Cmd.send(mac, paths, urls), ttlMs = 10_000L, onUnreachable)
    }

    private fun maybeRestoreCoex() {
        if (!yielded) return
        if (sendTracks.isNotEmpty() || live.isNotEmpty()) return
        if (!activeSeen && System.currentTimeMillis() - dispatchedAt < 90_000L) return
        val at = System.currentTimeMillis()
        restoreAt = at
        android.os.Handler(worker.looper).postDelayed({
            if (restoreAt == at && yielded && (activeSeen || System.currentTimeMillis() - dispatchedAt >= 90_000L) && sendTracks.isEmpty() && live.isEmpty()) {
                yielded = false
                module.log(android.util.Log.INFO, Module.TAG, "transfer over; Wi-Fi coexistence back on", null)
                client.send(Cmd.setCoex(true), ttlMs = 10_000L)
            }
        }, 3_000L)
    }

    fun cancelSendCard(mac: String) {
        val t = sendTracks.remove(mac) ?: return
        runCatching { sendCard?.cancelled(app, t.task) }
    }

    private fun cancelSends(): Boolean {
        val c = sendCard ?: return false
        if (sendTracks.isEmpty()) return false
        for ((mac, t) in sendTracks.entries.toList()) {
            sendTracks.remove(mac)
            client.send(Cmd.cancel(mac))
            runCatching { c.cancelled(app, t.task) }
        }
        return true
    }

    fun install(m: Module, application: Application, loader: ClassLoader) {
        module = m; app = application
        WireProgress.init(application)
        yieldSends = runCatching { app.getSharedPreferences("airdrop_bridge", Context.MODE_PRIVATE).getBoolean("yield", true) }.getOrDefault(true)
        stamp()
        val bridge = openBridge(loader)
        try {
            setupCards(loader, bridge)
        } catch (t: Throwable) {
            m.log(android.util.Log.ERROR, Module.TAG, "card setup failed", t)
        }
        client = DaemonClient(onConnected = ::onDaemonConnection, onEvent = ::onEvent, onRaw = ::relay)
        client.start()
        cleanStaleSendDirs()
        try { SharePanel.install(m, app, loader, client, bridge) } catch (t: Throwable) { m.log(android.util.Log.ERROR, Module.TAG, "share panel setup failed", t) }
        runCatching { bridge?.close() }
        try { listenForApp() } catch (t: Throwable) { m.log(android.util.Log.ERROR, Module.TAG, "app bridge failed", t) }
        try { holdShareStateAtSource() } catch (t: Throwable) { m.log(android.util.Log.ERROR, Module.TAG, "share-state hook failed", t) }
        try { keepSessionAlive() } catch (t: Throwable) { m.log(android.util.Log.ERROR, Module.TAG, "session keeper failed", t) }
    }

    @Volatile private var wantDiscover = false

    private fun onDaemonConnection(up: Boolean) {
        SharePanel.onConnection(up)
        if (!up) {
            coexActive = false
            sawDownSince = System.currentTimeMillis()
            return
        }
        if (wantDiscover) {
            module.log(android.util.Log.INFO, Module.TAG, "daemon is back; asking for discovery again", null)
            client.send(Cmd.discover(true))
        }
        client.send(Cmd.status())
    }

    private fun cleanStaleSendDirs() = runCatching {
        val cutoff = System.currentTimeMillis() - 6 * 3_600_000L
        File(app.cacheDir, "owly-send").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private var sessionOn: Boolean? = null
    @Volatile private var coexActive = false
    private val pad2 = com.airdrop.android.daemon.RadioInstaller.isMt6991()
    private var lastNudge = 0L
    private var lastWriteLog = 0L
    private var lastEvent: String? = null
    private var lastEventAt = 0L

    @Volatile private var lastReceived: String? = null

    @Volatile private var lastReceivedAt = 0L

    @Volatile private var lastCardOpenAt = 0L

    @Volatile private var lastShareSeen = -1

    @Volatile private var keepUntil = 0L

    private fun inFlight() = System.currentTimeMillis() < keepUntil

    private fun keepSession(ms: Long) {
        keepUntil = maxOf(keepUntil, System.currentTimeMillis() + ms)
    }

    private fun holdShareStateAtSource() {
        val sys = android.provider.Settings.System::class.java
        val crClass = android.content.ContentResolver::class.java
        val int = Int::class.javaPrimitiveType
        val specs = listOfNotNull(
            runCatching { sys.getDeclaredMethod("putInt", crClass, String::class.java, int) }.getOrNull(),
            runCatching { sys.getDeclaredMethod("putIntForUser", crClass, String::class.java, int, int) }.getOrNull(),
            runCatching { sys.getDeclaredMethod("putString", crClass, String::class.java, String::class.java) }.getOrNull(),
            runCatching { sys.getDeclaredMethod("putStringForUser", crClass, String::class.java, String::class.java, int) }.getOrNull(),
        )
        for (m in specs) {
            module.hook(m).intercept(XposedInterface.Hooker { chain ->
                if (chain.getArg(1) != SHARE_STATE_KEY) return@Hooker chain.proceed()
                val v = chain.getArg(2)
                val shown = v?.toString()
                noteWriter(shown)
                if ((shown == "2" || v == null) && inFlight()) {
                    module.log(
                        android.util.Log.INFO, Module.TAG,
                        "mode -> ${shown ?: "cleared"} during a transfer, holding 1 (${writer()})", null,
                    )
                    writeShareState(chain.getArg(0) as android.content.ContentResolver, 1)
                    true
                } else {
                    chain.proceed()
                }
            })
            module.log(android.util.Log.INFO, Module.TAG, "hooked ${m.name} for $SHARE_STATE_KEY", null)
        }
    }

    private fun noteWriter(shown: String?) {
        val now = System.currentTimeMillis()
        if (shown == "1" || now - lastWriteLog < 5_000) return
        lastWriteLog = now
        module.log(
            android.util.Log.INFO, Module.TAG,
            "receive mode -> ${shown ?: "cleared"} by ${writer()}", null,
        )
    }

    private fun writer(): String = Throwable().stackTrace
        .filter { !it.className.startsWith("com.airdrop.android") && !it.className.startsWith("java.") && !it.className.startsWith("android.") }
        .take(3)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        .ifEmpty { "unknown" }

    private fun writeShareState(cr: android.content.ContentResolver, v: Int) {
        runCatching { android.provider.Settings.System.putInt(cr, SHARE_STATE_KEY, v) }
    }

    private val shareState get() = runCatching {
        android.provider.Settings.System.getInt(app.contentResolver, SHARE_STATE_KEY, -1)
    }.getOrDefault(-1)

    private fun keepSessionAlive() {
        val cr = app.contentResolver
        val looper = worker.looper
        val observer = object : android.database.ContentObserver(android.os.Handler(looper)) {
            override fun onChange(selfChange: Boolean) {
                checkSession()
            }
        }
        cr.registerContentObserver(android.provider.Settings.System.getUriFor(SHARE_STATE_KEY), false, observer)
        val timer = android.os.Handler(looper)
        timer.post(object : Runnable {
            override fun run() {
                checkSession()
                timer.postDelayed(this, 5_000)
            }
        })
        client.send(Cmd.status())
    }

    private fun checkSession() {
        lastShareSeen = shareState
        when (lastShareSeen) {
            2 -> if (inFlight()) {
                module.log(android.util.Log.INFO, Module.TAG, "mode dropped to contacts mid-transfer, holding 1", null)
                putShareState(1)
            }
            1 -> if (!pad2 && sessionOn == false && System.currentTimeMillis() - lastNudge > NUDGE_GAP_MS) {
                lastNudge = System.currentTimeMillis()
                module.log(android.util.Log.INFO, Module.TAG, "no session, riding the 0 -> 1 edge", null)
                nudgeSession()
            }
        }
    }

    private fun nudgeSession() {
        putShareState(0)
        android.os.Handler(worker.looper).postDelayed({
            var tries = 0
            while (tries < 4) {
                putShareState(1)
                if (shareState == 1) return@postDelayed
                tries++
                module.log(android.util.Log.WARN, Module.TAG, "the share mode came back as ${shareState} after the nudge; retry $tries", null)
                runCatching { Thread.sleep(1_000) }
            }
            module.log(android.util.Log.ERROR, Module.TAG, "could not put the share mode back to 1; AirDrop stays off until the user turns it on", null)
        }, 1_000)
    }

    private fun putShareState(v: Int) {
        runCatching {
            android.provider.Settings.System.putInt(app.contentResolver, SHARE_STATE_KEY, v)
        }.onFailure { module.log(android.util.Log.WARN, Module.TAG, "cannot write $SHARE_STATE_KEY=$v", it) }
    }

    private val appPkg get() = module.moduleApplicationInfo.packageName

    private const val APP_NS_FALLBACK = "com.airdrop.android"

    private fun appNamespace(): String =
        runCatching { app.packageManager.getLaunchIntentForPackage(appPkg)?.component?.className }
            .getOrNull()
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: APP_NS_FALLBACK

    private val worker by lazy { android.os.HandlerThread("airdrop-bridge").apply { start() } }

    private fun listenForApp() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val sender = if (android.os.Build.VERSION.SDK_INT >= 34) sentFromUid else -1
                module.log(
                    android.util.Log.INFO,
                    Module.TAG,
                    "app command cmd=${i.getStringExtra("cmd")} from uid $sender (app uid ${appUid()})",
                    null,
                )
                if (!fromApp(sender, i)) return
                when (i.getStringExtra("cmd")) {
                    "discover" -> {
                        val on = i.getBooleanExtra("on", false)
                        module.log(android.util.Log.INFO, Module.TAG, "app asked to discover=$on", null)
                        wantDiscover = on
                        client.send(Cmd.discover(on), ttlMs = 15_000L)
                    }
                    "cancel" -> i.getStringExtra("mac")?.let { client.send(Cmd.cancel(it)) }
                    "policy" -> setYield(i.getBooleanExtra("yield", true))
                    "send" -> handleSend(i)
                    "answer" -> {
                        val id = i.getLongExtra("id", -1)
                        val yes = i.getBooleanExtra("accept", false)
                        module.log(android.util.Log.INFO, Module.TAG, "answering the daemon for id=$id accept=$yes", null)
                        client.send(Cmd.answer(id, yes), ttlMs = 15_000L) {
                            module.log(android.util.Log.WARN, Module.TAG, "answer for id=$id never reached the daemon", null)
                        }
                    }
                    "open" -> relayShow(i.getStringExtra("name"), i.getBooleanExtra("folder", false))
                }
            }
        }
        ContextCompat.registerReceiver(
            app, r, android.content.IntentFilter(Ipc.ACTION_REQUEST),
            null, android.os.Handler(worker.looper), ContextCompat.RECEIVER_EXPORTED,
        )

        module.log(android.util.Log.INFO, Module.TAG, "bridge ready ($BUILD)", null)
    }

    private const val BUILD = "fix12-reliability"

    private const val SHARE_STATE_KEY = "oshare_state"

    private const val NUDGE_GAP_MS = 60_000L

    private const val ASK_HOLD_MS = 3 * 60_000L

    private const val TRANSFER_HOLD_MS = 60_000L

    private const val VIEW_WINDOW_MS = 10 * 60_000L

    private fun appUid(): Int = try {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageUid(appPkg, 0)
    } catch (_: Exception) {
        -2
    }

    private fun tokenOk(given: String?): Boolean {
        val mine = Ipc.moduleToken(app) ?: return false
        return given != null && java.security.MessageDigest.isEqual(given.toByteArray(), mine.toByteArray())
    }

    private fun fromApp(sentFromUid: Int, i: Intent): Boolean {
        return tokenOk(i.getStringExtra("token"))
    }

    private val stager = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "airdrop-stage").apply { isDaemon = true }
    }

    private fun failSend(mac: String, why: String) = relay(
        org.json.JSONObject().put("ev", "transfer").put("mac", mac).put("state", "failed").put("error", why).toString(),
    )

    private fun handleSend(i: Intent) {
        val mac = i.getStringExtra("mac") ?: return
        val clip = i.clipData
        val uris = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        val text = i.getStringExtra("text").orEmpty()
        relay(org.json.JSONObject().put("ev", "transfer").put("mac", mac).put("state", "asking").toString())
        stager.execute {
            val box = File(app.cacheDir, "owly-send/${System.currentTimeMillis()}").apply { mkdirs() }
            val paths = ArrayList<String>()
            try {
                for (uri in uris) {
                    val f = uniqueFile(box, nameOf(uri))
                    val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open shared file")
                    input.use { s -> f.outputStream().use { o -> s.copyTo(o) } }
                    paths += f.path
                }
            } catch (t: Throwable) {
                module.log(android.util.Log.WARN, Module.TAG, "can't read the shared files", t)
                box.deleteRecursively()
                failSend(mac, "Could not read the shared file")
                return@execute
            }
            val urls = ArrayList<String>()
            if (text.isNotEmpty()) {
                if (Regex("^https?://\\S+$").matches(text)) urls += text
                else paths += File(box, "text.txt").apply { writeText(text) }.path
            }
            if (paths.isEmpty() && urls.isEmpty()) {
                box.deleteRecursively()
                failSend(mac, "Nothing to send")
                return@execute
            }
            dispatchSend(mac, paths, urls) {
                module.log(android.util.Log.WARN, Module.TAG, "send to $mac never reached the daemon", null)
                failSend(mac, "The AirDrop service is not responding")
            }
        }
    }

    private fun nameOf(u: android.net.Uri): String {
        app.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { n -> return n.replace('/', '_') }
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); var n = 1
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$stem ($n)$ext").also { n++ }
        return f
    }

    private val RELAYED = setOf(
        "peers", "transfer", "incoming", "receiving", "received", "urls", "withdrawn", "failed", "show", "wake",
    )

    private val lastRelayAt = ConcurrentHashMap<String, Long>()
    @Volatile private var lastRelayLog = 0L
    @Volatile private var lastOutgoingLog = 0L

    private fun relay(line: String) {
        try {
            val ev = org.json.JSONObject(line).optString("ev")
            if (ev == "transfer" || ev == "receiving") {
                val j = org.json.JSONObject(line)
                val key = ev + ":" + j.optString("mac", j.optString("id"))
                val st = j.optString("state")
                val total = j.optLong("total")
                val done = total > 0 && j.optLong(if (ev == "transfer") "sent" else "received") >= total
                val progress = (ev == "receiving") || st == "sending"
                val now = System.currentTimeMillis()
                if (progress && !done && now - (lastRelayAt[key] ?: 0L) < 250L) return
                lastRelayAt[key] = now
                if (!progress || now - lastRelayLog > 2_000L) {
                    lastRelayLog = now
                    module.log(android.util.Log.INFO, Module.TAG, "daemon -> module: $line", null)
                }
            } else {
                module.log(android.util.Log.INFO, Module.TAG, "daemon -> module: $line", null)
            }
            if (ev !in RELAYED) return
            var line = line
            if (ev == "transfer") {
                val j = org.json.JSONObject(line)
                val st = j.optString("state")
                val mac = j.optString("mac")
                val total = j.optLong("total")
                val fixed = WireProgress.sent(mac, st == "asking" || st == "sending", st == "sending", j.optLong("sent"), total)
                line = j.put("sent", fixed).toString()
            }
            val i = Intent(Ipc.ACTION_EVENT)
                .setPackage(appPkg)
                .setClassName(appPkg, "${appNamespace()}.RelayReceiver")
                .putExtra(Ipc.EXTRA_JSON, line)
                .putExtra(Ipc.EXTRA_TOKEN, Ipc.moduleToken(app) ?: return)
            app.sendBroadcast(i)
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, Module.TAG, "could not relay $line", t)
        }
    }

    @Volatile private var card: LiveCard? = null

    private fun openBridge(loader: ClassLoader): DexKitBridge? = try {
        System.load(File(module.moduleApplicationInfo.nativeLibraryDir, "libdexkit.so").path)
        DexKitBridge.create(loader, true)
    } catch (t: Throwable) {
        module.log(android.util.Log.WARN, Module.TAG, "DexKit unavailable, using known names", t); null
    }

    private fun setupCards(loader: ClassLoader, bridge: DexKitBridge?) {
        try { hookShareStateCorrection(loader, bridge) } catch (t: Throwable) {
            module.log(android.util.Log.WARN, Module.TAG, "share-state correction hook failed", t)
        }
        try {
            card = try { LiveCard.find(loader, bridge) } catch (t: Throwable) {
                module.log(android.util.Log.WARN, Module.TAG, "live card lookup failed", t); null
            }
            sendCard = try { SendCard.find(loader, bridge) } catch (t: Throwable) {
                module.log(android.util.Log.WARN, Module.TAG, "send card lookup failed", t); null
            }
            module.log(android.util.Log.INFO, Module.TAG, if (sendCard != null) "using the send card" else "send card not found", null)
            if (card == null && sendCard != null) hookCardButtons(loader)
            if (card != null) {
                hookCardButtons(loader)
                CardProbe.install(module, app, loader)
                CardDataProbe.install(module, loader)
                module.log(android.util.Log.INFO, Module.TAG, "using the live-activity card", null)
            } else {
                module.log(android.util.Log.WARN, Module.TAG, "live card not found, falling back to the full-screen dialog", null)
                if (bridge != null) {
                    hookByString(bridge, loader, "oshare_receive_ack_ok", accept = true)
                    hookByString(bridge, loader, "oshare_receive_reject", accept = false)
                    hookByString(bridge, loader, "oshare_receive_cancel", accept = false)
                }
            }
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, Module.TAG, "card setup incomplete", t)
        }
    }

    private fun hookCardButtons(loader: ClassLoader) {
        val call = loader.loadClass(LiveCard.PROVIDER).getDeclaredMethod(
            "call", String::class.java, String::class.java, android.os.Bundle::class.java,
        )
        module.hook(call).intercept(XposedInterface.Hooker { chain ->
            val method = runCatching { chain.getArg(0) as? String }.getOrNull()
            val arg = runCatching { chain.getArg(1) as? String }.getOrNull()
            val extras = runCatching { chain.getArg(2) as? android.os.Bundle }.getOrNull()
            val event = extras?.getString("event")
            val id = current
            val waiting = id >= 0 && live.containsKey(id)

            module.log(
                android.util.Log.INFO, Module.TAG,
                "card call method=$method arg=$arg event=$event waiting=$waiting live=${live.keys} " +
                    "extras=${CardProbe.dumpBundle(extras)}",
                null,
            )

            if (method.equals("pull", ignoreCase = true)) {
                return@Hooker chain.proceed()
            }

            noteCardEvent(event, extras)
            if (event == "cancelTask" && cancelSends()) return@Hooker consumedBundle()
            when {
                event == "accept" && waiting -> {
                    client.send(Cmd.answer(id, true))
                    lastPercent = 0
                    try { card?.percent(app, 0) } catch (t: Throwable) {
                        module.log(android.util.Log.WARN, Module.TAG, "progress card", t)
                    }
                    android.os.Bundle()
                }
                event == "accept" -> {
                    if (openAfterReceive()) {
                        runCatching { card?.done(app) }
                        consumedBundle()
                    } else chain.proceed()
                }
                event == "decline" || event == "cancel" -> {
                    if (waiting) {
                        client.send(Cmd.answer(id, false)); live.remove(id); current = -1
                    }
                    chain.proceed()
                }
                else -> {
                    openAfterReceive()
                    chain.proceed()
                }
            }
        })
    }

    private fun consumedBundle() = android.os.Bundle().apply {
        putBoolean("consumed", true)
        putInt("call_result", 0)
    }

    private fun findStateCorrection(loader: ClassLoader, bridge: DexKitBridge?): Method? {
        val int = Int::class.javaPrimitiveType
        fun ok(m: Method) = java.lang.reflect.Modifier.isStatic(m.modifiers) && m.returnType == int &&
            m.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java, int))
        if (bridge != null) runCatching {
            bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("correctionOShareState: sync global state to")))
                .forEach { hit ->
                    val cls = loader.loadClass(hit.className)
                    cls.declaredMethods.firstOrNull { it.name == hit.methodName && ok(it) }?.let { return it }
                }
        }
        for ((c, n) in listOf("m1" to "f", "r1" to "g")) {
            runCatching {
                loader.loadClass("com.oplus.oshare.utils.$c").declaredMethods.firstOrNull { it.name == n && ok(it) }?.let { return it }
            }
        }
        return null
    }

    private fun hookShareStateCorrection(loader: ClassLoader, bridge: DexKitBridge?) {
        val m = findStateCorrection(loader, bridge) ?: error("state correction method not found")
        module.hook(m).intercept(XposedInterface.Hooker { chain ->
            val asked = chain.getArg(1) as Int
            val corrected = chain.proceed() as Int
            if (coexActive && asked == 1 && corrected != 1) {
                runCatching {
                    val ctx = chain.getArg(0) as android.content.Context
                    android.provider.Settings.System.putInt(ctx.contentResolver, "oshare_state", 1)
                }
                module.log(
                    android.util.Log.INFO, Module.TAG,
                    "honored Everyone while Wi-Fi coexistence is active",
                    null,
                )
                return@Hooker 1
            }
            corrected
        })
        module.log(android.util.Log.INFO, Module.TAG, "share-state correction is hooked", null)
    }

    private fun noteCardEvent(event: String?, extras: android.os.Bundle?) {
        val now = System.currentTimeMillis()
        if (event == null) {
            if (extras != null && now - lastEventAt > 2_000) {
                lastEventAt = now
                module.log(android.util.Log.INFO, Module.TAG, "card tapped, keys=${extras.keySet()}", null)
            }
            return
        }
        if (event == lastEvent && now - lastEventAt < 2_000) return
        lastEvent = event
        lastEventAt = now
        module.log(android.util.Log.INFO, Module.TAG, "card event=$event id=$current", null)
    }

    @Suppress("unused")
    private fun dumpBundle(b: android.os.Bundle): String =
        b.keySet().joinToString(", ") { k ->
            val v = runCatching { b.get(k) }.getOrNull()
            when (v) {
                null -> "$k=null"
                is android.os.Bundle -> "$k=Bundle{${dumpBundle(v)}}"
                is Array<*> -> "$k=${v.javaClass.simpleName}[${v.size}](${v.joinToString(";") { it?.toString().orEmpty() }})"
                else -> "$k=${v.javaClass.simpleName}:${v.toString().take(200)}"
            }
        }

    private fun openAfterReceive(): Boolean {
        val name = lastReceived ?: return false
        val now = System.currentTimeMillis()
        if (now - lastReceivedAt > VIEW_WINDOW_MS) return false
        if (live.isNotEmpty() || current >= 0) return false
        if (now - lastCardOpenAt < 2_000) return false
        lastCardOpenAt = now
        module.log(android.util.Log.INFO, Module.TAG, "card tapped after a receive", null)
        relayShow(name, false)
        return true
    }

    private fun offerOpen(c: LiveCard) {
        val name = lastReceived
        val details = if (name.isNullOrBlank()) "已接收" else "已接收 $name"
        val json = org.json.JSONObject()
            .put("panel_title", "接收完成").put("capsule_title", "已接收").put("detailsText", details)
            .put("accept_text", "開啟").put("reject_text", "關閉").put("isB0Show", true)
            .put("isShowProcess", false).put("showConfirm", true).put("showCancel", true)
            .put("showD1", true).put("isShowLottie", false).put("isE4Show", false)
            .put("accept", "開啟").put("reject", "關閉").put("cancel_text", "開啟").toString()
        val pushed = runCatching { c.pushData(json) }.getOrElse {
            module.log(android.util.Log.WARN, Module.TAG, "cannot offer the card's Open button", it)
            return
        }
        module.log(android.util.Log.INFO, Module.TAG, "offered the card's Open button ($pushed push(es))", null)
    }

    private const val RECEIVED_DIR = "primary:Download/Owly"
    private const val STORAGE = "com.android.externalstorage.documents"

    private fun relayShow(name: String?, folder: Boolean) {
        val path = if (folder || name.isNullOrBlank()) null else "Download/Owly/$name"
        if (path != null) {
            module.log(android.util.Log.INFO, Module.TAG, "asking the app to show $name", null)
            relay(org.json.JSONObject().put("ev", "show").put("path", path).toString())
            return
        }
        val dir = android.provider.DocumentsContract.buildDocumentUri(STORAGE, RECEIVED_DIR)
        if (!showFolder(dir)) {
            module.log(android.util.Log.ERROR, Module.TAG, "cannot show the received folder either", null)
        }
    }

    private fun showFolder(uri: android.net.Uri): Boolean = runCatching {
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setComponent(
                    android.content.ComponentName(
                        "com.android.documentsui", "com.android.documentsui.files.FilesActivity",
                    ),
                )
                .setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        module.log(android.util.Log.WARN, Module.TAG, "cannot open Download/Owly either", it)
        false
    }

    private fun hookByString(bridge: DexKitBridge, loader: ClassLoader, key: String, accept: Boolean) {
        val found = bridge.findMethod(
            FindMethod.create().matcher(MethodMatcher.create().usingStrings(key))
        ).filter { it.className.startsWith(ACTIVITY) }
        if (found.isEmpty()) {
            module.log(android.util.Log.WARN, Module.TAG, "no handler found for $key", null)
            return
        }
        for (md in found) {
            val method: Method = md.getMethodInstance(loader) ?: continue
            module.hook(method).intercept(XposedInterface.Hooker { chain ->
                val id = current
                if (id >= 0 && live.containsKey(id)) {
                    client.send(Cmd.answer(id, accept))
                    if (!accept) { live.remove(id); current = -1 }
                    try { chain.proceed() } catch (t: Throwable) { null }
                } else chain.proceed()
            })
            module.log(android.util.Log.INFO, Module.TAG, "hooked ${md.className}.${md.methodName} for $key", null)
        }
    }

    private fun onEvent(raw: Event) {
        val e = if (raw is Event.Transfer) {
            val o = raw.out
            Event.Transfer(o.copy(sent = WireProgress.sent(o.mac, o.active, o.state == SendState.Sending, o.sent, o.total)))
        } else raw
        SharePanel.onEvent(e)
        try {
            when (e) {
                is Event.IncomingAsk -> {
                    keepSession(ASK_HOLD_MS)
                    val inc = e.incoming
                    live[inc.id] = inc
                    current = inc.id
                    lastPercent = -1
                    val c = card
                    if (c != null) askCard(c, inc) else show(askIntent(inc))
                    module.log(android.util.Log.INFO, Module.TAG, "incoming from ${inc.from}; live card raised", null)
                }
                is Event.Receiving -> {
                    if (e.received > 0) keepSession(TRANSFER_HOLD_MS)
                    val i = live[e.id] ?: return
                    val total = e.total.takeIf { it > 0 } ?: i.total
                    val pct = if (total > 0) (e.received * 100 / total).toInt().coerceIn(0, 100) else 0
                    if (pct != lastPercent) {
                        lastPercent = pct
                        val c = card
                        if (c != null) c.percent(app, pct) else show(stateIntent(S_PROGRESS).putExtra("key_receive_percent", pct))
                    }
                }
                is Event.Received -> {
                    keepUntil = 0
                    lastReceived = e.paths.lastOrNull()?.substringAfterLast('/')
                    lastReceivedAt = System.currentTimeMillis()
                    val i = live.remove(e.id) ?: return
                    if (current == e.id) current = -1
                    val c = card
                    if (c != null) {
                        lastPercent = 100
                        runCatching { c.percent(app, 100) }
                        val finishedAt = lastReceivedAt
                        val h = android.os.Handler(worker.looper)
                        h.postDelayed({ if (lastReceivedAt == finishedAt) runCatching { c.done(app) } }, 700)
                    } else show(stateIntent(S_DONE).putExtra("key_mime_type", mimeOf(e.paths.firstOrNull() ?: (i.singleName ?: ""))))
                }
                is Event.UrlsReceived -> {
                    keepUntil = 0
                    live.remove(e.id) ?: return
                    val c = card
                    if (c != null) c.done(app) else show(stateIntent(S_DONE).putExtra("key_mime_type", "http/*"))
                }
                is Event.Withdrawn -> { keepUntil = 0; if (live.remove(e.id) != null) { val c = card; if (c != null) c.senderCancelled(app) else show(stateIntent(S_CANCELLED)) } }
                is Event.Failed -> { keepUntil = 0; if (live.remove(e.id) != null) { val c = card; if (c != null) c.failed(app) else show(stateIntent(S_FAILED)) } }
                is Event.Transfer -> {
                    keepSession(TRANSFER_HOLD_MS)
                    runCatching { onSendEvent(e.out) }.onFailure { module.log(android.util.Log.WARN, Module.TAG, "send card update failed", it) }
                    maybeRestoreCoex()
                    val nowLog = System.currentTimeMillis()
                    if (e.out.state != SendState.Sending || nowLog - lastOutgoingLog > 2_000L) {
                        lastOutgoingLog = nowLog
                        module.log(android.util.Log.INFO, Module.TAG, "outgoing ${e.out.state} ${e.out.sent}/${e.out.total} to ${e.out.mac}${e.out.error.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}", null)
                    }
                }
                is Event.Peers -> {
                    e.peers.forEach { peerNames[it.mac] = it.name }
                    module.log(
                    android.util.Log.INFO,
                    Module.TAG,
                    "discovery returned ${e.peers.size}: " + e.peers.joinToString { "${it.name}/${it.mac}" },
                    null,
                )
                }
                is Event.Status -> {
                    sessionOn = e.on
                    coexActive = e.coexWanted && e.coexActive
                    coexWantedNow = e.coexWanted
                    statusAt = System.currentTimeMillis()
                }
                else -> Unit
            }
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, Module.TAG, "event failed", t)
        }
    }

    private fun askCard(c: LiveCard, i: Incoming) {
        val names = i.fileNames + i.urls
        val ext = { n: String -> n.substringAfterLast('.', "").lowercase() }
        val images = names.count { ext(it) in setOf("jpg", "jpeg", "png", "heic", "heif", "gif", "webp") }
        val videos = names.count { ext(it) in setOf("mov", "mp4", "m4v", "mkv") }
        val kind = when { images > 0 && images == names.size -> "image"; videos > 0 && videos == names.size -> "video"; else -> "" }
        try {
            c.ask(i.from, i.model, i.count, kind, images, videos) {
                if (live.remove(i.id) != null) { client.send(Cmd.answer(i.id, false)); if (current == i.id) current = -1 }
            }
            c.show(app)
            module.log(android.util.Log.INFO, Module.TAG, "raised the live card for ${i.from}", null)
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, Module.TAG, "live card unavailable, using the dialog", t)
            show(askIntent(i))
        }
    }

    private fun askIntent(i: Incoming) = stateIntent(S_ASK)
        .putExtra("key_sender_name", i.from)
        .putExtra("key_sender_sub_name", i.model)
        .putExtra("key_sender_is_ios", true)
        .putExtra("key_data_name", i.singleName ?: itemsLabel(i.count))
        .putExtra("key_data_num", i.count)
        .putExtra("key_data_size", android.text.format.Formatter.formatFileSize(app, i.total))
        .putExtra("key_mime_type", mimeOf(i.fileNames.firstOrNull() ?: if (i.urls.isNotEmpty()) "x.url" else ""))

    private fun stateIntent(state: Int) = Intent()
        .setClassName(app.packageName, ACTIVITY)
        .putExtra("key_receive_state", state)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun itemsLabel(n: Int): String {
        val l = app.resources.configuration.locales[0]
        return when {
            l.language != "zh" -> "$n items"
            l.country in setOf("CN", "SG") || l.script == "Hans" -> "$n 个项目"
            else -> "$n 個項目"
        }
    }

    private fun show(i: Intent) = app.startActivity(i)

    private fun mimeOf(name: String): String {
        if (name.endsWith(".url")) return "http/*"
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun stamp() {
        try {
            Settings.Global.putString(app.contentResolver, ISLAND_STAMP_KEY, System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            module.log(android.util.Log.WARN, Module.TAG, "could not stamp module state", t)
        }
    }
}
