package dev.sumin.skeleton.payment

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/**
 * 결제 장부의 id 접두사와 중복 방지 키를 만드는 곳. 행 id(`ord_` · `rfd_` · `sub_`)는 공통 도우미 `SortableIds` 가 만든다
 * (시간순 ULID — 시각은 드러나지만 80비트 난수라 추측할 수 없다). **같은 요청이 한 줄이게 하는 일은 id 가 아니라 [dedupeKey] 의 유니크 열이 맡는다.**
 */
object PaymentOrderIds {
    /** 결제사에 보내는 주문 번호이기도 하다(토스: 6~64자, 영숫자 - _) — 접두사 + 26자. */
    const val ORDER_PREFIX = "ord_"
    const val SUBSCRIPTION_PREFIX = "sub_"

    private val HEX = "0123456789abcdef".toCharArray()

    /** `sha256(계정 id + "\n" + Idempotency-Key)` — 같은 요청이 늦게 다시 와도 한 줄이게 하는 유니크 열의 값. */
    fun dedupeKey(accountId: String, key: IdempotencyKey): String {
        require(!key.value.contains('\n')) { "Idempotency key must not contain a line break" }
        return hex(MessageDigest.getInstance("SHA-256").digest("$accountId\n${key.value}".toByteArray(Charsets.UTF_8)))
    }

    /**
     * 구독의 **첫 주문 번호**(미리 정해진다) - `sub_<26자>` → `ord_<같은 26자>`. 결제사가 결제 준비 때 주문 번호를 먼저 받는 결제사(카카오페이 `ready` 의 `partner_order_id`)가
     * 구독을 만들 때 이 번호를 쓰고, `activate` 가 같은 번호로 첫 주문 줄을 넣는다. 구독마다 하나다.
     */
    fun initialOrderId(subscriptionId: String): String = ORDER_PREFIX + subscriptionId.removePrefix(SUBSCRIPTION_PREFIX)

    /** `cus_` + 16바이트 무작위의 16진수 32자 — 결제사에 대는 고객 키(행 id 가 아니다. 결제사에 묶이므로 시간순 id 로 바꾸지 않는다). 추측 가능한 값이 아니다. */
    fun newCustomerKey(random: Random = SecureRandom()): String = "cus_" + hex(ByteArray(16).also(random::nextBytes))

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = HEX[(b.toInt() shr 4) and 0x0f]
            out[i * 2 + 1] = HEX[b.toInt() and 0x0f]
        }
        return String(out)
    }
}
