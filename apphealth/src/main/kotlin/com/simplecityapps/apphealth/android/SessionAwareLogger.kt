package com.simplecityapps.apphealth.android

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A Logger wrapper that automatically adds session.id to every log record.
 *
 * This allows collectors to use the logger without knowing about sessions -
 * the session ID is transparently added to all logs.
 */
internal class SessionAwareLogger(
    private val delegate: Logger,
    private val sessionProvider: () -> String
) : Logger {

    override fun logRecordBuilder(): LogRecordBuilder {
        return SessionAwareLogRecordBuilder(delegate.logRecordBuilder(), sessionProvider)
    }
}

/**
 * A LogRecordBuilder wrapper that adds session.id when the log is emitted.
 */
internal class SessionAwareLogRecordBuilder(
    private val delegate: LogRecordBuilder,
    private val sessionProvider: () -> String
) : LogRecordBuilder {

    override fun setTimestamp(timestamp: Long, unit: TimeUnit): LogRecordBuilder {
        delegate.setTimestamp(timestamp, unit)
        return this
    }

    override fun setTimestamp(instant: Instant): LogRecordBuilder {
        delegate.setTimestamp(instant)
        return this
    }

    override fun setObservedTimestamp(timestamp: Long, unit: TimeUnit): LogRecordBuilder {
        delegate.setObservedTimestamp(timestamp, unit)
        return this
    }

    override fun setObservedTimestamp(instant: Instant): LogRecordBuilder {
        delegate.setObservedTimestamp(instant)
        return this
    }

    override fun setContext(context: Context): LogRecordBuilder {
        delegate.setContext(context)
        return this
    }

    override fun setSeverity(severity: Severity): LogRecordBuilder {
        delegate.setSeverity(severity)
        return this
    }

    override fun setSeverityText(severityText: String): LogRecordBuilder {
        delegate.setSeverityText(severityText)
        return this
    }

    override fun setBody(body: String): LogRecordBuilder {
        delegate.setBody(body)
        return this
    }

    override fun <T : Any> setAttribute(key: AttributeKey<T>, value: T): LogRecordBuilder {
        delegate.setAttribute(key, value)
        return this
    }

    override fun setAllAttributes(attributes: Attributes): LogRecordBuilder {
        delegate.setAllAttributes(attributes)
        return this
    }

    override fun emit() {
        // Add session.id attribute just before emitting
        delegate.setAttribute(AttributeKey.stringKey("session.id"), sessionProvider())
        delegate.emit()
    }
}
