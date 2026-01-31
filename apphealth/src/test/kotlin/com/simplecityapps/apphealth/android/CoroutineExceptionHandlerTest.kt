package com.simplecityapps.apphealth.android

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertTrue

class CoroutineExceptionHandlerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes coroutine exception to storage`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = AppHealthCoroutineExceptionHandler()
        handler.setCrashStorage(storage)

        val exception = IllegalStateException("Coroutine failed")
        val context: CoroutineContext = Job() + CoroutineName("test-coroutine")

        handler.handleException(context, exception)

        val crashFile = tempFolder.root.resolve("coroutine_crash.txt")
        assertTrue(crashFile.exists())
        val content = crashFile.readText()
        assertTrue(content.contains("IllegalStateException"))
    }

    @Test
    fun `includes coroutine name in crash file`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = AppHealthCoroutineExceptionHandler()
        handler.setCrashStorage(storage)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job() + CoroutineName("my-coroutine")

        handler.handleException(context, exception)

        val content = tempFolder.root.resolve("coroutine_crash.txt").readText()
        assertTrue(content.contains("my-coroutine"))
    }

    @Test
    fun `includes exception details in crash file`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = AppHealthCoroutineExceptionHandler()
        handler.setCrashStorage(storage)

        val exception = RuntimeException("Something went wrong")
        val context: CoroutineContext = Job()

        handler.handleException(context, exception)

        val content = tempFolder.root.resolve("coroutine_crash.txt").readText()
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("Something went wrong"))
    }

    @Test
    fun `handles missing coroutine name gracefully`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = AppHealthCoroutineExceptionHandler()
        handler.setCrashStorage(storage)

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job() // No CoroutineName

        handler.handleException(context, exception)

        val crashFile = tempFolder.root.resolve("coroutine_crash.txt")
        assertTrue(crashFile.exists())
        val content = crashFile.readText()
        assertTrue(content.contains("unknown"))
    }

    @Test
    fun `does not write if storage not set`() {
        val handler = AppHealthCoroutineExceptionHandler()
        // Don't set storage

        val exception = RuntimeException("Test")
        val context: CoroutineContext = Job()

        handler.handleException(context, exception)

        val crashFile = tempFolder.root.resolve("coroutine_crash.txt")
        assertTrue(!crashFile.exists())
    }
}
