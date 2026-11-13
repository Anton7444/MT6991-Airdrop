package com.airdrop.android.update

import android.content.Context
import com.airdrop.android.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

object Updater {
    private const val API = "https://api.github.com/repos/Anton7444/MT6991-Airdrop/releases/latest"
    private const val PREFS = "airdrop"
    private const val KEY_AUTO = "update_auto"
    private const val KEY_LAST = "update_checked_at"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data object Failed : State
        data class Available(val version: String, val page: String, val notes: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _auto = MutableStateFlow(true)
    val auto: StateFlow<Boolean> = _auto

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _auto.value = prefs.getBoolean(KEY_AUTO, true)
        if (_auto.value && System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0L) > INTERVAL_MS) check(ctx)
    }

    fun setAuto(ctx: Context, on: Boolean) {
        _auto.value = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun check(ctx: Context) {
        if (_state.value == State.Checking) return
        _state.value = State.Checking
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch {
            val result = fetch()
            if (result != State.Failed) prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            _state.value = result
        }
    }

    private fun fetch(): State {
        val conn = try {
            URL(API).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return State.Failed
        }
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MT6991-Airdrop")
            when (conn.responseCode) {
                404 -> State.UpToDate
                200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name")
                    if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                        State.Available(
                            tag.removePrefix("v"),
                            json.optString("html_url"),
                            json.optString("body").trim().take(400),
                        )
                    } else {
                        State.UpToDate
                    }
                }
                else -> State.Failed
            }
        } catch (_: Exception) {
            State.Failed
        } finally {
            conn.disconnect()
        }
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        val a = numbers(remote)
        val b = numbers(local)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        Regex("\\d+(\\.\\d+)*").find(version)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
}
