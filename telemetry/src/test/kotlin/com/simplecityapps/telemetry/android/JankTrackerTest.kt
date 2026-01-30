package com.simplecityapps.telemetry.android

import org.junit.Test
import kotlin.test.assertEquals

class JankTrackerTest {

    @Test
    fun `categorizes frame under 16ms as normal`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 10_000_000) // 10ms

        val snapshot = aggregator.snapshot()
        assertEquals(1, snapshot.totalFrames)
        assertEquals(0, snapshot.slowFrames)
        assertEquals(0, snapshot.frozenFrames)
    }

    @Test
    fun `categorizes frame over 16ms as slow`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 20_000_000) // 20ms

        val snapshot = aggregator.snapshot()
        assertEquals(1, snapshot.totalFrames)
        assertEquals(1, snapshot.slowFrames)
        assertEquals(0, snapshot.frozenFrames)
    }

    @Test
    fun `categorizes frame over 700ms as frozen`() {
        val aggregator = FrameMetricsAggregator()

        aggregator.recordFrame(durationNanos = 800_000_000) // 800ms

        val snapshot = aggregator.snapshot()
        assertEquals(1, snapshot.totalFrames)
        assertEquals(1, snapshot.slowFrames)
        assertEquals(1, snapshot.frozenFrames)
    }

    @Test
    fun `reset clears all counters`() {
        val aggregator = FrameMetricsAggregator()
        aggregator.recordFrame(durationNanos = 800_000_000)

        aggregator.reset()

        val snapshot = aggregator.snapshot()
        assertEquals(0, snapshot.totalFrames)
        assertEquals(0, snapshot.slowFrames)
        assertEquals(0, snapshot.frozenFrames)
    }
}
