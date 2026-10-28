package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.discount.DiscountClaim
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

    /** 할인 수단 지정 `[{ "type": "coupon", "ref": "…" }]` - 없으면 빈 목록. 몇 개까지 받을지는 서버가 정한다([dev.sumin.skeleton.payment.PaymentDiscounts.MAX_CLAIMS]); 여기서는 모양과 크기만 본다. */
    fun discounts(node: JsonNode?): List<DiscountClaim> {
        if (node == null || node.isNull || node.isMissingNode) return emptyList()
        if (!node.isArray) throw FieldValidationException("discounts", "Pattern", "discounts must be a list")
        if (node.size() > DISCOUNTS_MAX) throw FieldValidationException("discounts", "Size", "discounts must have at most $DISCOUNTS_MAX entries")
        return (0 until node.size()).map { node[it] }.map { entry ->
            if (!entry.isObject) throw FieldValidationException("discounts", "Pattern", "each discount must be an object")
            DiscountClaim(text(entry["type"], "discounts.type", 16), text(entry["ref"], "discounts.ref", 128))
        }
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

    /** 없으면 [default]. 있으면 true · false 여야 한다. */
    fun flag(node: JsonNode?, field: String, default: Boolean): Boolean {
        if (node == null || node.isNull || node.isMissingNode) return default
        if (!node.isBoolean) throw FieldValidationException(field, "Pattern", "$field must be true or false")
        return node.booleanValue()
    }

    fun requiredWholeNumber(node: JsonNode?, field: String): Long = wholeNumber(node, field) ?: throw FieldValidationException(field, "Required", "$field is required")

    fun size(value: Int?): Int = (value ?: dev.sumin.skeleton.payment.PaymentQueries.DEFAULT_PAGE).coerceIn(1, dev.sumin.skeleton.payment.PaymentQueries.MAX_PAGE)

    private const val KEY_MAX = 200
    private const val DISCOUNTS_MAX = 5
}
