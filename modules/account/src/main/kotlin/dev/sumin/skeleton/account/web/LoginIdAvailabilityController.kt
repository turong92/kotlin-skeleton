package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.LoginIdAvailability
import dev.sumin.skeleton.account.LoginIdAvailabilityService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 가입 양식 · 「아이디 정하기」 화면의 실시간 아이디 확인. `skeleton.account.login-id.enabled=true` 일 때만 등록된다 ([AccountWebAutoConfiguration]) — 꺼짐이면 경로가 없어 404.
 * 후보 아이디가 주소 줄 · 접근 로그에 남지 않게 POST 이고, 응답은 `Cache-Control: no-store`.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account")
class LoginIdAvailabilityController(
    private val availability: LoginIdAvailabilityService,
    private val clientIps: ClientIps,
) {
    @Operation(
        summary = "Is this login ID free? Always 200 { normalized, available, reason?: INVALID | TAKEN, code?: Required | Size | Pattern }",
        description = "Public. TAKEN covers reserved, live and erased IDs alike. A malformed ID (INVALID) neither reads accounts nor uses the allowance. " +
            "429 ACCOUNT.RATE_LIMITED (Retry-After) per IP (skeleton.account.login-id.check-per-ip). Not a reservation: sign-up decides again.",
    )
    @PostMapping("/account/login-id/availability")
    fun check(@RequestBody request: LoginIdAvailabilityRequest, http: HttpServletRequest): ResponseEntity<DataResponse<LoginIdAvailabilityResponse>> {
        val answer = availability.check(request.loginId, clientIps.of(http).limitKey)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.ok(answer.toBody()))
    }

    private fun LoginIdAvailability.toBody() = LoginIdAvailabilityResponse(normalized, available, reason, code)
}
