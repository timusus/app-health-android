package com.simplecityapps.telemetry.sample

import android.app.Application
import com.simplecityapps.telemetry.android.Telemetry

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val endpoint = System.getProperty("telemetry.endpoint", "http://10.0.2.2:4318")

        Telemetry.init(
            context = this,
            endpoint = endpoint,
            serviceName = "telemetry-sample"
        )
    }
}
