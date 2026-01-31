# Store-and-Forward Crash Handling Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reliably capture JVM and coroutine crashes by writing to disk during crash and reporting on next launch.

**Architecture:** New `CrashStorage` writes crash data to disk (used during crash). New `CrashReporter` reads and reports crashes via OTel on next launch. Handlers updated to use storage instead of logger directly.

**Tech Stack:** Kotlin, JUnit 4, TemporaryFolder rule for file tests, InMemoryTelemetry for OTel assertions

**Reference:** See `docs/plans/2026-01-31-store-and-forward-crashes-design.md` for full design rationale.

---

## Task 1: Create CrashStorage

**Files:**
- Create: `apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashStorage.kt`
- Create: `apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CrashStorageTest.kt`

### Step 1: Write failing test for JVM crash storage

```kotlin
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
}
```

### Step 2: Run test to verify it fails

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashStorageTest" -q`

Expected: FAIL - CrashStorage class not found

### Step 3: Write minimal CrashStorage implementation

```kotlin
package com.simplecityapps.apphealth.android

import java.io.File
import java.io.FileOutputStream

internal class CrashStorage(private val directory: File) {

    private val jvmCrashFile = File(directory, "jvm_crash.txt")
    private val coroutineCrashFile = File(directory, "coroutine_crash.txt")

    fun writeJvmCrash(thread: Thread, throwable: Throwable) {
        try {
            val content = buildString {
                appendLine(throwable.javaClass.name)
                appendLine(throwable.message ?: "")
                appendLine(thread.name)
                appendLine(thread.id)
                append(throwable.stackTraceToString())
            }
            writeToFile(jvmCrashFile, content)
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    fun writeCoroutineCrash(
        thread: Thread,
        throwable: Throwable,
        coroutineName: String,
        isCancelled: Boolean
    ) {
        try {
            val content = buildString {
                appendLine(throwable.javaClass.name)
                appendLine(throwable.message ?: "")
                appendLine(thread.name)
                appendLine(thread.id)
                appendLine(coroutineName)
                appendLine(isCancelled)
                append(throwable.stackTraceToString())
            }
            writeToFile(coroutineCrashFile, content)
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    private fun writeToFile(file: File, content: String) {
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
        }
    }
}
```

### Step 4: Run test to verify it passes

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashStorageTest" -q`

Expected: PASS

### Step 5: Add remaining JVM crash storage tests

Add these tests to `CrashStorageTest.kt`:

```kotlin
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
```

### Step 6: Run all CrashStorage tests

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashStorageTest" -q`

Expected: All PASS

### Step 7: Add coroutine crash storage tests

Add these tests to `CrashStorageTest.kt`:

```kotlin
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
```

### Step 8: Run all CrashStorage tests

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashStorageTest" -q`

Expected: All PASS

### Step 9: Commit

```bash
git add apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashStorage.kt \
        apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CrashStorageTest.kt
git commit -m "feat: add CrashStorage for writing crash data to disk"
```

---

## Task 2: Create CrashReporter

**Files:**
- Create: `apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashReporter.kt`
- Create: `apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CrashReporterTest.kt`

### Step 1: Write failing test for JVM crash reporting

```kotlin
package com.simplecityapps.apphealth.android

import com.simplecityapps.apphealth.android.fakes.InMemoryTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var telemetry: InMemoryTelemetry
    private lateinit var reporter: CrashReporter

    @Before
    fun setup() {
        telemetry = InMemoryTelemetry()
        reporter = CrashReporter(telemetry.logger)
    }

    @After
    fun teardown() {
        telemetry.shutdown()
    }

    @Test
    fun `reports JVM crash with ERROR severity`() {
        val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
        jvmCrashFile.writeText("""
            java.lang.RuntimeException
            Test message
            main
            1
            java.lang.RuntimeException: Test message
            	at com.example.Test.method(Test.kt:10)
        """.trimIndent())

        reporter.checkAndReportCrashes(
            jvmCrashFile = jvmCrashFile,
            coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
        )

        val logs = telemetry.getLogRecords()
        assertEquals(1, logs.size)
        assertEquals(Severity.ERROR, logs[0].severity)
    }
}
```

