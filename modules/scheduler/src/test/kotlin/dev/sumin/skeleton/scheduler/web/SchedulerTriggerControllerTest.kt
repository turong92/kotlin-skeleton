package dev.sumin.skeleton.scheduler.web

import dev.sumin.skeleton.scheduler.SkeletonScheduled
import dev.sumin.skeleton.scheduler.SkeletonScheduledTaskRegistrar
import dev.sumin.skeleton.scheduler.SkeletonSchedulerAutoConfiguration
import java.util.function.Supplier
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus

class SchedulerTriggerControllerTest {
    private val secret = "s3cret-Value-for-scheduler-0123"

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SkeletonSchedulerAutoConfiguration::class.java))
        .withBean(Job::class.java, Supplier { Job() })

    private fun controller(context: org.springframework.context.ApplicationContext) =
        SchedulerTriggerController(context.getBean(SkeletonScheduledTaskRegistrar::class.java), context.getBean(dev.sumin.skeleton.scheduler.SkeletonSchedulerProperties::class.java))

    @Test
    fun `closed in local mode and without secret`() {
        runner.withPropertyValues("skeleton.scheduler.external.secret=$secret").run { context ->
            assertThat(controller(context).run("job.ok", secret).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
        runner.withPropertyValues("skeleton.scheduler.mode=external").run { context ->
            assertThat(controller(context).run("job.ok", "").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `external mode checks the secret and maps results to status codes`() {
        runner.withPropertyValues("skeleton.scheduler.mode=EXTERNAL", "skeleton.scheduler.external.secret=$secret").run { context ->
            val controller = controller(context)
            assertThat(controller.run("job.ok", null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(controller.run("job.ok", "wrong").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(context.getBean(Job::class.java).calls).isZero()

            val ok = controller.run("job.ok", secret)
            assertThat(ok.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(ok.body!!.ran).isTrue()
            assertThat(context.getBean(Job::class.java).calls).isEqualTo(1)

            assertThat(controller.run("job.missing", secret).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            val failed = controller.run("job.boom", secret)
            assertThat(failed.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            assertThat(failed.body!!.error).isEqualTo("IllegalStateException")
        }
    }

    class Job {
        var calls = 0

        @SkeletonScheduled(id = "job.ok", cron = "0 0 3 * * *") fun ok() { calls++ }
        @SkeletonScheduled(id = "job.boom", cron = "0 0 4 * * *") fun boom() { throw IllegalStateException("contains user@example.com") }
    }
}
