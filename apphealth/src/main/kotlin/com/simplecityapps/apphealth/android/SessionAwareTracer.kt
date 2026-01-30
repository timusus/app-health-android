package com.simplecityapps.apphealth.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.TimeUnit

/**
 * A Tracer wrapper that automatically adds session.id to every span.
 *
 * This allows collectors to use the tracer without knowing about sessions -
 * the session ID is transparently added to all spans.
 */
internal class SessionAwareTracer(
    private val delegate: Tracer,
    private val sessionProvider: () -> String
) : Tracer {

    override fun spanBuilder(spanName: String): SpanBuilder {
        return SessionAwareSpanBuilder(delegate.spanBuilder(spanName), sessionProvider)
    }
}

/**
 * A SpanBuilder wrapper that adds session.id when the span is started.
 */
internal class SessionAwareSpanBuilder(
    private val delegate: SpanBuilder,
    private val sessionProvider: () -> String
) : SpanBuilder {

    override fun setParent(context: Context): SpanBuilder {
        delegate.setParent(context)
        return this
    }

    override fun setNoParent(): SpanBuilder {
        delegate.setNoParent()
        return this
    }

    override fun addLink(spanContext: SpanContext): SpanBuilder {
        delegate.addLink(spanContext)
        return this
    }

    override fun addLink(spanContext: SpanContext, attributes: Attributes): SpanBuilder {
        delegate.addLink(spanContext, attributes)
        return this
    }

    override fun setAttribute(key: String, value: String): SpanBuilder {
        delegate.setAttribute(key, value)
        return this
    }

    override fun setAttribute(key: String, value: Long): SpanBuilder {
        delegate.setAttribute(key, value)
        return this
    }

    override fun setAttribute(key: String, value: Double): SpanBuilder {
        delegate.setAttribute(key, value)
        return this
    }

    override fun setAttribute(key: String, value: Boolean): SpanBuilder {
        delegate.setAttribute(key, value)
        return this
    }

    override fun <T : Any> setAttribute(key: AttributeKey<T>, value: T): SpanBuilder {
        delegate.setAttribute(key, value)
        return this
    }

    override fun setSpanKind(spanKind: SpanKind): SpanBuilder {
        delegate.setSpanKind(spanKind)
        return this
    }

    override fun setStartTimestamp(startTimestamp: Long, unit: TimeUnit): SpanBuilder {
        delegate.setStartTimestamp(startTimestamp, unit)
        return this
    }

    override fun startSpan(): Span {
        // Add session.id attribute just before starting the span
        delegate.setAttribute("session.id", sessionProvider())
        return delegate.startSpan()
    }
}
