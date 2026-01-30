package com.simplecityapps.telemetry.android

import android.content.Context
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.io.File

internal class NdkCrashHandler(
    private val context: Context,
    private val logger: Logger
) {
    private val crashFile: File = File(context.filesDir, CRASH_FILE_NAME)

    init {
        System.loadLibrary("telemetry")
    }

    fun initialize() {
        checkForPreviousCrash()

        nativeInit(crashFile.absolutePath)
    }

    private fun checkForPreviousCrash() {
        if (!crashFile.exists()) return

        runCatching {
            val crashData = crashFile.readText()
            if (crashData.isNotEmpty()) reportNativeCrash(crashData)
        }
        crashFile.delete()
    }

    private fun reportNativeCrash(crashData: String) {
        val lines = crashData.lines()
        val signal = lines.getOrNull(0) ?: "unknown"
        val faultAddress = lines.getOrNull(1) ?: "unknown"
        val backtrace = lines.drop(2).joinToString("\n")

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("crash.type"), "native")
            .put(AttributeKey.stringKey("signal"), signal)
            .put(AttributeKey.stringKey("fault.address"), faultAddress)
            .put(AttributeKey.stringKey("backtrace"), backtrace)
            .build()

        logger.logRecordBuilder()
            .setSeverity(Severity.FATAL)
            .setBody("Native Crash: $signal")
            .setAllAttributes(attributes)
            .emit()
    }

    private external fun nativeInit(crashFilePath: String)

    companion object {
        private const val CRASH_FILE_NAME = "native_crash.txt"
    }
}
