package dev.sumin.skeleton.waitingroom

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class TokenKind(val tag: String) {
    TICKET("t"),
    ENTRY("e"),
    ;

    companion object {
        fun of(tag: String) = entries.firstOrNull { it.tag == tag }
    }
}

data class TokenClaims(val kind: TokenKind, val roomId: String, val accountId: String, val position: Long?, val expiresAt: Instant?)

/**
 * 순번표 · 입장권 토큰 - `<키 id>.<본문>.<서명>` (HMAC-SHA256, 서명은 키 id 와 본문 전체에 건다). 계정에 묶인다.
 * 순번표는 방 · 계정 · 번호를 싣고 입장권은 방 · 계정 · 만료만 싣는다(종류도 서명 안에 있어 서로 바꿔 쓸 수 없다).
 *
 * **키 돌리기**: [key] 만 서명하고 [previousKey] 는 검증에만 쓴다. 새 키를 `key` 로, 옛 키를 `previous-key` 로 옮겨 배포하고, 옛 토큰이 다 지나면 `previous-key` 를 뺀다.
 * 키 id 는 비밀의 지문(앞 8자)이라 따로 정하지 않는다.
 * [key] 가 없으면 시작 때 만든 무작위 키 하나로 서명한다 - 재시작 · 다른 인스턴스에서는 토큰이 통하지 않는다(보호 환경에서는 [WaitingRoomDeployGuard] 가 기동을 막는다).
 */
class WaitingRoomTokens(key: String?, previousKey: String? = null) {
    private val secrets: Map<String, ByteArray>
    private val active: String

    init {
        val signing = key?.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        active = fingerprint(signing)
        secrets = listOfNotNull(signing, previousKey?.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8)).associateBy { fingerprint(it) }
    }

    fun ticket(roomId: String, accountId: String, position: Long?): String =
        sign(TokenKind.TICKET, roomId, accountId, position?.toString() ?: NONE, NONE)

    fun entry(roomId: String, accountId: String, expiresAt: Instant): String =
        sign(TokenKind.ENTRY, roomId, accountId, NONE, expiresAt.toEpochMilli().toString())

    /** 서명이 맞고 모양이 올바른 토큰의 내용. 아니면 null - 어디가 틀렸는지는 알리지 않는다. 만료 · 계정 · 방은 부르는 쪽이 본다 */
    fun read(token: String?): TokenClaims? {
        if (token == null || token.length > MAX_TOKEN_LENGTH) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        val (keyId, body, mac) = parts
        val secret = secrets[keyId] ?: return null
        val given = decode(mac) ?: return null
        if (!MessageDigest.isEqual(hmac(secret, "$keyId.$body"), given)) return null
        val fields = decode(body)?.toString(StandardCharsets.UTF_8)?.split('\n') ?: return null
        if (fields.size != 5) return null
        val kind = TokenKind.of(fields[0]) ?: return null
        val position = fields[3].takeIf { it != NONE }?.let { it.toLongOrNull() ?: return null }
        val expiresAt = fields[4].takeIf { it != NONE }?.let { Instant.ofEpochMilli(it.toLongOrNull() ?: return null) }
        return TokenClaims(kind, fields[1], fields[2], position, expiresAt)
    }

    private fun sign(kind: TokenKind, roomId: String, accountId: String, position: String, expiresAt: String): String {
        require(listOf(roomId, accountId).none { '\n' in it }) { "token fields must not contain a newline" }
        val body = ENCODER.encodeToString("${kind.tag}\n$roomId\n$accountId\n$position\n$expiresAt".toByteArray(StandardCharsets.UTF_8))
        val mac = ENCODER.encodeToString(hmac(secrets.getValue(active), "$active.$body"))
        return "$active.$body.$mac"
    }

    private fun fingerprint(secret: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(secret).take(4).joinToString("") { "%02x".format(it) }

    private fun hmac(secret: ByteArray, data: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }.doFinal(data.toByteArray(StandardCharsets.UTF_8))

    private fun decode(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val NONE = "-"
        const val MAX_TOKEN_LENGTH = 1024
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
