package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.TraceIdFilter
import java.time.Instant
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PasswordLoginRequest(
    val accountId: String? = null,
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
)

data class AuthTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val principal: CurrentPrincipal,
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val accountRepository: AuthAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenService: JwtTokenService,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: PasswordLoginRequest): ResponseEntity<Any> {
        val identifier = try {
            AccountIdentifier.from(request.accountId, request.username, request.email)
        } catch (_: IllegalArgumentException) {
            return unauthorized()
        }

        val account = accountRepository.findBy(identifier)
            ?: return unauthorized()

        val password = request.password?.takeIf { it.isNotEmpty() }
            ?: return unauthorized()

        if (!passwordEncoder.matches(password, account.passwordHash)) {
            return unauthorized()
        }

        val principal = account.toCurrentPrincipal()
        val token = jwtTokenService.issue(principal)
        return ResponseEntity.ok(
            AuthTokenResponse(
                accessToken = token.accessToken,
                expiresAt = token.expiresAt,
                principal = principal,
            ),
        )
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): CurrentPrincipal =
        authentication.principal as CurrentPrincipal

    private fun unauthorized(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiError(
                title = "Unauthorized",
                status = HttpStatus.UNAUTHORIZED.value(),
                detail = "Invalid credentials",
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )

    private fun AuthAccount.toCurrentPrincipal(): CurrentPrincipal =
        CurrentPrincipal(
            accountId = accountId,
            username = username,
            email = email,
            roles = roles,
        )
}
