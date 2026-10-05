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

## Output-end masking (opt-in)

`SensitiveValueRedactor` works at the call site, so a log line that forgets it leaks. `LogMasker` is the last line of
defence at the **output end**: logback converters mask the finished message and stack trace of every line.
It is off until the app wires it, because the logback configuration belongs to the app.

1. Human-readable lines — in the app's `logback-spring.xml`, include the skeleton fragment after Boot's defaults:

   ```xml
   <configuration>
       <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
       <include resource="dev/sumin/skeleton/logging/logback-masking.xml"/>
       <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
       <root level="INFO"><appender-ref ref="CONSOLE"/></root>
   </configuration>
   ```

2. Structured (JSON) logs — in `application.yml`:

   ```yaml
   logging:
     structured:
       format:
         console: ecs        # or logstash / gelf
       json:
         customizer: dev.sumin.skeleton.common.logging.MaskingStructuredLoggingCustomizer
   ```

Default rules (always on once wired): `Authorization` / `Cookie` / `Set-Cookie` header lines, `Bearer <value>`, JWT-looking
values (`eyJ….….…`) and `name=value` / `"name":"value"` pairs whose name ends in token, secret, password or apikey.
Everything else (trace ids, timestamps, words) is left alone. Tune with `skeleton.redaction.output.*`:

```yaml
skeleton:
  redaction:
    replacement: "[REDACTED]"
    output:
      mask-emails: false                       # true: alice@domain.com -> a***@d***.com
      patterns:                                # your own secret shapes, regex
        - '[A-Za-z0-9_-]{43}'                  # e.g. a 43-char base64url token: the whole match is replaced
        - '(?i)\bcode=(?<value>\d{4,8})\b'      # a named group `value` replaces only that part
```

The converters are created by logback before Spring starts, so lines logged during startup use the default rules; once the
context is up the configured rules take over. An invalid pattern fails startup with the pattern in the message.

## Rate limit store

`InMemoryFixedWindowRateLimitStore` (the default when `skeleton.web.rate-limit.enabled=true`) keeps each counter's own window end, so
a call with a short window never evicts another key's longer-window counter, and expired windows are swept at most once a
second while requests arrive. Call `sweep()` from a scheduler if the server can go quiet with many keys; it reads the app's
`TimeProvider` (`TimeProvider.asClock()` bridges to any code that wants a `java.time.Clock`).
