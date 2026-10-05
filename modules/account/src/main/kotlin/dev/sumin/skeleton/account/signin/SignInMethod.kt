package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.Emails
import dev.sumin.skeleton.account.SignInMethods

/**
 * 로그인 수단 하나의 **성격**. 계정에는 수단이 [dev.sumin.skeleton.account.Identity] 행으로 붙고, 이 SPI 는 그 행을 어떻게 다룰지만 말한다
 * (증명하는 방법 — 비밀번호 확인 · OAuth 코드 교환 · 메일 링크 소비 — 은 수단이 자기 엔드포인트에 둔다).
 *
 * 새 수단을 더하는 법: ① 이 인터페이스를 구현한 빈 하나 ② 자기 엔드포인트에서 증명이 끝나면 [AccountSignInService.signIn] 에 [SignInProof] 를 넘기고
 * 돌려받은 `AuthAccount` 를 `AuthTokenResponseFactory.issue` 에 넘긴다. 스키마 · 계정 모듈 변경은 없다 — 수단 코드는 문자열이다.
 * OAuth 제공자는 `OAuthProvider` 빈만 더하면 이 SPI 구현이 자동으로 따라온다 (docs/accounts.md).
 */
interface SignInMethod {
    /** 저장되는 안정 코드 (소문자 · 숫자 · `_`). 바꾸면 기존 행이 끊긴다 */
    val code: String

    /** 이 수단 안에서 같은 주체를 같은 문자열로 — 이메일은 소문자, 제공자 사용자 id 는 그대로 */
    fun normalize(subject: String): String = subject.trim()

    /** 이 수단이 "계정에 들어올 수 있는 길" 로 센다 — 마지막 길은 지울 수 없다 */
    val countsAsCredential: Boolean get() = true

    /** 사용자가 설정에서 스스로 뗄 수 있나 */
    val userRemovable: Boolean get() = true

    /** 이 수단의 증명이 곧 **그 이메일 주소의 메일함을 가졌다는 증명**이다 (매직 링크). 그러면 같은 이메일의 기존 계정에 붙여도 안전하다 */
    val provesEmail: Boolean get() = false

    /** 목록에 [dev.sumin.skeleton.account.Identity.subject] 를 보여 줄까 (이메일 계열은 예, 제공자 사용자 id 는 아니오) */
    val exposesSubject: Boolean get() = false
}

/** 비밀번호 수단 — 주체는 이메일이고, 해시는 [dev.sumin.skeleton.account.Identity.secret] 에 있다 */
class PasswordSignInMethod : SignInMethod {
    override val code: String = SignInMethods.PASSWORD
    override fun normalize(subject: String): String = Emails.normalize(subject)
    override val exposesSubject: Boolean = true
}

class SignInMethodRegistry(methods: List<SignInMethod>) {
    private val byCode: Map<String, SignInMethod>

    init {
        methods.forEach { require(CODE.matches(it.code)) { "sign-in method code '${it.code}' must match ${CODE.pattern}" } }
        val dup = methods.groupBy { it.code }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate sign-in method code(s): $dup" }
        byCode = methods.associateBy { it.code }
    }

    fun find(code: String): SignInMethod? = byCode[code]

    fun require(code: String): SignInMethod = byCode[code] ?: throw AccountException(AccountErrorCode.METHOD_UNKNOWN)

    fun all(): List<SignInMethod> = byCode.values.toList()

    fun credentialCodes(): Set<String> = byCode.values.filter { it.countsAsCredential }.map { it.code }.toSet()

    private companion object { val CODE = Regex("^[a-z][a-z0-9_]{1,31}$") }
}
