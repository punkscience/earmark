package com.derpy.earmarks.nostr

import com.derpy.earmarks.data.Earmark
import kotlinx.coroutines.delay

/**
 * Publishes the earmark list, retrying with exponential backoff
 * (1s/2s/4s/8s/16s). Returns the ack count from the first attempt that reached
 * at least one relay, or 0 if every attempt failed.
 *
 * Both callers publish a list the user has already been told is gone — a
 * delete, or an orphan cleanup that follows blobs being removed — so giving up
 * after one failed round-trip would leave the published list pointing at blobs
 * that no longer exist. Exceptions are caught per attempt so a transient
 * signing or socket failure does not end the loop early.
 */
suspend fun NostrService.publishWithRetry(
    privKeyHex: String,
    earmarks: List<Earmark>,
    maxAttempts: Int = 5
): Int {
    var delayMs = 1_000L
    repeat(maxAttempts) { attempt ->
        try {
            val acks = publishEarmarks(privKeyHex, earmarks)
            if (acks > 0) return acks
        } catch (_: Exception) {
            // fall through to backoff
        }
        if (attempt < maxAttempts - 1) {
            delay(delayMs)
            delayMs *= 2
        }
    }
    return 0
}
