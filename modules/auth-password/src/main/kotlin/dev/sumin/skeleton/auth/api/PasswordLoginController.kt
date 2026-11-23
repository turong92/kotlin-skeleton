package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.login.PasswordLoginService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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

@RestController
@RequestMapping("/api/v1/auth")
class PasswordLoginController(
    private val loginService: PasswordLoginService,
    private val clientIps: ClientIps,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: PasswordLoginRequest,
        httpRequest: HttpServletRequest,
    ): DataResponse<AuthTokenResponse> =
        clientIps.of(httpRequest).let { Response.ok(loginService.login(request, it.ip, it.limitKey)) }
}
