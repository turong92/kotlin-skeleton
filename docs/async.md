# Async Capability

`modules:async` provides the backend skeleton's standard executor and named
task-group helper.

## What It Propagates

- SLF4J MDC, including `traceId`, `spanId`, `parentSpanId`, `runId`, and
  `accountId` when present.
- Spring Security `Authentication`.

This keeps logs searchable across the original request thread and background
work spawned from it.

## Configuration

```yaml
skeleton:
  async:
    enabled: true
    core-pool-size: 4
    max-pool-size: 16
    queue-capacity: 100
    thread-name-prefix: skeleton-async-
    await-termination-seconds: 20
    wait-for-tasks-to-complete-on-shutdown: true
```

The auto-configuration contributes:

- `AsyncContextTaskDecorator`
- `skeletonAsyncTaskExecutor`
- a default `AsyncConfigurer` when the app does not define one

Use explicit executor names when a service has multiple pools:

```kotlin
@Async("skeletonAsyncTaskExecutor")
fun sendLater(command: SendCommand) {
    // trace/run/account MDC and Authentication are available here.
}
```

## CompletableFuture Task Groups

Use `AsyncTaskGroup` when multiple named futures should be awaited and logged
as one operation.

```kotlin
val result = AsyncTaskGroup.waitAllAndLog(
    tasks = mapOf(
        "thumbnail" to thumbnailFuture,
        "metadata" to metadataFuture,
    ),
    title = "content enrichment",
    timeout = Duration.ofSeconds(10),
)

result.throwIfFailures()
```

The summary is written to `skeleton.debug.async` by default, and each failed
task is logged with its task name and original exception. When MDC has
`traceId`, `spanId`, `parentSpanId`, `runId`, or `accountId`, those values are
included in the log line; blank or missing values are omitted.

## Async Failure Notifications

Use `modules:async-notification` when a service wants async execution plus
notification events from uncaught `@Async` failures:

```kotlin
implementation(project(":modules:async-notification"))
```

That bundle exposes `modules:async` and `modules:notification` transitively,
then registers an `AsyncUncaughtExceptionHandler` that publishes
`async.exception` events. The event payload includes exception class, method,
argument count/types, and any non-blank trace/run/account MDC values.

```yaml
skeleton:
  async-notification:
    enabled: true
    topic: async.exception
    type: async-exception
    title: Async task failed
```

If an application defines its own `AsyncConfigurer`, the skeleton default backs
off. If it only needs custom failure routing, prefer defining an
`AsyncUncaughtExceptionHandler` bean.

## Workbench Probe

`apps/api` exposes `GET /api/v1/skeleton/async/probe` to prove the module is
assembled. It submits one task to `skeletonAsyncTaskExecutor` and returns the
trace/run/account values observed from the executor thread.

`POST /api/v1/skeleton/async/fail` intentionally throws from an `@Async` method
so the sample app can prove the async-notification bridge.
