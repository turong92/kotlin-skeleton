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
task is logged with its task name and original exception.

## Workbench Probe

`apps/api` exposes `GET /api/v1/skeleton/async/probe` to prove the module is
assembled. It submits one task to `skeletonAsyncTaskExecutor` and returns the
trace/run/account values observed from the executor thread.
