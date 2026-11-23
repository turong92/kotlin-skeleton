package dev.sumin.skeleton.account.password

import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.PasswordViolation
import org.slf4j.LoggerFactory

/**
 * 선택 고리 — 이 비밀번호가 알려진 유출 목록에 있나? 기본 구현은 **없다**(네트워크 호출을 기본으로 하지 않는다).
 * 앱이 빈으로 등록하면(HIBP k-익명 조회 · 자체 목록 …) 로컬 규칙을 모두 통과한 비밀번호에만 불린다. 던지면 가입을 막지 않고(열린 채 실패) 경고만 남긴다.
 */
fun interface BreachedPasswordCheck {
    fun isBreached(password: String): Boolean
}

/** 화면에 힌트를 그리려고 내보내는 정책 요약 (내장 금지 목록은 싣지 않는다) */
data class PasswordPolicyView(
    val minLength: Int,
    val maxBytes: Int,
    val requireLetter: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val forbidEmailLocalPart: Boolean,
)

interface PasswordPolicy {
    /** 위반 목록 (비면 통과). [email] 은 "이메일 앞부분 포함 금지" 검사용 */
    fun check(password: String, email: String?): List<PasswordViolation>

    fun describe(): PasswordPolicyView
}

class DefaultPasswordPolicy(
    private val props: AccountProperties.Password,
    private val breached: BreachedPasswordCheck? = null,
) : PasswordPolicy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(password: String, email: String?): List<PasswordViolation> {
        val found = mutableListOf<PasswordViolation>()
        if (password.length < props.minLength) found += PasswordViolation.TOO_SHORT
        if (password.toByteArray(Charsets.UTF_8).size > props.maxBytes) found += PasswordViolation.TOO_LONG
        if (props.requireLetter && password.none { it.isLetter() }) found += PasswordViolation.NEEDS_LETTER
        if (props.requireDigit && password.none { it.isDigit() }) found += PasswordViolation.NEEDS_DIGIT
        if (props.requireSymbol && password.none { !it.isLetterOrDigit() && !it.isWhitespace() }) found += PasswordViolation.NEEDS_SYMBOL
        if (props.forbidEmailLocalPart && email != null) {
            val local = email.substringBefore('@').lowercase()
            if (local.length >= MIN_LOCAL_PART && password.lowercase().contains(local)) found += PasswordViolation.CONTAINS_EMAIL
        }
        if (props.denyCommon && isCommon(password)) found += PasswordViolation.TOO_COMMON
        if (found.isEmpty() && breached != null) {
            val hit = try { breached.isBreached(password) } catch (e: Exception) {
                // 조회가 죽었다고 가입을 막지 않는다 — 대신 한 줄 경고 (비밀번호 · 예외 메시지는 싣지 않는다)
                log.warn("BreachedPasswordCheck failed; skipping it: {}", e.javaClass.simpleName)
                false
            }
            if (hit) found += PasswordViolation.BREACHED
        }
        return found
    }

    override fun describe() = PasswordPolicyView(
        props.minLength, props.maxBytes, props.requireLetter, props.requireDigit, props.requireSymbol, props.forbidEmailLocalPart,
    )

    private fun isCommon(password: String): Boolean {
        val p = password.lowercase()
        return p in COMMON || COMMON.any { it.length >= 6 && p.startsWith(it) && p.drop(it.length).all(Char::isDigit) }
    }

    private companion object {
        const val MIN_LOCAL_PART = 3

        /** 가장 흔한 비밀번호 몇십 개 (오프라인) — 더 큰 목록은 BreachedPasswordCheck 로 */
        val COMMON = setOf(
            "password", "password1", "passw0rd", "p@ssw0rd", "123456", "1234567", "12345678", "123456789", "1234567890", "0123456789",
            "qwerty", "qwerty123", "qwertyuiop", "qwerty12345", "abc123", "abcd1234", "iloveyou", "admin", "admin123", "welcome",
            "welcome1", "letmein", "monkey", "dragon", "football", "baseball", "sunshine", "princess", "login", "starwars",
            "master", "hello123", "freedom", "whatever", "trustno1", "654321", "111111", "000000", "1q2w3e4r", "zaq12wsx",
            "asdfghjkl", "asdf1234", "changeme", "default", "secret", "test1234", "kotlin", "skeleton",
        )
    }
}
