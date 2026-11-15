package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.appcontrol.web.AppConfigController
import dev.sumin.skeleton.appcontrol.web.AppControlAdminAutoConfiguration
import dev.sumin.skeleton.appcontrol.web.AppControlAdminController
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/** 조립: 꺼짐 = 이 모듈이 없는 앱과 같다. 켰는데 저장소가 없으면 시작이 멈추고 무엇을 얹을지 말한다. 경로는 각자의 스위치가 켜져야 열린다 */
class AppControlAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class Ports {
        @Bean fun store(): AppControlStore = FakeAppControlStore()
        @Bean fun mapper(): ObjectMapper = testMapper
        @Bean fun access(): OperatorAccess = object : OperatorAccess { override fun permissionsOf(ref: OperatorRef) = emptySet<String>() }
    }

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AppControlAutoConfiguration::class.java, AppControlAdminAutoConfiguration::class.java))

    private fun failure(context: AssertableWebApplicationContext): String = generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `off by default - no bean at all, not even the filter`() {
        runner.withUserConfiguration(Ports::class.java).run {
            assertTrue(it.getBeanNamesForType(AppControl::class.java).isEmpty())
            assertTrue(it.getBeanNamesForType(FeatureFlags::class.java).isEmpty())
            assertTrue(it.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean::class.java).isEmpty())
        }
    }

    @Test
    fun `on without a store - startup stops and names the module to add`() {
        runner.withPropertyValues("skeleton.app-control.enabled=true").withBean(ObjectMapper::class.java, { testMapper }).run {
            assertTrue("app-control-jdbc" in failure(it), failure(it))
        }
    }

    @Test
    fun `on with a store - the core beans exist, the paths do not until their own switch is on`() {
        runner.withPropertyValues("skeleton.app-control.enabled=true").withUserConfiguration(Ports::class.java).run {
            assertEquals(1, it.getBeanNamesForType(FeatureFlags::class.java).size)
            assertEquals(1, it.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean::class.java).size)
            assertTrue(it.getBeanNamesForType(AppConfigController::class.java).isEmpty())
            assertTrue(it.getBeanNamesForType(AppControlAdminController::class.java).isEmpty())
            assertTrue(it.getBeanNamesForType(PublicEndpointContributor::class.java).isEmpty())
        }
    }

    @Test
    fun `the config path opens public, the admin path has its own switch`() {
        runner.withPropertyValues("skeleton.app-control.enabled=true", "skeleton.app-control.config.enabled=true").withUserConfiguration(Ports::class.java).run {
            assertEquals(1, it.getBeanNamesForType(AppConfigController::class.java).size)
            assertEquals(1, it.getBeanNamesForType(PublicEndpointContributor::class.java).size)
            assertTrue(it.getBeanNamesForType(AppControlAdminController::class.java).isEmpty())
        }
        runner.withPropertyValues("skeleton.app-control.enabled=true", "skeleton.app-control.admin.enabled=true").withUserConfiguration(Ports::class.java).run {
            assertEquals(1, it.getBeanNamesForType(AppControlAdminController::class.java).size)
            assertTrue(it.getBeanNamesForType(AppConfigController::class.java).isEmpty())
        }
    }

    @Test
    fun `the module declares its two permissions`() {
        runner.withPropertyValues("skeleton.app-control.enabled=true").withUserConfiguration(Ports::class.java).run {
            val declared = it.getBeansOfType(dev.sumin.skeleton.common.authz.PermissionDeclaration::class.java).values.single()
            assertEquals(listOf("app-control.read", "app-control.write"), declared.permissions.map { p -> p.key })
        }
    }
}
