package com.simplecityapps.telemetry.android

import okhttp3.Interceptor
import okhttp3.Response

internal class NetworkInterceptor(
    private val urlSanitizerProvider: () -> ((String) -> String)?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Full implementation in Task 8
        return chain.proceed(chain.request())
    }
}
