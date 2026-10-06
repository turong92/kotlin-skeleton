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
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.idempotency.IdempotentOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
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
    private val clientIps: ClientIps = ClientIps(),
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
        passwords.change(caller.accountId, request.currentPassword, request.newPassword!!, caller.sessionId, request.confirmationCode)
        return Response.noContent()
    }

    @Operation(
        summary = "Ask to change the account email; a 6-digit code goes to the NEW address and nothing changes until it is entered in this session (202)",
        description = "Re-authenticate with currentPassword, or confirmationCode (account without a password), or socialReauth (account without an address).",
    )
    @IdempotentOperation(ignoredBodyFields = ["currentPassword", "confirmationCode", "socialReauth"], cacheClientErrors = false)
    @PostMapping("/email/change")
    @AcceptedOperation
    fun changeEmail(authentication: Authentication?, @Valid @RequestBody request: ChangeEmailRequest): ResponseEntity<DataResponse<StatusResponse>> {
        val caller = callers.require(authentication)
        emailChange.request(caller.accountId, request.newEmail!!, reauthOf(request.currentPassword, request.confirmationCode, request.socialReauth), caller.sessionId)
        return Response.accepted(StatusResponse("VERIFICATION_SENT"))
    }

    @Operation(
        summary = "Enter the code mailed to the new address (only in the session that asked); the email switches and the OTHER sessions are signed out",
        description = "400 ACCOUNT.CODE_INVALID (data.attemptsLeft), 410 ACCOUNT.CODE_EXPIRED, 409 ACCOUNT.EMAIL_TAKEN.",
    )
    @PostMapping("/email/change/confirm")
    fun confirmEmailChange(authentication: Authentication?, @Valid @RequestBody request: CodeRequest, http: HttpServletRequest): ResponseEntity<Void> {
        val caller = callers.require(authentication)
        val client = clientIps.of(http)
        emailChange.confirm(caller.accountId, caller.sessionId, request.code!!, client.ip, client.limitKey)
        return Response.noContent()
    }

    @Operation(summary = "Mail a 6-digit code that re-authenticates an account that has no password (for email change, first password, social link, unlink); only this session can use it")
    @PostMapping("/reauth/confirmation")
    @AcceptedOperation
    fun reauthConfirmation(authentication: Authentication?): ResponseEntity<DataResponse<StatusResponse>> {
        val caller = callers.require(authentication)
        reauth.requestConfirmation(caller.accountId, caller.sessionId)
        return Response.accepted(StatusResponse("ACCEPTED"))
    }

    @Operation(summary = "Sign-in methods linked to this account")
    @GetMapping("/identities")
    fun identities(authentication: Authentication?): ListResponse<IdentityView> = Response.ok(identities.list(callers.require(authentication).accountId))

    @Operation(
        summary = "Unlink a sign-in method (re-authentication required; 409 ACCOUNT.LAST_SIGN_IN_METHOD for the last one)",
        description = "Optional JSON body { currentPassword?, confirmationCode?, socialReauth? } — the proof that fits the account.",
    )
    @DeleteMapping("/identities/{id}")
    fun unlink(authentication: Authentication?, @PathVariable id: String, @Valid @RequestBody(required = false) request: ReauthRequest?): ResponseEntity<Void> {
        val caller = callers.require(authentication)
        identities.unlink(caller.accountId, id, caller.sessionId, reauthOf(request?.currentPassword, request?.confirmationCode, request?.socialReauth))
        return Response.noContent()
    }

    @Operation(summary = "Mail a 6-digit code for deleting an account that has no password; only this session can use it")
    @PostMapping("/delete/confirmation")
    @AcceptedOperation
    fun deletionConfirmation(authentication: Authentication?): ResponseEntity<DataResponse<StatusResponse>> {
        val caller = callers.require(authentication)
        deletion.requestConfirmation(caller.accountId, caller.sessionId)
        return Response.accepted(StatusResponse("ACCEPTED"))
    }

    @Operation(
        summary = "Delete the account (re-authenticate with the password, the mailed code, or - for an account without an address - a fresh social code)",
        description = "Sign-in is blocked at once; the data is erased after skeleton.account.deletion.grace.",
    )
    @IdempotentOperation(ignoredBodyFields = ["currentPassword", "confirmationCode", "socialReauth"], cacheClientErrors = false)
    @PostMapping("/delete")
    @AcceptedOperation
    fun delete(authentication: Authentication?, @Valid @RequestBody request: DeleteAccountRequest): ResponseEntity<DataResponse<DeletionResponse>> {
        val caller = callers.require(authentication)
        val purgeAfter = deletion.delete(caller.accountId, reauthOf(request.currentPassword, request.confirmationCode, request.socialReauth), caller.sessionId)
        return Response.accepted(DeletionResponse("DELETION_SCHEDULED", purgeAfter))
    }
}
