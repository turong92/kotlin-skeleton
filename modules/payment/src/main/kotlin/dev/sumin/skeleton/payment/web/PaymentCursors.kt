package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.payment.OrderCursor
import dev.sumin.skeleton.payment.RefundCursor
import dev.sumin.skeleton.payment.WebhookEventCursor
import java.time.Instant
import java.util.Base64

/**
 * 목록 커서의 글자 모양 — 불투명한 글자 하나(`<종류>.<시각 마이크로초>.<id>` 를 URL 안전 base64 로). 시각은 마이크로초까지다(두 DB 의 시각 정밀도). 읽을 수 없거나 시각이 1970 ~ 9999년 밖이거나 id 에 영문 · 숫자 · `_` · `-` 밖의 글자가 있으면 `400 VALIDATION_FAILED`(field `cursor`).
 * 종류가 달라 한 목록의 커서를 다른 목록에 쓰면 거절된다.
 */
internal object PaymentCursors {
    fun order(c: OrderCursor): String = encode("o", c.createdAt, c.id)
    fun refund(c: RefundCursor): String = encode("r", c.requestedAt, c.id)
    fun webhook(c: WebhookEventCursor): String = encode("w", c.receivedAt, c.id.toString())

    fun order(text: String?): OrderCursor? = decode(text, "o")?.let { OrderCursor(it.first, it.second) }
    fun refund(text: String?): RefundCursor? = decode(text, "r")?.let { RefundCursor(it.first, it.second) }
    fun webhook(text: String?): WebhookEventCursor? =
        decode(text, "w")?.let { (at, id) -> WebhookEventCursor(at, id.toLongOrNull() ?: throw invalid()) }

    private fun encode(kind: String, at: Instant, id: String): String {
        val micros = at.epochSecond * 1_000_000 + at.nano / 1_000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$kind.$micros.$id".toByteArray(Charsets.UTF_8))
    }

    private fun decode(text: String?, kind: String): Pair<Instant, String>? {
        if (text.isNullOrBlank()) return null
        val raw = try {
            String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw invalid()
        }
        val parts = raw.split('.', limit = 3)
        if (parts.size != 3 || parts[0] != kind || parts[2].isEmpty() || parts[2].length > 64) throw invalid()
        val micros = parts[1].toLongOrNull()?.takeIf { it in MIN_MICROS..MAX_MICROS } ?: throw invalid()
        if (!ID_SHAPE.matches(parts[2])) throw invalid()
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000) to parts[2]
    }

    /** 두 DB 가 저장하는 시각의 범위 안(1970 ~ 9999년) — 밖의 값은 질의에서 오류가 되어 500 이 되므로 여기서 400 으로 끊는다. */
    private const val MIN_MICROS = 0L
    private const val MAX_MICROS = 253_402_300_799L * 1_000_000L
    private val ID_SHAPE = Regex("[A-Za-z0-9_-]+")

    private fun invalid() = FieldValidationException("cursor", "Pattern", "cursor is not one this list gave out")
}
