# Owner alerts (`alert`, `alert-jdbc`)

The skeleton's answer to "how do I learn that production is broken" is **not Sentry**: it is an alert to the owner, over a Discord-compatible
webhook (and optionally mail), for the few things that need a human *now*. Ported from the Ovation project's hardening work and made generic.

## What it does

```
OwnerAlerts.emit(kind, key, detail)         never throws into the business flow
   |  inside a transaction: after commit (rolled back = it did not happen); immediate=true: right away (failure alerts)
   v
AlertStore.record(kind, key, ...)           one row per (kind, key); inside min-interval -> folded (suppressed++), else send
   |  memory by default; alert-jdbc = alerts, one conditional UPDATE decides, safe across instances
   v
AlertChannel(s)                             webhook (Discord JSON); mail if notification-mail's MailSender + mail-to
      each on its own virtual thread, 3 attempts with doubling backoff; one failing channel never blocks another
```

If recording itself fails and the kind is `directFallback` (the 5xx surge), the webhook is called **directly** once per interval, so the alert survives a dead database.

## Off by default

Nothing is sent until `skeleton.alert.webhook-url` is set (use an environment variable — the address is the secret):

```yaml
skeleton:
  alert:
    webhook-url: ${ALERT_WEBHOOK_URL:}
```

Without it `OwnerAlerts` still exists (so app code can inject it) and only writes a log line at the kind's severity.
The 5xx filter, the dead-job hook and the startup-failure attempt are registered only when the address is set. The address is never logged and never put into an exception message.
Mail is an additional channel next to the webhook: it needs `notification-mail` on the classpath (a `MailSender` bean) and `skeleton.alert.mail-to`.

## Built-in triggers

| kind | severity | when | default interval |
|---|---|---|---|
| `SERVER_ERROR_SURGE` | CRITICAL | 20 responses >= 500 (or exceptions leaving the filter chain) within 1 minute (`surge.*`), counted in memory | 15 min |
| `STARTUP_FAILED` | CRITICAL | the context failed to start (`ApplicationFailedEvent`); webhook only, sent directly, skipped for `startup-failure.skip-profiles` (default `local`) | every time |
| `JOB_DEAD` | ERROR | a `job-queue-jdbc` job became DEAD (retries used, `PermanentJobFailureException`, no handler, or a stale job that used all attempts). Needs `job-queue-jdbc` on the classpath; the detail has the exception **class** only, never its message | 30 min per job type |
| `TEST` | INFO | `alerts.emit(BuiltInAlertKind.TEST, "manual")` to check the wiring | 1 min |

## Your own kinds

`AlertKind` is an interface, not a closed enum:

```kotlin
enum class ShopAlert(override val severity: AlertSeverity, override val title: String, override val minInterval: Duration = Duration.ZERO) : AlertKind {
    ORDER_STUCK(AlertSeverity.WARN, "An order is stuck", Duration.ofMinutes(10)),
}
alerts.emit(ShopAlert.ORDER_STUCK, key = orderId, detail = "waiting for payment")   // key = what is counted separately
```

Override an interval from yml: `skeleton.alert.intervals.order-stuck: 1h` (any case / separator of the kind name). Never put secrets in `detail`; it goes through `LogMasker` anyway and is cut to 1,000 characters.

## alert-jdbc

Add `implementation(project(":modules:alert-jdbc"))` (and a dialect module) to share the folding decision across instances and restarts and keep a list (`JdbcAlertStore.recent`).
The table `alerts` has one row per (kind, key): `occurrences`, `suppressed_count`, `last_suppressed`. The decision is one conditional
`UPDATE ... WHERE sent_at <= cutoff`, so concurrent callers are ordered by the row lock and exactly one sends (proved with 8 threads on both PostgreSQL and MySQL).
`skeleton.alert-jdbc.retention.*` deletes old rows; it is **off by default** (a retention policy is the app's choice).

## Limits

- The direct fallback is webhook-only (mail needs SMTP and the queue). It does not leave a row, and a restart forgets the in-memory interval.
- If the process is dead, or the webhook service itself is unreachable, no alert can leave — keep an external uptime check as well.
- Delivery retries are in memory (3 attempts); a durable queue-backed delivery is not included.
