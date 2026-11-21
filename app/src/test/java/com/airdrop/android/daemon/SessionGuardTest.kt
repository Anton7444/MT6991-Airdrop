package com.airdrop.android.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGuardTest {
    private val log = """
        2026-11-21T23:13:27.955625Z  INFO owly::android: session up as "tablet"
        2026-11-21T23:13:29.762403Z  INFO owly::android: seen by [fe80::1%177]:55336
        2026-11-21T23:13:51.424104Z  INFO owly::android: seen by [fe80::1%177]:55340
        2026-11-21T23:15:47.000000Z  INFO owly_awdl::driver: STATS tx_action 1 | rx_action 1 rx_data 1350 rx_malformed 2
        2026-11-21T23:15:57.000000Z  INFO owly_awdl::driver: STATS tx_action 1 | rx_action 1 rx_data 1352 rx_malformed 2
        2026-11-21T23:16:07.000000Z  INFO owly_awdl::driver: STATS tx_action 1 | rx_action 1 rx_data 1363 rx_malformed 2
        2026-11-21T23:16:17.000000Z  INFO owly_awdl::driver: STATS tx_action 1 | rx_action 1 rx_data 1374 rx_malformed 2
    """.trimIndent()

    private fun at(s: String) = java.time.Instant.parse(s).toEpochMilli()

    @Test fun parsesTheLog() {
        val snap = SessionGuard.parse(log)
        assertEquals(at("2026-11-21T23:13:27.955625Z"), snap.sessionUpAt)
        assertEquals(at("2026-11-21T23:13:51.424104Z"), snap.lastSeenAt)
        assertEquals(4, snap.rxSamples.size)
    }

    @Test fun iphoneTalkingButUnansweredIsStale() {
        // The 10:16 episode: data keeps arriving, the last lookup answered was minutes ago.
        val now = at("2026-11-21T23:16:20Z")
        val snap = SessionGuard.parse(log)
        val (flowing, sinceData) = SessionGuard.dataFlow(snap.rxSamples, now)
        val action = SessionGuard.decide(false, now - snap.sessionUpAt!!, Long.MAX_VALUE, flowing, now - snap.lastSeenAt!!, sinceData)
        assertEquals(SessionGuard.Action.RefreshStale, action)
    }

    @Test fun answeredLookupsAreLeftAlone() {
        assertEquals(SessionGuard.Action.None, SessionGuard.decide(false, 120_000, Long.MAX_VALUE, 40_000, 10_000, 1_000))
    }

    @Test fun neverDuringATransfer() {
        assertEquals(SessionGuard.Action.None, SessionGuard.decide(true, 900_000, Long.MAX_VALUE, 40_000, null, 1_000))
    }

    @Test fun idleSessionIsRefreshedAfterTenMinutes() {
        assertEquals(SessionGuard.Action.RefreshIdle, SessionGuard.decide(false, 11 * 60_000L, Long.MAX_VALUE, null, null, null))
        assertEquals(SessionGuard.Action.None, SessionGuard.decide(false, 5 * 60_000L, Long.MAX_VALUE, null, null, null))
    }

    @Test fun idleRefreshWaitsWhileAnIphoneIsAround() {
        assertEquals(SessionGuard.Action.None, SessionGuard.decide(false, 11 * 60_000L, Long.MAX_VALUE, null, 60_000, 5_000))
    }
}
