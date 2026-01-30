package com.simplecityapps.telemetry.android

import androidx.navigation.NavController
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicReference

internal class NavigationTracker(
    private val tracer: Tracer
) {
    private val currentSpan = AtomicReference<Span?>(null)
    private val currentRoute = AtomicReference<String?>(null)

    suspend fun track(navController: NavController) {
        navController.currentBackStackEntryFlow.collectLatest { entry ->
            val route = entry.destination.route ?: return@collectLatest
            val normalizedRoute = RouteNormalizer.normalize(route)

            currentSpan.get()?.end()

            val span = tracer.spanBuilder("screen.view")
                .setAttribute("screen.name", normalizedRoute)
                .startSpan()

            currentSpan.set(span)
            currentRoute.set(normalizedRoute)
        }
    }

    fun endCurrentSpan() {
        currentSpan.getAndSet(null)?.end()
    }
}

internal object RouteNormalizer {

    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NUMERIC_PATTERN = Regex("(?<=/)\\d+(?=/|$)")
    private val ALREADY_PARAMETERIZED = Regex("\\{[^}]+\\}")

    fun normalize(route: String): String {
        if (ALREADY_PARAMETERIZED.containsMatchIn(route)) {
            return route
        }

        var normalized = route

        normalized = normalized.replace(UUID_PATTERN, "{id}")

        normalized = normalized.replace(NUMERIC_PATTERN, "{id}")

        return normalized
    }
}
