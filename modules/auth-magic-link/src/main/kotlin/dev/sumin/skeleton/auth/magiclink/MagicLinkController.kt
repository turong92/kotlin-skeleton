package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.account.web.EmailRequest
import dev.sumin.skeleton.account.web.StatusResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class MagicLinkRedeemRequest(@field:NotBlank @field:Size(max = 128) val token: String?) {
    override fun toString() = "MagicLinkRedeemRequest(token=<redacted>)"
}

/** 매직 링크 로그인 HTTP — 요청(항상 202)과 소비(토큰 응답). [MagicLinkAutoConfiguration] 이 등록한다 */
@RestController
@RequestMapping("/api/v1/auth/magic-link")
@Tag(name = "Auth magic link")
class MagicLinkController(
    private val service: MagicLinkService,
    private val tokens: AuthTokenResponseFactory,
    private val clientIps: ClientIps,
) {
    @Operation(summary = "Mail a one-time sign-in link (always 202, whether or not the address has an account)")
    @PostMapping("/request")
    fun request(@Valid @RequestBody body: EmailRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        service.request(body.email!!, clientIps.of(http).ip, body.captchaToken)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Response.ok(StatusResponse("SENT")))
    }

    @Operation(summary = "Sign in with the link token (single use; 410 ACCOUNT.TOKEN_INVALID otherwise)")
    @PostMapping("/redeem")
    fun redeem(@Valid @RequestBody body: MagicLinkRedeemRequest, http: HttpServletRequest): DataResponse<AuthTokenResponse> =
        Response.ok(tokens.issue(service.redeem(body.token!!, clientIps.of(http).ip)))
}
