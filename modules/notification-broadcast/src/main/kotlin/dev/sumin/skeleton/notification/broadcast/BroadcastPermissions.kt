package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 공지 운영 권한 — 보기와 보내기를 나눈다. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만(역할 화면에서 나눠 준다) */
object BroadcastPermissions {
    const val READ = "notification-broadcast.read"
    const val SEND = "notification-broadcast.send"

    fun declaration() = PermissionDeclaration("notification-broadcast", listOf(DeclaredPermission(READ), DeclaredPermission(SEND)))
}
