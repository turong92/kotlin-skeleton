package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired

/**
 * 탈퇴 고리의 **순서**(B8 리뷰 m2): 응원 기록 삭제(`cheers`)가 장부의 탈퇴 고리(`payment`)보다 **뒤에** 돈다. 장부 고리가 먼저 주문 줄에 `account_erased_at` 을 찍어야, 그 뒤 `PAID` 가 되는 주문의
 * `onPaid` 가 기록을 남기지 않는다(`order.accountErasedAt`) — 순서가 거꾸로면 방금 지운 표에 줄이 되살아난다. 계정 모듈은 `orderedStream()` 으로 고리를 모으므로 순서는 `@Order` 가 정한다.
 */
class CheerErasureOrderIntegrationTest : CheerIntegrationBase() {
    @Autowired lateinit var listeners: ObjectProvider<AccountErasureListener>

    @Test
    fun `the cheer records are erased after the ledger's own erasure listener ran`() {
        val names = listeners.orderedStream().map { it.name }.toList()
        assertTrue("payment" in names && "cheers" in names, "both listeners are registered: $names")
        assertTrue(names.indexOf("payment") < names.indexOf("cheers"), "the ledger erases first, the app's cheers after it: $names")
    }
}
