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
}

class RecordingSessionEventListener : dev.sumin.skeleton.auth.session.SessionEventListener {
    val events = java.util.concurrent.CopyOnWriteArrayList<dev.sumin.skeleton.auth.session.SessionEvent>()
    override fun on(event: dev.sumin.skeleton.auth.session.SessionEvent) { events += event }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingSessionEvents {
    @Bean fun recordingSessionEventListener(): RecordingSessionEventListener = RecordingSessionEventListener()
}
