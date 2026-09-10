# job-queue-jdbc — MySQL table retry queue (no Redis)

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
    stale-lock-timeout: 10m   # RUNNING older than this is returned to PENDING (worker died)
```

Schema: `modules/job-queue-jdbc/src/main/resources/db/migration/V2026091001__skeleton_jobs.sql`
(applied by Flyway automatically; copy into `schema.sql` in the no-Flyway mode).

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

Handlers run **outside** the claim transaction so long jobs do not hold row locks.

Tested against MySQL 8.4 (Testcontainers): claim ordering, backoff arithmetic, DEAD transitions,
two workers claiming the same batch without overlap, stale-lock recovery.
