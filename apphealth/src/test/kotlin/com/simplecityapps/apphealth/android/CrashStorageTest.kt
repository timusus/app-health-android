package com.simplecityapps.apphealth.android

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writeJvmCrash creates file with exception type on line 0`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("Test message")
        val thread = Thread("test-thread")

        storage.writeJvmCrash(thread, exception)

        val file = tempFolder.root.resolve("jvm_crash.txt")
        assertTrue(file.exists())
        val lines = file.readLines()
        assertEquals("java.lang.RuntimeException", lines[0])
    }

    @Test
    fun `writeJvmCrash writes exception message on line 1`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("Test message")
        val thread = Thread("test-thread")

        storage.writeJvmCrash(thread, exception)

        val lines = tempFolder.root.resolve("jvm_crash.txt").readLines()
        assertEquals("Test message", lines[1])
    }

    @Test
    fun `writeJvmCrash writes empty string for null message`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException()
        val thread = Thread("test-thread")

        storage.writeJvmCrash(thread, exception)

        val lines = tempFolder.root.resolve("jvm_crash.txt").readLines()
        assertEquals("", lines[1])
    }

    @Test
    fun `writeJvmCrash writes thread name on line 2`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("msg")
        val thread = Thread("my-thread-name")

        storage.writeJvmCrash(thread, exception)

        val lines = tempFolder.root.resolve("jvm_crash.txt").readLines()
        assertEquals("my-thread-name", lines[2])
    }

    @Test
    fun `writeJvmCrash writes thread id on line 3`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("msg")
        val thread = Thread("t")

        storage.writeJvmCrash(thread, exception)

        val lines = tempFolder.root.resolve("jvm_crash.txt").readLines()
        assertEquals(thread.id.toString(), lines[3])
    }

    @Test
    fun `writeJvmCrash writes stacktrace starting at line 4`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("msg")
        val thread = Thread("t")

        storage.writeJvmCrash(thread, exception)

        val content = tempFolder.root.resolve("jvm_crash.txt").readText()
        assertTrue(content.contains("java.lang.RuntimeException: msg"))
        assertTrue(content.contains("at "))
    }

    @Test
    fun `writeJvmCrash overwrites existing file`() {
        val storage = CrashStorage(tempFolder.root)
        val file = tempFolder.root.resolve("jvm_crash.txt")
        file.writeText("old content")

        storage.writeJvmCrash(Thread("t"), RuntimeException("new"))

        val content = file.readText()
        assertTrue(content.contains("new"))
        assertTrue(!content.contains("old content"))
    }

    @Test
    fun `writeCoroutineCrash creates file with exception type on line 0`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = IllegalStateException("Failed")
        val thread = Thread("worker-1")

        storage.writeCoroutineCrash(thread, exception, "my-coroutine", false)

        val file = tempFolder.root.resolve("coroutine_crash.txt")
        assertTrue(file.exists())
        val lines = file.readLines()
        assertEquals("java.lang.IllegalStateException", lines[0])
    }

    @Test
    fun `writeCoroutineCrash writes coroutine name on line 4`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("msg")
        val thread = Thread("t")

        storage.writeCoroutineCrash(thread, exception, "my-coroutine", false)

        val lines = tempFolder.root.resolve("coroutine_crash.txt").readLines()
        assertEquals("my-coroutine", lines[4])
    }

    @Test
    fun `writeCoroutineCrash writes cancelled status on line 5`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("msg")
        val thread = Thread("t")

        storage.writeCoroutineCrash(thread, exception, "coro", true)

        val lines = tempFolder.root.resolve("coroutine_crash.txt").readLines()
        assertEquals("true", lines[5])
    }

    @Test
    fun `writeCoroutineCrash writes stacktrace starting at line 6`() {
        val storage = CrashStorage(tempFolder.root)
        val exception = RuntimeException("msg")
        val thread = Thread("t")

        storage.writeCoroutineCrash(thread, exception, "coro", false)

        val content = tempFolder.root.resolve("coroutine_crash.txt").readText()
        assertTrue(content.contains("java.lang.RuntimeException: msg"))
    }
}
