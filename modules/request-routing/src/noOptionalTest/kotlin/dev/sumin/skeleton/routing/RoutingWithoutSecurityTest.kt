package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.time.TimeProvider
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
}
