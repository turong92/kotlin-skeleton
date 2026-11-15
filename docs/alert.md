# Owner alerts (`alert`, `alert-jdbc`)

The skeleton's answer to "how do I learn that production is broken" is **not Sentry**: it is an alert to the owner, over a Discord-compatible
webhook (and optionally mail), for the few things that need a human *now*. Ported from a real-world project's hardening work and made generic.

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
| `CLIENT_ERROR_SURGE` | WARN | 10 client error reports within 5 minutes (`client-errors.surge.*`) — only when the client error path below is on | 15 min |
| `TEST` | INFO | `alerts.emit(BuiltInAlertKind.TEST, "manual")` to check the wiring | 1 min |

## Errors from the user's device (web / app)

Without this, nobody learns that the page or the app broke on someone's phone. The client packages (react-skeleton `@skeleton/api-client` `createErrorReporter`, flutter-skeleton `skeleton_error_report`) post what they caught to `POST /api/v1/client-errors`;
the server writes **one structured log line** per report and raises **one folded alert** when they pile up. No table, no Sentry — the client talks to an `ErrorReporter` interface, so a real service can be plugged in later without touching the screens.

```yaml
skeleton:
  alert:
    client-errors:
      enabled: true          # default false: without it the path does not exist (404)
```

- **Open path**: `POST /api/v1/client-errors`, no login (an error on the login screen matters too). It is the only path this switch opens.
- **Body** (JSON, or `text/plain` for `navigator.sendBeacon`): `message` (required), `platform`, `source`, `name`, `stack`, `componentStack`, `route`, `release`, `traceId` (32 hex — the trace id of the device's **last API request**, the same value you grep in the server log), `count` (how many equal errors the device folded into this report). Other fields are dropped, wrong-typed values are ignored. Answer: `204`, no body.
- **Abuse limits**: the call spends one of `per-client` (default 10) per `window` (1 min) for the client IP **before** the body is looked at (`429` + `retryAfterSeconds`, the shared `RateLimitStore` bean when there is one — `redis-rate-limit` shares it across instances); body over `max-body-bytes` (16 KiB) is `413` and never read whole; every field has a length cap (message 500, stack 4,000, route 200 …); a non-object or message-less body is `400`.
- **Scrubbing**: the route loses its query and fragment; URLs in text lose their query; `Bearer …`, JWT-looking values, `…token=`/`…secret=`/`…password=` pairs (`LogMasker`) and e-mail addresses are removed; control characters (line breaks) become spaces, so a report cannot forge a log line (the stack keeps its own line breaks, only in a key-value field).
- **Log**: logger `skeleton.client-error`, level WARN, message `client error [web] <message>`, fields as key-values `clientPlatform` · `clientSource` · `clientName` · `clientRoute` · `clientRelease` · `clientTraceId` · `clientCount` · `clientUserAgent` · `clientStack` · `clientComponentStack` (they appear in the JSON of `logging.structured.format.console`; the plain pattern shows the message only). Find a user's report from the traceId they show: `clientTraceId=<id>` finds the report, the same id finds the server side of that request.
- **Alert**: the log line is the record of each report; an alert is only for a **pile-up** — `CLIENT_ERROR_SURGE` when `surge.threshold` (10) reports arrive within `surge.window` (5 min), with the count and the latest trace id in the text, then folded by the normal `alert` interval (15 min). One broken release gives one Discord message, not one per user; and since anyone can post, a flood cannot become a flood of alerts either.

**Before opening it to the public, set `skeleton.web.client-ip.mode`** (see `docs/client-ip.md`): the per-client limit counts the address the app sees, and behind a proxy or tunnel without that setting every caller looks like one address (one noisy device blocks the rest) — or a caller-chosen forwarding header chooses its own address and the limit is empty.

The alert text carries the count, platform, release and the latest trace id only; the caller-written message and route stay in the log (a public caller's text must not reach the Discord embed).

Limits: the counter is in memory (per instance, forgotten on restart); the per-client limit is by IP, so many addresses can still write many log lines — set `skeleton.web.rate-limit` or a Cloudflare rule in front when the app is public and attacked.

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
