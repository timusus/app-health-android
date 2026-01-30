package com.simplecityapps.apphealth.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplecityapps.apphealth.android.AppHealth
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AppHealth.okHttpInterceptor())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppHealth.reportFullyDrawn()

        setContent {
            MaterialTheme {
                TelemetryTestScreen(
                    onTriggerCrash = { triggerJvmCrash() },
                    onTriggerNdkCrash = { triggerNdkCrash() },
                    onTriggerNetworkRequest = { triggerNetworkRequest() }
                )
            }
        }
    }

    private fun triggerJvmCrash() {
        throw RuntimeException("Test JVM crash from sample app")
    }

    private fun triggerNdkCrash() {
        try {
            AppHealth.triggerNativeCrashForTesting()
        } catch (e: IllegalStateException) {
            Toast.makeText(this, "NDK crash handler not initialized", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerNetworkRequest() {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://httpbin.org/get")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Request complete: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Request failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }
}

@Composable
fun TelemetryTestScreen(
    onTriggerCrash: () -> Unit,
    onTriggerNdkCrash: () -> Unit,
    onTriggerNetworkRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "App Health Sample",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

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