### Step 2: Run test to verify it fails

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashReporterTest" -q`

Expected: FAIL - CrashReporter class not found

### Step 3: Write CrashReporter implementation

```kotlin
package com.simplecityapps.apphealth.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.File

internal class CrashReporter(private val logger: Logger) {

    fun checkAndReportCrashes(jvmCrashFile: File, coroutineCrashFile: File) {
        checkAndReportJvmCrash(jvmCrashFile)
        checkAndReportCoroutineCrash(coroutineCrashFile)
    }

    private fun checkAndReportJvmCrash(crashFile: File): Boolean {
        if (!crashFile.exists()) return false

        return runCatching {
            val content = crashFile.readText()
            if (content.isEmpty()) return false

            val lines = content.lines()
            val exceptionType = lines.getOrNull(0) ?: "unknown"
            val exceptionMessage = lines.getOrNull(1) ?: ""
            val threadName = lines.getOrNull(2) ?: "unknown"
            val threadId = lines.getOrNull(3)?.toLongOrNull() ?: 0L
            val stacktrace = lines.drop(4).joinToString("\n")

            val simpleTypeName = exceptionType.substringAfterLast('.')

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("crash.type"), "jvm")
                .put(AttributeKey.stringKey("exception.type"), exceptionType)
                .put(AttributeKey.stringKey("exception.message"), exceptionMessage)
                .put(AttributeKey.stringKey("exception.stacktrace"), stacktrace)
                .put(AttributeKey.stringKey("thread.name"), threadName)
                .put(AttributeKey.longKey("thread.id"), threadId)
                .build()

            logger.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("JVM Crash: $simpleTypeName: $exceptionMessage")
                .setAllAttributes(attributes)
                .emit()

            true
        }.onSuccess {
            crashFile.delete()
        }.onFailure {
            crashFile.delete()
        }.getOrDefault(false)
    }

    private fun checkAndReportCoroutineCrash(crashFile: File): Boolean {
        if (!crashFile.exists()) return false

        return runCatching {
            val content = crashFile.readText()
            if (content.isEmpty()) return false

            val lines = content.lines()
            val exceptionType = lines.getOrNull(0) ?: "unknown"
            val exceptionMessage = lines.getOrNull(1) ?: ""
            val threadName = lines.getOrNull(2) ?: "unknown"
            val threadId = lines.getOrNull(3)?.toLongOrNull() ?: 0L
            val coroutineName = lines.getOrNull(4) ?: "unknown"
            val isCancelled = lines.getOrNull(5)?.toBoolean() ?: false
            val stacktrace = lines.drop(6).joinToString("\n")

            val simpleTypeName = exceptionType.substringAfterLast('.')

            val attributes = Attributes.builder()
                .put(AttributeKey.stringKey("crash.type"), "coroutine")
                .put(AttributeKey.stringKey("exception.type"), exceptionType)
                .put(AttributeKey.stringKey("exception.message"), exceptionMessage)
                .put(AttributeKey.stringKey("exception.stacktrace"), stacktrace)
                .put(AttributeKey.stringKey("thread.name"), threadName)
                .put(AttributeKey.longKey("thread.id"), threadId)
                .put(AttributeKey.stringKey("coroutine.name"), coroutineName)
                .put(AttributeKey.booleanKey("coroutine.cancelled"), isCancelled)
                .build()

            logger.logRecordBuilder()
                .setSeverity(Severity.ERROR)
                .setBody("Coroutine Crash: $simpleTypeName: $exceptionMessage")
                .setAllAttributes(attributes)
                .emit()

            true
        }.onSuccess {
            crashFile.delete()
        }.onFailure {
            crashFile.delete()
        }.getOrDefault(false)
    }
}
```

### Step 4: Run test to verify it passes

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashReporterTest" -q`

Expected: PASS

### Step 5: Add remaining JVM crash reporter tests

Add to `CrashReporterTest.kt`:

