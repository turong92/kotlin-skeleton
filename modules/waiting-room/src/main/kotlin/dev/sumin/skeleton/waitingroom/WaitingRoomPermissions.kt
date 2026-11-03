package dev.sumin.skeleton.waitingroom

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 운영자 경로의 권한. 기본 역할이 없어 전권(`ADMIN`)만 가진다 — 역할 관리에서 나눠 준다 */
object WaitingRoomPermissions {
    /** 대기실 만들기 · 속도 바꾸기 · 닫기 · 현황 */
    const val MANAGE = "waiting-room.manage"

    val declaration = PermissionDeclaration("waiting-room", listOf(DeclaredPermission(MANAGE)))
}
