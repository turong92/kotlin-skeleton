package dev.sumin.skeleton.common.authz

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.http.HttpStatus

/** 전권 — `skeleton.authz.super-role` 의 권한 전용 */
const val ALL_PERMISSIONS = "*"

enum class AuthzErrorCode(override val code: String, override val status: HttpStatus, override val title: String) : ErrorCode {
    FORBIDDEN("PLATFORM.FORBIDDEN", HttpStatus.FORBIDDEN, "Forbidden"),
}

/**
 * 모든 운영 경로의 판정 한 곳. 모듈은 호출자를 [OperatorRef] 로 풀어 [require] 한 줄로 묻는다.
 * 교체 지점도 이 빈 하나 — 앱이 빈을 두면 [TokenOperatorAccess](기본)와 `account` 구현이 모두 물러난다.
 */
interface OperatorAccess {
    /** 이 호출자의 권한 키(사전순). 전권이면 `["*"]` 그대로 */
    fun permissionsOf(ref: OperatorRef): Set<String>

    fun has(ref: OperatorRef, permission: String): Boolean =
        permissionsOf(ref).let { ALL_PERMISSIONS in it || permission in it }

    /** [ref] 가 null 이면 익명 → 401, 권한이 없으면 403 `PLATFORM.FORBIDDEN` */
    fun require(ref: OperatorRef?, permission: String) {
        if (ref == null) throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        if (!has(ref, permission)) {
            throw ApplicationException("Missing permission: $permission", AuthzErrorCode.FORBIDDEN)
        }
    }
}
