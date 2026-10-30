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
}

class WorkspaceException(val workspaceError: WorkspaceErrorCode, message: String = workspaceError.title) : ApplicationException(message, workspaceError)
