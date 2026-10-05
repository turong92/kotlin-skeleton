package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 게시판 에러 코드 — `BOARD.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class BoardErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND("BOARD.NOT_FOUND", HttpStatus.NOT_FOUND, "Board not found"),
    POST_NOT_FOUND("BOARD.POST_NOT_FOUND", HttpStatus.NOT_FOUND, "Post not found"),
    COMMENT_NOT_FOUND("BOARD.COMMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Comment not found"),
    FORBIDDEN("BOARD.FORBIDDEN", HttpStatus.FORBIDDEN, "Not allowed"),
    REACTION_TYPE_INVALID("BOARD.REACTION_TYPE_INVALID", HttpStatus.BAD_REQUEST, "Unknown reaction type"),
    COMMENT_TOO_DEEP("BOARD.COMMENT_TOO_DEEP", HttpStatus.UNPROCESSABLE_ENTITY, "Reply is nested too deeply"),
    CONTENT_INVALID("BOARD.CONTENT_INVALID", HttpStatus.BAD_REQUEST, "Invalid content"),
    POST_NOT_COMMENTABLE("BOARD.POST_NOT_COMMENTABLE", HttpStatus.CONFLICT, "Cannot comment here"),
    CODE_TAKEN("BOARD.CODE_TAKEN", HttpStatus.CONFLICT, "Board code already taken"),
    RATE_LIMITED("BOARD.RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
}

class BoardException(errorCode: BoardErrorCode, message: String) : ApplicationException(message, errorCode)
