# Logging and Debug Categories

The platform keeps normal implementation logs on class loggers and exposes
stable debug categories for operational switches.

## Standard Categories

```yaml
logging:
  level:
    skeleton.debug.request: INFO
    skeleton.debug.external-http: INFO
    skeleton.debug.auth: INFO
    skeleton.debug.payment: INFO
    skeleton.debug.sql: OFF
    skeleton.debug.async: INFO
```

- `skeleton.debug.request`: inbound HTTP start/end lines from `RequestLoggingFilter`.
- `skeleton.debug.external-http`: outbound HTTP completion and WebClient debug lines.
- `skeleton.debug.auth`: authentication and emergency access diagnostics.
- `skeleton.debug.payment`: payment routing/provider diagnostics.
- `skeleton.debug.sql`: reserved for SQL/persistence diagnostics; keep `OFF` unless needed.
- `skeleton.debug.async`: async task-group summaries and failed task details,
  including non-null trace/span/run/account MDC context.

Use class loggers for implementation details:

```kotlin
private val log = LoggerFactory.getLogger(javaClass)
```

Use stable debug categories when operators should enable a whole concern without
knowing concrete class names:

```kotlin
private val debugLog = SkeletonLoggers.externalHttp()
```

Guard expensive debug output:

```kotlin
if (debugLog.isDebugEnabled) {
    debugLog.debug("payload={}", redactor.redactJsonString(payload))
}
```

All request, header, query, and body diagnostics must pass through
`SensitiveValueRedactor` before logging.
