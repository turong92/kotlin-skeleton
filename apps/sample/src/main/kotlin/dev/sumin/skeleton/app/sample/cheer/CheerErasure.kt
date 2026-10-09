package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 계정이 지워질 때(삭제 유예가 끝난 뒤) 그 계정의 **응원 기록을 지운다**. 결제 장부의 주문 줄은 남는다 — 대금결제 기록의 보존 의무(스펙 §8)이고, 장부 모듈의 탈퇴 고리가
 * 카드 두 칸을 지운다. 응원 기록은 이 앱의 표이므로 이 앱이 지운다. 멱등.
 *
 * **순서: 장부의 탈퇴 고리(`PaymentAccountErasure.ORDER`)보다 뒤.** 장부 고리가 먼저 주문 줄에 `account_erased_at` 을 찍어야, 그 뒤 `PAID` 가 되는 주문의 `onPaid` 가 기록을 남기지 않는다
 * ([CheerOrderListener]). 순서가 거꾸로면 그 사이 `PAID` 가 된 주문이 방금 지운 표에 줄을 되살린다.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Component
class CheerErasure(private val cheers: CheerRepository) : AccountErasureListener {
    override val name: String = "cheers"

    override fun erase(request: ErasureRequest) {
        cheers.deleteByAccount(request.accountId)
    }
}
