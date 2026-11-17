package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 웹 보상형 광고 에러 코드 — `ADS_WEB_REWARDED.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class WebRewardedErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    DAILY_LIMIT("ADS_WEB_REWARDED.DAILY_LIMIT", HttpStatus.CONFLICT, "Daily reward limit reached"),
    SESSION_NOT_FOUND("ADS_WEB_REWARDED.SESSION_NOT_FOUND", HttpStatus.NOT_FOUND, "No such ad session"),
    SESSION_USED("ADS_WEB_REWARDED.SESSION_USED", HttpStatus.CONFLICT, "Ad session already rewarded"),
    SESSION_EXPIRED("ADS_WEB_REWARDED.SESSION_EXPIRED", HttpStatus.CONFLICT, "Ad session expired"),
    TOO_EARLY("ADS_WEB_REWARDED.TOO_EARLY", HttpStatus.CONFLICT, "Reward requested too early"),
    REWARD_UNAVAILABLE("ADS_WEB_REWARDED.REWARD_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Reward temporarily unavailable"),
}

class WebRewardedException(errorCode: WebRewardedErrorCode, cause: Throwable? = null) :
    ApplicationException(errorCode.title, errorCode, cause = cause)
