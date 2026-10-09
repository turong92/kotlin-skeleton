package dev.sumin.skeleton.account.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/** 닉네임 요청 본문의 거친 상한(UTF-16 단위 — 악의적으로 큰 본문만 거른다). 진짜 규칙(60 글자 = 코드 포인트)은 서비스의 `DisplayNameRules` 가 `errors[].field=displayName` 으로 거절한다 — 이모지는 두 단위라 60 이 아니라 120 */
private const val DISPLAY_NAME_UNITS = 120

/** 요청 본문 상한에 걸렸을 때의 문구 — 서비스 규칙([dev.sumin.skeleton.account.NameProblem.TOO_LONG])과 같은 말이다 (120 이라는 내부 단위를 드러내지 않는다) */
private const val DISPLAY_NAME_SIZE_MESSAGE = "Display name must be 1 to 60 characters"

// 비밀번호 · 토큰을 담는 요청은 toString 을 가린다 — Spring MVC 가 DEBUG · TRACE 에서 요청 본문을 toString 으로 찍는다 (SecretsStayOutOfToStringTest)

data class SignUpRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    @field:NotBlank @field:Size(max = 128) val password: String?,
    @field:Size(max = DISPLAY_NAME_UNITS, message = DISPLAY_NAME_SIZE_MESSAGE) val displayName: String? = null,
    @field:Size(max = 35) val locale: String? = null,
    @field:Size(max = 64) val timeZone: String? = null,
    @field:Size(max = 2048) val captchaToken: String? = null,
    /** 가입자가 보고 동의한 약관 (`legal` 모듈이 있을 때만 쓰인다 — 없으면 무시) */
    @field:Size(max = 8) @field:Valid val consents: List<SignUpConsentRequest>? = null,
    /** 로그인 아이디 — `login-id.enabled` 일 때만 읽는다. Bean Validation 을 붙이지 않는다: 규칙은 서비스의 `LoginIds` 한 곳이고 어긋나면 `errors[].field=loginId` 로 거절한다 */
    val loginId: String? = null,
) {
    override fun toString() = "SignUpRequest(email=<redacted>, password=<redacted>)"
}

/** 가입 요청의 약관 동의 한 건 — "이 판을 이 언어로 보고 동의한다" */
data class SignUpConsentRequest(
    @field:NotBlank @field:Size(max = 32) val type: String?,
    @field:NotBlank @field:Size(max = 32) val version: String?,
    @field:Size(max = 35) val locale: String? = null,
)

/**
 * 가입 응답 — [signUpId] 는 코드 확인 · 재전송에 쓰는 불투명한 가입 시도 id (이메일 확인을 끈 앱에는 없다).
 * [expiresAt] · [resendAvailableAt]: 카운트다운용 (ISO-8601 instant, 시도가 없으면 없다) — 계정이 이미 있어도 · 메일을 안 보내도 같은 모양 · 같은 계산이다
 */
data class SignUpResponse(val status: String, val signUpId: String? = null, val expiresAt: Instant? = null, val resendAvailableAt: Instant? = null) {
    // Spring MVC 가 TRACE 에서 응답 객체를 toString 으로 찍는다 — 가입 id 는 코드와 함께 시도를 끝내는 열쇠다
    override fun toString() = "SignUpResponse(status=$status, signUpId=${if (signUpId == null) "none" else "<redacted>"})"
}

data class CancelDeletionRequest(@field:NotBlank @field:Size(max = 128) val restoreToken: String?) {
    override fun toString() = "CancelDeletionRequest(restoreToken=<redacted>)"
}

data class VerifyEmailRequest(
    @field:NotBlank @field:Size(max = 128) val signUpId: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9]{6}$") val code: String?,
    /** 선택 — 가입 요청의 닉네임 대신 쓸 닉네임. `409 DISPLAY_NAME_TAKEN` 으로 막힌 사람이 같은 가입 id · 코드로 다른 닉네임을 내 다시 확인한다 (시도는 닫히지 않았다) */
    @field:Size(max = DISPLAY_NAME_UNITS, message = DISPLAY_NAME_SIZE_MESSAGE) val displayName: String? = null,
    /** 선택 — 가입 요청의 아이디 대신 쓸 아이디 (`login-id.enabled` 일 때만; 꺼짐이면 무시). `409 ACCOUNT.LOGIN_ID_TAKEN` 으로 막힌 사람이 같은 가입 id · 코드로 다시 낸다 */
    val loginId: String? = null,
) {
    override fun toString() = "VerifyEmailRequest(signUpId=<redacted>, code=<redacted>)"
}

