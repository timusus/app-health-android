package com.simplecityapps.telemetry.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplecityapps.telemetry.android.Telemetry
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real E2E test for NDK crash handling.
 *
 * This test requires Android Test Orchestrator to run each test in a separate process.
 * Test execution order is important:
 * 1. test1_TriggerNativeCrash - Initializes SDK and triggers a real native crash
 * 2. test2_VerifyCrashWasReported - Verifies the crash was captured and reported
 *
 * The first test will crash the process. Orchestrator will start a new process for
 * the second test, which verifies the crash was properly recorded.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NdkCrashRealE2ETest {

    companion object {
        // Shared file to coordinate between test phases
        private const val PHASE_FILE = "ndk_crash_test_phase.txt"
        private const val PHASE_CRASHED = "crashed"
    }

    private lateinit var collector: MockOtlpCollector
    private lateinit var crashFile: File
    private lateinit var phaseFile: File

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        crashFile = File(context.filesDir, "native_crash.txt")
        phaseFile = File(context.filesDir, PHASE_FILE)
    }

    @After
    fun teardown() {
        if (::collector.isInitialized) {
            try {
                collector.stop()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Phase 1: Initialize SDK and trigger a native crash.
     * This test WILL crash the process - that's expected.
     * Orchestrator will then run test2 in a fresh process.
     */
    @Test
    fun test1_TriggerNativeCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Clean up any previous test state
        crashFile.delete()
        phaseFile.delete()

        // Start a collector (won't receive the crash since we're about to die)
        collector = MockOtlpCollector()
        collector.start()

        // Initialize Telemetry - this installs the signal handler
        Telemetry.reset()
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "ndk-crash-real-test"
        )

        // Wait for init to complete
        Thread.sleep(1000)

        // Mark that we're about to crash
        phaseFile.writeText(PHASE_CRASHED)

        // Trigger the native crash - this will kill the process
        // The signal handler should write crash info to crashFile
        try {
            Telemetry.triggerNativeCrashForTesting()
            // If we get here, something is wrong
            fail("Should have crashed")
        } catch (e: Exception) {
            // This shouldn't happen - the crash should kill us before we catch anything
            fail("Native crash should have terminated the process: ${e.message}")
        }
    }

    /**
     * Phase 2: Verify the crash was captured and reported.
     * This runs in a fresh process after Orchestrator restarts.
     */
    @Test
    fun test2_VerifyCrashWasReported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Check if phase 1 ran (it should have crashed)
        if (!phaseFile.exists() || phaseFile.readText() != PHASE_CRASHED) {
            // Phase 1 didn't run or didn't crash - skip this test
            println("Skipping test2: Phase 1 didn't run. Run tests with Orchestrator.")
            return
        }

        // Verify crash file exists (signal handler should have written it)
        assertTrue(
            crashFile.exists(),
            "Crash file should exist after native crash. " +
            "This means the signal handler didn't write the crash file."
        )

        val crashContent = crashFile.readText()
        assertTrue(
            crashContent.contains("SIGSEGV") || crashContent.contains("SIG"),
            "Crash file should contain signal info. Content: $crashContent"
        )

        // Now start collector and init SDK to verify crash is reported
        collector = MockOtlpCollector()
        collector.start()
        collector.expectLogs(1)

        Telemetry.reset()
        Telemetry.init(
            context = context,
            endpoint = collector.endpoint,
            serviceName = "ndk-crash-real-test"
        )

        // Wait for crash to be reported
        val received = collector.awaitLogs(timeoutSeconds = 30)
        assertTrue(received, "Should receive crash log within timeout")

        val logs = collector.getLogs()
        assertTrue(logs.isNotEmpty(), "Should have received crash log")

        // Verify it's a native crash log
        val hasNativeCrash = logs.any { request ->
            val body = request.body.clone().readUtf8()
            body.contains("native") ||
            body.contains("SIGSEGV") ||
            body.contains("FATAL") ||
            body.contains("Native Crash")
        }
        assertTrue(hasNativeCrash, "Log should be a native crash report")

        // Clean up
        phaseFile.delete()
        crashFile.delete()
    }
}
