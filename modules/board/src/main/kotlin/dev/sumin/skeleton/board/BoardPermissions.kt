package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/**
 * 게시판 운영 권한 둘. [MODERATE] — 숨기기 · 고정 · 숨은 글 보기 · 남의 글/댓글 지우기, [MANAGE] — 게시판 만들기 · 설정 고치기.
 * `skeleton.authz.roles` 를 주지 않으면 `MODERATOR` 역할이 둘 다 받는다(전권 역할은 `*` 로 늘 받는다).
 */
object BoardPermissions {
    const val MODERATE = "board.moderate"
    const val MANAGE = "board.manage"

    fun declaration() = PermissionDeclaration(
        "board",
        listOf(DeclaredPermission(MODERATE, setOf("MODERATOR")), DeclaredPermission(MANAGE, setOf("MODERATOR"))),
    )
}
