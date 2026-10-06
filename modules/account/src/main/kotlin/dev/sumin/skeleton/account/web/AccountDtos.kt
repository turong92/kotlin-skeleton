package dev.sumin.skeleton.account.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
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

data class ChangePasswordRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
    /** 비밀번호 없는 계정이 첫 비밀번호를 정할 때 — `POST /account/reauth/confirmation` 으로 메일 받은 링크의 토큰 */
    @field:Size(max = 128) val confirmationToken: String? = null,
) {
    override fun toString() = "ChangePasswordRequest(currentPassword=<redacted>, newPassword=<redacted>, confirmationToken=<redacted>)"
}

data class ChangeEmailRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val newEmail: String?,
    @field:Size(max = 128) val currentPassword: String? = null,
    /** 비밀번호 없는 계정 — `POST /account/reauth/confirmation` 으로 메일 받은 링크의 토큰 */
    @field:Size(max = 128) val confirmationToken: String? = null,
) {
    override fun toString() = "ChangeEmailRequest(newEmail=<redacted>, currentPassword=<redacted>, confirmationToken=<redacted>)"
}

data class UpdateProfileRequest(
    @field:Size(min = 1, max = 60) val displayName: String? = null,
    @field:Size(max = 35) val locale: String? = null,
    @field:Size(max = 64) val timeZone: String? = null,
)

data class DeleteAccountRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Size(max = 128) val confirmationToken: String? = null,
) {
    override fun toString() = "DeleteAccountRequest(currentPassword=<redacted>, confirmationToken=<redacted>)"
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
