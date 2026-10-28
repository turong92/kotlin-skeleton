package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.time.TimeProvider
import java.security.SecureRandom
import java.time.Instant

/**
 * 공용 코드 — 코드 하나를 여러 사람이 계정당 한 번씩 등록해 쿠폰함으로 받는다(결제에서는 쿠폰함에서 고른다).
 * 등록은 코드 줄을 잠그고 한 트랜잭션에서 계정 중복 · 한도 · 발급을 함께 판정한다 — 실패하면 아무것도 남지 않는다.
 */
class CouponCodes(
    private val store: CouponStore,
    private val issuance: CouponIssuance,
    private val time: TimeProvider,
    private val random: SecureRandom = SecureRandom(),
) {
    /** 코드를 만든다. [code] 를 주면 그 글자(앱의 씨앗용), 아니면 읽기 쉬운 열 글자를 만든다. 이미 있으면 409 */
    fun create(definitionId: String, code: String? = null, maxRedemptions: Int? = null, expiresAt: Instant? = null): String {
        if (maxRedemptions != null && maxRedemptions < 1) throw CouponException(CouponErrorCode.DEFINITION_INVALID, "maxRedemptions is at least 1")
        val wanted = code?.let { normalize(it) }
        if (wanted != null && !CODE_SHAPE.matches(wanted)) throw CouponException(CouponErrorCode.DEFINITION_INVALID, "a code is 4-32 letters, digits, '-' or '_'")
        return store.inTransaction {
            if (store.findDefinition(definitionId) == null) throw CouponException(CouponErrorCode.DEFINITION_NOT_FOUND, "coupon definition $definitionId not found")
            val value = wanted ?: generate()
            if (store.findCode(value) != null) throw CouponException(CouponErrorCode.CODE_EXISTS, "coupon code already exists")
            store.insertCode(CouponCodeRecord(value, definitionId, maxRedemptions, 0, expiresAt, time.now()))
            value
        }
    }

    /** 코드를 쿠폰함 한 장으로 바꾼다. 없는 · 끝난 · 멈춘 코드 400 `CODE_INVALID`, 이미 등록한 계정 409 `CODE_USED`, 한도 다 참 409 `CODE_EXHAUSTED` */
    fun redeem(accountId: String, rawCode: String): Coupon = store.inTransaction {
        val now = time.now()
        val code = normalize(rawCode)
        val record = store.lockCode(code)?.takeUnless { CouponExpiry.isExpired(it.expiresAt, now) } ?: throw failure(CouponErrorCode.CODE_INVALID)
        if (store.hasRedeemed(code, accountId)) throw failure(CouponErrorCode.CODE_USED)
        if (record.maxRedemptions != null && record.redeemedCount >= record.maxRedemptions) throw failure(CouponErrorCode.CODE_EXHAUSTED)

        val outcome = try {
            issuance.outcome(record.definitionId, accountId, CouponSource.CODE, "$code:$accountId")
        } catch (e: CouponException) {
            // 발행 기간 밖은 그대로 알린다. 정의가 멈췄거나 사라졌다면 손님에게는 쓸 수 없는 코드다
            if (e.couponError == CouponErrorCode.ISSUE_CLOSED) throw e
            throw failure(CouponErrorCode.CODE_INVALID)
        }
        val coupon = when (outcome) {
            is IssueOutcome.Issued -> outcome.coupon
            IssueOutcome.DefinitionLimitReached -> throw failure(CouponErrorCode.CODE_EXHAUSTED)
            IssueOutcome.AccountLimitReached -> throw failure(CouponErrorCode.CODE_USED)
        }
        store.insertRedemption(code, accountId)
        store.incrementRedeemed(code)
        coupon
    }

    private fun failure(error: CouponErrorCode) = CouponException(error, error.title)

    private fun generate(): String = buildString { repeat(GENERATED_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private fun normalize(raw: String) = raw.trim().uppercase()

    private companion object {
        const val GENERATED_LENGTH = 10
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // 0 O 1 I 가 없다 - 읽어 주고 받아 적는 코드
        val CODE_SHAPE = Regex("[A-Z0-9_-]{4,32}")
    }
}
