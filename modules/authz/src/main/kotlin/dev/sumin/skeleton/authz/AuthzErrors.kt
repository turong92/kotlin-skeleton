package dev.sumin.skeleton.authz

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 역할 관리 에러 코드 — `AUTHZ.` 로 시작한다 (프론트는 이 문자열로 분기한다). 권한 부족 403 은 `COMMON.FORBIDDEN` */
enum class AuthzRoleErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    ROLE_PROTECTED("AUTHZ.ROLE_PROTECTED", HttpStatus.CONFLICT, "The full-access role is fixed"),
    ROLE_IN_USE("AUTHZ.ROLE_IN_USE", HttpStatus.CONFLICT, "Accounts still hold this role"),
    ROLE_EXISTS("AUTHZ.ROLE_EXISTS", HttpStatus.CONFLICT, "Role already exists"),
    ROLE_NOT_FOUND("AUTHZ.ROLE_NOT_FOUND", HttpStatus.NOT_FOUND, "Role not found"),
    PERMISSION_UNKNOWN("AUTHZ.PERMISSION_UNKNOWN", HttpStatus.BAD_REQUEST, "Unknown permission"),
    ESCALATION("AUTHZ.ESCALATION", HttpStatus.FORBIDDEN, "You cannot grant or take away a permission you do not hold"),
    ROLE_CYCLE("AUTHZ.ROLE_CYCLE", HttpStatus.CONFLICT, "Roles cannot include each other in a loop"),
    INCLUDE_UNKNOWN("AUTHZ.INCLUDE_UNKNOWN", HttpStatus.BAD_REQUEST, "Unknown role to include"),
    ROLE_INCLUDED("AUTHZ.ROLE_INCLUDED", HttpStatus.CONFLICT, "Another role still includes this role"),
    SELF_LOCKOUT("AUTHZ.SELF_LOCKOUT", HttpStatus.CONFLICT, "This change would remove your own access to role management"),
}

class AuthzRoleException(errorCode: AuthzRoleErrorCode, message: String) : ApplicationException(message, errorCode)
