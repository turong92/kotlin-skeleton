package dev.sumin.skeleton.auth.api

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import java.time.Instant
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
    private val operatorAccess: OperatorAccess? = null,
) {
    @GetMapping("/me")
    fun me(authentication: Authentication): DataResponse<CurrentPrincipal> =
        Response.ok((authentication.principal as CurrentPrincipal).withPermissions(operatorAccess))
}
