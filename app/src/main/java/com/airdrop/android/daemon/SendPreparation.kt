package com.airdrop.android.daemon

/** A preparation may publish exactly once; cancellation and publication are mutually exclusive. */
internal class SendPreparation {
    @Volatile var dispatchedAt: Long? = null
        private set
    @Volatile var cancelled = false
        private set

    @Synchronized fun cancel() { cancelled = true }

    @Synchronized fun dispatch(now: Long, send: () -> Unit): Boolean {
        if (cancelled || dispatchedAt != null) return false
        dispatchedAt = now
        send()
        return true
    }

    fun checkActive() {
        if (cancelled) throw java.util.concurrent.CancellationException("Send preparation cancelled")
    }
}
