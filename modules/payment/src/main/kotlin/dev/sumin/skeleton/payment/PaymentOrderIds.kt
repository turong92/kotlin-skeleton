package dev.sumin.skeleton.payment

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/** 주문 id · 중복 방지 키를 만드는 곳. 순번 · 시각을 담지 않는다(추측할 수 없어야 한다). */
object PaymentOrderIds {
    private val HEX = "0123456789abcdef".toCharArray()

    /** `ord_` + 16바이트 무작위의 16진수 32자. 결제사에 보내는 주문 번호이기도 하다(토스: 6~64자, 영숫자 - _). */
    fun newOrderId(random: Random = SecureRandom()): String = "ord_" + hex(ByteArray(16).also(random::nextBytes))

    /** `sha256(계정 id + "\n" + Idempotency-Key)` — 같은 요청이 늦게 다시 와도 한 줄이게 하는 유니크 열의 값. */
    fun dedupeKey(accountId: String, key: IdempotencyKey): String {
        require(!key.value.contains('\n')) { "Idempotency key must not contain a line break" }
        return hex(MessageDigest.getInstance("SHA-256").digest("$accountId\n${key.value}".toByteArray(Charsets.UTF_8)))
    }

    /** `cus_` + 16바이트 무작위의 16진수 32자 — 결제사에 대는 고객 키. 추측 가능한 값(계정 id · 이메일 · 순번)이 아니다. */
    fun newCustomerKey(random: Random = SecureRandom()): String = "cus_" + hex(ByteArray(16).also(random::nextBytes))

    /** 구독을 만드는 요청의 id — 같은 계정의 같은 `Idempotency-Key` 는 늘 같은 id 라 같은 요청이 한 줄이다(`sub_` + `sha256(계정 id + "\n" + 키)` 의 앞 32자). */
    fun subscriptionId(accountId: String, key: IdempotencyKey): String = "sub_" + dedupeKey(accountId, key).take(32)

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = HEX[(b.toInt() shr 4) and 0x0f]
            out[i * 2 + 1] = HEX[b.toInt() and 0x0f]
        }
        return String(out)
    }
}
