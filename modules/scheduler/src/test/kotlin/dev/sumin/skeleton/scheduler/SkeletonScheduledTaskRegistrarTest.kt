package dev.sumin.skeleton.scheduler

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger

class SkeletonScheduledTaskRegistrarTest {
    @Test
    fun `registers annotated tasks only when registrar starts`() {
        val fixture = Fixture()

        fixture.contextRunner.run { context ->
            val registrar = context.getBean(SkeletonScheduledTaskRegistrar::class.java)

            assertThat(fixture.scheduler.scheduled).isEmpty()

            val registered = registrar.registerTasks()

            assertThat(registered).extracting<String> { it.taskId }
                .containsExactlyInAnyOrder("sample.fixed", "sample.locked", "sample.failing")
            assertThat(fixture.scheduler.scheduled).hasSize(3)
        }
    }

    @Test
    fun `runs locked task through lock manager`() {
        val fixture = Fixture()

        fixture.contextRunner.run { context ->
            context.getBean(SkeletonScheduledTaskRegistrar::class.java).registerTasks()

            fixture.scheduler.scheduled.forEach { it.runnable.run() }

            assertThat(fixture.lockManager.requests).extracting<String> { it.lockKey }.containsExactly("scheduler:sample.locked")
            assertThat(context.getBean(SampleScheduledBean::class.java).lockedCalls).isEqualTo(1)
        }
    }

    @Test
    fun `reports task failures to failure handlers`() {
        val fixture = Fixture()

        fixture.contextRunner.run { context ->
            context.getBean(SkeletonScheduledTaskRegistrar::class.java).registerTasks()

            fixture.scheduler.scheduled.forEach { it.runnable.run() }

            assertThat(fixture.failureHandler.failures).hasSize(1)
            assertThat(fixture.failureHandler.failures.single().taskId).isEqualTo("sample.failing")
            assertThat(fixture.failureHandler.failures.single().throwable).isInstanceOf(IllegalStateException::class.java)
        }
    }

    private class Fixture {
        val scheduler = RecordingTaskScheduler()
        val lockManager = RecordingLockManager()
        val failureHandler = RecordingFailureHandler()

        val contextRunner: ApplicationContextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SkeletonSchedulerAutoConfiguration::class.java))
            .withBean(TaskScheduler::class.java, Supplier { scheduler })
            .withBean(SkeletonScheduledLockManager::class.java, Supplier { lockManager })
            .withBean(SkeletonScheduledFailureHandler::class.java, Supplier { failureHandler })
            .withBean(SampleScheduledBean::class.java, Supplier { SampleScheduledBean() })
    }

    private class SampleScheduledBean {
        var fixedCalls = 0
        var lockedCalls = 0

        @SkeletonScheduled(id = "sample.fixed", fixedDelay = 1)
        fun fixed() {
            fixedCalls++
        }

        @SkeletonScheduled(id = "sample.locked", fixedRate = 1, lockKey = "scheduler:sample.locked")
        fun locked() {
            lockedCalls++
        }

        @SkeletonScheduled(id = "sample.failing", fixedRate = 1)
        fun failing() {
            throw IllegalStateException("expected")
        }
    }

    private class RecordingLockManager : SkeletonScheduledLockManager {
        val requests = mutableListOf<SkeletonScheduledLockRequest>()

        override fun execute(
            request: SkeletonScheduledLockRequest,
            action: () -> Unit,
        ): Boolean {
            requests += request
            action()
            return true
        }
    }

    private class RecordingFailureHandler : SkeletonScheduledFailureHandler {
        val failures = mutableListOf<SkeletonScheduledTaskFailure>()

        override fun handle(failure: SkeletonScheduledTaskFailure) {
            failures += failure
        }
    }

    private class RecordingTaskScheduler : TaskScheduler {
        val scheduled = mutableListOf<ScheduledTask>()

        override fun getClock(): Clock = Clock.systemUTC()

        override fun schedule(
            task: Runnable,
            trigger: Trigger,
        ): ScheduledFuture<*> {
            scheduled += ScheduledTask(runnable = task, trigger = trigger)
            return NoopScheduledFuture
        }

        override fun schedule(
            task: Runnable,
            startTime: Instant,
        ): ScheduledFuture<*> = error("not used")

        override fun scheduleAtFixedRate(
            task: Runnable,
            startTime: Instant,
            period: Duration,
        ): ScheduledFuture<*> = error("not used")

        override fun scheduleAtFixedRate(
            task: Runnable,
            period: Duration,
        ): ScheduledFuture<*> = error("not used")

        override fun scheduleWithFixedDelay(
            task: Runnable,
            startTime: Instant,
            delay: Duration,
        ): ScheduledFuture<*> = error("not used")

        override fun scheduleWithFixedDelay(
            task: Runnable,
            delay: Duration,
        ): ScheduledFuture<*> = error("not used")
    }

    private data class ScheduledTask(
        val runnable: Runnable,
        val trigger: Trigger,
    )

    private object NoopScheduledFuture : ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = 0
        override fun compareTo(other: Delayed): Int = 0
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
        override fun get(): Unit = Unit
        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Unit = Unit
    }
}
