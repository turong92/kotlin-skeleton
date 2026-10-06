package dev.sumin.skeleton.auth.api

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.auth.login.PasswordLoginService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import org.springframework.security.core.Authentication
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
) {
    // Spring MVC 가 DEBUG · TRACE 에서 요청 본문을 toString 으로 찍는다 — 비밀번호가 로그에 남지 않게
    override fun toString() = "PasswordLoginRequest(identifier=<redacted>, password=<redacted>)"
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuthTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val principal: CurrentPrincipal,
    /** `auth-session` 이 설치되고 body 전달일 때만 — 쿠키 전달이면 쿠키로 내려가고 여기는 비어 있다 */
    val refreshToken: String? = null,
    val refreshExpiresAt: Instant? = null,
    val sessionId: String? = null,
) {
    // 응답 본문도 DEBUG 에서 toString 으로 찍힐 수 있다 — 토큰이 로그에 남지 않게
    override fun toString() = "AuthTokenResponse(principal=${principal.accountId}, expiresAt=$expiresAt, accessToken=<redacted>, refreshToken=${if (refreshToken == null) "none" else "<redacted>"})"
}

class InvalidCredentialsException : ApplicationException(
    message = "Invalid credentials",
    errorCode = AuthErrorCode.INVALID_CREDENTIALS,
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val loginService: PasswordLoginService,
    private val clientIps: ClientIps,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: PasswordLoginRequest,
        httpRequest: HttpServletRequest,
    ): DataResponse<AuthTokenResponse> =
        clientIps.of(httpRequest).let { Response.ok(loginService.login(request, it.ip, it.limitKey)) }

    @GetMapping("/me")
    fun me(authentication: Authentication): DataResponse<CurrentPrincipal> =
        Response.ok(authentication.principal as CurrentPrincipal)
}
