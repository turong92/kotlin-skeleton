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

    fun declaration() = PermissionDeclaration("payment", listOf(DeclaredPermission(READ), DeclaredPermission(REFUND), DeclaredPermission(OPERATE)))
}
