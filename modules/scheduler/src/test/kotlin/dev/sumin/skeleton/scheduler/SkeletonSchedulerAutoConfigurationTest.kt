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
}
