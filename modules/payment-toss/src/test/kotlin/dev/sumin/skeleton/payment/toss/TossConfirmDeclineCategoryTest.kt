package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.toss.support.FakeTossServer
import dev.sumin.skeleton.payment.toss.support.tossProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 토스의 확정 거절 코드 → `DECLINED` 응답의 `data.category`(화면이 문구를 고르는 분류). 프론트 e2e 가 `REJECT_CARD_COMPANY` → `CARD_DECLINED` 를 단정한다.
 * 분류는 확정 거절 목록([TossPaymentErrorMapper.DEFINITIVE_CODES])의 승인 코드를 빠짐없이 덮고, 모르는 코드는 `OTHER` 다.
 */
class TossConfirmDeclineCategoryTest {
    private val server = FakeTossServer()
    private val provider = tossProvider(server)

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `the card refusal codes are CARD_DECLINED, the limit codes are LIMIT_OR_BALANCE and anything else is OTHER`() {
        assertEquals(ConfirmDeclineCategory.CARD_DECLINED, provider.confirmDeclineCategory("REJECT_CARD_COMPANY"))
        listOf("REJECT_CARD_PAYMENT", "REJECT_ACCOUNT_PAYMENT", "INVALID_REJECT_CARD", "INVALID_STOPPED_CARD", "INVALID_CARD_LOST_OR_STOLEN", "INVALID_CARD_NUMBER", "INVALID_CARD_EXPIRATION", "FDS_ERROR")
            .forEach { assertEquals(ConfirmDeclineCategory.CARD_DECLINED, provider.confirmDeclineCategory(it), it) }
        listOf("EXCEED_MAX_AMOUNT", "EXCEED_MAX_PAYMENT_AMOUNT", "EXCEED_MAX_DAILY_PAYMENT_COUNT", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT")
            .forEach { assertEquals(ConfirmDeclineCategory.LIMIT_OR_BALANCE, provider.confirmDeclineCategory(it), it) }
        listOf("INVALID_REQUEST", "UNAUTHORIZED_KEY", "NOT_FOUND_PAYMENT", "SOMETHING_NEW", null).forEach { assertEquals(ConfirmDeclineCategory.OTHER, provider.confirmDeclineCategory(it), it.toString()) }
    }
}
