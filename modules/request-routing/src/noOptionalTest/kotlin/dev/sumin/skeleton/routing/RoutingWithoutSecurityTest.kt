package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.RequestRoutingProperties.Inbound
import dev.sumin.skeleton.routing.web.RouteInboundFilter
import dev.sumin.skeleton.routing.web.RoutingErrorWriter
import jakarta.servlet.FilterChain
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** spring-security-core 가 클래스패스에 없어도(compileOnly) 모듈은 기동한다 — 창은 늘 닫혀 있다 */
class RoutingWithoutSecurityTest {
    @Test
    fun `boots without spring security on the classpath`() {
        assertTrue(runCatching { Class.forName("org.springframework.security.core.Authentication") }.isFailure)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestRoutingAutoConfiguration::class.java))
            .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
            .withPropertyValues("skeleton.routing.enabled=true")
            .run { ctx ->
                assertEquals(null, ctx.startupFailure)
                ctx.getBean(RouteTargets::class.java)
                assertTrue(ctx.getBean(LocalRouteWindow::class.java) is ClosedLocalRouteWindow)
            }
    }

    private fun routeSeenByChain(inbound: Inbound): String? {
        val properties = RequestRoutingProperties(
            enabled = true,
            inbound = inbound,
            services = mapOf("orders" to RequestRoutingProperties.Service(mapOf("candidate" to RequestRoutingProperties.Target("https://candidate.a.run.app")))),
        )
        val access = object : OperatorAccess { override fun permissionsOf(ref: OperatorRef) = setOf("*") }
        val filter = RouteInboundFilter(properties, RouteTargets(properties), ClosedLocalRouteWindow, access, TimeProvider.systemUtc(), RoutingErrorWriter(JsonMapper.builder().build()), null)
        var seen: String? = null
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/x").apply { addHeader("baggage", "route=orders:candidate") }, response, FilterChain { _, _ -> seen = MDC.get("route") })
        assertEquals(200, response.status)
        return seen
    }

    @Test
    fun `without spring security nobody is staff, so an edge filter drops the route and an internal one keeps it`() {
        assertEquals(null, routeSeenByChain(Inbound.EDGE))
        assertEquals("orders:candidate", routeSeenByChain(Inbound.INTERNAL))
    }
}
