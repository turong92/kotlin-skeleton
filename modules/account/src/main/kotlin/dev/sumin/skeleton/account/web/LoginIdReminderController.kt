package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.LoginIdReminderService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.AcceptedOperation
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 아이디 찾기 — 주소를 넣으면 그 주소의 계정이 있을 때만 아이디를 메일로 보낸다. `skeleton.account.login-id.enabled=true` 일 때만 등록된다 ([AccountWebAutoConfiguration]) — 꺼짐이면 경로가 없어 404.
 * 응답은 계정 존재 여부와 무관하게 같다 (`password/forgot` 과 같은 틀).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account")
class LoginIdReminderController(
    private val reminders: LoginIdReminderService,
    private val clientIps: ClientIps,
) {
    @Operation(
        summary = "Ask for the login ID by mail (always 202)",
        description = "Public. Always 202 { status: ACCEPTED }, whether or not an account exists for the address. Only 429 ACCOUNT.RATE_LIMITED (Retry-After) per IP (skeleton.account.reset.per-ip, its own bucket). " +
            "The mail goes to the account email only, for ACTIVE and PENDING_VERIFICATION accounts, within skeleton.account.reset.per-email per address (silent); an account without an ID gets the mail that says so.",
    )
    @PostMapping("/account/login-id/forgot")
    @AcceptedOperation
    fun forgot(@Valid @RequestBody request: LoginIdForgotRequest, http: HttpServletRequest): ResponseEntity<DataResponse<StatusResponse>> {
        clientIps.of(http).let { reminders.forgot(request.email!!, it.ip, request.captchaToken, it.limitKey) }
        return Response.accepted(StatusResponse("ACCEPTED"))
    }
}
