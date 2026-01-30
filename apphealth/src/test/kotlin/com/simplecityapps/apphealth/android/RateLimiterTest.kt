package com.simplecityapps.apphealth.android

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun `allows requests up to limit`() {
        val limiter = RateLimiter(maxPerMinute = 3)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `blocks requests after limit reached`() {
        val limiter = RateLimiter(maxPerMinute = 2)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `refills tokens after one minute`() {
        var currentTime = 0L
        val limiter = RateLimiter(maxPerMinute = 2) { currentTime }

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())

        // Advance time by 59 seconds - should still be blocked
        currentTime = 59_000L
        assertFalse(limiter.tryAcquire())

        // Advance time to 60 seconds - should refill
        currentTime = 60_000L
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `handles zero limit`() {
        val limiter = RateLimiter(maxPerMinute = 0)

        assertFalse(limiter.tryAcquire())
    }
}
