package dev.sumin.skeleton.scheduler

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.core.env.StandardEnvironment

class SkeletonSchedulerExecutionGuardTest {
    @Test
    fun `allows execution when local guard has no profile restrictions`() {
        val guard = guard(
            properties = SkeletonSchedulerProperties(),
            activeProfiles = "local",
        )

        assertThat(guard.canExecute(annotation("open"))).isTrue()
    }

    @Test
    fun `blocks execution when local guard is disabled`() {
        val guard = guard(
            properties = SkeletonSchedulerProperties(
                localExecution = SkeletonSchedulerProperties.LocalExecution(enabled = false),
            ),
            activeProfiles = "prod",
        )

        assertThat(guard.canExecute(annotation("open"))).isFalse()
    }

    @Test
    fun `blocks execution outside allowed profiles`() {
        val guard = guard(
            properties = SkeletonSchedulerProperties(
                localExecution = SkeletonSchedulerProperties.LocalExecution(allowedProfiles = setOf("prod")),
            ),
            activeProfiles = "local",
        )

        assertThat(guard.canExecute(annotation("open"))).isFalse()
    }

    @Test
    fun `blocks execution when annotation profile does not match`() {
        val guard = guard(
            properties = SkeletonSchedulerProperties(),
            activeProfiles = "dev",
        )

        assertThat(guard.canExecute(annotation("prodOnly"))).isFalse()
    }

    @Test
    fun `blocks execution when annotation enabled property is false`() {
        val environment = StandardEnvironment()
        TestPropertyValues.of(
            "spring.profiles.active=prod",
            "jobs.billing.enabled=false",
        ).applyTo(environment)
        val guard = SkeletonSchedulerExecutionGuard(environment, SkeletonSchedulerProperties())

        assertThat(guard.canExecute(annotation("propertyGuarded"))).isFalse()
    }

    private fun guard(
        properties: SkeletonSchedulerProperties,
        activeProfiles: String,
    ): SkeletonSchedulerExecutionGuard {
        val environment = StandardEnvironment()
        TestPropertyValues.of("spring.profiles.active=$activeProfiles").applyTo(environment)
        return SkeletonSchedulerExecutionGuard(environment, properties)
    }

    private fun annotation(methodName: String): SkeletonScheduled =
        GuardedSchedules::class.java.getDeclaredMethod(methodName).getAnnotation(SkeletonScheduled::class.java)

    private class GuardedSchedules {
        @SkeletonScheduled(fixedRate = 1)
        fun open() = Unit

        @SkeletonScheduled(fixedRate = 1, profiles = ["prod"])
        fun prodOnly() = Unit

        @SkeletonScheduled(fixedRate = 1, enabledProperty = "jobs.billing.enabled")
        fun propertyGuarded() = Unit
    }
}
