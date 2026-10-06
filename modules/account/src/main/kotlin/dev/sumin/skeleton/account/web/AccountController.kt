package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.DeletionService
import dev.sumin.skeleton.account.EmailChangeService
import dev.sumin.skeleton.account.MeView
import dev.sumin.skeleton.account.PasswordService
import dev.sumin.skeleton.account.ProfileChange
import dev.sumin.skeleton.account.ProfileService
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.IdentityView
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.idempotency.IdempotentOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 로그인한 사람의 계정: 프로필 · 비밀번호 변경 · 이메일 변경 · 로그인 수단 · 삭제. HTTP 변환만. [AccountWebAutoConfiguration] 이 등록한다. */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account")
class AccountController(
    private val callers: AccountCallers,
    private val profile: ProfileService,
    private val passwords: PasswordService,
    private val emailChange: EmailChangeService,
    private val identities: IdentityService,
    private val deletion: DeletionService,
    private val reauth: Reauth,
) {
    @Operation(summary = "My profile, roles and sign-in methods")
    @GetMapping("/me")
    fun me(authentication: Authentication?): DataResponse<MeView> = Response.ok(profile.me(callers.require(authentication).accountId))

    @Operation(summary = "Update display name, locale or time zone")
    @PatchMapping("/me")
    fun update(authentication: Authentication?, @Valid @RequestBody request: UpdateProfileRequest): DataResponse<MeView> =
        Response.ok(profile.update(callers.require(authentication).accountId, ProfileChange(request.displayName, request.locale, request.timeZone)))

    @Operation(
        summary = "Change the password (or set a first one)",
        description = "currentPassword is required when the account has a password. Other sessions are signed out; the caller's stays.",
    )
    @PostMapping("/password/change")
    fun changePassword(authentication: Authentication?, @Valid @RequestBody request: ChangePasswordRequest): ResponseEntity<Void> {
        val caller = callers.require(authentication)
        passwords.change(caller.accountId, request.currentPassword, request.newPassword!!, caller.sessionId, request.confirmationToken)
        return Response.noContent()
    }

    @Operation(summary = "Ask to change the account email; nothing changes until the new address confirms (202)")
    @IdempotentOperation(ignoredBodyFields = ["currentPassword", "confirmationToken"])
    @PostMapping("/email/change")
    @AcceptedOperation
    fun changeEmail(authentication: Authentication?, @Valid @RequestBody request: ChangeEmailRequest): ResponseEntity<DataResponse<StatusResponse>> {
        emailChange.request(callers.require(authentication).accountId, request.newEmail!!, request.currentPassword, request.confirmationToken)
        return Response.accepted(StatusResponse("VERIFICATION_SENT"))
    }

    @Operation(summary = "Mail a one-time confirmation link that re-authenticates an account that has no password (for email change, first password, social link)")
    @PostMapping("/reauth/confirmation")
    @AcceptedOperation
    fun reauthConfirmation(authentication: Authentication?): ResponseEntity<DataResponse<StatusResponse>> {
        reauth.requestConfirmation(callers.require(authentication).accountId)
        return Response.accepted(StatusResponse("ACCEPTED"))
    }

    @Operation(summary = "Sign-in methods linked to this account")
    @GetMapping("/identities")
    fun identities(authentication: Authentication?): ListResponse<IdentityView> = Response.ok(identities.list(callers.require(authentication).accountId))

    @Operation(summary = "Unlink a sign-in method (409 ACCOUNT.LAST_SIGN_IN_METHOD for the last one)")
    @DeleteMapping("/identities/{id}")
    fun unlink(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        val caller = callers.require(authentication)
        identities.unlink(caller.accountId, id, caller.sessionId)
        return Response.noContent()
    }

    @Operation(summary = "Mail a one-time confirmation link for deleting an account that has no password")
    @PostMapping("/delete/confirmation")
    @AcceptedOperation
    fun deletionConfirmation(authentication: Authentication?): ResponseEntity<DataResponse<StatusResponse>> {
        deletion.requestConfirmation(callers.require(authentication).accountId)
        return Response.accepted(StatusResponse("ACCEPTED"))
    }

    @Operation(
        summary = "Delete the account (re-authenticate with the password or the mailed confirmation token)",
        description = "Signs in is blocked at once; the data is erased after skeleton.account.deletion.grace.",
    )
    @IdempotentOperation(ignoredBodyFields = ["currentPassword", "confirmationToken"])
    @PostMapping("/delete")
    @AcceptedOperation
    fun delete(authentication: Authentication?, @Valid @RequestBody request: DeleteAccountRequest): ResponseEntity<DataResponse<DeletionResponse>> {
        val purgeAfter = deletion.delete(callers.require(authentication).accountId, request.currentPassword, request.confirmationToken)
        return Response.accepted(DeletionResponse("DELETION_SCHEDULED", purgeAfter))
    }
}
