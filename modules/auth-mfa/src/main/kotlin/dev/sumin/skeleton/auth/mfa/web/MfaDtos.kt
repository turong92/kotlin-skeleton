package dev.sumin.skeleton.auth.mfa.web

import dev.sumin.skeleton.account.ReauthInput
import dev.sumin.skeleton.account.web.SocialReauthRequest
import dev.sumin.skeleton.account.web.reauthOf
import dev.sumin.skeleton.auth.mfa.Enrollment
import dev.sumin.skeleton.auth.mfa.MfaFactor
import dev.sumin.skeleton.auth.mfa.MfaStatus
import dev.sumin.skeleton.common.FieldValidationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

private const val CODE = "^[0-9]{6}$"
private const val RECOVERY = "^[A-Za-z2-7 -]{16,24}$"

/** 다시 인증 증거 - 계정에 맞는 하나(`currentPassword` · `confirmationCode` · `socialReauth`). 등록 시작 · 복구 코드 재발급이 쓴다 */
data class ReauthRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    fun proof(): ReauthInput = reauthOf(currentPassword, confirmationCode, socialReauth)

    override fun toString() = "ReauthRequest(<credentials redacted>)"
}

data class ConfirmEnrollmentRequest(@field:NotBlank @field:Pattern(regexp = CODE) val code: String?) {
    override fun toString() = "ConfirmEnrollmentRequest(<redacted>)"
}

/** 끄기 - 다시 인증 증거와 **지금 코드**(`code` 인증 앱 6자리 | `recoveryCode` 복구 코드 하나) */
data class DisableRequest(
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    @field:Valid val socialReauth: SocialReauthRequest? = null,
    @field:Pattern(regexp = CODE) val code: String? = null,
    @field:Pattern(regexp = RECOVERY) val recoveryCode: String? = null,
) {
    fun proof(): ReauthInput = reauthOf(currentPassword, confirmationCode, socialReauth)

    fun factor(): MfaFactor = factorOf(code, recoveryCode)

    override fun toString() = "DisableRequest(<credentials redacted>)"
}

/** 로그인 두 번째 단계 - `mfaToken` 과 코드(`code` | `recoveryCode`) 하나 */
data class VerifyRequest(
    @field:NotBlank @field:Size(max = 128) val mfaToken: String?,
    @field:Pattern(regexp = CODE) val code: String? = null,
    @field:Pattern(regexp = RECOVERY) val recoveryCode: String? = null,
) {
    fun factor(): MfaFactor = factorOf(code, recoveryCode)

    override fun toString() = "VerifyRequest(<redacted>)"
}

private fun factorOf(code: String?, recoveryCode: String?): MfaFactor = when {
    code != null && recoveryCode == null -> MfaFactor.TotpCode(code)
    code == null && recoveryCode != null -> MfaFactor.RecoveryCode(recoveryCode)
    else -> throw FieldValidationException("code", "Required", "send exactly one of code or recoveryCode")
}

data class MfaStatusResponse(val enabled: Boolean, val enabledAt: Instant?, val recoveryCodesRemaining: Int, val required: Boolean)

fun MfaStatus.toResponse() = MfaStatusResponse(enabled, enabledAt, recoveryCodesRemaining, required)

data class QrResponse(val size: Int, val path: String)

/** 비밀 · 주소는 이 응답에만 있다(`no-store`) */
data class EnrollmentResponse(val secret: String, val otpauthUri: String, val qr: QrResponse, val expiresAt: Instant) {
    override fun toString() = "EnrollmentResponse(expiresAt=$expiresAt, <secret redacted>)"
}

fun Enrollment.toResponse() = EnrollmentResponse(secret, otpauthUri, QrResponse(qr.size, qr.path), expiresAt)

/** 첫 등록이면 복구 코드 10개(한 번뿐), 재등록이면 null */
data class RecoveryCodesResponse(val recoveryCodes: List<String>?) {
    override fun toString() = "RecoveryCodesResponse(<redacted>)"
}
