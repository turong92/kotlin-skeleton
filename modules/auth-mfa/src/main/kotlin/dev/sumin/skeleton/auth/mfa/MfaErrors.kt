package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 2단계 에러 코드 - `MFA.` 로 시작한다 (프론트는 이 문자열로 분기한다). 로그인 첫 단계의 `AUTH.MFA_REQUIRED` 는 `auth` 모듈이 낸다 */
enum class MfaErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    /** 코드가 틀렸다 · 이미 쓴 걸음이다 - `data.attemptsLeft`(로그인: 이 대기의 남은 시도, 그 밖: 계정 한도의 남은 수) */
    CODE_INVALID("MFA.CODE_INVALID", HttpStatus.BAD_REQUEST, "The code is not valid"),

    /** 로그인 대기가 없다 · 만료됐다 · 시도를 다 썼다 - 1단계(로그인)부터 다시 */
    CHALLENGE_EXPIRED("MFA.CHALLENGE_EXPIRED", HttpStatus.GONE, "The sign-in step expired - sign in again"),

    /** 계정 한도(10번/15분 · 30번/하루)에 걸렸다 - `Retry-After` */
    TOO_MANY_ATTEMPTS("MFA.TOO_MANY_ATTEMPTS", HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes"),

    /** 등록을 시작한 적이 없거나 10분이 지났다 - 다시 시작 */
    ENROLLMENT_EXPIRED("MFA.ENROLLMENT_EXPIRED", HttpStatus.GONE, "The enrollment expired - start again"),

    /** 켜져 있지 않은데 끄기 · 복구 코드 재발급 */
    NOT_ENABLED("MFA.NOT_ENABLED", HttpStatus.CONFLICT, "Two-factor authentication is not enabled"),

    /** 앱이 이 계정에 2단계를 필수로 정했다 - 끌 수 없다(재등록은 된다) */
    REQUIRED_BY_POLICY("MFA.REQUIRED_BY_POLICY", HttpStatus.CONFLICT, "Two-factor authentication is required for this account"),
}

class MfaException(val mfaError: MfaErrorCode, data: Any? = null) :
    ApplicationException(message = mfaError.title, errorCode = mfaError, data = data)