data class ResendRequest(
    @field:NotBlank @field:Size(max = 128) val signUpId: String?,
    @field:Size(max = 2048) val captchaToken: String? = null,
) {
    override fun toString() = "ResendRequest(signUpId=<redacted>)"
}

data class CodeRequest(@field:NotBlank @field:Pattern(regexp = "^[0-9]{6}$") val code: String?) {
    override fun toString() = "CodeRequest(code=<redacted>)"
}

data class EmailRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    @field:Size(max = 2048) val captchaToken: String? = null,
)

/** 아이디 찾기의 요청 — 주소는 가려 둔다 (`toString` 이 로그에 남지 않게) */
data class LoginIdForgotRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    @field:Size(max = 2048) val captchaToken: String? = null,
) {
    override fun toString() = "LoginIdForgotRequest(email=<redacted>, captchaToken=<redacted>)"
}

data class ResetPasswordRequest(
    @field:NotBlank @field:Size(max = 128) val token: String?,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
) {
    override fun toString() = "ResetPasswordRequest(token=<redacted>, newPassword=<redacted>)"
}

/** 이메일이 없는 계정의 다시 인증 — 이미 연결된 제공자의 **새** 인가 코드 */
data class SocialReauthRequest(
    @field:NotBlank @field:Size(max = 64) val provider: String?,
    @field:NotBlank @field:Size(max = 2048) val authorizationCode: String?,
    @field:Size(max = 2048) val redirectUri: String? = null,
    /** PKCE(S256) 검증기와 인가 요청의 nonce — 제공자의 `pkce` · `nonce` (`GET /auth/methods`) 가 요구할 때 */
    @field:Size(max = 256) val codeVerifier: String? = null,
    @field:Size(max = 256) val nonce: String? = null,
    /** 콜백이 받은 OAuth `state` — Naver 처럼 토큰 요청에 요구하는 제공자에게만 전달된다 */
    @field:Size(max = 512) val state: String? = null,
) {
    override fun toString() = "SocialReauthRequest(provider=$provider, authorizationCode=<redacted>, codeVerifier=<redacted>)"

    fun toReauth() = dev.sumin.skeleton.account.SocialReauth(provider!!, authorizationCode!!, redirectUri, codeVerifier, nonce, state)
}

/** 다시 인증 증거 묶음 — 계정이 가진 것에 맞는 하나: 비밀번호 · 메일로 받은 6자리 코드 · (이메일 없는 계정) 소셜 코드 */
fun reauthOf(currentPassword: String?, confirmationCode: String?, social: SocialReauthRequest?) =
    dev.sumin.skeleton.account.ReauthInput(currentPassword, confirmationCode, social?.toReauth())

data class ChangePasswordRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
    /** 비밀번호 없는 계정이 첫 비밀번호를 정할 때 — `POST /account/reauth/confirmation` 으로 메일 받은 6자리 코드 */
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
) {
    override fun toString() = "ChangePasswordRequest(currentPassword=<redacted>, newPassword=<redacted>, confirmationCode=<redacted>)"
}

data class ContactEmailRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    /** 계정 이메일이 **없는** 계정만 — 지금 새로 받은 제공자 인가 코드 (주소가 있는 계정은 보내지 않는다) */
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    override fun toString() = "ContactEmailRequest(email=<redacted>)"
}

data class ChangeEmailRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val newEmail: String?,
    @field:Size(max = 128) val currentPassword: String? = null,
    /** 비밀번호 없는 계정 — `POST /account/reauth/confirmation` 으로 메일 받은 6자리 코드 */
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    override fun toString() = "ChangeEmailRequest(newEmail=<redacted>, <credentials redacted>)"
}

/** 로그인 수단 해제 · 삭제가 같이 쓰는 다시 인증 본문 (본문 없이도 호출할 수 있다 — 그러면 계정 종류에 맞는 에러가 돌아온다) */
data class ReauthRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    override fun toString() = "ReauthRequest(<credentials redacted>)"
}

data class UpdateProfileRequest(
    /** 빈 문자열은 여기서 막지 않는다 — 서비스 규칙이 `Required` 로 거절한다 (Bean Validation 의 Size 문구가 먼저 나가지 않게) */
    @field:Size(max = DISPLAY_NAME_UNITS, message = DISPLAY_NAME_SIZE_MESSAGE) val displayName: String? = null,
    @field:Size(max = 35) val locale: String? = null,
    @field:Size(max = 64) val timeZone: String? = null,
)

