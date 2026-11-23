package dev.sumin.skeleton.account.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

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

data class ResetPasswordRequest(
    @field:NotBlank @field:Size(max = 128) val token: String?,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
) {
    override fun toString() = "ResetPasswordRequest(token=<redacted>, newPassword=<redacted>)"
}

data class ChangePasswordRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
    /** 비밀번호 없는 계정이 첫 비밀번호를 정할 때 — `POST /account/reauth/confirmation` 으로 메일 받은 6자리 코드 */
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
) {
    override fun toString() = "ChangePasswordRequest(currentPassword=<redacted>, newPassword=<redacted>, confirmationCode=<redacted>)"
}

data class PasswordPolicyResponse(
    val minLength: Int,
    val maxBytes: Int,
    val requireLetter: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val forbidEmailLocalPart: Boolean,
)
