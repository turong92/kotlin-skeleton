package dev.sumin.skeleton.auth.sessions.web

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.sessions.AuthSessionErrorCode
import dev.sumin.skeleton.auth.sessions.RefreshInvalidException
import dev.sumin.skeleton.auth.sessions.RefreshTokenDelivery
import dev.sumin.skeleton.auth.sessions.SessionClients
import dev.sumin.skeleton.auth.sessions.SessionService
import dev.sumin.skeleton.auth.sessions.SessionView
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.NoContentOperation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class RefreshRequest(@field:Size(max = 128) val refreshToken: String? = null) {
    override fun toString() = "RefreshRequest(refreshToken=<redacted>)"
}

/** 리프레시 · 로그아웃 · 세션 목록. HTTP 변환만 — 규칙은 [SessionService]. [dev.sumin.skeleton.auth.sessions.AuthSessionAutoConfiguration] 이 등록한다. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth sessions")
class SessionController(
    private val sessions: SessionService,
    private val delivery: RefreshTokenDelivery,
    private val clients: SessionClients,
    private val accounts: AuthAccountRepository,
    private val tokens: AuthTokenResponseFactory,
) {
    @Operation(summary = "Exchange a refresh token for a new access token and a rotated refresh token",
        description = "Body mode: {refreshToken}. Cookie mode: the cookie plus the CSRF header. A replayed (already rotated) token closes the whole session: AUTH.REFRESH_REUSED.")
    @PostMapping("/refresh")
    fun refresh(@RequestBody(required = false) body: RefreshRequest?, request: HttpServletRequest, response: HttpServletResponse): DataResponse<AuthTokenResponse> {
        requireCsrf(request)
        val raw = delivery.read(request, body?.refreshToken) ?: throw RefreshInvalidException()
        val result = sessions.refresh(raw, clients.of(request))
        val account = accounts.findBy(AccountIdentifier(accountId = result.accountId))
            ?: run { sessions.revokeAll(result.accountId, null, "ACCOUNT_GONE"); throw RefreshInvalidException() }
        val issued = tokens.issueWith(account, delivery.deliver(result.session, response))
        response.setHeader("Cache-Control", "no-store")
        return Response.ok(issued)
    }

    @Operation(summary = "Sign out this session (idempotent, always 204)")
    @NoContentOperation
    @PostMapping("/logout")
    fun logout(@RequestBody(required = false) body: RefreshRequest?, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        requireCsrf(request)
        delivery.read(request, body?.refreshToken)?.let(sessions::logout)
        delivery.clear(response)
        return Response.noContent()
    }

    @Operation(summary = "Active sessions of the caller (device, IP, last used); the current one is flagged")
    @GetMapping("/sessions")
    fun list(authentication: Authentication?): ListResponse<SessionView> {
        val principal = principal(authentication)
        return Response.ok(sessions.list(principal.accountId, principal.sessionId))
    }

    @Operation(summary = "Revoke one of the caller's sessions")
    @NoContentOperation
    @DeleteMapping("/sessions/{id}")
    fun revoke(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        sessions.revoke(principal(authentication).accountId, id)
        return Response.noContent()
    }

    @Operation(summary = "Revoke all the caller's sessions (keepCurrent=true by default)")
    @NoContentOperation
    @DeleteMapping("/sessions")
    fun revokeAll(authentication: Authentication?, @RequestParam(defaultValue = "true") keepCurrent: Boolean): ResponseEntity<Void> {
        val principal = principal(authentication)
        sessions.revokeAll(principal.accountId, if (keepCurrent) principal.sessionId else null)
        return Response.noContent()
    }

    private fun requireCsrf(request: HttpServletRequest) {
        if (!delivery.csrfOk(request)) throw ApplicationException("CSRF header required", AuthSessionErrorCode.CSRF_HEADER_REQUIRED)
    }

    private fun principal(authentication: Authentication?): CurrentPrincipal =
        authentication?.principal as? CurrentPrincipal ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
