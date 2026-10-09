package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.LoginIdService
import dev.sumin.skeleton.account.MeView
import dev.sumin.skeleton.account.ProfileService
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 아이디 정하기 — 아이디 게이트(`403 ACCOUNT.LOGIN_ID_REQUIRED`)가 막은 계정이 풀리는 길. `skeleton.account.login-id.enabled=true` 일 때만 등록된다 ([AccountWebAutoConfiguration]) — 꺼짐이면 경로가 없어 404.
 * 규칙은 [LoginIdService], 여기는 HTTP 변환만.
 */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account")
class LoginIdController(
    private val callers: AccountCallers,
    private val loginIds: LoginIdService,
    private val profile: ProfileService,
    private val clientIps: ClientIps = ClientIps(),
) {
    @Operation(
        summary = "Choose the login ID (once; there is no change). 200 with the same body as GET /account/me",
        description = "Works without an email address and without re-authentication. 400 with errors[].field=loginId (Required, Size, Pattern); 409 ACCOUNT.LOGIN_ID_TAKEN (reserved, live and erased IDs alike); " +
            "409 ACCOUNT.LOGIN_ID_ALREADY_SET; 403 ACCOUNT.NOT_ACTIVE; 429 ACCOUNT.RATE_LIMITED (Retry-After) after skeleton.account.login-id.claim-limit attempts per claim-window, counted per account and per client IP - failed attempts count, form errors do not.",
    )
    @PostMapping("/login-id")
    fun claim(authentication: Authentication?, @RequestBody request: LoginIdRequest, http: HttpServletRequest): DataResponse<MeView> {
        val caller = callers.require(authentication)
        val client = clientIps.of(http)
        loginIds.claim(caller.accountId, request.loginId, client.ip, client.limitKey)
        return Response.ok(profile.me(caller.accountId))
    }
}
