package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 워크스페이스 에러 코드 — `WORKSPACE.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class WorkspaceErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    /** 없는 id · 멤버가 아님 · 닫힘 — **셋이 같은 응답**이다(존재를 알리지 않는다) */
    NOT_FOUND("WORKSPACE.NOT_FOUND", HttpStatus.NOT_FOUND, "Workspace not found"),
    REQUIRED("WORKSPACE.REQUIRED", HttpStatus.BAD_REQUEST, "X-Workspace-Id header is required"),
    FORBIDDEN("WORKSPACE.FORBIDDEN", HttpStatus.FORBIDDEN, "Your role in this workspace is not enough"),
    MEMBER_NOT_FOUND("WORKSPACE.MEMBER_NOT_FOUND", HttpStatus.NOT_FOUND, "Member not found"),
    OWNER_CANNOT_LEAVE("WORKSPACE.OWNER_CANNOT_LEAVE", HttpStatus.CONFLICT, "The owner cannot leave; transfer ownership first"),
    OWNER_PROTECTED("WORKSPACE.OWNER_PROTECTED", HttpStatus.CONFLICT, "The owner's role cannot be changed or removed; transfer ownership instead"),
    SLUG_TAKEN("WORKSPACE.SLUG_TAKEN", HttpStatus.CONFLICT, "Workspace address is already taken"),
    INVALID("WORKSPACE.INVALID", HttpStatus.BAD_REQUEST, "Workspace input is invalid"),

    // 초대
    TOO_MANY_PENDING("WORKSPACE.TOO_MANY_PENDING", HttpStatus.CONFLICT, "Too many pending invitations; revoke some first"),
    RESEND_TOO_SOON("WORKSPACE.RESEND_TOO_SOON", HttpStatus.TOO_MANY_REQUESTS, "This invitation was sent a moment ago; try again shortly"),
    /** 이 워크스페이스에 그 id 의 대기 초대가 없다(없음 · 이미 끝남 · 남의 워크스페이스의 초대) — 재발송 · 취소 */
    INVITATION_NOT_FOUND("WORKSPACE.INVITATION_NOT_FOUND", HttpStatus.NOT_FOUND, "Invitation not found"),
    /** 없는 · 만료 · 쓴 · 취소된 토큰 — `data.reason` 이 `EXPIRED` | `USED` | `INVALID` (토큰을 가진 사람에게만 보인다) */
    INVITATION_INVALID("WORKSPACE.INVITATION_INVALID", HttpStatus.BAD_REQUEST, "The invitation link is not valid"),
    INVITATION_EMAIL_MISMATCH("WORKSPACE.INVITATION_EMAIL_MISMATCH", HttpStatus.FORBIDDEN, "This invitation was sent to another email address"),
    EMAIL_UNVERIFIED("WORKSPACE.EMAIL_UNVERIFIED", HttpStatus.FORBIDDEN, "Verify your email address to accept an invitation"),
    /** 이미 멤버 — 초대는 닫힌다. `data` 에 `{id, slug}` */
    ALREADY_MEMBER("WORKSPACE.ALREADY_MEMBER", HttpStatus.CONFLICT, "You are already a member of this workspace"),
    SEAT_LIMIT("WORKSPACE.SEAT_LIMIT", HttpStatus.CONFLICT, "This workspace has no seat left"),
}

class WorkspaceException(val workspaceError: WorkspaceErrorCode, message: String = workspaceError.title, data: Any? = null) : ApplicationException(message, workspaceError, data = data)
