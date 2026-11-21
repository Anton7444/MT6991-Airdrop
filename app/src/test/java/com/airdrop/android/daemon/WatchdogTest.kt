package com.airdrop.android.daemon

import com.airdrop.android.daemon.Watchdog.RecvAction
import com.airdrop.android.daemon.Watchdog.SendTimeout
import com.airdrop.android.update.Updater
import org.junit.Assert.*
import org.junit.Test

class WatchdogTest {
    @Test fun unacknowledgedSendFailsAfterTheResponseDeadline() {
        assertNull(Watchdog.sendTimeout(SendState.Asking, acked = false, idleMs = Watchdog.SEND_RESPONSE_MS))
        assertEquals(SendTimeout.NoResponse, Watchdog.sendTimeout(SendState.Asking, false, Watchdog.SEND_RESPONSE_MS + 1))
    }

    @Test fun acknowledgedAskWaitsLongerForThePerson() {
        assertNull(Watchdog.sendTimeout(SendState.Asking, acked = true, idleMs = Watchdog.SEND_RESPONSE_MS + 1))
        assertNull(Watchdog.sendTimeout(SendState.Asking, true, Watchdog.ASK_ANSWER_MS))
        assertEquals(SendTimeout.NoAnswer, Watchdog.sendTimeout(SendState.Asking, true, Watchdog.ASK_ANSWER_MS + 1))
    }

    @Test fun sendingStallsOnlyAfterTheStallWindow() {
        assertNull(Watchdog.sendTimeout(SendState.Sending, true, Watchdog.SEND_STALL_MS))
        assertEquals(SendTimeout.Stalled, Watchdog.sendTimeout(SendState.Sending, true, Watchdog.SEND_STALL_MS + 1))
    }

    @Test fun silentReceiveIsAnsweredAgainOnceThenFailed() {
        assertNull(Watchdog.recvAction(received = 0, retried = false, idleMs = Watchdog.RECV_START_MS))
        assertEquals(RecvAction.AnswerAgain, Watchdog.recvAction(0, false, Watchdog.RECV_START_MS + 1))
        assertNull(Watchdog.recvAction(0, retried = true, idleMs = Watchdog.RECV_START_MS * 3))
        assertEquals(RecvAction.Fail, Watchdog.recvAction(0, true, Watchdog.RECV_START_MS * 3 + 1))
    }

    @Test fun receiveWithDataOnlyFailsOnALongStall() {
        assertNull(Watchdog.recvAction(received = 10, retried = false, idleMs = Watchdog.RECV_STALL_MS))
        assertEquals(RecvAction.Fail, Watchdog.recvAction(10, false, Watchdog.RECV_STALL_MS + 1))
    }

    @Test fun updateVersionsCompareNumerically() {
        assertTrue(Updater.isNewer("v1.0.2", "1.0.1-mt6991"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("1.0.1.1", "1.0.1"))
        assertFalse(Updater.isNewer("v1.0.1", "1.0.1-mt6991"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updater.isNewer("latest", "1.0.1"))
    }
}
