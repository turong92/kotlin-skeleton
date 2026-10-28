package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.authz.TokenOperatorAccess
import dev.sumin.skeleton.common.authz.YmlRolePermissionSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 결제 면제는 판정 한 곳(`OperatorAccess`)을 따르므로 역할 위계가 그대로 통한다: STAFF 가 EXEMPT 역할을 물려받으면 STAFF 계정은 면제다. */
class ExemptionHierarchyTest {
    private val source = YmlRolePermissionSource(
        roles = mapOf("ADMIN" to setOf("*"), "EXEMPT" to setOf(PaymentPermissions.EXEMPT), "CS" to setOf(PaymentPermissions.READ), "STAFF" to setOf(PaymentPermissions.REFUND)),
        includes = mapOf("STAFF" to setOf("EXEMPT")),
    )
    private val exemption = PaymentExemption(TokenOperatorAccess(source))

    @Test
    fun `an inherited role exempts, an unrelated role does not, and the super role is exempt without any assignment`() {
        assertTrue(exemption.isExempt("acc_1", setOf("STAFF")), "STAFF includes EXEMPT")
        assertTrue(exemption.isExempt("acc_2", setOf("EXEMPT")))
        assertFalse(exemption.isExempt("acc_3", setOf("CS")))
        assertFalse(exemption.isExempt("acc_4", emptySet()))
        assertTrue(exemption.isExempt("acc_5", setOf("ADMIN")))
    }
}
