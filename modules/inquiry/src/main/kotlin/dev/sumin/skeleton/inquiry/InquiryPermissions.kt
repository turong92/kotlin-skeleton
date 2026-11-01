package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.PermissionDeclaration

/** 운영자 경로의 권한. 기본 역할이 없어 전권(`ADMIN`)만 가진다 — 역할 관리에서 나눠 준다 */
object InquiryPermissions {
    /** 문의 목록 보기 (본문 · 답장 이메일 원문이 든다) */
    const val READ = "inquiry.read"

    /** 처리함 표시를 바꾸기 */
    const val HANDLE = "inquiry.handle"

    val declaration = PermissionDeclaration("inquiry", listOf(DeclaredPermission(READ), DeclaredPermission(HANDLE)))
}
