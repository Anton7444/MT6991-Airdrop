package com.airdrop.android.daemon

/** Pure timeout rules for the transfer watchdog, kept free of Android types so they can be unit-tested. */
internal object Watchdog {
    const val SEND_RESPONSE_MS = 20_000L
    const val ASK_ANSWER_MS = 90_000L
    const val SEND_STALL_MS = 45_000L
    const val RECV_START_MS = 8_000L
    const val RECV_STALL_MS = 60_000L

    enum class SendTimeout { NoResponse, NoAnswer, Stalled }

    /** Why a dispatched send should be failed after [idleMs] without an event, or null to keep waiting. */
    fun sendTimeout(state: SendState, acked: Boolean, idleMs: Long): SendTimeout? = when {
        // The bridge has no idempotency key: retrying can send the same files twice.
        !acked && idleMs > SEND_RESPONSE_MS -> SendTimeout.NoResponse
        state == SendState.Asking && acked && idleMs > ASK_ANSWER_MS -> SendTimeout.NoAnswer
        state == SendState.Sending && idleMs > SEND_STALL_MS -> SendTimeout.Stalled
        else -> null
    }

    enum class RecvAction { AnswerAgain, Fail }

    /** What to do about an accepted receive that has been idle for [idleMs], or null to keep waiting. */
    fun recvAction(received: Long, retried: Boolean, idleMs: Long): RecvAction? = when {
        received == 0L && !retried && idleMs > RECV_START_MS -> RecvAction.AnswerAgain
        idleMs > RECV_STALL_MS || (received == 0L && retried && idleMs > RECV_START_MS * 3) -> RecvAction.Fail
        else -> null
    }
}

/** Smoothed bytes per second from a running byte count; sampled at most once a second. */
internal class SpeedMeter {
    private var at = 0L
    private var bytes = 0L
    private var speed = 0L

    fun update(now: Long, total: Long): Long {
        if (at == 0L || total < bytes) { at = now; bytes = total; return speed }
        val dt = now - at
        if (dt >= 1_000L) {
            val inst = (total - bytes) * 1_000L / dt
            speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10
            at = now; bytes = total
        }
        return speed
    }
}

/**
 * Reconciles the app's AirDrop switch with the system share mode (0 off, 1 everyone, 2 contacts only).
 * The radio only runs in mode 1, and the share app falls back from 1 to 2 by itself.
 */
internal object ShareMode {
    const val SETTLE_MS = 12_000L
    const val FIX_WINDOW_MS = 10 * 60_000L
    const val FIX_MAX = 3

    enum class Action { Agree, Wait, FollowOn, FollowOff, Restore, GiveUp }

    /**
     * [oddForMs] is how long the two have disagreed (null when this is the first sighting), [sinceToggleMs] how
     * long ago the user last used the switch, [recentFixes] how often mode 1 was restored in [FIX_WINDOW_MS].
     */
    fun decide(switchOn: Boolean, share: Int, oddForMs: Long?, sinceToggleMs: Long, recentFixes: Int): Action = when {
        share < 0 || (share == 1) == switchOn -> Action.Agree
        // The switch writes the setting asynchronously, and the radio watchdog cycles it 1 -> 0 -> 1 on purpose.
        sinceToggleMs < SETTLE_MS -> Action.Agree
        oddForMs == null || oddForMs < SETTLE_MS -> Action.Wait
        !switchOn -> Action.FollowOn
        share == 0 -> Action.FollowOff
        recentFixes >= FIX_MAX -> Action.GiveUp
        else -> Action.Restore
    }
}
