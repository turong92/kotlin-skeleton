package dev.sumin.skeleton.auth.handoff

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class WebHandoffRedeemRequest(@field:NotBlank @field:Size(max = 128) val code: String?) {
    override fun toString() = "WebHandoffRedeemRequest(code=<redacted>)"
}

/** 웹이 주소의 `#` 조각에서 읽은 코드를 본문으로 보내 로그인 세션을 받는다 - 코드가 주소(접근 로그)에 실리지 않게 POST 본문으로만 */
@RestController
@RequestMapping("/api/v1/auth/web-handoff")
@Tag(name = "Auth web handoff")
class WebHandoffController(
    private val handoff: WebHandoff,
    private val tokens: AuthTokenResponseFactory,
) {
    @Operation(summary = "Exchange the one-time code the app handed over for a sign-in (single use, short-lived; 410 ACCOUNT.TOKEN_INVALID otherwise)")
    @PostMapping("/redeem")
    fun redeem(@Valid @RequestBody body: WebHandoffRedeemRequest): DataResponse<AuthTokenResponse> =
        Response.ok(tokens.issue(handoff.redeem(body.code!!)))
}
