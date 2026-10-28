package dev.sumin.skeleton.common.authz

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class YmlRoleHierarchyTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthzAutoConfiguration::class.java))
        .withBean(PermissionDeclaration::class.java, {
            PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"), DeclaredPermission("payment.refund")))
        })
        .withPropertyValues(
            "skeleton.authz.roles.ADMIN=*",
            "skeleton.authz.roles.CS=payment.read",
            "skeleton.authz.roles.STAFF=payment.refund",
        )

    private fun ref(vararg roles: String) = OperatorRef("acc_1", roles.toSet())

    private fun fails(vararg properties: String, expected: String) =
        runner.withPropertyValues(*properties).run { context: AssertableApplicationContext ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining(expected)
        }

    @Test
    fun `a role passes what the roles it includes allow - and the inherited roles stay unlisted`() {
        runner.withPropertyValues("skeleton.authz.role-includes.STAFF=CS").run { context ->
            val access = context.getBean(OperatorAccess::class.java)
            assertThat(access.permissionsOf(ref("STAFF"))).containsExactly("payment.read", "payment.refund")
            assertThat(access.has(ref("STAFF"), "payment.read")).isTrue()
            assertThat(access.permissionsOf(ref("CS"))).containsExactly("payment.read")   // 위계는 한 방향
            assertThat(context.getBean(RolePermissionSource::class.java).includesOf("STAFF")).containsExactly("CS")
        }
    }

    @Test
    fun `a cycle, an unknown role, the super role included and the super role including all fail startup naming the role`() {
        fails("skeleton.authz.role-includes.STAFF=CS", "skeleton.authz.role-includes.CS=STAFF", expected = "CS -> STAFF -> CS")
        fails("skeleton.authz.role-includes.STAFF=GHOST", expected = "GHOST")
        fails("skeleton.authz.role-includes.GHOST=CS", expected = "GHOST")
        fails("skeleton.authz.role-includes.STAFF=ADMIN", expected = "STAFF: cannot include the super role")
        fails("skeleton.authz.role-includes.ADMIN=CS", expected = "ADMIN: the super role")
    }
}
