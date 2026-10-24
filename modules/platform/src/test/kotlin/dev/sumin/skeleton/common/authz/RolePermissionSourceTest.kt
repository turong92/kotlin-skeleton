package dev.sumin.skeleton.common.authz

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import java.time.Clock
import java.time.Instant.EPOCH

class RolePermissionSourceTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthzAutoConfiguration::class.java))
        .withBean(PermissionDeclaration::class.java, {
            PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"), DeclaredPermission("payment.refund")))
        })

    private fun fails(vararg properties: String, expected: String) =
        runner.withPropertyValues(*properties).run { context: AssertableApplicationContext ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining(expected)
        }

    @Test
    fun `the wildcard belongs to the super role only`() {
        fails("skeleton.authz.roles.CS=*", expected = "CS")
        runner.withPropertyValues("skeleton.authz.roles.ADMIN=*").run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `a role name off the account rule fails startup - an env var binds the map key in lower case`() {
        fails("skeleton.authz.roles.cs=payment.read", expected = "cs")
        fails("skeleton.authz.super-role=admin", expected = "admin")
    }

    @Test
    fun `a permission no module declares fails startup`() {
        fails("skeleton.authz.roles.CS=payment.read,payment.nope", expected = "payment.nope")
    }

    @Test
    fun `a malformed permission key fails startup`() {
        fails("skeleton.authz.roles.CS=Payment.Read", expected = "Payment.Read")
        fails("skeleton.authz.roles.CS=payment", expected = "payment")
    }

    @Test
    fun `defined roles are the keys of the mapping`() {
        runner.withPropertyValues("skeleton.authz.roles.CS=payment.read").run { context ->
            assertThat(context.getBean(RolePermissionSource::class.java).definedRoles()).containsExactly("CS")
        }
    }

    @Test
    fun `the cache serves a stale mapping until the ttl passes or it is invalidated, and ttl 0 turns it off`() {
        var now = Instant.EPOCH
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        var calls = 0
        val delegate = object : RolePermissionSource {
            override fun permissionsOf(role: String): Set<String> { calls++; return setOf("a.b") }
            override fun definedRoles(): Set<String> = setOf("R")
        }
        val cached = CachingRolePermissionSource(delegate, Duration.ofSeconds(30), clock)
        cached.permissionsOf("R"); cached.permissionsOf("R")
        assertEquals(1, calls)
        now = EPOCH.plusSeconds(31)
        cached.permissionsOf("R")
        assertEquals(2, calls)
        cached.invalidate()
        cached.permissionsOf("R")
        assertEquals(3, calls)

        val off = CachingRolePermissionSource(delegate, Duration.ZERO, clock)
        off.permissionsOf("R"); off.permissionsOf("R")
        assertEquals(5, calls)
    }
}
