package com.airdrop.android.daemon

import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test fun unknownSenderRequiresTokenAndExplicitTarget() {
        assertFalse(RelayAuth.accepts(-1, 100, true, null, "secret"))
        assertFalse(RelayAuth.accepts(-1, 100, true, "wrong", "secret"))
        assertFalse(RelayAuth.accepts(-1, 100, false, "secret", "secret"))
        assertTrue(RelayAuth.accepts(-1, 100, true, "secret", "secret"))
    }
    @Test fun identifiedOtherAppCannotUseFallback() {
        assertFalse(RelayAuth.accepts(200, 100, true, "secret", "secret"))
        assertTrue(RelayAuth.accepts(100, 100, true, null, "secret"))
    }
    @Test fun slowPreparationHasNoAcknowledgementDeadline() {
        val preparation = SendPreparation()
        assertNull(preparation.dispatchedAt)
    }
    @Test fun cancelledPreparationCannotDispatch() {
        val preparation = SendPreparation()
        preparation.cancel()
        var sent = false
        assertFalse(preparation.dispatch(123) { sent = true })
        assertFalse(sent)
    }
    @Test fun successfulDispatchStartsDeadlineOnlyOnce() {
        val preparation = SendPreparation()
        var sends = 0
        assertTrue(preparation.dispatch(123) { sends++ })
        assertFalse(preparation.dispatch(456) { sends++ })
        assertEquals(123L, preparation.dispatchedAt)
        assertEquals(1, sends)
    }
    @Test fun cancellationAfterDispatchWaitsForPublication() {
        val preparation = SendPreparation()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sender = Thread {
            preparation.dispatch(123) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                order.add("send")
            }
        }
        sender.start()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val canceller = Thread { preparation.cancel(); order.add("cancel") }
        canceller.start()
        release.countDown()
        sender.join(5000); canceller.join(5000)
        assertFalse(sender.isAlive); assertFalse(canceller.isAlive)
        assertEquals(listOf("send", "cancel"), order)
    }
    @Test fun cancelledCopyStopsBeforeReadingAnotherChunk() {
        val preparation = SendPreparation()
        preparation.cancel()
        try {
            preparation.checkActive()
            fail("Cancelled copy continued")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
    @Test fun missingTrustedPackageNeverAuthenticatesNegativeUid() {
        assertFalse(RelayAuth.accepts(-2, -2, true, null, "secret"))
    }
}
