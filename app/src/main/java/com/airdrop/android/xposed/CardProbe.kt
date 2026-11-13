package com.airdrop.android.xposed

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import io.github.libxposed.api.XposedInterface
import java.util.Collections
import java.util.WeakHashMap

internal object CardProbe {
    private lateinit var module: Module
    private lateinit var app: Application

    private val piIntents: MutableMap<PendingIntent, Intent> = Collections.synchronizedMap(WeakHashMap())

    fun install(m: Module, application: Application, loader: ClassLoader) {
        module = m
        app = application
        hookNotifications()
        hookPendingIntentFactories(loader)
        hookRemoteViews()
        hookPendingIntentSend()
    }

    private fun hookNotifications() {
        for (m in NotificationManager::class.java.declaredMethods) {
            if (m.name != "notify" && m.name != "notifyAsPackage") continue
            if (m.parameterTypes.none { it == Notification::class.java }) continue
            val n = m.parameterTypes
            runCatching {
                module.hook(m).intercept(XposedInterface.Hooker { chain ->
                    val args = n.indices.map { runCatching { chain.getArg(it) }.getOrNull() }
                    (args.firstOrNull { it is Notification } as? Notification)?.let { logNotification(m.name, args, it) }
                    chain.proceed()
                })
            }.onFailure { module.log(Log.WARN, Module.TAG, "notify hook failed (${m.name}/${n.size})", it) }
        }
    }

    private fun logNotification(methodName: String, args: List<Any?>, n: Notification) {
        val tag = args.filterIsInstance<String>().joinToString(",").ifEmpty { "-" }
        val id = args.filterIsInstance<Int>().firstOrNull() ?: -1
        val actions = n.actions.orEmpty().mapIndexed { i, a ->
            "action[$i] title='${a.title}' pi=${describePi(a.actionIntent)}"
        }
        module.log(
            Log.INFO, Module.TAG,
            "notify $methodName id=$id tag=$tag channel=${n.channelId} flags=0x${Integer.toHexString(n.flags)} " +
                "actions=${actions.size} [${actions.joinToString("; ")}] " +
                "contentIntent=${describePi(n.contentIntent)} deleteIntent=${describePi(n.deleteIntent)} " +
                "contentView=${describeRemoteViews(n.contentView)} bigContentView=${describeRemoteViews(n.bigContentView)}",
            null,
        )
    }

    private fun hookPendingIntentFactories(loader: ClassLoader) {
        for (m in PendingIntent::class.java.declaredMethods) {
            if (m.name !in setOf("getActivity", "getActivities", "getBroadcast", "getService")) continue
            val ps = m.parameterTypes
            if (ps.size < 4 || ps[0] != Context::class.java || ps[2] != Intent::class.java) continue
            runCatching {
                module.hook(m).intercept(XposedInterface.Hooker { chain ->
                    val i = runCatching { chain.getArg(2) as? Intent }.getOrNull()
                    val made = chain.proceed() as? PendingIntent
                    if (i != null && made != null) {
                        piIntents[made] = i
                        module.log(
                            Log.INFO, Module.TAG,
                            "PendingIntent.${m.name}(flags=0x${Integer.toHexString(runCatching { chain.getArg(3) as Int }.getOrDefault(0))}) -> ${dumpIntent(i)}",
                            null,
                        )
                    }
                    made
                })
            }.onFailure { module.log(Log.WARN, Module.TAG, "PendingIntent hook failed (${m.name}/${ps.size})", it) }
        }
    }

    private fun hookPendingIntentSend()  {
        for (m in PendingIntent::class.java.declaredMethods) {
            if (m.name != "send") continue
            runCatching {
                module.hook(m).intercept(XposedInterface.Hooker { chain ->
                    val pi = runCatching { chain.thisObject as? PendingIntent }.getOrNull()
                    module.log(
                        Log.INFO, Module.TAG,
                        "PendingIntent.send from this process (${m.parameterTypes.size} arg): ${describePi(pi)}",
                        null,
                    )
                    chain.proceed()
                })
            }.onFailure { module.log(Log.WARN, Module.TAG, "send hook failed (${m.parameterTypes.size} args)", it) }
        }
    }

