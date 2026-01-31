package com.simplecityapps.apphealth.android

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

class CrashHandlerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes crash to storage and chains to previous handler`() {
        var chainedHandlerCalled = false
        val previousHandler = Thread.UncaughtExceptionHandler { _, _ ->
            chainedHandlerCalled = true
        }

        val storage = CrashStorage(tempFolder.root)
        val handler = CrashHandler(storage, previousHandler)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        val crashFile = tempFolder.root.resolve("jvm_crash.txt")
        assertTrue(crashFile.exists())
        val content = crashFile.readText()
        assertTrue(content.contains("java.lang.RuntimeException"))
        assertTrue(content.contains("Test crash"))
        assertTrue(chainedHandlerCalled, "Should chain to previous handler")
    }

    @Test
    fun `includes thread name in crash file`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = CrashHandler(storage, null)
        val exception = RuntimeException("Test crash")
        val thread = Thread("test-thread-name")

        handler.uncaughtException(thread, exception)

        val content = tempFolder.root.resolve("jvm_crash.txt").readText()
        assertTrue(content.contains("test-thread-name"))
    }

    @Test
    fun `includes exception stacktrace in crash file`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = CrashHandler(storage, null)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        val content = tempFolder.root.resolve("jvm_crash.txt").readText()
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("Test crash"))
        assertTrue(content.contains("at "))
    }

    @Test
    fun `handles null previous handler gracefully`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = CrashHandler(storage, null)
        val exception = RuntimeException("Test crash")

        handler.uncaughtException(Thread.currentThread(), exception)

        val crashFile = tempFolder.root.resolve("jvm_crash.txt")
        assertTrue(crashFile.exists())
    }
}
