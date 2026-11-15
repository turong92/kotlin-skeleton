package dev.sumin.skeleton.appcontrol

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 운영 스위치 권한 — 보기와 바꾸기를 나눈다. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만(역할 화면에서 나눠 준다) */
object AppControlPermissions {
    const val READ = "app-control.read"
    const val WRITE = "app-control.write"

    fun declaration() = PermissionDeclaration("app-control", listOf(DeclaredPermission(READ), DeclaredPermission(WRITE)))
}
