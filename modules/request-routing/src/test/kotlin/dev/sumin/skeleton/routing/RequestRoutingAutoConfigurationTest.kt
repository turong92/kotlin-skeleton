package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RequestRoutingAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RequestRoutingAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    private val store = object : LocalRouteWindowStore {
        override fun findOpen(now: Instant): LocalWindow? = null

        override fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant) = error("not used")

        override fun closeAll(closedBy: String, now: Instant): List<Long> = emptyList()
    }

    @Test
    fun `no beans when disabled`() {
        runner.run { context ->
            assertEquals(0, context.getBeansOfType(RequestRoutingProperties::class.java).size)
            assertEquals(0, context.getBeansOfType(RouteTargets::class.java).size)
            assertEquals(0, context.getBeansOfType(LocalRouteWindow::class.java).size)
            assertEquals(0, context.getBeansOfType(PermissionDeclaration::class.java).size)
        }
    }

    @Test
    fun `declares both permissions without default roles`() {
        runner.withPropertyValues("skeleton.routing.enabled=true").run { context ->
            val declaration = context.getBean(PermissionDeclaration::class.java)
            assertEquals("request-routing", declaration.module)
            assertEquals(listOf("routing.use", "routing.window"), declaration.permissions.map { it.key })
            assertEquals(true, declaration.permissions.all { it.defaultRoles.isEmpty() })
        }
    }

    @Test
    fun `window stays closed when there is no store bean`() {
        runner.withPropertyValues("skeleton.routing.enabled=true").run { context ->
            assertIs<ClosedLocalRouteWindow>(context.getBean(LocalRouteWindow::class.java))
        }
    }

    @Test
    fun `window reads the store when there is a store bean`() {
        runner.withPropertyValues("skeleton.routing.enabled=true")
            .withBean(LocalRouteWindowStore::class.java, { store })
            .run { context -> assertIs<StoreLocalRouteWindow>(context.getBean(LocalRouteWindow::class.java)) }
    }

    @Test
    fun `a bad target url stops startup`() {
        runner.withPropertyValues(
            "skeleton.routing.enabled=true",
            "skeleton.routing.services.orders.targets.alice.url=https://alice.example.com/path",
        ).run { context -> assertEquals(true, context.startupFailure != null) }
    }
}