```kotlin
@Test
fun `JVM crash log body contains exception simple name and message`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("""
        java.lang.NullPointerException
        Cannot invoke method on null
        main
        1
        stacktrace here
    """.trimIndent())

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    )

    val logs = telemetry.getLogRecords()
    assertEquals("JVM Crash: NullPointerException: Cannot invoke method on null", logs[0].body.asString())
}

@Test
fun `JVM crash includes crash type attribute`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("java.lang.RuntimeException\nmsg\nmain\n1\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    )

    val logs = telemetry.getLogRecords()
    val crashType = logs[0].attributes.get(AttributeKey.stringKey("crash.type"))
    assertEquals("jvm", crashType)
}

@Test
fun `JVM crash includes exception type attribute`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("java.lang.IllegalArgumentException\nmsg\nmain\n1\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    )

    val logs = telemetry.getLogRecords()
    val exceptionType = logs[0].attributes.get(AttributeKey.stringKey("exception.type"))
    assertEquals("java.lang.IllegalArgumentException", exceptionType)
}

@Test
fun `JVM crash includes thread name attribute`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("java.lang.RuntimeException\nmsg\nworker-thread\n42\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    )

    val logs = telemetry.getLogRecords()
    val threadName = logs[0].attributes.get(AttributeKey.stringKey("thread.name"))
    assertEquals("worker-thread", threadName)
}

@Test
fun `JVM crash deletes file after reporting`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("java.lang.RuntimeException\nmsg\nmain\n1\nstack")
    assertTrue(jvmCrashFile.exists())

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    )

    assertFalse(jvmCrashFile.exists())
}

@Test
fun `does not report when JVM crash file does not exist`() {
    val jvmCrashFile = tempFolder.root.resolve("jvm_crash.txt") // does not exist
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = coroutineCrashFile
    )

    assertEquals(0, telemetry.getLogRecords().size)
}

@Test
fun `does not report when JVM crash file is empty`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("")

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    )

    assertEquals(0, telemetry.getLogRecords().size)
}
```

### Step 6: Run all CrashReporter tests

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashReporterTest" -q`

Expected: All PASS

### Step 7: Add coroutine crash reporter tests

Add to `CrashReporterTest.kt`:

```kotlin
@Test
fun `reports coroutine crash with ERROR severity`() {
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("""
        java.lang.IllegalStateException
        Coroutine failed
        DefaultDispatcher-worker-1
        42
        my-coroutine
        false
        stacktrace here
    """.trimIndent())

    reporter.checkAndReportCrashes(
        jvmCrashFile = tempFolder.newFile("jvm_crash.txt"),
        coroutineCrashFile = coroutineCrashFile
    )

    val logs = telemetry.getLogRecords()
    assertEquals(1, logs.size)
    assertEquals(Severity.ERROR, logs[0].severity)
}

@Test
fun `coroutine crash log body contains exception simple name and message`() {
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("""
        kotlinx.coroutines.JobCancellationException
        Job was cancelled
        worker
        1
        coro
        true
        stack
    """.trimIndent())

    reporter.checkAndReportCrashes(
        jvmCrashFile = tempFolder.newFile("jvm_crash.txt"),
        coroutineCrashFile = coroutineCrashFile
    )

    val logs = telemetry.getLogRecords()
    assertEquals("Coroutine Crash: JobCancellationException: Job was cancelled", logs[0].body.asString())
}

@Test
fun `coroutine crash includes crash type attribute`() {
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("java.lang.RuntimeException\nmsg\nt\n1\ncoro\nfalse\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = tempFolder.newFile("jvm_crash.txt"),
        coroutineCrashFile = coroutineCrashFile
    )

    val logs = telemetry.getLogRecords()
    val crashType = logs[0].attributes.get(AttributeKey.stringKey("crash.type"))
    assertEquals("coroutine", crashType)
}

@Test
fun `coroutine crash includes coroutine name attribute`() {
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("java.lang.RuntimeException\nmsg\nt\n1\nmy-special-coroutine\nfalse\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = tempFolder.newFile("jvm_crash.txt"),
        coroutineCrashFile = coroutineCrashFile
    )

    val logs = telemetry.getLogRecords()
    val coroutineName = logs[0].attributes.get(AttributeKey.stringKey("coroutine.name"))
    assertEquals("my-special-coroutine", coroutineName)
}

@Test
fun `coroutine crash includes cancelled status attribute`() {
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("java.lang.RuntimeException\nmsg\nt\n1\ncoro\ntrue\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = tempFolder.newFile("jvm_crash.txt"),
        coroutineCrashFile = coroutineCrashFile
    )

    val logs = telemetry.getLogRecords()
    val cancelled = logs[0].attributes.get(AttributeKey.booleanKey("coroutine.cancelled"))
    assertEquals(true, cancelled)
}

@Test
fun `coroutine crash deletes file after reporting`() {
    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("java.lang.RuntimeException\nmsg\nt\n1\ncoro\nfalse\nstack")
    assertTrue(coroutineCrashFile.exists())

    reporter.checkAndReportCrashes(
        jvmCrashFile = tempFolder.newFile("jvm_crash.txt"),
        coroutineCrashFile = coroutineCrashFile
    )

    assertFalse(coroutineCrashFile.exists())
}

@Test
fun `reports both JVM and coroutine crashes when both exist`() {
    val jvmCrashFile = tempFolder.newFile("jvm_crash.txt")
    jvmCrashFile.writeText("java.lang.RuntimeException\njvm msg\nmain\n1\nstack")

    val coroutineCrashFile = tempFolder.newFile("coroutine_crash.txt")
    coroutineCrashFile.writeText("java.lang.IllegalStateException\ncoro msg\nworker\n2\ncoro\nfalse\nstack")

    reporter.checkAndReportCrashes(
        jvmCrashFile = jvmCrashFile,
        coroutineCrashFile = coroutineCrashFile
    )

    val logs = telemetry.getLogRecords()
    assertEquals(2, logs.size)
}
```

