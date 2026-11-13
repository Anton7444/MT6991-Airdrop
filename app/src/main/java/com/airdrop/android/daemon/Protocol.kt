package com.airdrop.android.daemon

import org.json.JSONArray
import org.json.JSONObject

const val SOCKET_NAME = "owly-android"

data class Peer(val mac: String, val name: String, val model: String)

enum class SendState { Asking, Sending, Delivered, Declined, Cancelled, Failed }

data class Outgoing(
    val mac: String,
    val state: SendState,
    val sent: Long = 0,
    val total: Long = 0,
    val error: String = "",
) {
    val fraction: Float get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = state == SendState.Asking || state == SendState.Sending
}

enum class ReceiveState { Asking, Receiving, Done, Failed, Declined }

data class Incoming(
    val id: Long,
    val from: String,
    val model: String = "",
    val fileNames: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val total: Long = 0,
    val thumbnailB64: String = "",
    val state: ReceiveState = ReceiveState.Asking,
    val received: Long = 0,
    val paths: List<String> = emptyList(),
    val reason: String = "",
) {
    val fraction: Float get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    val count: Int get() = (fileNames.size + urls.size).coerceAtLeast(1)
    val singleName: String? get() = if (fileNames.size + urls.size == 1) (fileNames.firstOrNull() ?: urls.firstOrNull()) else null
}

data class InboxFile(val name: String, val size: Long, val modified: Long)

