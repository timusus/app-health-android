package com.simplecityapps.telemetry.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplecityapps.telemetry.android.Telemetry
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Telemetry.okHttpInterceptor())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Telemetry.reportFullyDrawn()

        setContent {
            MaterialTheme {
                TelemetryTestScreen(
                    onTriggerCrash = { triggerJvmCrash() },
                    onTriggerNdkCrash = { triggerNdkCrash() },
                    onTriggerNetworkRequest = { triggerNetworkRequest() },
                    onTriggerCustomSpan = { triggerCustomSpan() },
                    onTriggerCustomEvent = { triggerCustomEvent() }
                )
            }
        }
    }

    private fun triggerJvmCrash() {
        throw RuntimeException("Test JVM crash from sample app")
    }

    private fun triggerNdkCrash() {
        Telemetry.logEvent("ndk_crash_requested")
    }

    private fun triggerNetworkRequest() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://httpbin.org/get")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    Telemetry.logEvent("network_request_complete", mapOf(
                        "status_code" to response.code
                    ))
                }
            } catch (e: Exception) {
                Telemetry.logEvent("network_request_failed", mapOf(
                    "error" to (e.message ?: "unknown")
                ))
            }
        }.start()
    }

    private fun triggerCustomSpan() {
        val span = Telemetry.startSpan("sample.custom_operation")
        Thread.sleep(100)
        span.end()
    }

    private fun triggerCustomEvent() {
        Telemetry.logEvent("sample.button_clicked", mapOf(
            "button_name" to "custom_event",
            "timestamp" to System.currentTimeMillis()
        ))
    }
}

@Composable
fun TelemetryTestScreen(
    onTriggerCrash: () -> Unit,
    onTriggerNdkCrash: () -> Unit,
    onTriggerNetworkRequest: () -> Unit,
    onTriggerCustomSpan: () -> Unit,
    onTriggerCustomEvent: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Telemetry Sample",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onTriggerCustomEvent) {
            Text("Trigger Custom Event")
        }

        Button(onClick = onTriggerCustomSpan) {
            Text("Trigger Custom Span")
        }

        Button(onClick = onTriggerNetworkRequest) {
            Text("Trigger Network Request")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Crash Tests (will crash app)",
            style = MaterialTheme.typography.titleSmall
        )

        Button(
            onClick = onTriggerCrash,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Trigger JVM Crash")
        }

        Button(
            onClick = onTriggerNdkCrash,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Trigger NDK Crash")
        }
    }
}
