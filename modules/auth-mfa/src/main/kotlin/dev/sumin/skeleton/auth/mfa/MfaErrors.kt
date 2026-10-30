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

    /** 앱이 이 계정에 2단계를 필수로 정했는데 아직 등록 전이다 - 허용 목록 밖의 요청이 막힌다. 등록하면 풀린다 */
    ENROLLMENT_REQUIRED("MFA.ENROLLMENT_REQUIRED", HttpStatus.FORBIDDEN, "Set up two-factor authentication to continue"),

    /** 운영자가 자기 자신의 2단계를 해제하려 했다 - 다른 운영자가 한다 */
    SELF_RESET("MFA.SELF_RESET", HttpStatus.CONFLICT, "You cannot reset your own two-factor authentication"),

    /** 대상 계정이 호출자보다 높은 권한을 가졌다 - 높은 권한은 그 이상이 다룬다(역할 부여와 같은 규칙) */
    ESCALATION("MFA.ESCALATION", HttpStatus.FORBIDDEN, "You cannot reset an account above your own permissions"),
}

class MfaException(val mfaError: MfaErrorCode, data: Any? = null) :
    ApplicationException(message = mfaError.title, errorCode = mfaError, data = data)
