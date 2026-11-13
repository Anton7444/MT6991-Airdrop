package com.airdrop.android.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

internal object CardDataProbe {
    private lateinit var module: Module

    fun install(m: Module, loader: ClassLoader) {
        module = m
        val cls = runCatching { loader.loadClass(LiveCard.CLASS) }.getOrNull() ?: return
        var hooked = 0
        for (method in cls.declaredMethods) {
            if (method.isSynthetic || method.isBridge) continue
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(XposedInterface.Hooker { chain ->
                    log(method, chain)
                    chain.proceed()
                })
                hooked++
            }
        }
        module.log(Log.INFO, Module.TAG, "card data probe: watching $hooked method(s) of ${LiveCard.CLASS}", null)
    }

    private fun log(method: Method, chain: XposedInterface.Chain) {
        val args = method.parameterTypes.indices.map { runCatching { chain.getArg(it) }.getOrNull() }
        val interesting = args.any { it is Map<*, *> || it is android.os.Bundle || it is String || it is Collection<*> }
        if (!interesting) return
        val text = args.joinToString(", ") { describe(it) }
        module.log(Log.INFO, Module.TAG, "card data ${method.name}(${text})", null)
    }

    private fun describe(v: Any?): String = describe(v, 0)

    private fun describe(v: Any?, depth: Int): String = when (v) {
        null -> "null"
        is Map<*, *> -> "Map${v.entries.joinToString(", ", "{", "}") { (k, x) -> "$k=${brief(x, depth + 1)}" }}"
        is android.os.Bundle -> "Bundle${CardProbe.dumpBundle(v)}"
        is String -> "\"${v.take(300)}\""
        is Collection<*> -> "List[${v.size}](${v.joinToString(";") { brief(it, depth + 1) }})"
        else -> if (depth < 2) {
            val fields = runCatching {
                v.javaClass.declaredFields
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .joinToString(", ") { f ->
                        f.isAccessible = true
                        "${f.name}=${brief(runCatching { f.get(v) }.getOrNull(), depth + 1)}"
                    }
            }.getOrDefault("fields?")
            "${v.javaClass.name}{$fields}"
        } else {
            "${v.javaClass.simpleName}:${v.toString().take(200)}"
        }
    }

    private fun brief(v: Any?, depth: Int): String = when {
        v == null -> "null"
        v is String -> "\"${v.take(160)}\""
        v is Number || v is Boolean -> v.toString()
        depth > 3 -> v.javaClass.simpleName
        else -> describe(v, depth)
    }
}
