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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NdkCrashReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var telemetry: InMemoryTelemetry
    private lateinit var reporter: NdkCrashReporter
    private lateinit var crashFile: File

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        reporter = NdkCrashReporter(telemetry.logger)
        crashFile = tempFolder.newFile("native_crash.txt")
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `reports crash with FATAL severity`() {
        crashFile.writeText("""
            SIGSEGV
            0x0000007f
            frame1
            frame2
        """.trimIndent())

        reporter.checkAndReportCrash(crashFile)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.FATAL, logs[0].severity)
    }

    @Test
    fun `parses signal from crash data`() {
        crashFile.writeText("""
            SIGABRT
            0xdeadbeef
            backtrace line
        """.trimIndent())

        reporter.checkAndReportCrash(crashFile)

        val logs = telemetry.getLogRecords()
        assertTrue(logs[0].body.asString().contains("SIGABRT"))

        val signal = logs[0].attributes.get(AttributeKey.stringKey("signal"))
        assertEquals("SIGABRT", signal)
    }

    @Test
    fun `parses fault address from crash data`() {
        crashFile.writeText("""
            SIGSEGV
            0xdeadbeef
            backtrace
        """.trimIndent())

        reporter.checkAndReportCrash(crashFile)

        val logs = telemetry.getLogRecords()
        val faultAddress = logs[0].attributes.get(AttributeKey.stringKey("fault.address"))
        assertEquals("0xdeadbeef", faultAddress)
    }

    @Test
    fun `parses backtrace from crash data`() {
        crashFile.writeText("""
            SIGSEGV
            0x0
            #0 libcrash.so!crash_function+0x10
            #1 libapp.so!main+0x20
            #2 libc.so!__libc_init+0x30
        """.trimIndent())

        reporter.checkAndReportCrash(crashFile)

        val logs = telemetry.getLogRecords()
        val backtrace = logs[0].attributes.get(AttributeKey.stringKey("backtrace"))
        assertNotNull(backtrace)
        assertTrue(backtrace.contains("crash_function"))
        assertTrue(backtrace.contains("main"))
    }

    @Test
    fun `sets crash type to native`() {
        crashFile.writeText("SIGSEGV\n0x0\nbacktrace")

        reporter.checkAndReportCrash(crashFile)

        val logs = telemetry.getLogRecords()
        val crashType = logs[0].attributes.get(AttributeKey.stringKey("crash.type"))
        assertEquals("native", crashType)
    }

    @Test
    fun `deletes crash file after reporting`() {
        crashFile.writeText("SIGSEGV\n0x0\nbacktrace")
        assertTrue(crashFile.exists())

        reporter.checkAndReportCrash(crashFile)

        assertFalse(crashFile.exists())
    }

    @Test
    fun `returns false when no crash file exists`() {
        crashFile.delete()

        val result = reporter.checkAndReportCrash(crashFile)

        assertFalse(result)
        assertEquals(0, telemetry.getLogRecords().size)
    }

    @Test
    fun `returns false for empty crash file`() {
        crashFile.writeText("")

        val result = reporter.checkAndReportCrash(crashFile)

        assertFalse(result)
        assertEquals(0, telemetry.getLogRecords().size)
    }

    @Test
    fun `returns true when crash is reported`() {
        crashFile.writeText("SIGSEGV\n0x0\nbacktrace")

        val result = reporter.checkAndReportCrash(crashFile)

        assertTrue(result)
    }

    @Test
    fun `handles malformed crash data gracefully`() {
        crashFile.writeText("just one line")

        reporter.checkAndReportCrash(crashFile)

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        // Should still emit with "unknown" for missing fields
        val signal = logs[0].attributes.get(AttributeKey.stringKey("signal"))
        assertEquals("just one line", signal)
    }
}
