package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/**
 * 리뷰 운영 권한 둘. [MODERATE] — 신고 큐 · 숨김 · 되살리기 · 문제없음, [REPLY] — 운영자 답글.
 * `skeleton.authz.roles` 를 주지 않으면 `MODERATOR` 역할은 [MODERATE] 를 받고, [REPLY] 는 전권 역할(`*`)만 받는다(`board` 와 같은 방식).
 */
object ReviewPermissions {
    const val MODERATE = "review.moderate"
    const val REPLY = "review.reply"

    fun declaration() = PermissionDeclaration("review", listOf(DeclaredPermission(MODERATE, setOf("MODERATOR")), DeclaredPermission(REPLY)))
}
