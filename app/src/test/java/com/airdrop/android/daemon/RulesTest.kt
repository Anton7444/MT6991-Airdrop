package com.airdrop.android.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val long = ShareMode.SETTLE_MS + 1

    @Test fun matchingSwitchAndModeNeedNothing() {
        assertEquals(ShareMode.Action.Agree, ShareMode.decide(true, 1, null, long, 0))
        assertEquals(ShareMode.Action.Agree, ShareMode.decide(false, 0, null, long, 0))
        assertEquals(ShareMode.Action.Agree, ShareMode.decide(false, 2, null, long, 0))
        assertEquals(ShareMode.Action.Agree, ShareMode.decide(true, -1, long, long, 0))
    }

    @Test fun aBriefDisagreementIsLeftAlone() {
        // The watchdog's deliberate 1 -> 0 -> 1 cycle and a fresh tap on the switch both look like this.
        assertEquals(ShareMode.Action.Wait, ShareMode.decide(true, 0, null, long, 0))
        assertEquals(ShareMode.Action.Wait, ShareMode.decide(true, 0, 3_000, long, 0))
        assertEquals(ShareMode.Action.Agree, ShareMode.decide(false, 1, long, 2_000, 0))
    }

    @Test fun fallbackToContactsOnlyIsRestoredAFewTimesOnly() {
        assertEquals(ShareMode.Action.Restore, ShareMode.decide(true, 2, long, long, 0))
        assertEquals(ShareMode.Action.Restore, ShareMode.decide(true, 2, long, long, ShareMode.FIX_MAX - 1))
        assertEquals(ShareMode.Action.GiveUp, ShareMode.decide(true, 2, long, long, ShareMode.FIX_MAX))
    }

    @Test fun theSwitchFollowsALastingSystemChange() {
        assertEquals(ShareMode.Action.FollowOff, ShareMode.decide(true, 0, long, long, 0))
        assertEquals(ShareMode.Action.FollowOn, ShareMode.decide(false, 1, long, long, 0))
    }

    @Test fun speedIsSmoothedAndSurvivesACounterRestart() {
        val m = SpeedMeter()
        assertEquals(0L, m.update(1_000, 0))
        assertEquals(0L, m.update(1_500, 500_000)) // less than a second: no sample yet
        assertEquals(1_000_000L, m.update(2_000, 1_000_000))
        val next = m.update(3_000, 4_000_000) // a 3 MB/s second moves the average part of the way
        assertTrue(next in 1_000_001..2_999_999)
        assertEquals(next, m.update(3_500, 100)) // the count went backwards: start again, keep the last figure
    }
}
