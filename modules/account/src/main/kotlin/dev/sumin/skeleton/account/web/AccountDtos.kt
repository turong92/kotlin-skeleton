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
    @field:Size(max = 60) val displayName: String? = null,
    @field:Size(max = 35) val locale: String? = null,
    @field:Size(max = 64) val timeZone: String? = null,
    @field:Size(max = 2048) val captchaToken: String? = null,
) {
    override fun toString() = "SignUpRequest(email=<redacted>, password=<redacted>)"
}

/** 가입 응답 — [signUpId] 는 코드 확인 · 재전송에 쓰는 불투명한 가입 시도 id (이메일 확인을 끈 앱에는 없다) */
data class SignUpResponse(val status: String, val signUpId: String? = null) {
    // Spring MVC 가 TRACE 에서 응답 객체를 toString 으로 찍는다 — 가입 id 는 코드와 함께 시도를 끝내는 열쇠다
    override fun toString() = "SignUpResponse(status=$status, signUpId=${if (signUpId == null) "none" else "<redacted>"})"
}

data class VerifyEmailRequest(
    @field:NotBlank @field:Size(max = 128) val signUpId: String?,
    @field:NotBlank @field:Pattern(regexp = "^[0-9]{6}$") val code: String?,
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

data class TokenRequest(@field:NotBlank @field:Size(max = 128) val token: String?) {
    override fun toString() = "TokenRequest(token=<redacted>)"
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
) {
    override fun toString() = "SocialReauthRequest(provider=$provider, authorizationCode=<redacted>)"

    fun toReauth() = dev.sumin.skeleton.account.SocialReauth(provider!!, authorizationCode!!, redirectUri)
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
    @field:Size(min = 1, max = 60) val displayName: String? = null,
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

data class SuspendRequest(@field:Size(max = 200) val reason: String? = null)

data class StatusResponse(val status: String)

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
    val createdAt: Instant,
    val lastLoginAt: Instant?,
    val suspendedReason: String?,
    val purgeAfter: Instant?,
)
