package dev.sumin.skeleton.sessionstest

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.LoginBlock
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.crypto.password.PasswordEncoder

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class SessionWebTestApplication

class MutableAccounts(private val encoder: PasswordEncoder) : AuthAccountRepository {
    val accounts = ConcurrentHashMap<String, AuthAccount>()

    init { reset() }

    fun reset() {
        accounts.clear()
        put(AuthAccount("acc_ann", "ann@example.com", "ann@example.com", encoder.encode("correct-horse")!!, setOf("USER")))
        put(AuthAccount("acc_bob", "bob@example.com", "bob@example.com", encoder.encode("correct-horse")!!, setOf("USER")))
    }

    fun put(a: AuthAccount) { accounts[a.accountId] = a }
    fun block(id: String, block: LoginBlock?) { accounts.computeIfPresent(id) { _, a -> a.copy(loginBlock = block) } }
    fun roles(id: String, roles: Set<String>) { accounts.computeIfPresent(id) { _, a -> a.copy(roles = roles) } }
    fun remove(id: String) { accounts.remove(id) }

    override fun findBy(identifier: AccountIdentifier): AuthAccount? =
        accounts.values.firstOrNull { it.accountId == identifier.accountId || it.email == identifier.email }
}

@TestConfiguration(proxyBeanMethods = false)
class SessionTestAccounts {
    @Bean fun mutableAccounts(encoder: PasswordEncoder): MutableAccounts = MutableAccounts(encoder)
    @Bean fun firstStepPassedController(accounts: AuthAccountRepository, tokens: dev.sumin.skeleton.auth.api.AuthTokenResponseFactory): FirstStepPassedController = FirstStepPassedController(accounts, tokens)
    @Bean fun firstStepPassedPublic(): dev.sumin.skeleton.common.web.PublicEndpointContributor =
        dev.sumin.skeleton.common.web.PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/auth/test-first-step-passed") }
}

class RecordingSessionEventListener : dev.sumin.skeleton.auth.session.SessionEventListener {
    val events = java.util.concurrent.CopyOnWriteArrayList<dev.sumin.skeleton.auth.session.SessionEvent>()
    override fun on(event: dev.sumin.skeleton.auth.session.SessionEvent) { events += event }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingSessionEvents {
    @Bean fun recordingSessionEventListener(): RecordingSessionEventListener = RecordingSessionEventListener()
}

/**
 * 1단계(비밀번호 · 소셜 · 매직 링크)를 통과한 계정에 토큰 발급을 요청하는 시험용 길 — 진짜 발급 팩토리를 그대로 부르므로 세션이 열리고 응답이 본문 · 쿠키로 나간다.
 * 이 모듈은 `auth` 만 안다: 비밀번호 확인은 `auth-password` 의 몫이라 여기서는 계정이 있느냐만 본다 (없으면 로그인과 같은 401).
 */
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/v1/auth")
class FirstStepPassedController(
    private val accounts: AuthAccountRepository,
    private val tokens: dev.sumin.skeleton.auth.api.AuthTokenResponseFactory,
) {
    data class Request(val email: String)

    @org.springframework.web.bind.annotation.PostMapping("/test-first-step-passed")
    fun issue(@org.springframework.web.bind.annotation.RequestBody request: Request): dev.sumin.skeleton.common.DataResponse<dev.sumin.skeleton.auth.api.AuthTokenResponse> {
        val account = accounts.findBy(AccountIdentifier(email = request.email)) ?: throw dev.sumin.skeleton.auth.api.InvalidCredentialsException()
        return dev.sumin.skeleton.common.Response.ok(tokens.issue(account))
    }
}
