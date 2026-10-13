package dev.sumin.skeleton.payment.paddle

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `Paddle-Signature: ts=<unix>;h1=<hex>` 검증 — HMAC-SHA256(secret, "<ts>:<원문 바이트>"). 비밀 교체 중에는 `h1` 이 여럿일 수 있어 하나라도 맞으면 된다.
 * 시각이 [tolerance] 보다 멀면(과거든 미래든) 서명이 맞아도 거절한다 — 가로챈 요청의 재전송을 막는다.
 */
internal object PaddleSignature {
    fun verify(header: String?, body: ByteArray, secret: String, now: Instant, tolerance: Duration): Boolean {
        if (header.isNullOrBlank() || secret.isBlank()) return false
        var ts: Long? = null
        val signatures = mutableListOf<String>()
        for (part in header.split(';')) {
            val (key, value) = part.trim().split('=', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            when (key) {
                "ts" -> ts = value.toLongOrNull()
                "h1" -> signatures += value
            }
        }
        val seconds = ts ?: return false
        if (signatures.isEmpty()) return false
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs() > tolerance) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        mac.update("$seconds:".toByteArray())
        val expected = mac.doFinal(body)
        // 모든 후보를 끝까지 비교한다(맞는 것이 앞에 있어도 멈추지 않는다)
        return signatures.fold(false) { matched, h1 -> MessageDigest.isEqual(expected, hex(h1) ?: ByteArray(0)) or matched }
    }

    private fun hex(text: String): ByteArray? =
        if (text.length % 2 != 0 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) null else ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
