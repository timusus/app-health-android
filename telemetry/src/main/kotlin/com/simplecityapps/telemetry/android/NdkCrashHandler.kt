package com.simplecityapps.telemetry.android

import android.content.Context
import io.opentelemetry.api.logs.Logger
import java.io.File

internal class NdkCrashHandler(
    private val context: Context,
    private val logger: Logger
) {
    private val crashFile: File = File(context.filesDir, CRASH_FILE_NAME)
    private val reporter = NdkCrashReporter(logger)

    init {
        System.loadLibrary("telemetry")
    }

    fun initialize() {
        reporter.checkAndReportCrash(crashFile)
        nativeInit(crashFile.absolutePath)
    }

    private external fun nativeInit(crashFilePath: String)

    companion object {
        private const val CRASH_FILE_NAME = "native_crash.txt"
    }
}
