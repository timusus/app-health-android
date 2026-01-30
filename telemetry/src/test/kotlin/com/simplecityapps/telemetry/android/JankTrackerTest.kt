package com.simplecityapps.telemetry.android

import org.junit.Test
import kotlin.test.assertEquals

class JankTrackerTest {

    @Test
    fun `categorizes frame under 16ms as normal`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 10_000_000) // 10ms

        assertEquals(1, aggregator.totalFrames)
        assertEquals(0, aggregator.slowFrames)
        assertEquals(0, aggregator.frozenFrames)
    }

    @Test
    fun `categorizes frame over 16ms as slow`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 20_000_000) // 20ms

        assertEquals(1, aggregator.totalFrames)
        assertEquals(1, aggregator.slowFrames)
        assertEquals(0, aggregator.frozenFrames)
    }

    @Test
    fun `categorizes frame over 700ms as frozen`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 800_000_000) // 800ms

        assertEquals(1, aggregator.totalFrames)
        assertEquals(1, aggregator.slowFrames)
        assertEquals(1, aggregator.frozenFrames)
    }

    @Test
    fun `reset clears all counters`() {
        val aggregator = FrameMetricsAggregator()
        aggregator.recordFrame(durationNanos = 800_000_000)

        aggregator.reset()

        assertEquals(0, aggregator.totalFrames)
        assertEquals(0, aggregator.slowFrames)
        assertEquals(0, aggregator.frozenFrames)
    }
}
