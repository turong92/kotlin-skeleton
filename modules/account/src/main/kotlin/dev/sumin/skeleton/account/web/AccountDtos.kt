package dev.sumin.skeleton.account.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class SignUpRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    @field:NotBlank @field:Size(max = 128) val password: String?,
    @field:Size(max = 60) val displayName: String? = null,
    @field:Size(max = 35) val locale: String? = null,
    @field:Size(max = 64) val timeZone: String? = null,
    @field:Size(max = 2048) val captchaToken: String? = null,
)

data class EmailRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String?,
    @field:Size(max = 2048) val captchaToken: String? = null,
)

data class TokenRequest(@field:NotBlank @field:Size(max = 128) val token: String?)

data class ResetPasswordRequest(
    @field:NotBlank @field:Size(max = 128) val token: String?,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
)

data class ChangePasswordRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:NotBlank @field:Size(max = 128) val newPassword: String?,
)

data class ChangeEmailRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val newEmail: String?,
    @field:Size(max = 128) val currentPassword: String? = null,
)

data class UpdateProfileRequest(
    @field:Size(min = 1, max = 60) val displayName: String? = null,
    @field:Size(max = 35) val locale: String? = null,
    @field:Size(max = 64) val timeZone: String? = null,
)

data class DeleteAccountRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Size(max = 128) val confirmationToken: String? = null,
)

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
