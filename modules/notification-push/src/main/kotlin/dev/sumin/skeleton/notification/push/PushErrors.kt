package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 푸시 에러 코드 — `PUSH.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class PushErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    INVALID_DEVICE("PUSH.INVALID_DEVICE", HttpStatus.BAD_REQUEST, "Invalid push device"),
    KIND_DISABLED("PUSH.KIND_DISABLED", HttpStatus.BAD_REQUEST, "Push kind is not enabled"),
    UNKNOWN_TYPE("PUSH.UNKNOWN_TYPE", HttpStatus.NOT_FOUND, "Push preference type not found"),
    DEVICE_NOT_FOUND("PUSH.DEVICE_NOT_FOUND", HttpStatus.NOT_FOUND, "Push device not found"),
    TEST_TOO_SOON("PUSH.TEST_TOO_SOON", HttpStatus.TOO_MANY_REQUESTS, "Test push was sent too recently"),
}

/** [reason] 은 `data.reason` 에도 실린다 — 짧은 기계용 이유(`installation_id` · `endpoint_host_not_allowed` …). 요청 값(주소 · 열쇠)은 싣지 않는다 */
class PushException(errorCode: PushErrorCode, message: String, val reason: String? = null) :
    ApplicationException(message, errorCode, detail = reason ?: message, data = reason?.let { mapOf("reason" to it) })
