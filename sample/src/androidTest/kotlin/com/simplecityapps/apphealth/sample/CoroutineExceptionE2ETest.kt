package com.simplecityapps.apphealth.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.apphealth.android.AppHealth
import com.simplecityapps.apphealth.android.AppHealthCoroutineExceptionHandler
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CoroutineExceptionE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var otelSdk: OpenTelemetrySdk

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        AppHealth.reset()

        otelSdk = E2ETestOpenTelemetry.create(collector.endpoint, "coroutine-e2e-test")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppHealth.init(
            context = context,
            openTelemetry = otelSdk
        )

        val ready = AppHealth.awaitReady(timeoutMs = 5000)
        assertTrue(ready, "AppHealth SDK should be ready")
    }

    @After
    fun teardown() {
        AppHealth.reset()
        E2ETestOpenTelemetry.shutdown(otelSdk)
        collector.stop()
    }

    @Test
    fun unhandledCoroutineExceptionReachesCollector() {
        collector.expectLogs(1)

        val exceptionHandled = CountDownLatch(1)

        val scope = CoroutineScope(
            SupervisorJob() + AppHealthCoroutineExceptionHandler.getInstance()
        )

        scope.launch {
            try {
                throw RuntimeException("Test coroutine crash for E2E")
            } finally {
                exceptionHandled.countDown()
            }
        }

        // Wait for the coroutine to complete
        val handled = exceptionHandled.await(5, TimeUnit.SECONDS)
        assertTrue(handled, "Coroutine exception should be handled")

        // Force flush to ensure telemetry is exported
        AppHealth.forceFlush()

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive coroutine crash log")

        // Verify the log contains coroutine crash info
        val hasCoroutineCrash = collector.hasLogContaining("Coroutine") ||
            collector.hasLogContaining("coroutine") ||
            collector.hasLogContaining("RuntimeException")

        assertTrue(hasCoroutineCrash, "Log should contain coroutine exception info")
    }

    @Test
    fun coroutineExceptionLogContainsExceptionDetails() {
        collector.expectLogs(1)

        val exceptionHandled = CountDownLatch(1)

        val scope = CoroutineScope(
            SupervisorJob() + AppHealthCoroutineExceptionHandler.getInstance()
        )

        scope.launch {
            try {
                throw IllegalArgumentException("Invalid coroutine argument for testing")
            } finally {
                exceptionHandled.countDown()
            }
        }

        exceptionHandled.await(5, TimeUnit.SECONDS)
        AppHealth.forceFlush()

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive coroutine crash log")

        val logs = collector.getLogs()
        assertTrue(logs.isNotEmpty(), "Should have received log")

        val logBody = logs.first().body.clone().readUtf8()
        val containsExceptionInfo = logBody.contains("IllegalArgumentException") ||
            logBody.contains("Invalid coroutine argument")

        assertTrue(containsExceptionInfo, "Log should contain exception details")
    }
}
