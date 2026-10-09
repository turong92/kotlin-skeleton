package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.erasure.AccountDataExporter
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.payment.web.OrderDetailResponse
import org.springframework.core.Ordered

/**
 * 계정이 지워질 때(스펙 §8.2) — **주문 줄은 남는다**(대금결제 기록의 보존 의무). 지우는 것은 개인정보로 볼 수 있는 칸 둘(`card_issuer` · `card_last4`)뿐이다.
 *  1. 결제하지 않은 `PENDING` 주문은 `CANCELED` 로 닫는다(결제할 사람이 없다). 주문마다 잠가서 id 오름차순으로. `CONFIRMING` 은 건드리지 않는다 — 조회 작업이 끝낸다.
 *  2. 그 계정의 **모든** 주문 줄에서 카드 두 칸을 NULL 로, `account_erased_at` 을 찍는다(한 문장). 이 시각이 찍힌 줄은 나중에 `PAID` 가 돼도 카드 두 칸을 다시 쓰지 않는다([PaymentConvergence]).
 *  3. `erasure.account-id=TOMBSTONE` 이면 **그 계정이 산 주문의 줄에서만** 계정 id 를 바꾼다. 운영자로서 남의 주문에 남긴 id 는 남는다. 기본 `KEEP` 은 바꾸지 않는다.
 * 열린 환불 요청(`REQUESTED`)은 남는다 — 운영자가 처리할 수 있다. **멱등**이다: 두 번째 호출은 아무것도 바꾸지 않는다. 계정 모듈의 바깥 트랜잭션 안에서 불려도 된다(저장소 블록이 합류한다).
 * 이 모듈은 `account` 모듈을 모른다 — 플랫폼의 [AccountErasureListener] 만 구현한다.
 * **순서**: 계정 모듈은 고리를 `@Order` / [Ordered] 순으로 부른다. 이 고리는 [ORDER] (= 0)로 **앞에** 선다 — 순서를 말하지 않은 고리(기본 가장 뒤)는 모두 이 고리 뒤에 돈다.
 * 앱이 자기 표를 지우는 고리는 이 뒤여야 한다: 이 고리가 주문 줄에 `account_erased_at` 을 먼저 찍어야, 그 뒤 `PAID` 가 되는 주문의 훅이 방금 지운 표에 줄을 남기지 않는다(`PaymentOrder.accountErasedAt` 을 본다).
 */
class PaymentAccountErasure(
    private val store: PaymentLedgerStore,
    private val writer: PaymentLedgerWriter,
    private val mode: PaymentProperties.Erasure.AccountIdMode,
    /** 구독을 켠 조립에서만 — 그 계정의 구독을 닫는다. */
    private val subscriptions: SubscriptionLifecycle? = null,
) : AccountErasureListener, Ordered {
    override val name: String = "payment"

    override fun getOrder(): Int = ORDER

    override fun erase(request: ErasureRequest) {
        closeUnpaid(request.accountId)
        closeSubscriptions(request.accountId)
        store.eraseAccountOrders(request.accountId, writer.now())
        if (mode == PaymentProperties.Erasure.AccountIdMode.TOMBSTONE) store.replaceAccountReferences(request.accountId, request.tombstone)
    }

    private fun closeUnpaid(accountId: String) {
        var cursor: OrderCursor? = null
        while (true) {
            val page = store.listOrdersForOperator(OperatorOrderScope.ALL, OperatorOrderFilter(status = PaymentOrderStatus.PENDING, accountId = accountId), null, cursor, PAGE)
            page.map { it.id }.sorted().forEach(::close)
            if (page.size < PAGE) return
            cursor = page.last().let { OrderCursor(it.createdAt, it.id) }
        }
    }

    /** 그 계정의 구독을 하나씩 잠가 끝낸다(빌링키 삭제 작업이 따라 들어간다) — 이미 끝난 구독은 지운 시각만 찍는다. 멱등. 구독이 없는 조립에서는 구독 줄이 없으니 아무것도 하지 않는다. */
    private fun closeSubscriptions(accountId: String) {
        val lifecycle = subscriptions ?: return
        store.listSubscriptionIdsOfAccount(accountId).forEach { id ->
            store.inTransaction { tx ->
                val subscription = tx.lockSubscription(id) ?: return@inTransaction
                if (subscription.status != SubscriptionStatus.ENDED) {
                    lifecycle.end(tx, subscription, SubscriptionEndReason.ACCOUNT_ERASED)
                } else if (subscription.accountErasedAt == null) {
                    tx.updateSubscription(subscription.copy(accountErasedAt = writer.now(), updatedAt = writer.now()))
                }
            }
        }
    }

    private fun close(orderId: String) {
        store.inTransaction { tx ->
            val order = tx.lockOrder(orderId)?.takeIf { it.status == PaymentOrderStatus.PENDING } ?: return@inTransaction
            writer.transition(tx, order, PaymentOrderStatus.CANCELED, PaymentEventType.CANCELED, PaymentActor.SYSTEM) { it.copy(closedAt = writer.now()) }
        }
    }

    companion object {
        /** 이 고리의 순서 — 순서를 말하지 않은 고리([Ordered.LOWEST_PRECEDENCE])보다 앞이다. */
        const val ORDER: Int = 0
        private const val PAGE = 200
    }
}

/** 내보내기 고리 — 구역 `payments`: 내 결제와 환불 줄을 사용자 응답과 같은 모양으로(카드 표시 포함 — 지워지기 전의 본인 데이터다). 결제하지 않은 시도는 빠진다. */
class PaymentAccountDataExporter(private val queries: PaymentQueries) : AccountDataExporter {
    override val section: String = "payments"

    override fun export(accountId: String): Map<String, Any?> {
        val orders = mutableListOf<OrderDetailResponse>()
        var cursor: OrderCursor? = null
        do {
            val page = queries.myOrders(accountId, cursor, PaymentQueries.MAX_PAGE)
            orders += page.values.map(OrderDetailResponse::of)
            cursor = page.next
        } while (cursor != null)
        return mapOf("orders" to orders)
    }
}
