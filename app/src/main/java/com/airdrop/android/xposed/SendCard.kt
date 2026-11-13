package com.airdrop.android.xposed

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class SendCard private constructor(
    private val instance: Any,
    private val dataClass: Class<*>,
    private val dataCtor: java.lang.reflect.Constructor<*>,
    private val save: Method,
    private val start: Method,
    private val percent: Method,
    private val done: Method?,
    private val fail: Method?,
    private val cancelled: Method?,
    private val declined: Method?,
    private val loader: ClassLoader,
) {
    private fun device(name: String): Any? = runCatching {
        val dev = dataCtor.parameterTypes[6].getDeclaredConstructor().newInstance()
        val setName = dev.javaClass.getMethod("F", String::class.java)
        val setState = dev.javaClass.getMethod("I", Any::class.java)
        setName.invoke(dev, name)
        val state = listOf("com.oplus.oshare.OplusOshareState", "com.color.oshare.ColorOshareState").firstNotNullOfOrNull { n ->
            runCatching { loader.loadClass(n).enumConstants?.firstOrNull { (it as Enum<*>).name == "READY" } }.getOrNull()
        }
        if (state != null) runCatching { setState.invoke(dev, state) }
        dev
    }.getOrNull()

    fun begin(ctx: Context, task: String, receiver: String, items: Int) {
        val info = dataCtor.newInstance("", receiver, "", "", "", items, device(receiver))
        save.invoke(instance, task, info)
        start.invoke(instance, ctx, false, task)
    }

    private fun call(m: Method?, ctx: Context, task: String) {
        m ?: return
        if (m.parameterTypes[0] == Context::class.java) m.invoke(instance, ctx, task) else m.invoke(instance, task, ctx)
    }

    fun progress(ctx: Context, task: String, pct: Int) { percent.invoke(instance, ctx, task, pct) }
    fun finished(ctx: Context, task: String) = call(done, ctx, task)
    fun failed(ctx: Context, task: String) = call(fail, ctx, task)
    fun cancelled(ctx: Context, task: String) = call(cancelled, ctx, task)
    fun declined(ctx: Context, task: String) = call(declined, ctx, task)

    companion object {
        const val CLASS = "com.oplus.oshare.seeding.SendingSeedlingCard"

        fun find(loader: ClassLoader, bridge: DexKitBridge?): SendCard? {
            val cls = try { loader.loadClass(CLASS) } catch (_: Throwable) { return null }
            val inst = LiveCard.instanceOf(cls) ?: return null
            val data = cls.declaredClasses.firstOrNull { c ->
                c.declaredConstructors.any { k ->
                    val t = k.parameterTypes
                    t.size == 7 && t[0] == String::class.java && t[1] == String::class.java &&
                        t[2] == String::class.java && t[3] == String::class.java &&
                        t[4] == String::class.java && t[5] == Int::class.javaPrimitiveType
                }
            } ?: return null
            val ctor = data.declaredConstructors.first { it.parameterTypes.size == 7 }.also { it.isAccessible = true }

            val ctx = Context::class.java
            val str = String::class.java
            val int = Int::class.javaPrimitiveType!!
            val bool = Boolean::class.javaPrimitiveType!!
            fun shaped(vararg p: Class<*>) = cls.declaredMethods.filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(*p))
            }
            fun pick(cands: List<Method>, log: String?): Method? {
                if (cands.size == 1) return cands[0]
                if (bridge == null || log == null) return null
                val hits = runCatching {
                    bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(CLASS).usingStrings(log)))
                        .map { it.methodName }.toSet()
                }.getOrDefault(emptySet())
                return cands.filter { it.name in hits }.singleOrNull()
            }
            fun wrapper(log: String): Method? = pick(shaped(ctx, str) + shaped(str, ctx), log)

            val save = pick(shaped(str, data), "saveDataInfo") ?: return null
            val start = pick(shaped(ctx, bool, str), "startSeedling : ") ?: return null
            val pct = pick(shaped(ctx, str, int), "updatePercentCardData") ?: return null
            listOf(save, start, pct).forEach { it.isAccessible = true }
            fun w(log: String, vararg known: String): Method? {
                val m = wrapper(log) ?: (shaped(ctx, str) + shaped(str, ctx)).firstOrNull { it.name in known }
                return m?.also { it.isAccessible = true }
            }
            return SendCard(
                inst, data, ctor, save, start, pct,
                done = w("updateCompleteCardData", "x0", "A0"),
                fail = w("updateFailCardData", "K0", "O0"),
                cancelled = w("updateCanceledCardData", "s0", "v0"),
                declined = w("updateRefuseCardData", "T0", "X0"),
                loader = loader,
            )
        }
    }
}
