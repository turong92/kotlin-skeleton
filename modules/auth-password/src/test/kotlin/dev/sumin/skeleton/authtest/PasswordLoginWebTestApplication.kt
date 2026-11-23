package dev.sumin.skeleton.authtest

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.login.LoginAttempt
import dev.sumin.skeleton.auth.login.LoginFailure
import dev.sumin.skeleton.auth.login.LoginHooks
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class PasswordLoginWebTestApplication

/** 로그인 앞뒤 고리가 받은 시도를 기록한다 — `account-password` 가 이 자리에 한도 · 기록을 건다 */
class RecordingLoginHooks : LoginHooks {
    val attempts = CopyOnWriteArrayList<LoginAttempt>()
    val failures = CopyOnWriteArrayList<LoginFailure>()
    val successes = CopyOnWriteArrayList<String>()

    override fun beforeAttempt(attempt: LoginAttempt) { attempts += attempt }
    override fun onFailure(attempt: LoginAttempt, reason: LoginFailure, account: AuthAccount?) { failures += reason }
    override fun onSuccess(attempt: LoginAttempt, account: AuthAccount) { successes += account.accountId }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingLoginHooksBeans {
    @Bean fun recordingLoginHooks(): RecordingLoginHooks = RecordingLoginHooks()
}
