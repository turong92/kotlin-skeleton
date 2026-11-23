package dev.sumin.skeleton.accounttest

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AccountBlockedException
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.api.InvalidCredentialsException
import dev.sumin.skeleton.auth.api.SecondFactorRequiredException
import dev.sumin.skeleton.auth.login.LoginAttempt
import dev.sumin.skeleton.auth.login.LoginFailure
import dev.sumin.skeleton.auth.login.LoginHooks
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /api/v1/auth/login` 의 시험용 대역. 진짜 길은 `auth-password` 모듈이고 이 모듈은 그것을 모른다(형제 모듈에 시험 의존을 걸 수 없다) —
 * 그래서 이 시험들이 보려는 것(`account-password` 의 [LoginHooks] 구현 · 비밀번호 · 계정 상태가 로그인 응답에 닿는 모양)만 같은 모양의 길로 열어 둔다.
 * `PasswordLoginService` 의 핵심(식별자 · 고리 호출 · 해시 비교 · 토큰 발급 · 1단계 통과 기록)의 복사본이다 — 시간 맞추기용 더미 해시 · 해시 업그레이드는 뺐다.
 * 진짜 서비스와 이 모듈의 고리가 함께 도는 것은 앱의 통합 시험(`apps` 의 앱)이 본다.
 */
@RestController
@RequestMapping("/api/v1/auth")
class StandInLoginController(
    private val accounts: AuthAccountRepository,
    private val encoder: PasswordEncoder,
    private val tokens: AuthTokenResponseFactory,
    private val hooks: ObjectProvider<LoginHooks>,
    private val clientIps: ClientIps,
) {
    data class Request(
        @field:Size(max = 64) val accountId: String? = null,
        @field:Size(max = 64) val username: String? = null,
        @field:Size(max = 254) val email: String? = null,
        @field:NotBlank @field:Size(max = 128) val password: String? = null,
    )

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: Request, httpRequest: HttpServletRequest): DataResponse<AuthTokenResponse> {
        val ip = clientIps.of(httpRequest)
        val identifier = try {
            AccountIdentifier.from(request.accountId, request.username, request.email)
        } catch (_: IllegalArgumentException) {
            throw InvalidCredentialsException()
        }
        val attempt = LoginAttempt(
            when {
                identifier.accountId != null -> "accountId:${identifier.accountId}"
                identifier.email != null -> "email:${identifier.email}"
                else -> "username:${identifier.username}"
            },
            ip.ip, ip.limitKey,
        )
        val active = hooks.orderedStream().toList()
        active.forEach { it.beforeAttempt(attempt) }

        val account = accounts.findBy(identifier)
        val matched = account != null && account.passwordHash.isNotBlank() && encoder.matches(request.password, account.passwordHash)
        if (!matched || account == null) {
            active.forEach { it.onFailure(attempt, if (account == null) LoginFailure.UNKNOWN_ACCOUNT else LoginFailure.BAD_PASSWORD, account) }
            throw InvalidCredentialsException()
        }
        val response = try {
            tokens.issue(account)
        } catch (blocked: AccountBlockedException) {
            active.forEach { it.onFailure(attempt, LoginFailure.BLOCKED, account) }
            throw blocked
        } catch (secondStep: SecondFactorRequiredException) {
            active.forEach { it.onSuccess(attempt, account) }
            throw secondStep
        }
        active.forEach { it.onSuccess(attempt, account) }
        return Response.ok(response)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class StandInLoginBeans {
    /** 진짜 길(`auth-password`)이 여는 공개 경로 */
    @Bean
    fun standInLoginPublicEndpoint(): dev.sumin.skeleton.common.web.PublicEndpointContributor =
        dev.sumin.skeleton.common.web.PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/auth/login") }

    @Bean
    fun standInLoginController(
        accounts: AuthAccountRepository,
        encoder: PasswordEncoder,
        tokens: AuthTokenResponseFactory,
        hooks: ObjectProvider<LoginHooks>,
        clientIps: ObjectProvider<ClientIps>,
    ): StandInLoginController = StandInLoginController(accounts, encoder, tokens, hooks, clientIps.getIfAvailable { ClientIps() })
}
