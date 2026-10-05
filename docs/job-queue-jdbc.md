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
    propagated-mdc-keys: [traceId]   # MDC keys copied from the enqueuing thread to the worker's logs
    retention: { enabled: false, done: 14d, dead: 90d }   # opt-in purge of finished rows
```

Schema: `modules/job-queue-jdbc/src/main/resources/db/migration/<vendor>/V20260910010000__skeleton_jobs.sql` and
`V20261005175044__skeleton_jobs_log_context.sql` (adds `log_context`; in the `schema.sql` mode on MySQL fold the column into your copy of the `create table` instead — MySQL 8.4 has no `add column if not exists`)
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

Handlers run **outside** the claim transaction so long jobs do not hold row locks.

## Lease rules

A claimed job belongs to the worker that claimed it (`locked_by` + `attempts`):

- `markDone` / `markRetry` / `markDead` only apply while the row is still `RUNNING` for **that** claim. A worker that was
  recovered as stale and finishes late cannot overwrite the job another worker re-claimed (the late result is logged and
  ignored — keep handlers idempotent).
- A batch is claimed with one `locked_at`. Before each job's turn the worker renews its lease; if the job was already
  recovered and taken by someone else, it is skipped instead of run twice.

## Dead-job hook

Register a `JobDeadListener` bean to hear every transition to DEAD (retries exhausted, `PermanentJobFailureException`,
no handler, or a stale job that used all attempts — the worker running the recovery reports that one).
The listener decides where to send it (the `alert` module registers one when it is on the classpath); an exception in a
listener is logged and never stops the worker.

## Log context

`enqueue` copies the MDC keys in `skeleton.job-queue.propagated-mdc-keys` (default `traceId`) into
`skeleton_jobs.log_context`; while a handler runs the worker restores them and adds `jobId` / `jobKind`, so one `grep traceId=…`
follows a request into its background work. Set the list to `[]` to store nothing, or define a `JobContextPropagator`
bean to carry something else.

## Retention

Finished rows pile up. `skeleton.job-queue.retention.enabled=true` makes a worker-enabled instance delete `DONE` rows older
than `done` (default 14d) and `DEAD` rows older than `dead` (default 90d), in `batch-size` chunks, at most once per `interval`.
PENDING / RUNNING are never touched. It is **off by default**: deleting rows is a retention policy (audit, post-mortems), not
a mechanism, so the app opts in. `apps/sample` turns it on.

Tested against PostgreSQL 18 and MySQL 8.4 (Testcontainers, `postgresTest` / `mysqlTest` suites over the same `src/dbTest` sources): claim ordering, backoff arithmetic, DEAD transitions,
two workers claiming the same batch without overlap, stale-lock recovery, lease owner checks, per-job renew, DEAD listener, retention purge, MDC propagation.
