package dev.sumin.skeleton.auth.mfa

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 복구 코드 — 16자 base32(80비트)를 4자씩 하이픈으로 묶은 것 10개. 저장은 SHA-256(16진 64자)뿐이다: 엔트로피가 높아 느린 해시가 필요 없고,
 * JWT 비밀에서 유도한 HMAC 으로 하지 않은 까닭은 비밀을 돌려도 오래 사는 복구 코드가 죽지 않게 하려는 것이다. 입력은 대소문자 · 공백 · 하이픈을 무시한다.
 */
object RecoveryCodes {
    const val COUNT = 10
    private val random = SecureRandom()

    fun generate(): List<String> = List(COUNT) {
        Base32.encode(ByteArray(10).also(random::nextBytes)).chunked(4).joinToString("-")
    }

    fun hash(code: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalize(code).toByteArray(Charsets.UTF_8)))

    private fun normalize(code: String) = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()
}