    private fun hookRemoteViews() {
        for (name in listOf("setOnClickPendingIntent", "setOnClickFillInIntent", "setPendingIntentTemplate")) {
            val m = RemoteViews::class.java.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: continue
            runCatching {
                module.hook(m).intercept(XposedInterface.Hooker { chain ->
                    val viewId = runCatching { chain.getArg(0) as Int }.getOrDefault(-1)
                    val arg = runCatching { chain.getArg(1) }.getOrNull()
                    val what = when (arg) {
                        is PendingIntent -> describePi(arg)
                        is Intent -> dumpIntent(arg)
                        else -> arg?.toString().orEmpty()
                    }
                    module.log(Log.INFO, Module.TAG, "RemoteViews.$name(view=${viewName(viewId)}) -> $what", null)
                    chain.proceed()
                })
            }.onFailure { module.log(Log.WARN, Module.TAG, "RemoteViews.$name hook failed", it) }
        }
    }

    fun describePi(pi: PendingIntent?): String {
        if (pi == null) return "null"
        val i = piIntents[pi]
        return if (i == null) "PendingIntent@${System.identityHashCode(pi)} (intent not created in this process)"
        else "PendingIntent@${System.identityHashCode(pi)} from ${dumpIntent(i)}"
    }

    fun dumpIntent(i: Intent?): String {
        if (i == null) return "null"
        return "Intent{act=${i.action} pkg=${i.`package`} cmp=${i.component?.flattenToShortString()} " +
            "data=${i.data} type=${i.type} cat=${i.categories} flags=0x${Integer.toHexString(i.flags)} " +
            "extras=${dumpBundle(i.extras)}}"
    }

    fun dumpBundle(b: android.os.Bundle?): String {
        if (b == null) return "null"
        return "{" + b.keySet().sorted().joinToString(", ") { k ->
            "$k=${describe(runCatching { b.get(k) }.getOrNull())}"
        } + "}"
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> "byte[${v.size}]${if (v.isEmpty()) "" else "=${v.take(64).joinToString("") { "%02x".format(it) }}${if (v.size > 64) "…" else ""}"}"
        is android.os.Bundle -> "Bundle${dumpBundle(v)}"
        is Intent -> dumpIntent(v)
        is PendingIntent -> describePi(v)
        is Array<*> -> "${v.javaClass.simpleName}[${v.size}](${v.joinToString(";") { describe(it) }})"
        is Collection<*> -> "${v.javaClass.simpleName}[${v.size}](${v.joinToString(";") { describe(it) }})"
        else -> "${v.javaClass.simpleName}:${v.toString().take(300)}"
    }

    private fun describeRemoteViews(rv: RemoteViews?): String {
        if (rv == null) return "null"
        val actions: List<Any> = runCatching {
            RemoteViews::class.java.declaredFields
                .mapNotNull { f ->
                    f.isAccessible = true
                    f.get(rv)
                }
                .filterIsInstance<Collection<*>>()
                .flatten()
                .filterNotNull()
        }.getOrNull().orEmpty()
        if (actions.isEmpty()) return "RemoteViews@${System.identityHashCode(rv)} (no actions)"
        return "RemoteViews@${System.identityHashCode(rv)} actions=" + actions.joinToString("; ") { a ->
            val fields = runCatching {
                a.javaClass.declaredFields.mapNotNull { f ->
                    f.isAccessible = true
                    val value = runCatching { f.get(a) }.getOrNull() ?: return@mapNotNull null
                    "${f.name}=${describe(value)}"
                }
            }.getOrNull().orEmpty()
            "${a.javaClass.simpleName}{${fields.joinToString(", ")}}"
        }
    }

    fun viewName(viewId: Int): String = runCatching { app.resources.getResourceName(viewId) }.getOrDefault("0x${Integer.toHexString(viewId)}")
}
