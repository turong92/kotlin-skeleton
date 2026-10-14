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
    fun `repeated ticks inside one interval enqueue one job - the dispatch lease is held for the interval`() {
        runner.withUserConfiguration(Queue::class.java).withPropertyValues("skeleton.account.deletion.purge-interval=1h").run { ctx ->
            val dispatch = ctx.getBean(AccountPurgeDispatch::class.java)
            repeat(5) { dispatch.dispatch() }   // five ticks through ONE lease object; that several instances share one lease row is proven by JdbcAccountMaintenanceLeaseDbTest (32 threads, both DBs)
            assertEquals(listOf("account-purge"), (ctx.getBean(JobQueue::class.java) as RecordingQueue).enqueued)
        }
    }

    @Test
    fun `without a job queue the tick purges directly`() {
        runner.run { ctx ->
            assertTrue(ctx.getBeansOfType(JobHandler::class.java).isEmpty())
            ctx.getBean(AccountPurgeDispatch::class.java).dispatch()
        }
    }

    class ProjectRecordingQueue : JobQueue {
        val enqueued = java.util.concurrent.CopyOnWriteArrayList<String?>()
        override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long {
            enqueued += dev.sumin.skeleton.common.project.ProjectContext.current().id // 문맥 없이 부르면 여기서 예외
            return enqueued.size.toLong()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class ProjectQueue {
        @Bean fun queue(): JobQueue = ProjectRecordingQueue()
        // 이 시험 클래스패스에는 아직 프로젝트를 모르는 모듈(소셜 · 메일 · 캡차)도 들어 있다 — 가드는 따로 시험한다(ProjectUnawareFeaturesTest)
        @Bean fun projectUnawareFeaturesCheck(): org.springframework.beans.factory.SmartInitializingSingleton = org.springframework.beans.factory.SmartInitializingSingleton { }
    }

    @Test
    fun `with two projects and a job queue the purge job is enqueued in each project - not in no project at all`() {
        runner.withConfiguration(AutoConfigurations.of(dev.sumin.skeleton.common.project.ProjectAutoConfiguration::class.java))
            .withUserConfiguration(ProjectQueue::class.java)
            .withPropertyValues(
                "skeleton.projects.a.hosts[0]=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.a.config.account.mail.link-base-url=https://a.test",
                "skeleton.projects.b.hosts[0]=b.test", "skeleton.projects.b.schema=pb", "skeleton.projects.b.config.account.mail.link-base-url=https://b.test",
            ).run { ctx ->
                assertEquals(null, ctx.startupFailure)
                ctx.getBean(AccountPurgeDispatch::class.java).dispatch()
                assertEquals(setOf("a", "b"), (ctx.getBean(JobQueue::class.java) as ProjectRecordingQueue).enqueued.toSet())
            }
    }
}
