package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CrashHandlerE2ETest {

    private lateinit var collector: MockOtlpCollector

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        Telemetry.reset()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "crash-e2e-test"
        )

        Thread.sleep(500)
    }

    @After
    fun teardown() {
        Telemetry.reset()
        collector.stop()
    }

    @Test
    fun jvmExceptionOnBackgroundThreadEmitsErrorLog() {
        collector.expectLogs(1)

        // Install handler that catches after telemetry processes it
        var crashProcessed = false
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Let telemetry handler run first (it's chained)
            currentHandler?.uncaughtException(thread, throwable)
            crashProcessed = true
        }

        // Trigger crash on background thread
        val crashThread = Thread({
            throw RuntimeException("Test crash for E2E verification")
        }, "test-crash-thread")
        crashThread.start()
        crashThread.join(5000)

        val received = collector.awaitLogs(timeoutSeconds = 30)

        assertTrue(crashProcessed, "Crash should have been processed")
        assertTrue(received, "Should receive crash log within timeout")
        assertTrue(collector.getLogs().isNotEmpty(), "Should have received crash log")
    }

    @Test
    fun crashLogContainsExceptionDetails() {
        collector.expectLogs(1)

        var crashProcessed = false
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            currentHandler?.uncaughtException(thread, throwable)
            crashProcessed = true
        }

        val crashThread = Thread({
            throw IllegalStateException("Detailed crash message for testing")
        }, "detail-test-thread")
        crashThread.start()
        crashThread.join(5000)

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive crash log")

        val logs = collector.getLogs()
        assertTrue(logs.isNotEmpty())

        val logBody = logs.first().body.clone().readUtf8()
        val containsExceptionInfo = logBody.contains("IllegalStateException") ||
            logBody.contains("Detailed crash message") ||
            logBody.contains("detail-test-thread")

        assertTrue(containsExceptionInfo, "Log should contain exception details")
    }

    @Test
    fun multipleCrashesEachEmitLog() {
        collector.expectLogs(2)

        var crashCount = 0
        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            currentHandler?.uncaughtException(thread, throwable)
            crashCount++
        }

        // First crash
        val thread1 = Thread({ throw RuntimeException("Crash 1") }, "crash-1")
        thread1.start()
        thread1.join(2000)

        // Second crash
        val thread2 = Thread({ throw RuntimeException("Crash 2") }, "crash-2")
        thread2.start()
        thread2.join(2000)

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive crash logs")
        assertTrue(
            collector.getLogs().size >= 2,
            "Should have received at least 2 crash logs"
        )
    }
}
