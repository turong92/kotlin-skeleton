# job-queue-jdbc — database table retry queue (no Redis)

For retries and state transitions that must survive restarts (translation retries, media processing,
outbound calls). One table, `FOR UPDATE SKIP LOCKED` claiming, exponential backoff, dead-lettering.
Works on a single instance and stays correct with several instances — no distributed lock needed.

```yaml
skeleton:
  job-queue:
    enabled: true
    worker-enabled: true      # false on instances that only enqueue
    poll-interval: 2s
    batch-size: 10
    max-attempts: 10
    backoff: { initial: 30s, multiplier: 2.0, max: 1h }
    stale-lock-timeout: 10m   # RUNNING older than this is returned to PENDING (worker died), or DEAD when all attempts are used
    unhandled-type-grace: 1d  # job types this worker has no handler for are left alone (a newer version may own them); DEAD only after this long
    propagated-mdc-keys: [traceId]   # MDC keys copied from the enqueuing thread to the worker's logs
    retention: { enabled: false, done: 14d, dead: 90d }   # opt-in purge of finished rows
```

Schema: `modules/job-queue-jdbc/src/main/resources/db/migration/<vendor>/V20260910010000__jobs.sql` and
`V20261005175044__jobs_log_context.sql` (adds `log_context`; in the `schema.sql` mode on MySQL fold the column into your copy of the `create table` instead — MySQL 8.4 has no `add column if not exists`)
(`postgresql` and `mysql`; Flyway picks the right one through `classpath:db/migration/{vendor}`; copy it into
`schema.sql` in the no-Flyway mode). The app must assemble exactly one dialect module (`modules:db-postgresql`
or `modules:db-mysql`) — instants are bound through its `SqlDialect`.

## Enqueue

```kotlin
class TranslationService(private val jobs: JobQueue) {
    @Transactional
    fun requestTranslation(cardId: Long) {
        // ... domain writes ...
        jobs.enqueue(type = "translate-card", payloadJson = """{"cardId":$cardId}""")   // same transaction
    }
}
```

## Handle

```kotlin
@Component
class TranslateCardHandler(private val json: ObjectMapper) : JobHandler {
    override val type = "translate-card"
    override fun handle(job: Job) {
        val cardId = json.readTree(job.payloadJson)["cardId"].asLong()
        // idempotent work — the job may run again after a crash
        if (unrecoverable) throw PermanentJobFailureException("card deleted")   // -> DEAD immediately
        // any other exception -> retry after backoff, DEAD after max-attempts
    }
}
```

## Lifecycle

`PENDING --claim--> RUNNING --ok--> DONE`  
`RUNNING --error--> PENDING (next_run_at = now + initial * multiplier^(attempts-1), capped at max)`  
`RUNNING --error, attempts >= max_attempts--> DEAD`  
`RUNNING --locked_at older than stale-lock-timeout--> PENDING` (recovered on the next poll)  
`RUNNING --locked_at older than stale-lock-timeout, attempts >= max_attempts--> DEAD` (a poison job that kills its worker never resurrects)

A worker only claims job types it has a handler for. A type nobody on this build handles — typically a job enqueued by
the **new** version while the old version is still running during a deploy — stays `PENDING` with its attempts untouched, so a worker
of the other version picks it up. If it is still unclaimed `unhandled-type-grace` (default 1d) after it was due, no worker has a
handler anywhere: it becomes `DEAD` with `No handler registered for job type '…'`, is logged at ERROR and reported to the dead-job
hook. Set the grace longer than a deploy takes. (Before, the old worker marked such jobs `DEAD` immediately.)

Handlers run **outside** the claim transaction so long jobs do not hold row locks.

## Waking and concurrency

Polling (`poll-interval`) is the safety net, not the latency. When a transaction that called `enqueue` **commits** (or at once when there is no
transaction), the worker of the same process is woken and takes the job without waiting for the next poll; a failed wake is swallowed (the poll
still finds the job). Housekeeping (stale-lock recovery, the unhandled-type DEAD grace, retention) runs at most once per `poll-interval` per project in the worker loop, so wake-ups and back-to-back rounds add only the claim query. A batch that came back full (`batch-size` jobs) means more may be waiting, so the worker claims again immediately instead of
sleeping. Jobs enqueued by *another* instance (or a job whose `runAt` is later) are still found by the poll — there is no cross-instance signal.