sealed interface Event {
    data class Peers(val peers: List<Peer>) : Event
    data class Transfer(val out: Outgoing) : Event
    data class IncomingAsk(val incoming: Incoming) : Event
    data class Receiving(val id: Long, val received: Long, val total: Long) : Event
    data class Received(val id: Long, val paths: List<String>) : Event
    data class ShowFile(val path: String) : Event
    data class UrlsReceived(val id: Long, val urls: List<String>) : Event
    data class Withdrawn(val id: Long) : Event
    data object Wake : Event
    data class Failed(val id: Long, val reason: String) : Event
    data class Status(
        val state: String = "off",
        val share: Int = -1,
        val name: String = "",
        val version: String = "",
        val error: String = "",
        val coexWanted: Boolean = false,
        val coexAvailable: Boolean = false,
        val coexActive: Boolean = false,
        val coexReason: String = "",
        val moduleLoaded: Boolean = false,
        val moduleState: String = "off",
    ) : Event {
        val moduleActive: Boolean get() = moduleState == "on"
        val on: Boolean get() = state == "on"
        val failed: Boolean get() = state == "failed"
        val starting: Boolean get() = !on && !failed && state != "off" && state.isNotEmpty()
    }
    data class Inbox(val files: List<InboxFile>) : Event
    data object Unknown : Event
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { optString(it) }

private fun cleanName(s: String): String {
    var t = s.trim()
    while (t.length >= 2 && t.first() == '\\' && t.last() == '\\') t = t.substring(1, t.length - 1).trim()
    while (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
        t = t.substring(1, t.length - 1).trim()
    }
    return t.ifBlank { s.trim() }
}

fun parseEvent(o: JSONObject): Event = when (o.optString("ev")) {
    "peers" -> {
        val a = o.optJSONArray("peers") ?: JSONArray()
        Event.Peers(List(a.length()) {
            val p = a.getJSONObject(it)
            Peer(
                p.optString("mac"),
                cleanName(p.optString("name")).ifBlank { "Apple device" },
                p.optString("model"),
            )
        })
    }
    "transfer" -> Event.Transfer(
        Outgoing(
            mac = o.optString("mac").removePrefix("airdrop:"),
            state = when (o.optString("state")) {
                "asking" -> SendState.Asking
                "sending" -> SendState.Sending
                "delivered" -> SendState.Delivered
                "declined" -> SendState.Declined
                "cancelled" -> SendState.Cancelled
                else -> SendState.Failed
            },
            sent = o.optLong("sent"),
            total = o.optLong("total"),
            error = o.optString("error"),
        )
    )
    "incoming" -> Event.IncomingAsk(
        Incoming(
            id = o.getLong("id"),
            from = cleanName(o.optString("from", "Apple device")),
            model = o.optString("model"),
            fileNames = o.optJSONArray("files").strings(),
            urls = o.optJSONArray("urls").strings(),
            total = o.optLong("total"),
            thumbnailB64 = o.optString("icon"),
        )
    )
    "receiving" -> Event.Receiving(o.getLong("id"), o.optLong("received"), o.optLong("total"))
    "received" -> Event.Received(o.getLong("id"), o.optJSONArray("paths").strings())
    "show" -> Event.ShowFile(o.optString("path"))
    "urls" -> Event.UrlsReceived(o.getLong("id"), o.optJSONArray("urls").strings())
    "withdrawn" -> Event.Withdrawn(o.getLong("id"))
    "wake" -> Event.Wake
    "failed" -> Event.Failed(o.getLong("id"), o.optString("reason"))
    "status" -> {
        val c = o.optJSONObject("coex")
        val active = c?.optBoolean("active") ?: false
        Event.Status(
            state = o.optString("state", "off"),
            share = o.optInt("oshare", -1),
            name = o.optString("name"),
            version = o.optString("version"),
            error = o.optString("error"),
            coexWanted = c?.optBoolean("wanted", active) ?: false,
            coexAvailable = c?.optBoolean("available", true) ?: false,
            coexActive = active,
            coexReason = c?.takeUnless { it.isNull("reason") }?.optString("reason").orEmpty(),
            moduleLoaded = o.optBoolean("module"),
            moduleState = o.optString("module_state", "off"),
        )
    }
    "inbox" -> {
        val a = o.optJSONArray("files") ?: JSONArray()
        Event.Inbox(List(a.length()) {
            val f = a.getJSONObject(it)
            InboxFile(f.optString("name"), f.optLong("size"), f.optLong("modified"))
        })
    }
    else -> Event.Unknown
}

object Cmd {
    fun discover(on: Boolean) = JSONObject().put("cmd", "discover").put("on", on)
    fun answer(id: Long, accept: Boolean) =
        JSONObject().put("cmd", "answer").put("id", id).put("accept", accept)
    fun cancel(mac: String) = JSONObject().put("cmd", "cancel").put("mac", mac)
    fun send(mac: String, paths: List<String>, urls: List<String>) =
        JSONObject().put("cmd", "send").put("mac", mac)
            .put("paths", JSONArray(paths)).put("urls", JSONArray(urls))
    fun inbox() = JSONObject().put("cmd", "inbox")
    fun status() = JSONObject().put("cmd", "reconcile")
    fun openShareSettings() = JSONObject().put("cmd", "open_share_settings")
    fun setCoex(on: Boolean) = JSONObject().put("cmd", "set_coex").put("on", on)
}

object Ipc {
    const val SHARE_PKG = "com.coloros.oshare"

    const val TOKEN_FILE = "airdrop-bridge-token"

    fun appToken(ctx: android.content.Context): String {
        val prefs = ctx.getSharedPreferences("airdrop", android.content.Context.MODE_PRIVATE)
        prefs.getString("bridge_token", null)?.let { return it }
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("bridge_token", t).apply()
        return t
    }

    fun moduleToken(ctx: android.content.Context): String? =
        runCatching { java.io.File(ctx.filesDir, TOKEN_FILE).readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    const val EXTRA_TOKEN = "token"
    const val ACTION_REQUEST = "com.airdrop.android.action.REQUEST"
    const val ACTION_EVENT = "com.airdrop.android.action.EVENT"

    const val ACTION_RECEIVED = "com.airdrop.android.action.RECEIVED"
    const val EXTRA_NAME = "name"
    const val EXTRA_JSON = "json"
}
