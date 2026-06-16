# Async Notification Design

## Purpose

`modules:async-notification` is a composable bundle capability. A service that
wants async failures to become notification events should be able to add one
dependency:

```kotlin
implementation(project(":modules:async-notification"))
```

That one dependency must bring in the async executor/context module, the base
notification broker module, and the bridge that publishes async failures.

## Scope

This design covers uncaught exceptions from Spring `@Async` methods, especially
void/unit-returning handlers where Spring invokes `AsyncUncaughtExceptionHandler`.

It does not try to hide exceptions from `CompletableFuture`, `Mono`, `Flux`, or
explicit task-group code. Those flows already expose failures to the caller and
should use `AsyncTaskGroup.waitAllAndLog(...)`, `NotificationPublisher`, or a
separately designed explicit failure-notification helper when needed.

## Module Boundary

### `modules:async`

Owns:

- `skeletonAsyncTaskExecutor`
- MDC and `SecurityContext` propagation
- `AsyncTaskGroup`
- a default `AsyncConfigurer`

It must stay notification-agnostic. It may depend only on Spring's standard
`AsyncUncaughtExceptionHandler` interface by accepting an optional handler bean.

### `modules:notification`

Owns:

- `NotificationEvent`
- `NotificationPublisher`
- `NotificationSubscriptionRegistry`
- the default in-memory broker

It must stay async-agnostic.

### `modules:async-notification`

Owns:

- transitive API dependencies on `modules:async` and `modules:notification`
- `AsyncNotificationExceptionHandler`
- auto-configuration that contributes the exception handler when enabled
- properties under `skeleton.async-notification`

It is both an adapter and a user-facing bundle module.

## Dependency Shape

`modules:async-notification/build.gradle.kts` should use:

```kotlin
dependencies {
    api(project(":modules:async"))
    api(project(":modules:notification"))
}
```

Using `api` is intentional. When an application depends on
`async-notification`, app code can still use `AsyncTaskGroup`,
`NotificationPublisher`, and related public types without adding the base modules
again.

## Auto-Configuration Flow

`modules:async` should create its default `AsyncConfigurer` with an optional
`AsyncUncaughtExceptionHandler` provider:

```kotlin
override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler? =
    handlerProvider.getIfAvailable()
```

This keeps `async` independent from `notification`, while allowing any adapter
module to supply a handler.

`modules:async-notification` should auto-configure after `SkeletonAsyncAutoConfiguration`
and `NotificationAutoConfiguration`. It contributes a single
`AsyncUncaughtExceptionHandler` bean if:

- `skeleton.async-notification.enabled=true` or missing
- a `NotificationPublisher` bean exists
- no other `AsyncUncaughtExceptionHandler` bean already exists

If a service already defines its own async exception handler, the skeleton backs
off.

## Notification Event Contract

Async failures should publish:

- `topic`: default `async.exception`
- `type`: `async-exception`
- `severity`: `ERROR`
- `title`: default `Async task failed`
- `message`: exception message or exception class name
- `payload`:
  - `exceptionClass`
  - `method`
  - `argumentCount`
  - `argumentTypes`
  - `traceId`
  - `spanId`
  - `parentSpanId`
  - `runId`
  - `accountId`

Payload entries with null or blank values should be omitted.

Raw argument values should not be included by default because async handlers
often receive user or payment data. A future property can add redacted argument
values, but the base bridge should stay conservative.

## Failure Policy

Publishing notification events must never break the original async executor
thread beyond the exception that already occurred. If notification publishing
fails, the handler logs a warning to `skeleton.debug.async` and swallows the
publishing failure.

## Workbench Proof

`apps:api` should use `modules:async-notification` as the dependency that proves
the bundle behavior. A smoke endpoint or test-only async service should trigger a
failing `@Async` method and assert that:

- request `traceparent` trace id reaches the async thread
- `accountId` and `runId` are present
- one `NotificationEvent` is published with `type=async-exception`
- Slack/SSE/WebSocket can consume the event through existing notification
  subscriptions when those modules are enabled

## Testing

Use TDD:

1. Add a failing test in `modules:async` proving the default `AsyncConfigurer`
   returns a supplied `AsyncUncaughtExceptionHandler`.
2. Add failing tests in `modules:async-notification` proving event construction,
   null omission, publish failure swallowing, and auto-configuration backoff.
3. Add an app-level integration test proving that depending on
   `async-notification` is enough to wire async, notification, and the bridge.

## Documentation

Update:

- `README.md` module list and capability description
- `docs/async.md` with the bridge dependency choice
- `docs/logging.md` if the warning log format changes
- the modular skeleton roadmap checklist
