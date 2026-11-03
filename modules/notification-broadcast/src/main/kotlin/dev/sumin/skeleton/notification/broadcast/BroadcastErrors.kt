package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 공지 에러 코드 — `BROADCAST.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class BroadcastErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND("BROADCAST.NOT_FOUND", HttpStatus.NOT_FOUND, "Broadcast not found"),
    INVALID_STATE("BROADCAST.INVALID_STATE", HttpStatus.CONFLICT, "The broadcast is not in a state that allows this"),
    UNKNOWN_TYPE("BROADCAST.UNKNOWN_TYPE", HttpStatus.BAD_REQUEST, "This notification type is not offered for broadcasts"),
    UNKNOWN_CHANNEL("BROADCAST.UNKNOWN_CHANNEL", HttpStatus.BAD_REQUEST, "Unknown channel"),
    UNKNOWN_AUDIENCE("BROADCAST.UNKNOWN_AUDIENCE", HttpStatus.BAD_REQUEST, "Unknown audience kind"),
    INVALID_AUDIENCE("BROADCAST.INVALID_AUDIENCE", HttpStatus.BAD_REQUEST, "The audience value is not usable"),
    INVALID_LINK("BROADCAST.INVALID_LINK", HttpStatus.BAD_REQUEST, "The link must be an in-app path"),
    INVALID_SCHEDULE("BROADCAST.INVALID_SCHEDULE", HttpStatus.BAD_REQUEST, "The schedule is outside the allowed window"),
    TEST_TOO_SOON("BROADCAST.TEST_TOO_SOON", HttpStatus.TOO_MANY_REQUESTS, "Wait before sending another test"),
}

class BroadcastException(errorCode: BroadcastErrorCode, message: String, data: Any? = null) :
    ApplicationException(message, errorCode, message, data)
