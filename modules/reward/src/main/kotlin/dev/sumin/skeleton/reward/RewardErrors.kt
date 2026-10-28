package dev.sumin.skeleton.reward

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 보상 지급 에러 코드 — `REWARD.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class RewardErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    NOT_FOUND("REWARD.NOT_FOUND", HttpStatus.NOT_FOUND, "Reward grant not found"),
    NOT_RETRYABLE("REWARD.NOT_RETRYABLE", HttpStatus.CONFLICT, "Only a failed grant can be retried"),
    NOT_CANCELABLE("REWARD.NOT_CANCELABLE", HttpStatus.CONFLICT, "Only a pending grant can be canceled"),
}

class RewardException(errorCode: RewardErrorCode, message: String) : ApplicationException(message, errorCode)
