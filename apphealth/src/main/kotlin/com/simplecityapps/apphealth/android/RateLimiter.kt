package com.simplecityapps.apphealth.android

/**
 * Simple token bucket rate limiter.
 *
 * Allows up to [maxPerMinute] acquisitions per minute, then blocks until the next minute.
 */
internal class RateLimiter(
    private val maxPerMinute: Int,
    private val timeSource: () -> Long = System::currentTimeMillis
) {
    private var tokens: Int = maxPerMinute
    private var lastRefill: Long = timeSource()

    @Synchronized
    fun tryAcquire(): Boolean {
        refillIfNeeded()
        return if (tokens > 0) {
            tokens--
            true
        } else {
            false
        }
    }

    private fun refillIfNeeded() {
        val now = timeSource()
        val elapsed = now - lastRefill
        if (elapsed >= 60_000) {
            tokens = maxPerMinute
            lastRefill = now
        }
    }
}
