package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/**
 * 결제 운영 경로의 권한. 읽기 · 환불 승인과 직접 환불 · 결제사와 맞추기 / 닫기 / 웹훅 다시 처리를 가른다
 * (CS 역할에 `payment.read` + `payment.refund` 만 주면 환불 줄만 만진다). 기본으로 받는 역할은 없다 — 전권 역할(`*`)만.
 */
object PaymentPermissions {
    const val READ = "payment.read"
    const val REFUND = "payment.refund"
    const val OPERATE = "payment.operate"

    /**
     * 결제 면제 - 이 권한을 가진 계정의 주문은 결제사를 부르지 않고 바로 완료된다([PaymentExemption]). 운영 화면의 권한이 아니라 **계정이 누리는** 권한이다.
     * 기본으로 받는 역할은 없다 - 운영자가 역할 화면에서 「무료 이용 계정」 같은 역할을 만들어 이 권한을 주고 계정에 배정한다(전권 역할 `*` 은 배정 없이 가진다).
     */
    const val EXEMPT = "payment.exempt"

    fun declaration() = PermissionDeclaration("payment", listOf(DeclaredPermission(READ), DeclaredPermission(REFUND), DeclaredPermission(OPERATE), DeclaredPermission(EXEMPT)))
}
