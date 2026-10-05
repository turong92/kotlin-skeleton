package dev.sumin.skeleton.common.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import org.springframework.mock.web.MockHttpServletRequest

class ClientIpAutoConfigurationTest {
    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebPolicyAutoConfiguration::class.java))
        .withBean(tools.jackson.databind.ObjectMapper::class.java, { tools.jackson.databind.ObjectMapper() })

    @Test
    fun `unset mode registers a ClientIps bean but no filter`() {
        runner.run { context ->
            assertNotNull(context.getBean(ClientIps::class.java))
            assertTrue(context.getBeansOfType(ClientIpFilter::class.java).isEmpty())
            assertTrue(context.getBeansOfType(FilterRegistrationBean::class.java).values.none { it.filter is ClientIpFilter })
        }
    }

    @Test
    fun `a set mode registers the filter ahead of ForwardedHeaderFilter`() {
        runner.withPropertyValues("skeleton.web.client-ip.mode=proxy").run { context ->
            val registrations = context.getBeansOfType(FilterRegistrationBean::class.java).values
            val clientIp = registrations.single { it.filter is ClientIpFilter }
            val forwarded = registrations.single { it.filter is org.springframework.web.filter.ForwardedHeaderFilter }
            assertTrue(clientIp.order < forwarded.order, "ClientIpFilter(${clientIp.order}) must run before ForwardedHeaderFilter(${forwarded.order})")
            assertTrue(clientIp.order >= Ordered.HIGHEST_PRECEDENCE)
        }
    }

    @Test
    fun `the default rate limit key resolver goes through ClientIps (IPv6 limit key is a 64-bit prefix once a mode is set)`() {
        runner.withPropertyValues("skeleton.web.rate-limit.enabled=true", "skeleton.web.client-ip.mode=direct").run { context ->
            val key = context.getBean(RateLimitKeyResolver::class.java).resolve(MockHttpServletRequest().apply { remoteAddr = "2001:db8:1:2:aaaa::1" })
            assertEquals("2001:db8:1:2:0:0:0:0/64", key)
        }
    }

    @Test
    fun `an invalid trusted proxy fails the context with the property name`() {
        runner.withPropertyValues("skeleton.web.client-ip.mode=proxy", "skeleton.web.client-ip.trusted-proxies=not-an-ip").run { context ->
            val message = generateSequence(context.startupFailure as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(message.contains("skeleton.web.client-ip.trusted-proxies"), message)
        }
    }
}
