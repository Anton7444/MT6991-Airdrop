package com.airdrop.android.daemon

import java.security.MessageDigest

internal object RelayAuth {
    fun accepts(sender: Int, trusted: Int, addressed: Boolean, given: String?, expected: String): Boolean {
        if (sender >= 0) return trusted >= 0 && sender == trusted
        return sender == -1 && addressed && !given.isNullOrEmpty() && expected.isNotEmpty() &&
            MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}
