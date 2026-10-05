package dev.sumin.skeleton.account

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class AccountPurgeJobTest {
    class RecordingQueue : JobQueue {
        val enqueued = mutableListOf<String>()
        override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long { enqueued += type; return enqueued.size.toLong() }
    }

    @Configuration(proxyBeanMethods = false)
    class Queue { @Bean fun queue(): JobQueue = RecordingQueue() }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AccountPurgeJobAutoConfiguration::class.java, AccountAutoConfiguration::class.java))
        .withPropertyValues("skeleton.account.password.bcrypt-strength=4", "skeleton.account.deletion.purge-interval=0s")

    private fun job(type: String) = Job(1, type, "{}", JobStatus.RUNNING, 1, 3, Instant.EPOCH, null, null, null, Instant.EPOCH, Instant.EPOCH)

    @Test
    fun `with a job queue the periodic tick enqueues a purge job and the handler purges`() {
        runner.withUserConfiguration(Queue::class.java).run { ctx ->
            val handler = ctx.getBean(JobHandler::class.java)
            assertEquals("account-purge", handler.type)
            ctx.getBean(AccountPurgeDispatch::class.java).dispatch()
            assertEquals(listOf("account-purge"), (ctx.getBean(JobQueue::class.java) as RecordingQueue).enqueued)
            handler.handle(job("account-purge"))   // runs purgeDue() on the (empty) store without error
        }
    }

    @Test
    fun `without a job queue the tick purges directly`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(JobHandler::class.java).isEmpty())
            ctx.getBean(AccountPurgeDispatch::class.java).dispatch()
        }
    }
}
