package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApiResponse
import dev.sumin.skeleton.common.ApiValueResponse
import dev.sumin.skeleton.common.ApplicationException
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequiredLoginIdentifier
data class PasswordLoginRequest(
    @field:Size(max = 64)
    val accountId: String? = null,
    @field:Size(max = 64)
    val username: String? = null,
    @field:Email
    @field:Size(max = 254)
    val email: String? = null,
    @field:NotBlank
    @field:Size(max = 128)
    val password: String? = null,
)

data class AuthTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val principal: CurrentPrincipal,
)

class InvalidCredentialsException : ApplicationException(
    message = "Invalid credentials",
    status = HttpStatus.UNAUTHORIZED,
    title = "Unauthorized",
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val accountRepository: AuthAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authTokenResponseFactory: AuthTokenResponseFactory,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: PasswordLoginRequest,
    ): ApiValueResponse<AuthTokenResponse> {
        val identifier = try {
            AccountIdentifier.from(request.accountId, request.username, request.email)
        } catch (_: IllegalArgumentException) {
            throw InvalidCredentialsException()
        }

        val account = accountRepository.findBy(identifier)
            ?: throw InvalidCredentialsException()

        val password = request.password?.takeIf { it.isNotEmpty() }
            ?: throw InvalidCredentialsException()

        if (!passwordEncoder.matches(password, account.passwordHash)) {
            throw InvalidCredentialsException()
        }

        return ApiResponse.value(authTokenResponseFactory.issue(account))
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): ApiValueResponse<CurrentPrincipal> =
        ApiResponse.value(authentication.principal as CurrentPrincipal)
}
