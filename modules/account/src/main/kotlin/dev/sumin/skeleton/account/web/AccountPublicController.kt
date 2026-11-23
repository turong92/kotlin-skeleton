package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.api.SecondFactorRequiredException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 전에 부르는 계정 흐름 중 어떤 로그인 수단에도 속하지 않는 것 (탈퇴 취소). 가입 · 이메일 확인 · 비밀번호 찾기 · 재설정은 `account-password` 의 컨트롤러다.
 * HTTP 변환만 — 규칙은 서비스. [AccountWebAutoConfiguration] 이 등록한다.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account")
class AccountPublicController(
    private val deletion: dev.sumin.skeleton.account.DeletionService,
    private val signIns: AccountSignInService,
    private val clientIps: ClientIps,
    /** 로그인 토큰을 내는 곳 — 취소가 곧 로그인이다. 늦게 찾는다 (`auth` 의 자동설정이 만든다) */
    private val tokens: () -> AuthTokenResponseFactory,
) {
    @Operation(
        summary = "Cancel a pending account deletion with the restore token that a successful sign-in returned (403 AUTH.ACCOUNT_DELETION_PENDING data.restoreToken); signs in",
        description = "Needs skeleton.account.deletion.self-restore=true. 410 ACCOUNT.TOKEN_INVALID for an unknown, used, expired or foreign token, a lapsed grace or a suspended account; 429 when tried too often.",
    )
    @PostMapping("/account/delete/cancel")
    fun cancelDeletion(@Valid @RequestBody request: CancelDeletionRequest, http: HttpServletRequest): DataResponse<AuthTokenResponse> =
        clientIps.of(http).let { client ->
            val cancelled = deletion.cancelWithMethod(request.restoreToken!!, client.ip, client.limitKey) { tokens().secondFactorEnrolled(it) }
            val response = try {
                // 2단계를 지난 뒤에 받은 취소 토큰이면 같은 단계를 다시 묻지 않는다
                if (cancelled.secondFactorPassed) tokens().issueAfterSecondFactor(cancelled.auth) else tokens().issue(cancelled.auth)
            } catch (secondStep: SecondFactorRequiredException) {
                signIns.recordSignIn(cancelled.auth.accountId, client.ip, cancelled.method)   // 첫 단계는 통과했다 — 토큰은 두 번째 단계 뒤에
                throw secondStep
            }
            signIns.recordSignIn(cancelled.auth.accountId, client.ip, cancelled.method)   // 취소를 낳은 로그인의 실제 수단
            Response.ok(response)
        }
}
