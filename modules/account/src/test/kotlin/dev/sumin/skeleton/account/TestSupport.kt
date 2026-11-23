package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant

class MutableTime(var at: Instant = Instant.parse("2026-10-06T00:00:00Z")) : TimeProvider {
    override fun now(): Instant = at
    fun advance(d: Duration) { at = at.plus(d) }
}

/**
 * 시험용 비밀번호 포트 — `account` 는 해시 방식을 모른다 (`account-password` 의 몫이고 형제 모듈은 시험 의존으로 쓰지 않는다).
 * 해시는 `fake:` + 원문이라, 어디서 만든 비밀번호인지 한눈에 보인다. 비밀번호 줄이 있는 다시 인증 · 시드 계정 시험이 쓴다.
 */
class FakePasswordCredentials : PasswordCredentials {
    override fun hash(raw: String): String = "fake:$raw"
    override fun matches(raw: String, hash: String): Boolean = hash == "fake:$raw"
}

/** 시험용 `password` 수단 — 계정에 비밀번호 줄(identity)이 있는 시험에서 레지스트리가 그 코드를 알게 한다 */
class FakePasswordSignInMethod : SignInMethod {
    override val code: String = SignInMethods.PASSWORD
    override fun normalize(subject: String): String = Emails.normalize(subject)
    override val exposesSubject: Boolean = true
}
