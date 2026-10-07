package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.PasswordService
import dev.sumin.skeleton.account.RegistrationService
import dev.sumin.skeleton.account.SignUpCommand
import dev.sumin.skeleton.account.SignUpStatus
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 전에 부르는 계정 흐름 (가입 · 이메일 확인 · 비밀번호 찾기 · 재설정 · 이메일 변경 확인). HTTP 변환만 — 규칙은 서비스.
 * 응답은 계정 존재 여부와 무관하게 같다. [AccountWebAutoConfiguration] 이 등록한다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account")
class AccountPublicController(
    private val registration: RegistrationService,
    private val passwords: PasswordService,
    private val policy: PasswordPolicy,
    private val deletion: dev.sumin.skeleton.account.DeletionService,
    private val clientIps: ClientIps,
    /** 로그인 토큰을 내는 곳 — 코드 확인이 곧 로그인이다. 늦게 찾는다 (`auth` 의 자동설정이 만든다) */
    private val tokens: () -> AuthTokenResponseFactory,
) {
    @Operation(
        summary = "Sign up with email and password",
        description = "Always 202 VERIFICATION_SENT, whether or not the address already has an account (an existing address receives an 'already registered' mail). " +
            "With email verification switched off the answer is 201 CREATED and a duplicate is 409 ACCOUNT.EMAIL_TAKEN.",
    )
    @PostMapping("/account/sign-up")
    @AcceptedOperation
    fun signUp(@Valid @RequestBody request: SignUpRequest, http: HttpServletRequest): ResponseEntity<DataResponse<SignUpResponse>> {
        val client = clientIps.of(http)
        val outcome = registration.signUp(
            SignUpCommand(request.email!!, request.password!!, request.displayName, request.locale, request.timeZone, client.ip, request.captchaToken, client.limitKey,
                consents = request.consents.orEmpty().map { dev.sumin.skeleton.common.consent.ConsentClaim(it.type!!, it.version!!, it.locale) },
                userAgent = http.getHeader("User-Agent"),
            ),
        )
        val http202 = if (outcome.status == SignUpStatus.CREATED) HttpStatus.CREATED else HttpStatus.ACCEPTED
        return ResponseEntity.status(http202).body(Response.ok(SignUpResponse(outcome.status.name, outcome.signUpId)))
    }

    @Operation(summary = "Mail a new code for the same sign-up attempt (always 202; silent inside the cooldown, after the resend limit, when unknown or expired)")
    @PostMapping("/account/verification/resend")
    @AcceptedOperation
    fun resend(@Valid @RequestBody request: ResendRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        clientIps.of(http).let { registration.resendVerification(request.signUpId!!, it.ip, request.captchaToken, it.limitKey) }
        return accepted("ACCEPTED")
    }

    @Operation(
        summary = "Finish a sign-up with the 6-digit code mailed to the address; creates the account with the password typed in THIS attempt and signs in",
        description = "400 ACCOUNT.CODE_INVALID (data.attemptsLeft) for a wrong code; 410 ACCOUNT.CODE_EXPIRED when the attempt is unknown, expired, used up or taken over by an existing account.",
    )
    @PostMapping("/auth/verify-email")
    fun verifyEmail(@Valid @RequestBody request: VerifyEmailRequest, http: HttpServletRequest): DataResponse<AuthTokenResponse> =
        clientIps.of(http).let { client ->
            val account = registration.verifyEmail(request.signUpId!!, request.code!!, client.ip, client.limitKey)
            val tokens = tokens().issue(account)   // 막힌 계정(정지 · 삭제)은 여기서 던진다 — 성공 로그인으로 기록하지 않는다
            registration.recordSignIn(account.accountId, client.ip)
            Response.ok(tokens)
        }

    @Operation(
        summary = "Cancel a pending account deletion with the restore token that a successful sign-in returned (403 AUTH.ACCOUNT_DELETION_PENDING data.restoreToken); signs in",
        description = "Needs skeleton.account.deletion.self-restore=true. 410 ACCOUNT.TOKEN_INVALID for an unknown, used, expired or foreign token, a lapsed grace or a suspended account; 429 when tried too often.",
    )
    @PostMapping("/account/delete/cancel")
    fun cancelDeletion(@Valid @RequestBody request: CancelDeletionRequest, http: HttpServletRequest): DataResponse<AuthTokenResponse> =
        clientIps.of(http).let { client ->
            val account = deletion.cancel(request.restoreToken!!, client.ip, client.limitKey)
            val response = tokens().issue(account)
            registration.recordSignIn(account.accountId, client.ip)
            Response.ok(response)
        }

    @Operation(summary = "Ask for a password-reset mail (always 202)")
    @PostMapping("/account/password/forgot")
    @AcceptedOperation
    fun forgot(@Valid @RequestBody request: EmailRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        clientIps.of(http).let { passwords.forgot(request.email!!, it.ip, request.captchaToken, it.limitKey) }
        return accepted("ACCEPTED")
    }

    @Operation(summary = "Set a new password with the reset link token; all sessions are signed out")
    @PostMapping("/account/password/reset")
    fun reset(@Valid @RequestBody request: ResetPasswordRequest): ResponseEntity<Void> {
        passwords.reset(request.token!!, request.newPassword!!)
        return Response.noContent()
    }

    @Operation(summary = "The password rules, for rendering hints")
    @GetMapping("/account/password/policy")
    fun policy(): DataResponse<PasswordPolicyResponse> {
        val p = policy.describe()
        return Response.ok(PasswordPolicyResponse(p.minLength, p.maxBytes, p.requireLetter, p.requireDigit, p.requireSymbol, p.forbidEmailLocalPart))
    }

    private fun accepted(status: String) = Response.accepted(StatusResponse(status))
}
