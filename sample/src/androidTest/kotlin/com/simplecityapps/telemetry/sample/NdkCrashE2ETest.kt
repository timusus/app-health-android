package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/**
 * E2E test for NDK crash reporting.
 *
 * Tests the cold-start crash reporting path by simulating what happens after
 * a native crash: the crash file exists, and on next init it gets reported.
 */
@RunWith(AndroidJUnit4::class)
class NdkCrashE2ETest {

    private lateinit var collector: MockOtlpCollector
    private lateinit var crashFile: File

    @Before
    fun setup() {
        collector = MockOtlpCollector()
        collector.start()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        crashFile = File(context.filesDir, "native_crash.txt")

        // Ensure clean state
        crashFile.delete()
        Telemetry.reset()
    }

    @After
    fun teardown() {
        crashFile.delete()
        Telemetry.reset()
        collector.stop()
    }

    @Test
    fun nativeCrashFileIsReportedOnColdStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Simulate what the native crash handler writes
        crashFile.writeText("""
            SIGSEGV
            0x0000007f8a3b4c5d
            #0 0x7f8a3b4c5d libcrash.so!crash_function+0x10
            #1 0x7f8a3b4c6e libapp.so!triggerNativeCrash+0x20
            #2 0x7f8a3b4c7f libc.so!__libc_init+0x30
        """.trimIndent())

        collector.expectLogs(1)

        // Initialize Telemetry - this should detect and report the crash
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "ndk-crash-e2e-test"
        )

        // Wait for crash to be reported
        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive crash log within timeout")

        val logs = collector.getLogs()
        assertTrue(logs.isNotEmpty(), "Should have received crash log")

        // Verify the log contains native crash information
        val logBody = logs.first().body.clone().readUtf8()
        assertTrue(
            logBody.contains("SIGSEGV") ||
            logBody.contains("native") ||
            logBody.contains("FATAL"),
            "Log should contain native crash information: $logBody"
        )
    }

    @Test
    fun nativeCrashIncludesBacktraceInLog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        crashFile.writeText("""
            SIGABRT
            0xdeadbeef
            #0 libart.so!abort+0x10
            #1 libapp.so!handleError+0x20
        """.trimIndent())

        collector.expectLogs(1)

        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "ndk-crash-e2e-test"
        )

        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive crash log")

        val logs = collector.getLogs()
        val logBody = logs.first().body.clone().readUtf8()

        // The backtrace should be included in the log attributes
        assertTrue(
            logBody.contains("backtrace") ||
            logBody.contains("libart") ||
            logBody.contains("SIGABRT"),
            "Log should contain backtrace or signal info"
        )
    }

    @Test
    fun crashFileIsDeletedAfterReporting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        crashFile.writeText("SIGSEGV\n0x0\nbacktrace")
        assertTrue(crashFile.exists(), "Crash file should exist before init")

        collector.expectLogs(1)

        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "ndk-crash-e2e-test"
        )

        collector.awaitLogs(timeoutSeconds = 30)

        // Give it a moment for file deletion
        Thread.sleep(500)

        assertTrue(!crashFile.exists(), "Crash file should be deleted after reporting")
    }

    @Test
    fun noCrashFileResultsInNoExtraLogs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Ensure no crash file exists
        crashFile.delete()

        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "ndk-crash-e2e-test"
        )

        // Wait a bit and verify no crash logs were sent
        Thread.sleep(2000)

        val logs = collector.getLogs()
        val hasCrashLog = logs.any { request ->
            val body = request.body.clone().readUtf8()
            body.contains("SIGSEGV") || body.contains("SIGABRT") || body.contains("Native Crash")
        }

        assertTrue(!hasCrashLog, "Should not have crash logs when no crash file exists")
    }
}
