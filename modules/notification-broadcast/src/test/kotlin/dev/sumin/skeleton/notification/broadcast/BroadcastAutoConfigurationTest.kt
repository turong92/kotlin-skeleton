package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.broadcast.web.BroadcastAdminController
import dev.sumin.skeleton.notification.broadcast.web.BroadcastWebAutoConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 조립: 꺼짐 = 이 모듈이 없는 앱과 같다. 켰는데 필요한 것이 없으면 시작이 멈추고 무엇을 얹을지 말한다 */
class BroadcastAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class Ports {
        @Bean fun store(): BroadcastStore = FakeBroadcastStore()
        @Bean fun tasks(): BroadcastTasks = RecordingTasks()
        @Bean fun access(): OperatorAccess = object : OperatorAccess { override fun permissionsOf(ref: OperatorRef) = emptySet<String>() }
    }

    @Configuration(proxyBeanMethods = false)
    class Audience {
        @Bean fun everyone(): AudienceSource = StaticAudience("everyone", listOf("acc_1"))
    }

    @Configuration(proxyBeanMethods = false)
    class Clash {
        @Bean fun everyone2(): AudienceSource = StaticAudience("everyone", listOf("acc_2"))
    }

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, BroadcastAutoConfiguration::class.java, BroadcastWebAutoConfiguration::class.java))

    private val on = arrayOf("skeleton.notification.broadcast.enabled=true", "skeleton.notification.broadcast.types=notice.service")

    private fun failure(context: AssertableWebApplicationContext): String = generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `off by default - no bean at all`() {
        runner.run { assertTrue(it.getBeanNamesForType(Broadcasts::class.java).isEmpty() && it.getBeanNamesForType(BroadcastDeliveryHook::class.java).isEmpty()) }
    }

    @Test
    fun `on without the jdbc ports, types, an audience, with a clashing kind or a bad batch size - startup stops with the way out`() {
        runner.withPropertyValues(*on).withUserConfiguration(Audience::class.java).run { assertTrue("notification-broadcast-jdbc" in failure(it), failure(it)) }
        runner.withPropertyValues("skeleton.notification.broadcast.enabled=true").withUserConfiguration(Ports::class.java, Audience::class.java).run { assertTrue("types" in failure(it), failure(it)) }
        runner.withPropertyValues(*on).withUserConfiguration(Ports::class.java).run { assertTrue("AudienceSource" in failure(it), failure(it)) }
        runner.withPropertyValues(*on).withUserConfiguration(Ports::class.java, Audience::class.java, Clash::class.java).run { assertTrue("unique" in failure(it), failure(it)) }
        runner.withPropertyValues(*on, "skeleton.notification.broadcast.batch-size=1001").withUserConfiguration(Ports::class.java, Audience::class.java).run { assertTrue("batch-size" in failure(it), failure(it)) }
    }

    @Test
    fun `complete setup starts, and the operator controller exists only when admin is enabled`() {
        runner.withPropertyValues(*on).withUserConfiguration(Ports::class.java, Audience::class.java).run {
            assertNotNull(it.getBean(Broadcasts::class.java))
            assertEquals(0, it.getBeanNamesForType(BroadcastAdminController::class.java).size)   // 경로가 없다 = 404
        }
        runner.withPropertyValues(*on, "skeleton.notification.broadcast.admin.enabled=true").withUserConfiguration(Ports::class.java, Audience::class.java).run {
            assertNotNull(it.getBean(BroadcastAdminController::class.java))
        }
    }
}
