package com.airdrop.android.xposed

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

internal class LiveCard private constructor(
    private val instance: Any,
    private val askMethod: Method,
    private val showMethod: Method,
    private val percentMethod: Method,
    private val doneMethod: Method?,
    private val failedMethod: Method?,
    private val senderCancelMethod: Method?,
    private val noSpaceMethod: Method?,
    private val pushMethods: List<Method>,
) {
    private val callbackType: Class<*> = askMethod.parameterTypes.last()

    fun ask(sender: String, device: String, count: Int, kind: String, images: Int, videos: Int, onTimeout: () -> Unit) {
        val cb = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, m, _ ->
            if (m.name == "invoke") onTimeout()
            null
        }
        askMethod.invoke(instance, sender, device, null, count, kind, 1, java.lang.Boolean.TRUE, images, videos, cb)
    }

    fun show(ctx: Context) = showMethod.invoke(instance, ctx, false)

    fun percent(ctx: Context, pct: Int) { percentMethod.invoke(instance, ctx, pct) }
    fun done(ctx: Context) { doneMethod?.invoke(instance, ctx) }
    fun failed(ctx: Context) { failedMethod?.invoke(instance, ctx) }
    fun senderCancelled(ctx: Context) { senderCancelMethod?.invoke(instance, ctx) }
    fun noSpace(ctx: Context) { noSpaceMethod?.invoke(instance, ctx) }

    fun pushData(json: String): Int {
        var pushed = 0
        for (m in pushMethods) {
            if (runCatching { m.invoke(instance, json, java.lang.Boolean.TRUE) }.isSuccess) pushed++
        }
        return pushed
    }

    companion object {
        const val CLASS = "com.oplus.oshare.seeding.ConfirmSeedlingCard"
        const val PROVIDER = "com.oplus.oshare.seeding.OShareCardWidgetProvider"

        fun find(loader: ClassLoader, bridge: DexKitBridge?): LiveCard? {
            val cls = try { loader.loadClass(CLASS) } catch (_: Throwable) { return null }
            val inst = instanceOf(cls) ?: return null

            val ask = cls.declaredMethods.firstOrNull {
                val t = it.parameterTypes
                t.size == 10 && t[0] == String::class.java && t[1] == String::class.java &&
                    t[2] == String::class.java && t[3] == Int::class.javaObjectType && t[4] == String::class.java &&
                    t[5] == Int::class.javaPrimitiveType && t[6] == java.lang.Boolean::class.java &&
                    t[7] == Int::class.javaObjectType && t[8] == Int::class.javaObjectType && t[9].isInterface
            } ?: return null
            ask.isAccessible = true

            val percent = byShape(cls, Context::class.java, Int::class.javaPrimitiveType!!) ?: return null
            percent.isAccessible = true

            val show = contextBool(cls, bridge, "startSeedling dataType", "R0") ?: return null

            fun wrapper(name: String, log: String): Method? {
                val ctxOnly = cls.declaredMethods.filter { it.parameterTypes.contentEquals(arrayOf(Context::class.java)) }
                ctxOnly.firstOrNull { it.name == name }?.let { return it.also { m -> m.isAccessible = true } }
                if (bridge == null) return null
                val hit = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(CLASS).usingStrings(log)))
                    .firstOrNull { it.paramTypeNames.size == 1 && it.paramTypeNames[0] == "android.content.Context" } ?: return null
                return ctxOnly.firstOrNull { it.name == hit.methodName }?.also { it.isAccessible = true }
            }

            fun isPush(m: Method): Boolean {
                val t = m.parameterTypes
                return t.size == 2 && t[0] == String::class.java &&
                    (t[1] == java.lang.Boolean::class.java || t[1] == java.lang.Boolean.TYPE)
            }

            val push = cls.declaredMethods.filter { isPush(it) && (it.name == "L0" || it.name == "K0") }
                .ifEmpty { cls.declaredMethods.filter { isPush(it) } }
                .onEach { it.isAccessible = true }

            return LiveCard(
                inst, ask, show, percent,
                doneMethod = wrapper("V0", "updateCompleteCardData"),
                failedMethod = wrapper("c1", "updateFailedCardData"),
                senderCancelMethod = wrapper("n1", "updateSenderCancelCardData"),
                noSpaceMethod = wrapper("h1", "updateNoSpaceCardData"),
                pushMethods = push,
            )
        }

        internal fun instanceOf(cls: Class<*>): Any? {
            cls.declaredFields.firstOrNull { f ->
                Modifier.isStatic(f.modifiers) &&
                    f.type.declaredMethods.any { m -> m.parameterCount == 0 && m.returnType == cls }
            }?.let { f ->
                val getter = f.type.declaredMethods.first { m ->
                    m.parameterCount == 0 && m.returnType == cls && !Modifier.isStatic(m.modifiers)
                }
                runCatching {
                    f.isAccessible = true
                    getter.isAccessible = true
                    getter.invoke(f.get(null))
                }.getOrNull()?.let { return it }
            }
            val statics = cls.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == cls
            }
            val pick = statics.firstOrNull { it.name == "e0" } ?: statics.firstOrNull { it.name != "f0" }
            return runCatching { pick?.also { it.isAccessible = true }?.invoke(null) }.getOrNull()
        }

        private fun byShape(cls: Class<*>, vararg p: Class<*>): Method? =
            cls.declaredMethods.firstOrNull { it.parameterTypes.contentEquals(arrayOf(*p)) }

        private fun contextBool(cls: Class<*>, bridge: DexKitBridge?, log: String, name: String): Method? {
            val cands = cls.declaredMethods.filter {
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, java.lang.Boolean.TYPE))
            }
            if (bridge != null) {
                val hit = runCatching {
                    bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(CLASS).usingStrings(log)))
                        .firstOrNull { it.paramTypeNames.size == 2 && it.paramTypeNames[0] == "android.content.Context" }
                }.getOrNull()
                if (hit != null) {
                    cands.firstOrNull { it.name == hit.methodName }?.let { return it.also { m -> m.isAccessible = true } }
                }
            }
            return cands.firstOrNull { it.name == name }?.also { it.isAccessible = true }
        }
    }
}