### Step 8: Run all CrashReporter tests

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashReporterTest" -q`

Expected: All PASS

### Step 9: Commit

```bash
git add apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashReporter.kt \
        apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CrashReporterTest.kt
git commit -m "feat: add CrashReporter for reading and reporting crashes on launch"
```

---

## Task 3: Update CrashHandler to use CrashStorage

**Files:**
- Modify: `apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashHandler.kt`
- Modify: `apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CrashHandlerTest.kt`

### Step 1: Create FakeCrashStorage test helper

Add to test file:

```kotlin
package com.simplecityapps.apphealth.android

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
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
```

### Step 2: Run tests to verify they fail

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashHandlerTest" -q`

Expected: FAIL - CrashHandler constructor signature mismatch

### Step 3: Update CrashHandler implementation

```kotlin
package com.simplecityapps.apphealth.android

internal class CrashHandler(
    private val crashStorage: CrashStorage,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            crashStorage.writeJvmCrash(thread, throwable)
        } catch (e: Exception) {
            // Never crash while handling a crash
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(crashStorage: CrashStorage) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            val handler = CrashHandler(crashStorage, previousHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
```

### Step 4: Run tests to verify they pass

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CrashHandlerTest" -q`

Expected: All PASS

### Step 5: Commit

```bash
git add apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashHandler.kt \
        apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CrashHandlerTest.kt
git commit -m "refactor: update CrashHandler to use CrashStorage"
```

---

## Task 4: Update AppHealthCoroutineExceptionHandler to use CrashStorage

**Files:**
- Modify: `apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashHandler.kt` (contains both classes)
- Modify: `apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CoroutineExceptionHandlerTest.kt`

### Step 1: Update CoroutineExceptionHandlerTest

