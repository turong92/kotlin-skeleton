package dev.sumin.skeleton.common.authz

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class TokenOperatorAccessTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthzAutoConfiguration::class.java))
        .withBean(PermissionDeclaration::class.java, {
            PermissionDeclaration(
                module = "board",
                permissions = listOf(
                    DeclaredPermission("board.moderate", setOf("MODERATOR")),
                    DeclaredPermission("board.manage", setOf("MODERATOR")),
                ),
            )
        })
        .withBean("payment", PermissionDeclaration::class.java, {
            PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"), DeclaredPermission("payment.refund")))
        })

    private fun ref(vararg roles: String) = OperatorRef("acc_1", roles.toSet())

    @Test
    fun `ADMIN holds everything through the wildcard`() {
        runner.run { context ->
            val access = context.getBean(OperatorAccess::class.java)
            assertThat(access.permissionsOf(ref("ADMIN"))).containsExactly("*")
            assertThat(access.has(ref("ADMIN"), "payment.refund")).isTrue()
            assertThat(access.has(ref("ADMIN"), "anything.at-all")).isTrue()
        }
    }

    @Test
    fun `MODERATOR gets the default roles the declarations name - and nothing else`() {
        runner.run { context ->
            val access = context.getBean(OperatorAccess::class.java)
            assertThat(access.permissionsOf(ref("MODERATOR"))).containsExactly("board.manage", "board.moderate")
            assertThat(access.has(ref("MODERATOR"), "board.moderate")).isTrue()
            assertThat(access.has(ref("MODERATOR"), "payment.read")).isFalse()
        }
    }

    @Test
    fun `a role without a mapping has no permissions - not an error`() {
        runner.run { context ->
            val access = context.getBean(OperatorAccess::class.java)
            assertThat(access.permissionsOf(ref("USER"))).isEmpty()
            assertThat(access.permissionsOf(ref())).isEmpty()
        }
    }

    @Test
    fun `skeleton authz roles replaces the whole default mapping`() {
        runner.withPropertyValues(
            "skeleton.authz.roles.CS=payment.read,payment.refund",
        ).run { context ->
            val access = context.getBean(OperatorAccess::class.java)
            assertThat(access.permissionsOf(ref("CS"))).containsExactly("payment.read", "payment.refund")
            // ADMIN 을 안 적었으니 권한 없음, MODERATOR 기본값도 사라졌다
            assertThat(access.permissionsOf(ref("ADMIN"))).isEmpty()
            assertThat(access.permissionsOf(ref("MODERATOR"))).isEmpty()
        }
    }

    @Test
    fun `the super role name is configurable`() {
        runner.withPropertyValues("skeleton.authz.super-role=OWNER").run { context ->
            val access = context.getBean(OperatorAccess::class.java)
            assertThat(access.permissionsOf(ref("OWNER"))).containsExactly("*")
            assertThat(access.permissionsOf(ref("ADMIN"))).isEmpty()
        }
    }

    @Test
    fun `permissions of several roles are merged and sorted`() {
        runner.withPropertyValues("skeleton.authz.roles.CS=payment.refund,payment.read", "skeleton.authz.roles.MOD=board.moderate")
            .run { context ->
                val permissions = context.getBean(OperatorAccess::class.java).permissionsOf(ref("MOD", "CS"))
                assertThat(permissions.toList()).containsExactly("board.moderate", "payment.read", "payment.refund")
            }
    }

    @Test
    fun `an app bean replaces the operator access`() {
        val custom = object : OperatorAccess {
            override fun permissionsOf(ref: OperatorRef): Set<String> = setOf("*")
        }
        runner.withBean(OperatorAccess::class.java, { custom }).run { context ->
            assertThat(context.getBean(OperatorAccess::class.java)).isSameAs(custom)
        }
    }
}
