package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** AdMob SSV 에러 코드 — `ADS_ADMOB.` 로 시작한다. 구글은 200 이 아니면 다시 보낸다: 400 은 보내도 소용없는 것, 503 은 우리 사정이라 다시 보내면 되는 것. */
enum class AdmobErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    REJECTED("ADS_ADMOB.REJECTED", HttpStatus.BAD_REQUEST, "Reward callback rejected"),
    KEYS_UNAVAILABLE("ADS_ADMOB.KEYS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Verifier keys unavailable"),
    LEDGER_UNAVAILABLE("ADS_ADMOB.LEDGER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Point ledger unavailable"),
}

/** 지급하지 않는 콜백. 메시지는 사유 코드뿐이다 — 쿼리 값(사용자 · 거래 번호)은 싣지 않는다. */
class AdmobRejectedException(val reason: String) : ApplicationException(reason, AdmobErrorCode.REJECTED)

/** 구글의 공개키 목록을 못 읽었다 — 위조인지 알 수 없으니 거절이 아니라 「다시 보내라」(503)다. */
class AdmobKeysUnavailableException(cause: Throwable? = null) :
    ApplicationException("AdMob verifier keys are unavailable", AdmobErrorCode.KEYS_UNAVAILABLE, cause = cause)
