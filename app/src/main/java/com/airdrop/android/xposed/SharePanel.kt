package com.airdrop.android.xposed

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import com.airdrop.android.ble.BleWakeAdvertiser
import com.airdrop.android.daemon.Cmd
import com.airdrop.android.daemon.DaemonClient
import com.airdrop.android.daemon.Event
import com.airdrop.android.daemon.SendState
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors

object SharePanel {
    private const val PREFIX = "airdrop:"
    private lateinit var app: Application
    private lateinit var module: Module
    private lateinit var client: DaemonClient
    private lateinit var loader: ClassLoader
    private lateinit var wrapper: Class<*>
    private lateinit var extended: Class<*>
    private lateinit var state: Class<*>
    private lateinit var manager: Class<*>
    private val main = Handler(Looper.getMainLooper())
    private val files = Executors.newSingleThreadExecutor()
    private val peers = LinkedHashMap<String, Any>()
    private val phases = HashMap<String, String>()
    private val lastSig = HashMap<String, String>()

    private fun own(name: String, fallback: String, vararg args: Any): String = runCatching {
        val id = app.resources.getIdentifier(name, "string", app.packageName)
        if (id == 0) fallback else if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
    }.getOrDefault(fallback)
    private val outgoing = HashMap<String, File>()
    private val outgoingAt = HashMap<String, Long>()
    private const val STALE_SEND_MS = 3 * 60_000L
    private val adapters = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    @Volatile private var ready = false
    private var scanning = false

    private class Names(val wrapper: Class<*>, val extended: Class<*>, val manager: Class<*>, val service: Class<*>)

