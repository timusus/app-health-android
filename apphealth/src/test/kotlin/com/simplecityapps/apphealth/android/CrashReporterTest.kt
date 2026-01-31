package com.simplecityapps.apphealth.android

import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var telemetry: InMemoryTelemetry
    private lateinit var reporter: CrashReporter
    private lateinit var jvmCrashFile: File
    private lateinit var coroutineCrashFile: File

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        reporter = CrashReporter(telemetry.logger)
        jvmCrashFile = File(tempFolder.root, "jvm_crash.txt")
        coroutineCrashFile = File(tempFolder.root, "coroutine_crash.txt")
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    // JVM crash tests

    @Test
    fun `reports JVM crash with ERROR severity`() {
        jvmCrashFile.writeText("""
            java.lang.NullPointerException
            Something was null
            main
            1
            java.lang.NullPointerException: Something was null
                at com.example.App.main(App.kt:10)
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.ERROR, logs[0].severity)
    }

    @Test
    fun `JVM crash log body contains exception simple name and message`() {
        jvmCrashFile.writeText("""
            java.lang.IllegalArgumentException
            Invalid argument provided
            main
            1
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val body = logs[0].body.asString()
        assertTrue(body.contains("IllegalArgumentException"))
        assertTrue(body.contains("Invalid argument provided"))
        assertEquals("JVM Crash: IllegalArgumentException: Invalid argument provided", body)
    }

    @Test
    fun `JVM crash includes crash type attribute`() {
        jvmCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            thread
            1
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val crashType = logs[0].attributes.get(AttributeKey.stringKey("crash.type"))
        assertEquals("jvm", crashType)
    }

    @Test
    fun `JVM crash includes exception type attribute`() {
        jvmCrashFile.writeText("""
            java.lang.OutOfMemoryError
            Java heap space
            main
            1
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val exceptionType = logs[0].attributes.get(AttributeKey.stringKey("exception.type"))
        assertEquals("java.lang.OutOfMemoryError", exceptionType)
    }

    @Test
    fun `JVM crash includes thread name attribute`() {
        jvmCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            worker-thread-42
            42
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val threadName = logs[0].attributes.get(AttributeKey.stringKey("thread.name"))
        assertEquals("worker-thread-42", threadName)
    }

    @Test
    fun `JVM crash deletes file after reporting`() {
        jvmCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            main
            1
            stacktrace
        """.trimIndent())
        assertTrue(jvmCrashFile.exists())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        assertFalse(jvmCrashFile.exists())
    }

    @Test
    fun `does not report when JVM crash file does not exist`() {
        assertFalse(jvmCrashFile.exists())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        assertEquals(0, telemetry.getLogRecords().size)
    }

    @Test
    fun `does not report when JVM crash file is empty`() {
        jvmCrashFile.writeText("")

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        assertEquals(0, telemetry.getLogRecords().size)
    }

    // Coroutine crash tests

    @Test
    fun `reports coroutine crash with ERROR severity`() {
        coroutineCrashFile.writeText("""
            java.lang.IllegalStateException
            Invalid state
            DefaultDispatcher-worker-1
            10
            my-coroutine
            false
            java.lang.IllegalStateException: Invalid state
                at com.example.App.doWork(App.kt:20)
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.ERROR, logs[0].severity)
    }

    @Test
    fun `coroutine crash log body contains exception simple name and message`() {
        coroutineCrashFile.writeText("""
            kotlinx.coroutines.TimeoutCancellationException
            Timed out waiting for 5000 ms
            worker
            5
            timeout-coroutine
            true
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val body = logs[0].body.asString()
        assertTrue(body.contains("TimeoutCancellationException"))
        assertTrue(body.contains("Timed out waiting for 5000 ms"))
        assertEquals("Coroutine Crash: TimeoutCancellationException: Timed out waiting for 5000 ms", body)
    }

    @Test
    fun `coroutine crash includes crash type attribute`() {
        coroutineCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            thread
            1
            coro
            false
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val crashType = logs[0].attributes.get(AttributeKey.stringKey("crash.type"))
        assertEquals("coroutine", crashType)
    }

    @Test
    fun `coroutine crash includes coroutine name attribute`() {
        coroutineCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            thread
            1
            my-special-coroutine#42
            false
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val coroutineName = logs[0].attributes.get(AttributeKey.stringKey("coroutine.name"))
        assertEquals("my-special-coroutine#42", coroutineName)
    }

    @Test
    fun `coroutine crash includes cancelled status attribute`() {
        coroutineCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            thread
            1
            coro
            true
            stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        val cancelled = logs[0].attributes.get(AttributeKey.booleanKey("coroutine.cancelled"))
        assertEquals(true, cancelled)
    }

    @Test
    fun `coroutine crash deletes file after reporting`() {
        coroutineCrashFile.writeText("""
            java.lang.RuntimeException
            msg
            thread
            1
            coro
            false
            stacktrace
        """.trimIndent())
        assertTrue(coroutineCrashFile.exists())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        assertFalse(coroutineCrashFile.exists())
    }

    @Test
    fun `reports both JVM and coroutine crashes when both exist`() {
        jvmCrashFile.writeText("""
            java.lang.NullPointerException
            NPE message
            main
            1
            jvm stacktrace
        """.trimIndent())

        coroutineCrashFile.writeText("""
            java.lang.IllegalStateException
            ISE message
            worker
            2
            my-coro
            false
            coroutine stacktrace
        """.trimIndent())

        reporter.checkAndReportCrashes(jvmCrashFile, coroutineCrashFile)

        val logs = telemetry.getLogRecords()
        assertEquals(2, logs.size)

        val jvmLog = logs.find { it.attributes.get(AttributeKey.stringKey("crash.type")) == "jvm" }
        val coroutineLog = logs.find { it.attributes.get(AttributeKey.stringKey("crash.type")) == "coroutine" }

        assertTrue(jvmLog != null)
        assertTrue(coroutineLog != null)
        assertTrue(jvmLog.body.asString().contains("NullPointerException"))
        assertTrue(coroutineLog.body.asString().contains("IllegalStateException"))
    }
}
