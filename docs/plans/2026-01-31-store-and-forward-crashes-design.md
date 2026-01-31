# Store-and-Forward Crash Handling

## Problem

JVM and coroutine crashes currently emit directly to OTel Logger during the crash. The process dies before the batch exporter can flush, so crash data is often lost.

NDK crashes already use store-and-forward (write to disk, report next launch) which is reliable.

## Solution

Apply the same store-and-forward pattern to JVM and coroutine crashes.

## Components

### New Components

| Component | Responsibility |
|-----------|----------------|
| `CrashStorage` | Writes crash data to disk during crash (synchronous, no OTel) |
| `CrashReporter` | Reads crash files on next launch, reports via OTel Logger |

### Modified Components

| Component | Change |
|-----------|--------|
| `CrashHandler` | Replace `Logger` with `CrashStorage` |
| `AppHealthCoroutineExceptionHandler` | Replace `Logger` with `CrashStorage` |
| `AppHealth` | Create storage/reporter, wire up on init |

## Flow

```
Crash occurs → CrashStorage writes to disk → Process dies
Next launch → CrashReporter reads file → Reports via OTel → Deletes file
```

## File Format

**File locations** (in `context.filesDir`):
- `jvm_crash.txt` - JVM uncaught exceptions
- `coroutine_crash.txt` - Coroutine exceptions

**JVM crash format:**
```
Line 0: exception.type (e.g., "java.lang.NullPointerException")
Line 1: exception.message (empty string if null)
Line 2: thread.name
Line 3: thread.id
Line 4+: stacktrace
```

**Coroutine crash format:**
```
Line 0: exception.type
Line 1: exception.message
Line 2: thread.name
Line 3: thread.id
Line 4: coroutine.name
Line 5: coroutine.cancelled (true/false)
Line 6+: stacktrace
```

Line-based format chosen over JSON for simplicity and to match NDK pattern.

## CrashStorage

```kotlin
internal class CrashStorage(context: Context) {

    private val jvmCrashFile = File(context.filesDir, "jvm_crash.txt")
    private val coroutineCrashFile = File(context.filesDir, "coroutine_crash.txt")

    fun writeJvmCrash(thread: Thread, throwable: Throwable)

    fun writeCoroutineCrash(
        thread: Thread,
        throwable: Throwable,
        coroutineName: String,
        isCancelled: Boolean
    )
}
```

Implementation notes:
- Uses `FileOutputStream` with `write()` + `flush()` + `close()` — no buffering
- Wraps everything in try-catch — never crash while handling a crash
- Overwrites existing file (single crash per type)
- No logging or OTel calls — pure file I/O

## CrashReporter

```kotlin
internal class CrashReporter(private val logger: Logger) {

    fun checkAndReportCrashes(
        jvmCrashFile: File,
        coroutineCrashFile: File
    )
}
```

Behavior:
- Called once during `AppHealth.init()` (before installing handlers)
- For each file that exists: read, parse, emit log, delete
- Wrapped in try-catch — corrupted files get deleted without crashing

Log format unchanged from current behavior:
- JVM: `"JVM Crash: {SimpleName}: {message}"` with severity `ERROR`
- Coroutine: `"Coroutine Crash: {SimpleName}: {message}"` with severity `ERROR`

## Handler Changes

**CrashHandler:**
```kotlin
internal class CrashHandler(
    private val crashStorage: CrashStorage,  // was: Logger
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
}
```

**AppHealthCoroutineExceptionHandler:**
- Replace `Logger` with `CrashStorage`
- Replace `setLogger()` with `setCrashStorage()`
- Extract coroutine context, call `crashStorage.writeCoroutineCrash()`

## Initialization Flow

```kotlin
private fun initializeCollectors(...) {

    // 1. Create storage and reporter
    val crashStorage = CrashStorage(application)
    val crashReporter = CrashReporter(logger)

    // 2. Report any crashes from previous session FIRST
    crashReporter.checkAndReportCrashes(
        jvmCrashFile = File(application.filesDir, "jvm_crash.txt"),
        coroutineCrashFile = File(application.filesDir, "coroutine_crash.txt")
    )

    // 3. Install handlers for this session
    if (userConfig.crashHandling) {
        CrashHandler.install(crashStorage)
    }

    if (userConfig.coroutineCrashHandling) {
        AppHealthCoroutineExceptionHandler.getInstance()
            .setCrashStorage(crashStorage)
    }

    // ... rest unchanged
}
```

Report previous crashes before installing new handlers to ensure clean state.

## Testing

**Unit tests:**

| Component | Test Strategy |
|-----------|---------------|
| `CrashStorage` | Pass temp directory, verify file contents after write |
| `CrashReporter` | Create crash files with known content, verify OTel logs via `InMemoryTelemetry` |
| `CrashHandler` | Inject fake `CrashStorage`, verify `writeJvmCrash` called |
| `AppHealthCoroutineExceptionHandler` | Inject fake `CrashStorage`, verify `writeCoroutineCrash` called |

**E2E test:**
- Trigger JVM crash in sample app
- Restart app
- Verify crash log arrives at `MockOtlpCollector`