    private fun resolve(l: ClassLoader, bridge: DexKitBridge?): Names? {
        val intent = Intent::class.java
        var service: Class<*>? = null; var wrapper: Class<*>? = null
        for (n in 1..24) {
            val c = runCatching { l.loadClass("com.oplus.oshare.OShareClient\$$n") }.getOrNull() ?: continue
            val send = c.declaredMethods.firstOrNull { it.name == "sendData" && it.parameterCount == 2 && it.parameterTypes[0] == intent } ?: continue
            val x = send.parameterTypes[1]
            if (c.declaredMethods.any { it.name == "cancelTask" && it.parameterCount == 1 && it.parameterTypes[0] == x }) { service = c; wrapper = x; break }
        }
        if (service == null || wrapper == null) return null
        fun isExt(c: Class<*>) = c.superclass == wrapper && runCatching { c.getDeclaredConstructor() }.isSuccess
        var extended: Class<*>? = null
        if (bridge != null) runCatching {
            extended = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().superClass(wrapper.name)))
                .mapNotNull { runCatching { l.loadClass(it.name) }.getOrNull() }.firstOrNull { isExt(it) }
        }
        fun isManager(c: Class<*>): Boolean {
            val ms = generateSequence(c) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.toList()
            return ms.any { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == c } &&
                ms.any { it.name == "m" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && it.returnType == wrapper } &&
                ms.any { it.name == "n" && it.parameterCount == 0 && java.util.List::class.java.isAssignableFrom(it.returnType) } &&
                ms.any { it.name == "r" && it.parameterCount == 0 }
        }
        var manager: Class<*>? = null
        if (bridge != null) runCatching {
            manager = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().addMethod(
                        MethodMatcher.create().returnType(wrapper.name).paramTypes(String::class.java.name),
                    ),
                ),
            ).mapNotNull { runCatching { l.loadClass(it.name) }.getOrNull() }.firstOrNull { isManager(it) }
        }
        for (name in listOf("v9.d", "w9.d")) if (extended == null) extended = runCatching { l.loadClass(name) }.getOrNull()?.takeIf { isExt(it) }
        for (name in listOf("v9.a", "w9.a")) if (manager == null) manager = runCatching { l.loadClass(name) }.getOrNull()?.takeIf { isManager(it) }
        return Names(wrapper, extended ?: return null, manager ?: return null, service)
    }

    fun install(m: Module, a: Application, l: ClassLoader, c: DaemonClient, bridge: DexKitBridge?) {
        val version = a.packageManager.getPackageInfo(a.packageName, 0).longVersionCode
        val names = resolve(l, bridge)
        if (names == null) {
            m.log(Log.WARN, Module.TAG, "share panel: this share-app build ($version) is not recognised; panel bridge off", null)
            return
        }
        module = m; app = a; client = c; loader = l
        wrapper = names.wrapper; extended = names.extended; manager = names.manager
        m.log(Log.INFO, Module.TAG, "share panel build $version: device=${wrapper.name} ext=${extended.name} manager=${manager.name}", null)
        state = l.loadClass("com.oplus.oshare.OplusOshareState")
        val adapter = l.loadClass("com.oplus.oshare.ui.DeviceAdapter")
        val activity = l.loadClass("com.oplus.oshare.ui.ShareActivity")
        val service = names.service
        BleWakeAdvertiser.attach(a)
        fun method(type: Class<*>, name: String, vararg args: Class<*>) = type.getDeclaredMethod(name, *args).apply { isAccessible = true }
        val list = method(manager, "n"); val get = method(manager, "m", String::class.java)
        val label = method(adapter, "q", state, wrapper)
        val resume = method(activity, "onResume"); val destroy = method(activity, "onDestroy")
        val sendMethod = method(service, "sendData", Intent::class.java, wrapper)
        val cancel = method(service, "cancelTask", wrapper)
        listOf("a", "n").forEach { method(wrapper, it) }
        listOf("w", "P", "F").forEach { method(wrapper, it, String::class.java) }
        method(wrapper, "O", Int::class.javaPrimitiveType!!); method(wrapper, "I", Any::class.java)
        method(wrapper, "D", Long::class.javaPrimitiveType!!); method(wrapper, "H", Int::class.javaPrimitiveType!!)
        method(manager, "h"); method(manager, "r"); extended.getDeclaredConstructor()
        for (ctor in adapter.declaredConstructors) m.hook(ctor).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed(); synchronized(adapters) { adapters.add(chain.thisObject) }; result
        })
        m.hook(list).intercept(XposedInterface.Hooker { chain ->
            val original = chain.proceed() as? List<*> ?: emptyList<Any>()
            ArrayList(original.filterNotNull().filterNot { key(it).startsWith(PREFIX) }).apply { synchronized(peers) { addAll(peers.values) } }
        })
        m.hook(get).intercept(XposedInterface.Hooker { chain -> synchronized(peers) { peers[chain.getArg(0) as? String] } ?: chain.proceed() })
        m.hook(label).intercept(XposedInterface.Hooker { chain ->
            val device = chain.getArg(1); val key = key(device)
            if (!key.startsWith(PREFIX)) chain.proceed() else synchronized(phases) { phases[key] } ?: "AirDrop"
        })
        m.hook(resume).intercept(XposedInterface.Hooker { chain -> val result = chain.proceed(); main.post { startScan() }; result })
        m.hook(destroy).intercept(XposedInterface.Hooker { chain ->
            if (!(chain.thisObject as Activity).isChangingConfigurations) main.post { stopScan() }; chain.proceed()
        })
        m.hook(sendMethod).intercept(XposedInterface.Hooker { chain ->
            val key = key(chain.getArg(1))
            if (!key.startsWith(PREFIX)) chain.proceed() else { val intent = Intent(chain.getArg(0) as Intent); main.post { SharePanel.send(key, intent) }; null }
        })
        m.hook(cancel).intercept(XposedInterface.Hooker { chain ->
            val key = key(chain.getArg(0))
            if (!key.startsWith(PREFIX)) chain.proceed() else { client.send(Cmd.cancel(key.removePrefix(PREFIX))); null }
        })
        ready = true
        m.log(Log.INFO, Module.TAG, "OnePlus Share 17.2.36 panel bridge installed", null)
    }

    private fun call(obj: Any, name: String, vararg args: Any?): Any? {
        val type = if (obj is Class<*>) obj else obj.javaClass
        val method = generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(if (obj is Class<*>) null else obj, *args)
    }
    private fun key(device: Any?) = if (device == null) "" else runCatching { call(device, "a") as? String }.getOrNull().orEmpty()
    private fun deviceState(name: String): Any = state.enumConstants.first { (it as Enum<*>).name == name }
    private val renewScan = object : Runnable {
        override fun run() {
            if (!scanning) return
            BleWakeAdvertiser.start("panel", 300_000)
            main.postDelayed(this, 240_000)
        }
    }
    private fun startScan() {
        scanning = true
        main.removeCallbacks(renewScan)
        renewScan.run()
        client.send(Cmd.discover(true))
    }
    private fun stopScan() {
        scanning = false
        main.removeCallbacks(renewScan)
        client.send(Cmd.discover(false))
        if (outgoing.isEmpty()) BleWakeAdvertiser.stop("panel")
    }
    fun onConnection(connected: Boolean) {
        if (!ready) return
        if (!connected) {
            if (Island.flipping) return
            main.post { failAll("The AirDrop service restarted") }
            return
        }
        main.post { if (scanning) client.send(Cmd.discover(true)) }
    }

    private fun failAll(why: String) {
        for (key in outgoing.keys.toList()) {
            outgoing.remove(key)?.let { f -> files.execute { f.deleteRecursively() } }
            outgoingAt.remove(key)
            show(key, "TRANSIT_FAILED", why, 0)
            main.postDelayed({ if (!outgoing.containsKey(key)) show(key, "IDLE", null, 0) }, 3000)
        }
        if (!scanning) BleWakeAdvertiser.stop("panel")
    }
    private fun refresh() = main.post {
        runCatching {
            val instance = call(manager, "h")!!; call(instance, "r")
            synchronized(adapters) { adapters.toList() }.forEach { call(it, "notifyDataSetChanged") }
        }.onFailure { module.log(Log.WARN, Module.TAG, "panel refresh failed", it) }
    }
    fun onEvent(event: Event) {
        if (!ready) return
        main.post {
            try {
                when (event) {
                    is Event.Peers -> {
                        val present = event.peers.map { PREFIX + it.mac }.toSet()
                        synchronized(peers) {
                            for (peer in event.peers) {
                                val key = PREFIX + peer.mac
                                val d = peers.getOrPut(key) { extended.getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
                                    call(it, "w", key); call(it, "P", key); call(it, "O", 0); call(it, "I", deviceState("IDLE"))
                                } }
                                call(d, "F", peer.name); call(d, "D", System.currentTimeMillis())
                            }
                            peers.keys.removeAll { it !in present && it !in outgoing }
                        }
                        refresh()
                    }
                    is Event.Transfer -> {
                        val key = PREFIX + event.out.mac
                        val name = when (event.out.state) {
                            SendState.Asking -> "TRANSIT_WAIT"; SendState.Sending -> "TRANSITING"
                            SendState.Delivered -> "TRANSIT_SUCCESS"; SendState.Declined -> "TRANSIT_REJECT"
                            SendState.Cancelled -> "CANCEL"; SendState.Failed -> "TRANSIT_FAILED"
                        }
                        val pct = (event.out.fraction * 100).toInt()
                        val who = synchronized(peers) { peers[key] }?.let { runCatching { call(it, "j") as? String }.getOrNull() }.orEmpty()
                        val phase = when (event.out.state) {
                            SendState.Asking -> if (who.isBlank()) own("main_oshare_state_waiting_send", "Waiting for recipient") else own("wait_receive_confirm", "Waiting for recipient", who)
                            SendState.Sending -> own("has_sent_percent", "Sent $pct%", "$pct%")
                            SendState.Delivered -> own("main_oshare_state_succes", "Sent")
                            SendState.Declined -> own("main_oshare_state_reject", "Declined")
                            SendState.Cancelled -> own("main_oshare_state_cancel", "Cancelled")
                            SendState.Failed -> own("base_oshare_state_transit_failed", "Failed")
                        }
                        val sig = "${event.out.state}:$pct"
                        if (lastSig[key] != sig) { lastSig[key] = sig; show(key, name, phase, pct) }
                        if (!event.out.active) {
                            outgoingAt.remove(key)
                            outgoing.remove(key)?.let { f -> files.execute { f.deleteRecursively() } }
                            if (!scanning && outgoing.isEmpty()) BleWakeAdvertiser.stop("panel")
                            main.postDelayed({ if (!outgoing.containsKey(key)) { lastSig.remove(key); show(key, "IDLE", null, 0) } }, 3000)
                        }
                    }
                    else -> Unit
                }
            } catch (t: Throwable) { module.log(Log.WARN, Module.TAG, "panel event failed", t) }
        }
    }
    private fun show(key: String, newState: String, phase: String?, progress: Int) {
        val d = synchronized(peers) { peers[key] } ?: return
        runCatching { call(d, "I", deviceState(newState)); call(d, "H", progress) }
            .onFailure { module.log(Log.WARN, Module.TAG, "panel state failed", it) }
        synchronized(phases) { if (phase == null) phases.remove(key) else phases[key] = phase }; refresh()
    }
    private fun send(key: String, intent: Intent) {
        if (outgoing.containsKey(key)) {
            client.send(Cmd.cancel(key.removePrefix(PREFIX)), ttlMs = 0)
            outgoing.remove(key)?.let { f -> files.execute { f.deleteRecursively() } }
            outgoingAt.remove(key)
            Island.cancelSendCard(key.removePrefix(PREFIX))
            if (!scanning && outgoing.isEmpty()) BleWakeAdvertiser.stop("panel")
            lastSig.remove(key)
            show(key, "CANCEL", own("main_oshare_state_cancel", "Cancelled"), 0)
            main.postDelayed({ if (!outgoing.containsKey(key)) show(key, "IDLE", null, 0) }, 2000)
            return
        }
        val box = File(app.cacheDir, "owly-send/${UUID.randomUUID()}")
        outgoing[key] = box; outgoingAt[key] = System.currentTimeMillis(); BleWakeAdvertiser.start("panel", 300_000); show(key, "TRANSIT_WAIT", "Preparing", 0)
        files.execute {
            try {
                val uris = LinkedHashSet<Uri>()
                @Suppress("DEPRECATION")
                if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                intent.clipData?.let { clip: ClipData -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } }
                if (intent.action == "com.oplus.oshare.action.DEVICE_LIST") {
                    intent.setExtrasClassLoader(loader)
                    @Suppress("DEPRECATION") val items = intent.getSerializableExtra("data_list")
                    if (items is Iterable<*>) items.filterNotNull().forEach { item ->
                        item.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.returnType == Uri::class.java }?.invoke(item)?.let { uris.add(it as Uri) }
                    }
                }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val paths = arrayListOf<String>(); val urls = arrayListOf<String>()
                if (uris.isEmpty() && text.matches(Regex("^https?://\\S+$"))) urls.add(text)
                else {
                    check(box.mkdirs())
                    uris.forEachIndexed { index, uri ->
                        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                            .orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { "file-$index" }
                        val file = File(box, "${index}_$name")
                        (app.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")).use { input -> file.outputStream().use { input.copyTo(it) } }
                        paths.add(file.path)
                    }
                    if (uris.isEmpty() && text.isNotBlank()) paths.add(File(box, "text.txt").apply { writeText(text) }.path)
                }
                check(paths.isNotEmpty() || urls.isNotEmpty()) { "Nothing shared" }
                Island.dispatchSend(key.removePrefix(PREFIX), paths, urls, allowYield = false) {
                    main.post {
                        outgoing.remove(key); outgoingAt.remove(key)
                        files.execute { box.deleteRecursively() }
                        show(key, "TRANSIT_FAILED", "The AirDrop service is not responding", 0)
                    }
                }
            } catch (t: Throwable) {
                module.log(Log.WARN, Module.TAG, "panel send preparation failed", t)
                main.post { outgoing.remove(key); outgoingAt.remove(key); files.execute { box.deleteRecursively() }; show(key, "TRANSIT_FAILED", "Cannot read shared file", 0) }
            }
        }
    }
}
