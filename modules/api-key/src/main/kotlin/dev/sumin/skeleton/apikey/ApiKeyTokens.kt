package dev.sumin.skeleton.apikey

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 토큰 모양 `<접두사>_<base62 32자>`(≈190비트)와 저장용 해시. 해시는 SHA-256 - 엔트로피가 높아 느린 해시가 필요 없다(OWASP).
 * 접두사는 JWT 와 가르고, 앱이 정한 값이라 비밀 검출 규칙(GitHub 비밀 검색 …)에 걸 수 있다.
 */
class ApiKeyTokens(private val prefix: String, private val random: SecureRandom = SecureRandom()) {
    class Generated(val token: String, val hash: String, val hint: String) {
        override fun toString() = "Generated(<redacted>)"
    }

    fun generate(): Generated {
        val body = CharArray(BODY_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }.concatToString()
        val token = "${prefix}_$body"
        return Generated(token, hash(token), "$prefix…${body.takeLast(HINT_TAIL)}")
    }

    fun hash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    /** 모양만 본다 - 이 접두사로 시작하면 JWT 가 아니라 키로 읽는다 */
    fun supports(token: String): Boolean = token.startsWith("${prefix}_")

    /** 이 접두사의 키는 늘 이 길이다 - 훨씬 긴 값은 해시도 계산하지 않고 거절한다 */
    fun fits(token: String): Boolean = token.length == prefix.length + 1 + BODY_LENGTH

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val BODY_LENGTH = 32
        const val HINT_TAIL = 4
    }
}
