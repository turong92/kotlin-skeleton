package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.payment.RefundDeclineCategory
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/** 확정 거절된 환불 코드가 `REFUND_DECLINED` 의 분류로 옮겨진다. 모름 코드(`ALREADY_*` · `NOT_CANCELABLE_*`)는 확정이 아니라 여기까지 오지 않는다. */
class TossRefundDeclineCategoryTest {
    private val noHttp: ExternalHttpClient =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalHttpClient::class.java)) { _, _, _ -> error("no HTTP expected") } as ExternalHttpClient
    private val provider = TossPaymentProvider(noHttp, TossPaymentProperties(enabled = true, secretKey = "test_sk_x", clientKey = "test_ck_x"))

    @Test
    fun `partial refund codes and the refund period code have their own categories`() {
        assertEquals(RefundDeclineCategory.PARTIAL_NOT_ALLOWED, provider.refundDeclineCategory("NOT_ALLOWED_PARTIAL_REFUND"))
        assertEquals(RefundDeclineCategory.PARTIAL_NOT_ALLOWED, provider.refundDeclineCategory("NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT"))
        assertEquals(RefundDeclineCategory.PERIOD_EXPIRED, provider.refundDeclineCategory("EXCEED_MAX_REFUND_DUE"))
    }

    @Test
    fun `the unknown-outcome refund codes are never given a kind - they are other, even if one ever reached the classifier`() {
        listOf("ALREADY_CANCELED_PAYMENT", "NOT_CANCELABLE_AMOUNT", "EXCEED_CANCEL_LIMIT", "REFUND_REJECTED").forEach {
            assertEquals(RefundDeclineCategory.OTHER, provider.refundDeclineCategory(it), it)
        }
    }
}