```kotlin
package com.simplecityapps.apphealth.android

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoroutineExceptionHandlerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes coroutine exception to storage`() {
        val storage = CrashStorage(tempFolder.root)
        val handler = AppHealthCoroutineExceptionHandler.getInstance()
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
        val handler = AppHealthCoroutineExceptionHandler.getInstance()
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
        val handler = AppHealthCoroutineExceptionHandler.getInstance()
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
        val handler = AppHealthCoroutineExceptionHandler.getInstance()
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
```

### Step 2: Run tests to verify they fail

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CoroutineExceptionHandlerTest" -q`

Expected: FAIL - setCrashStorage method not found

### Step 3: Update AppHealthCoroutineExceptionHandler implementation

Update in `CrashHandler.kt`:

```kotlin
class AppHealthCoroutineExceptionHandler : kotlinx.coroutines.CoroutineExceptionHandler {

    @Volatile
    private var crashStorage: CrashStorage? = null

    override val key: CoroutineContext.Key<*> = kotlinx.coroutines.CoroutineExceptionHandler

    fun setCrashStorage(storage: CrashStorage) {
        this.crashStorage = storage
    }

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val storage = crashStorage ?: return

        try {
            val coroutineName = context[kotlinx.coroutines.CoroutineName]?.name ?: "unknown"
            val job = context[kotlinx.coroutines.Job]
            val currentThread = Thread.currentThread()
            val isCancelled = job?.isCancelled ?: false

            storage.writeCoroutineCrash(currentThread, exception, coroutineName, isCancelled)
        } catch (e: Exception) {
            // Never crash while handling a crash
        }
    }

    companion object {
        @Volatile
        private var instance: AppHealthCoroutineExceptionHandler? = null

        fun getInstance(): AppHealthCoroutineExceptionHandler {
            return instance ?: synchronized(this) {
                instance ?: AppHealthCoroutineExceptionHandler().also { instance = it }
            }
        }
    }
}
```

### Step 4: Run tests to verify they pass

Run: `./gradlew :apphealth:test --tests "com.simplecityapps.apphealth.android.CoroutineExceptionHandlerTest" -q`

Expected: All PASS

### Step 5: Commit

```bash
git add apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/CrashHandler.kt \
        apphealth/src/test/kotlin/com/simplecityapps/apphealth/android/CoroutineExceptionHandlerTest.kt
git commit -m "refactor: update AppHealthCoroutineExceptionHandler to use CrashStorage"
```

---

## Task 5: Update AppHealth initialization

**Files:**
- Modify: `apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/AppHealth.kt`

### Step 1: Update initializeCollectors to wire up new components

In `AppHealth.kt`, update the `initializeCollectors` method:

```kotlin
private fun initializeCollectors(
    application: Application,
    tracer: Tracer,
    logger: Logger,
    meter: Meter,
    openTelemetry: OpenTelemetry,
    userConfig: AppHealthConfig
) {
    // 1. Create crash storage and reporter
    val crashStorage = CrashStorage(application.filesDir)
    val crashReporter = CrashReporter(logger)

    // 2. Report any crashes from previous session FIRST
    crashReporter.checkAndReportCrashes(
        jvmCrashFile = java.io.File(application.filesDir, "jvm_crash.txt"),
        coroutineCrashFile = java.io.File(application.filesDir, "coroutine_crash.txt")
    )

    // 3. JVM Crash Handler
    if (userConfig.crashHandling) {
        CrashHandler.install(crashStorage)
    }

    // 4. Coroutine Exception Handler
    if (userConfig.coroutineCrashHandling) {
        AppHealthCoroutineExceptionHandler.getInstance().setCrashStorage(crashStorage)
    }

    // 5. ANR Watchdog
    if (userConfig.anrDetection) {
        val anrWatchdog = AnrWatchdog(logger)
        anrWatchdog.start()
        AnrWatchdog.checkHistoricalAnrs(application, logger)
    }

    // ... rest unchanged (startup tracer, lifecycle, jank, navigation, ndk)
}
```

### Step 2: Run all unit tests to verify nothing broke

Run: `./gradlew :apphealth:test -q`

Expected: All PASS

### Step 3: Commit

```bash
git add apphealth/src/main/kotlin/com/simplecityapps/apphealth/android/AppHealth.kt
git commit -m "feat: wire up store-and-forward crash handling in AppHealth.init"
```

---

## Task 6: Run full test suite and verify

### Step 1: Run all unit tests

Run: `./gradlew :apphealth:test`

Expected: All PASS

### Step 2: Run E2E tests (requires device/emulator)

Run: `./gradlew :sample:connectedAndroidTest`

Expected: All PASS (existing E2E tests should still work)

### Step 3: Final commit if any cleanup needed

If all tests pass, the implementation is complete.

---

## Summary

| Task | Component | Description |
|------|-----------|-------------|
| 1 | CrashStorage | Write crash data to disk |
| 2 | CrashReporter | Read and report crashes on launch |
| 3 | CrashHandler | Update to use CrashStorage |
| 4 | AppHealthCoroutineExceptionHandler | Update to use CrashStorage |
| 5 | AppHealth | Wire up new components |
| 6 | Verification | Run full test suite |
