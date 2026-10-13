package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.payment.IdempotencyKey
import tools.jackson.databind.JsonNode

/** 요청 본문 · 헤더 읽기. 숫자 칸은 **정수 JSON 숫자만** 받는다(`"3000"` · `30.5` 를 조용히 바꿔 읽지 않는다). */
internal object PaymentRequests {
    fun idempotencyKey(header: String?): IdempotencyKey {
        val value = header?.trim()
        if (value.isNullOrEmpty()) throw FieldValidationException("Idempotency-Key", "Required", "The Idempotency-Key header is required")
        if (value.length > KEY_MAX || value.any { it.code < 0x21 || it.code > 0x7E }) {
            throw FieldValidationException("Idempotency-Key", "Pattern", "The Idempotency-Key header must be 1 to $KEY_MAX printable ASCII characters")
        }
        return IdempotencyKey(value)
    }

    private val PROVIDER = Regex("[a-z0-9_-]{1,40}")

    /** 결제사 id(선택) — 없으면 null. 모양만 본다; 없는 · 꺼진 결제사는 서비스가 거절한다. */
    fun provider(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        val text = if (node.isString) node.stringValue().trim().lowercase() else ""
        if (!PROVIDER.matches(text)) throw FieldValidationException("provider", "Pattern", "provider must be a payment provider id")
        return text
    }

    fun text(node: JsonNode?, field: String, max: Int): String {
        if (node == null || node.isNull) throw FieldValidationException(field, "Required", "$field is required")
        if (!node.isString) throw FieldValidationException(field, "Pattern", "$field must be a string")
        val value = node.stringValue()
        if (value.isBlank() || value.length > max) throw FieldValidationException(field, "Size", "$field must be 1 to $max characters")
        return value
    }

    /** 없으면 `null`. 있으면 정수여야 한다. */
    fun wholeNumber(node: JsonNode?, field: String): Long? {
        if (node == null || node.isNull || node.isMissingNode) return null
        if (!node.isIntegralNumber || !node.canConvertToLong()) throw FieldValidationException(field, "Pattern", "$field must be a whole number")
        return node.longValue()
    }

    fun requiredWholeNumber(node: JsonNode?, field: String): Long = wholeNumber(node, field) ?: throw FieldValidationException(field, "Required", "$field is required")

    fun size(value: Int?): Int = (value ?: dev.sumin.skeleton.payment.PaymentQueries.DEFAULT_PAGE).coerceIn(1, dev.sumin.skeleton.payment.PaymentQueries.MAX_PAGE)

    private const val KEY_MAX = 200
}
