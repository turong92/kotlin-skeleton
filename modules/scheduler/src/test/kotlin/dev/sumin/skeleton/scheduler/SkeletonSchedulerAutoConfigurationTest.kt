package dev.sumin.skeleton.scheduler

import java.util.function.Supplier
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler

class SkeletonSchedulerAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SkeletonSchedulerAutoConfiguration::class.java))

    @Test
    fun `creates scheduler infrastructure by default`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(SkeletonSchedulerProperties::class.java)
            assertThat(context).hasSingleBean(TaskScheduler::class.java)
            assertThat(context).hasSingleBean(SkeletonScheduleResolver::class.java)
            assertThat(context).hasSingleBean(SkeletonSchedulerExecutionGuard::class.java)
            assertThat(context).hasSingleBean(SkeletonScheduledLockManager::class.java)
            assertThat(context).hasSingleBean(SkeletonScheduledFailureHandler::class.java)
            assertThat(context).hasSingleBean(SkeletonScheduledTaskRegistrar::class.java)
        }
    }

    @Test
    fun `does not create infrastructure when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.scheduler.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(SkeletonScheduledTaskRegistrar::class.java)
                assertThat(context).doesNotHaveBean(SkeletonScheduleResolver::class.java)
            }
    }

    @Test
    fun `backs off when user provides lock manager`() {
        val custom = SkeletonScheduledLockManager { _, action ->
            action()
            true
        }

        contextRunner
            .withBean(SkeletonScheduledLockManager::class.java, Supplier { custom })
            .run { context ->
                assertThat(context.getBean(SkeletonScheduledLockManager::class.java)).isSameAs(custom)
            }
    }

    @Test
    fun `a task scheduled from inside a project runs in that project`() {
        val project = dev.sumin.skeleton.common.project.Project("a", listOf("a.test"), "a", dev.sumin.skeleton.common.project.ProjectDatasource(), emptyMap())
        contextRunner.run { context ->
            val scheduler = context.getBean("skeletonTaskScheduler", TaskScheduler::class.java)
            val seen = java.util.concurrent.CompletableFuture<String?>()

            dev.sumin.skeleton.common.project.ProjectContext.runIn(project) {
                scheduler.schedule({ seen.complete(dev.sumin.skeleton.common.project.ProjectContext.currentOrNull()?.id) }, java.time.Instant.now())
            }

            assertThat(seen.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("a")
        }
    }
}
