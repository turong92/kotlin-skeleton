package dev.sumin.skeleton.auth.mfa.web

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.mfa.MfaService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 2단계 인증 HTTP. `/verify` 만 공개(로그인 두 번째 단계 - `mfaToken` 이 자격이다), 나머지는 로그인한 사용자의 것이다.
 * `@ApiKeyScope` 표시가 없으므로 **API 키로는 닿지 않는다**. 이 클래스는 [MfaWebAutoConfiguration] 이 등록한다(`skeleton.auth-mfa.http.enabled=false` 로 끈다).
 */
@RestController
@RequestMapping("\${skeleton.auth-mfa.http.base-path:/api/v1/auth/mfa}")
class MfaController(
    private val service: MfaService,
    private val tokens: AuthTokenResponseFactory,
    private val clientIps: ClientIps,
) {
    @GetMapping
    fun status(authentication: Authentication?): DataResponse<MfaStatusResponse> =
        Response.ok(service.status(accountId(authentication)).toResponse())

    @PostMapping("/totp/enrollment")
    fun startEnrollment(authentication: Authentication?, @Valid @RequestBody request: ReauthRequest): ResponseEntity<DataResponse<EnrollmentResponse>> =
        noStore(Response.ok(service.startEnrollment(accountId(authentication), sessionId(authentication), request.proof()).toResponse()))

    @PostMapping("/totp/enrollment/confirm")
    fun confirmEnrollment(authentication: Authentication?, @Valid @RequestBody request: ConfirmEnrollmentRequest, http: HttpServletRequest): ResponseEntity<DataResponse<RecoveryCodesResponse>> =
        noStore(Response.ok(RecoveryCodesResponse(service.confirmEnrollment(accountId(authentication), sessionId(authentication), request.code!!, ip(http)).recoveryCodes)))

    @PostMapping("/recovery-codes")
    fun regenerate(authentication: Authentication?, @Valid @RequestBody request: ReauthRequest, http: HttpServletRequest): ResponseEntity<DataResponse<RecoveryCodesResponse>> =
        noStore(Response.ok(RecoveryCodesResponse(service.regenerateRecoveryCodes(accountId(authentication), sessionId(authentication), request.proof(), ip(http)))))

    @PostMapping("/disable")
    fun disable(authentication: Authentication?, @Valid @RequestBody request: DisableRequest, http: HttpServletRequest): ResponseEntity<Void> {
        service.disable(accountId(authentication), sessionId(authentication), request.proof(), request.factor(), ip(http))
        return Response.noContent()
    }

    /** 로그인 두 번째 단계(공개) - 맞으면 첫 단계가 낼 뻔한 것과 같은 응답(같은 토큰 · 같은 쿠키 전달) */
    @PostMapping("/verify")
    fun verify(@Valid @RequestBody request: VerifyRequest, http: HttpServletRequest): DataResponse<AuthTokenResponse> =
        Response.ok(tokens.issueAfterSecondFactor(service.verifyLogin(request.mfaToken!!, request.factor(), ip(http))))

    private fun <T> noStore(body: DataResponse<T>): ResponseEntity<DataResponse<T>> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun ip(http: HttpServletRequest) = clientIps.of(http).ip

    private fun accountId(authentication: Authentication?): String {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        return authentication.name
    }

    private fun sessionId(authentication: Authentication?): String? = (authentication?.principal as? CurrentPrincipal)?.sessionId
}
