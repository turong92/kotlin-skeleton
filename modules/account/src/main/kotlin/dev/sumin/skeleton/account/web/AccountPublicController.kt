package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.EmailChangeService
import dev.sumin.skeleton.account.PasswordService
import dev.sumin.skeleton.account.RegistrationService
import dev.sumin.skeleton.account.SignUpCommand
import dev.sumin.skeleton.account.SignUpStatus
import dev.sumin.skeleton.account.password.PasswordPolicy
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
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
    private val emailChange: EmailChangeService,
    private val policy: PasswordPolicy,
    private val clientIps: ClientIps,
) {
    @Operation(
        summary = "Sign up with email and password",
        description = "Always 202 VERIFICATION_SENT, whether or not the address already has an account (an existing address receives an 'already registered' mail). " +
            "With email verification switched off the answer is 201 CREATED and a duplicate is 409 ACCOUNT.EMAIL_TAKEN.",
    )
    @PostMapping("/account/sign-up")
    fun signUp(@Valid @RequestBody request: SignUpRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        val status = registration.signUp(
            SignUpCommand(request.email!!, request.password!!, request.displayName, request.locale, request.timeZone, clientIps.of(http).ip, request.captchaToken),
        )
        val http202 = if (status == SignUpStatus.CREATED) HttpStatus.CREATED else HttpStatus.ACCEPTED
        return ResponseEntity.status(http202).body(Response.ok(StatusResponse(status.name)))
    }

    @Operation(summary = "Resend the verification mail (always 202; silent when unknown, verified, or over the per-address limit)")
    @PostMapping("/account/verification/resend")
    fun resend(@Valid @RequestBody request: EmailRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        registration.resendVerification(request.email!!, clientIps.of(http).ip, request.captchaToken)
        return accepted("ACCEPTED")
    }

    @Operation(summary = "Confirm an email address with the link token (single use, 410 ACCOUNT.TOKEN_INVALID otherwise)")
    @PostMapping("/auth/verify-email")
    fun verifyEmail(@Valid @RequestBody request: TokenRequest): DataResponse<StatusResponse> {
        registration.verifyEmail(request.token!!)
        return Response.ok(StatusResponse("VERIFIED"))
    }

    @Operation(summary = "Ask for a password-reset mail (always 202)")
    @PostMapping("/account/password/forgot")
    fun forgot(@Valid @RequestBody request: EmailRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        passwords.forgot(request.email!!, clientIps.of(http).ip, request.captchaToken)
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

    @Operation(summary = "Confirm an email change with the link token sent to the new address")
    @PostMapping("/auth/confirm-email-change")
    fun confirmEmailChange(@Valid @RequestBody request: TokenRequest): ResponseEntity<Void> {
        emailChange.confirm(request.token!!)
        return Response.noContent()
    }

    private fun accepted(status: String) = ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.ok(StatusResponse(status)))
}
