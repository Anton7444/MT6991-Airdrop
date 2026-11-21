package com.airdrop.android.daemon

/**
 * Decides when the AirDrop session should be restarted, from what the daemon log shows.
 *
 * The daemon's session can go stale: an iPhone keeps sending to the tablet but its lookups are no longer
 * answered ("seen by" stops), so the iPhone shows "No People Found" until the session is restarted. Turning
 * AirDrop off and on fixes it; this does the same by itself. It also refreshes an idle session every
 * [IDLE_REFRESH_MS] as insurance, but only when no iPhone is around.
 */
internal object SessionGuard {
    const val STALE_AFTER_MS = 15_000L
    const val SEEN_RECENTLY_MS = 30_000L
    const val SETTLED_MS = 20_000L
    const val MIN_GAP_MS = 2 * 60_000L
    const val IDLE_REFRESH_MS = 10 * 60_000L
    const val IDLE_QUIET_MS = 2 * 60_000L
    const val IDLE_NO_DATA_MS = 20_000L

    enum class Action { None, RefreshStale, RefreshIdle }

    /**
     * [dataSinceMs]: how long iPhone data has kept arriving without a pause (null when none is arriving).
     * [sinceSeenMs]: how long since the last answered lookup (null when none in this session).
     * [sinceDataMs]: how long since any data last arrived (null when none in this session).
     */
    fun decide(
        busy: Boolean,
        sessionAgeMs: Long,
        sinceRefreshMs: Long,
        dataSinceMs: Long?,
        sinceSeenMs: Long?,
        sinceDataMs: Long?,
    ): Action {
        if (busy || sessionAgeMs < SETTLED_MS || sinceRefreshMs < MIN_GAP_MS) return Action.None
        val seenRecently = sinceSeenMs != null && sinceSeenMs < SEEN_RECENTLY_MS
        if (dataSinceMs != null && dataSinceMs >= STALE_AFTER_MS && !seenRecently) return Action.RefreshStale
        val quiet = (sinceSeenMs == null || sinceSeenMs >= IDLE_QUIET_MS) &&
            (sinceDataMs == null || sinceDataMs >= IDLE_NO_DATA_MS)
        if (quiet && sessionAgeMs >= IDLE_REFRESH_MS && sinceRefreshMs >= IDLE_REFRESH_MS) return Action.RefreshIdle
        return Action.None
    }

    private val TIME = Regex("^(\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?Z)")
    private val RX_DATA = Regex("rx_data (\\d+)")

    /** What one read of the log's tail says: the session start, the last answered lookup, and rx_data samples. */
    class Snapshot(val sessionUpAt: Long?, val lastSeenAt: Long?, val rxSamples: List<Pair<Long, Long>>)

    fun parse(tail: String): Snapshot {
        var up: Long? = null
        var seen: Long? = null
        val rx = ArrayList<Pair<Long, Long>>()
        for (line in tail.lineSequence()) {
            val t = TIME.find(line)?.groupValues?.get(1)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: continue
            when {
                line.contains("session up as") -> { up = t; seen = null; rx.clear() }
                line.contains("seen by ") -> seen = t
                line.contains(" STATS ") -> RX_DATA.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let { rx += t to it }
            }
        }
        return Snapshot(up, seen, rx)
    }

    /** From rx_data samples: (how long data has flowed without a pause, when data last arrived). */
    fun dataFlow(samples: List<Pair<Long, Long>>, now: Long): Pair<Long?, Long?> {
        if (samples.size < 2) return null to null
        var lastGrowth: Long? = null
        var runStart: Long? = null
        for (i in 1 until samples.size) {
            val (t, v) = samples[i]
            val grew = v > samples[i - 1].second
            if (grew) {
                if (runStart == null) runStart = samples[i - 1].first
                lastGrowth = t
            } else runStart = null
        }
        val flowing = runStart != null && lastGrowth != null && now - lastGrowth <= 15_000L
        return (if (flowing) lastGrowth!! - runStart!! else null) to lastGrowth?.let { now - it }
    }
}