data class DeleteAccountRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    override fun toString() = "DeleteAccountRequest(<credentials redacted>)"
}

/** 이메일 없는 계정 버리기 — 방금 로그인한 세션이면 본문이 비어도 된다. 옛 세션이면 `socialReauth`(지금 새로 받은 제공자 인가 코드) */
data class DiscardAccountRequest(
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    override fun toString() = "DiscardAccountRequest(<credentials redacted>)"
}

data class SuspendRequest(@field:Size(max = 200) val reason: String? = null)

/** 운영자 지우기의 사유 — 차단 줄에 남는다 (개인정보를 적지 않는다) */
data class AdminEraseRequest(@field:Size(max = 200) val reason: String? = null)

/** 재가입 차단 한 줄 — 해시 · 이메일 · 제공자 주체는 나가지 않는다 */
data class AccountBlockResponse(val id: Long, val kind: String, val reason: String?, val createdAt: Instant, val expiresAt: Instant?, val createdBy: String?, val accountId: String?)

data class StatusResponse(val status: String)

/** 코드를 보낸 202 의 본문 — 카운트다운용 두 시각 (ISO-8601 instant; `resendAvailableAt` 이 null 이면 더 재전송할 수 없다). 메일을 실제로 보냈는지와 무관하게 같은 모양 · 같은 계산이다 (docs/account-http-contract.md) */
data class CodeIssuedResponse(
    val status: String,
    val expiresAt: Instant,
    /** `null` (the key stays) = this attempt cannot be re-sent any more — hide the resend button (docs/account-http-contract.md) */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS) val resendAvailableAt: Instant?,
) {
    companion object {
        fun of(status: String, w: dev.sumin.skeleton.account.CodeWindow) = CodeIssuedResponse(status, w.expiresAt, w.resendAvailableAt)
    }
}

data class PasswordPolicyResponse(
    val minLength: Int,
    val maxBytes: Int,
    val requireLetter: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val forbidEmailLocalPart: Boolean,
)

data class DeletionResponse(val status: String, val purgeAfter: Instant)

data class AdminAccountResponse(
    val id: String,
    val email: String?,
    val status: String,
    val roles: Set<String>,
    val displayName: String?,
    /** 같은 닉네임을 구분하는 4자리 꼬리표 (`uniqueness=TAGGED` 일 때만) */
    val displayTag: String?,
    val createdAt: Instant,
    val lastLoginAt: Instant?,
    val suspendedReason: String?,
    val purgeAfter: Instant?,
    /** 개인정보를 지운 시각 (status=ERASED) */
    val erasedAt: Instant? = null,
    /** 로그인 아이디(`skeleton.account.login-id.enabled=true` 일 때만). 꺼져 있으면 키가 없다(null) — 켜져 있는데 아직 안 정한 계정은 키가 있고 값이 `null`(빈 [java.util.Optional]). `MeView` 와 같은 방식 */
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val loginId: java.util.Optional<String>? = null,
)

/** 실시간 아이디 확인의 요청 — 후보 아이디는 주소 줄 · 로그에 남지 않게 POST 로 받고 `toString` 도 가린다. 형식은 서비스(`LoginIds`)가 본다 */
data class LoginIdAvailabilityRequest(val loginId: String? = null) {
    override fun toString() = "LoginIdAvailabilityRequest(loginId=<redacted>)"
}

/** `reason` · `code` 는 해당할 때만 실린다 (null 이면 키가 없다 — `LoginIdWebTest` M4 가 실제 JSON 의 키 집합으로 지킨다). 후보 아이디(`normalized`)는 `toString` 에 나오지 않는다 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class LoginIdAvailabilityResponse(val normalized: String, val available: Boolean, val reason: String? = null, val code: String? = null) {
    override fun toString() = "LoginIdAvailabilityResponse(normalized=<redacted>, available=$available, reason=$reason, code=$code)"
}

/** 「아이디 정하기」 요청 — 아이디는 `toString` 에 나오지 않는다. 형식은 서비스(`LoginIds`)가 본다 (`errors[].field=loginId`) */
data class LoginIdRequest(val loginId: String? = null) {
    override fun toString() = "LoginIdRequest(loginId=<redacted>)"
}
