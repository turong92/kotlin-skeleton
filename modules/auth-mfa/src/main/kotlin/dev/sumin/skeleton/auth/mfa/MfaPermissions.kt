package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 운영자 해제 경로의 권한. 기본으로 받는 역할은 없다 - 전권 역할(`*`)만. 상태 보기는 `account.read` */
object MfaPermissions {
    /** 계정의 2단계(비밀 · 복구 코드)를 지운다 - **본인 확인 절차 없이 쓰지 말 것**(docs/modules/auth-mfa.md) */
    const val RESET = "mfa.reset"

    val declaration = PermissionDeclaration("auth-mfa", listOf(DeclaredPermission(RESET)))
}