`skeleton.job-queue.concurrency` (default `1`, the same as before) is the number of worker threads in this process. Each thread runs the same claim loop;
`FOR UPDATE SKIP LOCKED` keeps two threads from taking the same job. Handlers must be safe to run at the same time (they already must be idempotent), and each
thread holds a database connection while its handler runs. Because a full batch is re-claimed at once, a backlog is no longer throttled to `batch-size` per `poll-interval` (a queue of mail jobs goes out back to back) - use `concurrency`/handler-side limits if the downstream needs a rate. It has no meaning in `mode=on-request` (no worker threads).

## Lease rules

A claimed job belongs to the worker that claimed it (`locked_by` + `attempts`):

- `markDone` / `markRetry` / `markDead` only apply while the row is still `RUNNING` for **that** claim. A worker that was
  recovered as stale and finishes late cannot overwrite the job another worker re-claimed (the late result is logged and
  ignored — keep handlers idempotent).
- A batch is claimed with one `locked_at`. Before each job's turn the worker renews its lease; if the job was already
  recovered and taken by someone else, it is skipped instead of run twice.

## Dead-job hook

Register a `JobDeadListener` bean to hear every transition to DEAD (retries exhausted, `PermanentJobFailureException`,
no worker with a handler within `unhandled-type-grace`, or a stale job that used all attempts — the worker running the recovery reports that one).
The listener decides where to send it (the `alert` module registers one when it is on the classpath); an exception in a
listener is logged and never stops the worker.

## Log context

`enqueue` copies the MDC keys in `skeleton.job-queue.propagated-mdc-keys` (default `traceId`) into
`jobs.log_context`; while a handler runs the worker restores them and adds `jobId` / `jobKind`, so one `grep traceId=…`
follows a request into its background work. Set the list to `[]` to store nothing, or define a `JobContextPropagator`
bean to carry something else.

## Several projects in one process

With `skeleton.projects` ([multi-project](multi-project.md)) the worker visits every active project in turn, inside `ProjectContext.runIn(project)`,
so each project's own `jobs` table (its schema) is polled, retried, recovered and retained separately. Each round takes at most one
`batch-size` per project, so a project with a long backlog cannot starve another. An error in one project (its database included) is logged
with the project id and the round goes on to the next project. Handlers run with that project in `ProjectContext`; the project of an enqueue is
simply the schema it writes to (no id travels with the job). With no project list nothing changes. With `concurrency: 1` (the default) the worker is one thread: a slow
handler delays every project's next round, it does not starve them; more threads each run the same round loop.

## On-request mode (scale to zero)

With `skeleton.job-queue.mode=on-request` no polling thread starts. An outside caller (Cloud Scheduler) `POST`s `skeleton.job-queue.wake.path`
(default `/internal/jobs/drain`) with the header `X-Wake-Secret`; the app wakes its `OnRequestTask` beans (account purge, session purge), then
drains the backlog round by round (`batch-size` per project per round) until a round finds nothing or `wake.time-budget` (default 25s) is used up
(checked before each new round — a job already claimed always finishes). Retry back-offs that are not due yet wait for the next call. The secret is compared in constant time; an empty
server secret keeps the address closed (404), a wrong one is 401, and protected environments refuse to start with a missing or weak secret (< 24 characters).
The mode and the secret are read at run time (the wake controller is always present in a servlet app and answers 404 outside on-request mode), so one AOT / native image can run either way. Polling stays the default. Details and the Cloud Scheduler setup: [Cloud Run scale to zero](cloud-run-scale-to-zero.md).

## Retention

Finished rows pile up. `skeleton.job-queue.retention.enabled=true` makes a worker-enabled instance delete `DONE` rows older
than `done` (default 14d) and `DEAD` rows older than `dead` (default 90d), in `batch-size` chunks, at most once per `interval`.
PENDING / RUNNING are never touched. It is **off by default**: deleting rows is a retention policy (audit, post-mortems), not
a mechanism, so the app opts in. `apps/sample` turns it on.

Tested against PostgreSQL 18 and MySQL 8.4 (Testcontainers, `postgresTest` / `mysqlTest` suites over the same `src/dbTest` sources): claim ordering, backoff arithmetic, DEAD transitions,
two workers claiming the same batch without overlap, stale-lock recovery, lease owner checks, per-job renew, DEAD listener, retention purge, MDC propagation.
