package com.simplecityapps.telemetry.android

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import org.junit.Test
import kotlin.test.assertNotNull

class OtelConfigTest {

    @Test
    fun `creates tracer with service name`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        val tracer: Tracer = config.tracer
        assertNotNull(tracer)
    }

    @Test
    fun `creates logger for crash reporting`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        val logger: Logger = config.logger
        assertNotNull(logger)
    }

    @Test
    fun `creates meter for frame metrics`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        val meter: Meter = config.meter
        assertNotNull(meter)
    }

    @Test
    fun `shutdown completes without error`() {
        val config = OtelConfig(
            endpoint = "http://localhost:4318",
            serviceName = "test-service",
            sessionId = "test-session",
            deviceModel = "Pixel",
            deviceManufacturer = "Google",
            osVersion = "34",
            appVersion = "1.0.0",
            appVersionCode = 1L
        )

        config.shutdown()
    }
}
