package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 조립: 꺼짐 = 이 모듈이 없는 앱과 같다. 켰는데 저장소 · 종류가 틀리면 시작이 멈추고 무엇을 할지 말한다 */
class ScheduledPushAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class Store {
        @Bean fun store(): ScheduledPushStore = FakeScheduledPushStore()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, ScheduledPushAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    private fun failure(context: org.springframework.boot.test.context.assertj.AssertableApplicationContext): String =
        generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    @Test
    fun `off by default adds nothing`() {
        runner.withUserConfiguration(Store::class.java).run { ctx ->
            assertEquals(0, ctx.getBeansOfType(ScheduledPushes::class.java).size)
            assertEquals(0, ctx.getBeansOfType(OnRequestTask::class.java).size)
        }
    }

    @Test
    fun `when on it wires the service, the sweep, its wake task and the erasure hook`() {
        runner.withUserConfiguration(Store::class.java).withPropertyValues("skeleton.notification.scheduled.enabled=true", "skeleton.notification.scheduled.type=reminder").run { ctx ->
            ctx.getBean(ScheduledPushes::class.java)
            ctx.getBean(ScheduledPushSweep::class.java)
            assertEquals(listOf("notification-scheduled-sweep"), ctx.getBeansOfType(OnRequestTask::class.java).values.map { it.name })
            assertEquals("notification-scheduled", ctx.getBean(AccountErasureListener::class.java).name)
        }
    }

    @Test
    fun `on without a store or a type fails startup and says what to add`() {
        runner.withPropertyValues("skeleton.notification.scheduled.enabled=true", "skeleton.notification.scheduled.type=reminder").run { ctx ->
            assertTrue(failure(ctx).contains("notification-scheduled-jdbc"))
        }
        runner.withUserConfiguration(Store::class.java).withPropertyValues("skeleton.notification.scheduled.enabled=true").run { ctx ->
            assertTrue(failure(ctx).contains("scheduled.type"))
        }
    }

    @Test
    fun `an advertising type outside the advertising list fails startup so the consent gate cannot be bypassed`() {
        runner.withUserConfiguration(Store::class.java)
            .withPropertyValues("skeleton.notification.scheduled.enabled=true", "skeleton.notification.scheduled.type=reminder", "skeleton.notification.scheduled.advertising-type=reminder.ad")
            .run { ctx -> assertTrue(failure(ctx).contains("advertising.types")) }
    }

    @Test
    fun `a daily limit of zero fails startup`() {
        runner.withUserConfiguration(Store::class.java)
            .withPropertyValues("skeleton.notification.scheduled.enabled=true", "skeleton.notification.scheduled.type=reminder", "skeleton.notification.scheduled.daily-limit=0")
            .run { ctx -> assertTrue(failure(ctx).contains("daily-limit")) }
    }
}
