package dev.sumin.skeleton.mfatest

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountSeeder
import dev.sumin.skeleton.account.Emails
import dev.sumin.skeleton.account.PasswordCredentials
import dev.sumin.skeleton.account.SignInMethods
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory

/**
 * 비밀번호 가입 · 로그인 없이 계정과 토큰을 만드는 시험 장치 — `modules/account` 시험의 `AccountFixtures` 복사 (형제 모듈에 시험 의존을 걸지 않으려고 모듈마다 복사한다).
 * 계정은 기존 시드 장치로(비밀번호 줄은 [FakePasswordCredentials]), 토큰은 앱이 쓰는 그 팩토리로 낸다. 로그인 고리(LoginHooks)는 지나지 않는다.
 */
class AccountFixtures(
    private val core: AccountCore,
    private val accounts: AccountRepository,
    private val authAccounts: AuthAccountRepository,
    private val tokens: AuthTokenResponseFactory,
) {
    fun create(email: String, password: String = "tangerine-42-moon"): Account {
        AccountSeeder(core).seed(listOf(AccountProperties.SeedAccount(email = email, password = password)))
        return accounts.findByEmail(Emails.normalize(email))!!.also { core.bootstrap.afterVerified(it) }
    }

    fun bearer(email: String): String =
        "Bearer " + tokens.issue(authAccounts.findBy(AccountIdentifier(email = Emails.normalize(email)))!!).accessToken
}

/**
 * 1단계(비밀번호 · 소셜 · 매직 링크)를 통과한 계정에 토큰 발급을 요청하는 시험용 길 — 진짜 발급 팩토리를 그대로 부르므로 2단계가 켜진 계정은 `403 AUTH.MFA_REQUIRED` 도전 응답을 받는다.
 * 비밀번호 확인 · 로그인 고리는 없다 (그것은 `auth-password` · `account-password` 의 몫).
 */
@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/v1/auth")
class FirstStepPassedController(
    private val authAccounts: AuthAccountRepository,
    private val tokens: AuthTokenResponseFactory,
) {
    data class Request(val email: String)

    @org.springframework.web.bind.annotation.PostMapping("/test-first-step-passed")
    fun issue(@org.springframework.web.bind.annotation.RequestBody request: Request): dev.sumin.skeleton.common.DataResponse<dev.sumin.skeleton.auth.api.AuthTokenResponse> =
        dev.sumin.skeleton.common.Response.ok(tokens.issue(authAccounts.findBy(AccountIdentifier(email = Emails.normalize(request.email)))!!))
}

/** 시험용 비밀번호 포트 — 해시는 `fake:` + 원문 (진짜 해시는 `account-password` 의 시험) */
class FakePasswordCredentials : PasswordCredentials {
    override fun hash(raw: String): String = "fake:$raw"
    override fun matches(raw: String, hash: String): Boolean = hash == "fake:$raw"
}

/** 시험용 `password` 수단 — 계정에 비밀번호 줄이 있는 시험에서 레지스트리가 그 코드를 알게 한다 */
class FakePasswordSignInMethod : SignInMethod {
    override val code: String = SignInMethods.PASSWORD
    override fun normalize(subject: String): String = Emails.normalize(subject)
    override val exposesSubject: Boolean = true
}
