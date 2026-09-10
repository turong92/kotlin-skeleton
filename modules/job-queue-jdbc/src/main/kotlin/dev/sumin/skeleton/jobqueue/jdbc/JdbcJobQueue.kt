package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

class JdbcJobQueue(
    private val repository: JdbcJobRepository,
    private val properties: JobQueueProperties,
    private val timeProvider: TimeProvider,
) : JobQueue {
    override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long {
        require(type.isNotBlank()) { "Job type must not be blank." }
        val now = timeProvider.now()
        return repository.insert(type, payloadJson, runAt ?: now, maxAttempts ?: properties.maxAttempts, now)
    }
}
