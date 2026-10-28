package dev.sumin.skeleton.payment

import java.time.Instant

/**
 * 장부의 저장 포트(스펙 §4.3). 구현은 `payment-jdbc`(PostgreSQL · MySQL) — 메모리 구현은 제품 코드에 없다(재시작에 돈의 기록이 사라지면 안 된다).
 * 시험용 메모리 구현은 `testFixtures` 의 `InMemoryPaymentLedgerStore` 이고, 두 구현이 같은 약속을 지키는지는 `PaymentLedgerStoreContract` 가 잰다.
 *
 * **포트 모양이 규칙을 강제한다**: 주문 줄을 바꾸려면 [PaymentLedgerTx.lockOrder] 로 **잠그고 그 줄을 다시 읽은 값**으로 시작해야 한다.
 * 잠그지 않은 줄을 바꾸려는 [PaymentLedgerTx.updateOrder] 는 던진다. 락 밖에서 읽은 값으로 상태를 판단하면 안 된다 — [findOrder] 는 보여 주기 · 어느 결제사인지 알아내기용이다.
 */
interface PaymentLedgerStore {
    /**
     * 트랜잭션 하나를 열고 [block] 을 돈다. 정상 반환하면 커밋, 던지면 롤백하고 그 예외를 그대로 다시 던진다.
     * [PaymentTasks.enqueue] · 경보는 이 안에서 부르면 같은 트랜잭션에 참여한다(롤백되면 함께 사라진다).
     * **외부 호출(결제사)은 이 안에서 하지 않는다** — 선점(커밋) → 호출 → 확정(커밋) 세 걸음으로 나눈다.
     */
    fun <T> inTransaction(block: (PaymentLedgerTx) -> T): T

    /**
     * **지금 이 스레드에 열린 장부 트랜잭션이 있는가** — [inTransaction] 안이거나, 구현이 따르는 **바깥 트랜잭션**(앱의 `@Transactional` 등) 안이면 true.
     *
     * 이유: 결제사를 부르는 코드([PaymentOrders.confirm] · 조회 작업 처리기)는 락 · 트랜잭션 밖이어야 한다. 바깥 트랜잭션 안에서 부르면
     * 선점이 커밋되지 않은 채 결제사가 불리고, 바깥이 롤백되면 선점과 조회 작업이 함께 사라지는데 돈은 이미 나간 뒤다.
     * 그래서 그 코드들은 이것이 true 면 **프로그래밍 오류로 던진다**([requireNoOpenTransaction], [IllegalStateException]).
     *
     * 구현 메모: JDBC 구현은 Spring 의 트랜잭션 동기화로 답한다(`TransactionSynchronizationManager.isActualTransactionActive()`) —
     * 자기 [inTransaction] 이 열었든 앱이 열었든 같은 답이어야 한다. 같은 스레드만 본다.
     */
    fun hasOpenTransaction(): Boolean

    /** 잠그지 않고 커밋된 줄을 읽는다. 이 값으로 상태를 바꾸는 판단을 하지 않는다. */
    fun findOrder(id: String): PaymentOrder?

    fun findOrderByDedupeKey(dedupeKey: String): PaymentOrder?

    /** 주문의 이력, 오래된 것부터. */
    fun listEvents(orderId: String): List<PaymentEvent>

    /** 주문들의 할인 내역 한꺼번에(주문별로 `seq` 순). 잠그지 않는다 - 보여 주기용이다. 목록 줄마다 묻지 않으려고 여럿을 받는다. */
    fun listOrderDiscounts(orderIds: Collection<String>): List<OrderDiscount>

    /**
     * 이 계정이 **결제사에 돈을 낸 적이 있는가** - 쿠폰의 「첫 결제만」 판정이 쓴다. 결제사 없이 끝난 주문(쿠폰 0원 `free` · 결제 면제 `exempt`)은 「결제한 적」이 아니다.
     * 잠그지 않는다. 구독의 첫 결제도 센다.
     */
    fun hasPaidOrder(accountId: String): Boolean

    /** 잠그지 않고 커밋된 환불 줄을 읽는다 — 어느 주문의 줄인지 알아내기 · 보여 주기용. 이 값으로 상태를 바꾸는 판단을 하지 않는다. */
    fun findRefund(id: String): PaymentRefund?

    /** 같은 요청(호출자 + `Idempotency-Key`)이 앞서 만든 환불 줄(없으면 null). */
    fun findRefundByDedupeKey(dedupeKey: String): PaymentRefund?

    /** 한 주문의 환불 줄, 오래된 것부터(만든 시각, 같으면 id). */
    fun listRefunds(orderId: String): List<PaymentRefund>

    /** `(provider, provider_payment_id)` 유니크의 주인을 잠그지 않고 읽는다 — 웹훅이 가리키는 주문이 우리 것인지 알아내기용. 이 값으로 상태를 바꾸는 판단을 하지 않는다. */
    fun findOrderByProviderPayment(provider: String, providerPaymentId: String): PaymentOrder?

    /** 수신함 줄 하나를 잠그지 않고 읽는다(없으면 null). 이 값으로 줄을 바꾸는 판단을 하지 않는다 — 바꾸려면 [PaymentLedgerTx.lockWebhookEvent]. */
    fun findWebhookEvent(id: Long): PaymentWebhookEvent?

    /** `(provider, event_key)` 의 줄을 잠그지 않고 읽는다(없으면 null). 새 이벤트인지 가르는 용도 — 이 값으로 줄을 바꾸는 판단을 하지 않는다. */
    fun findWebhookEventByKey(provider: String, eventKey: String): PaymentWebhookEvent?

    /**
     * 수신함 목록, **최신순**. [status] 가 null 이면 모든 상태 — 문장 하나로 **id 가 큰 쪽이 먼저**(PK, 커서는 id): 한 번 읽은 쪽에서 같은 줄이 두 번 나오지 않고, 첫 쪽을 읽을 때 이미 커밋돼 있던 줄은 빠지지 않는다. 아직 커밋하지 않은 트랜잭션이 가진 줄(id 는 먼저 받았고 커밋이 늦은 것)은 그 시점의 쪽에서 안 보이고, 커서가 지나간 뒤에 커밋되면 이 훑기에는 끝내 안 나온다 — 새로 고치면 보인다.
     * [status] 가 있으면 받은 시각, 같으면 id 가 큰 쪽이 먼저이고 `(status, received_at, id)` 인덱스를 거꾸로 한 번 훑는다. [before] 는 이 줄 **다음**부터(그 줄은 빠진다), [limit] 줄까지.
     */
    fun listWebhookEvents(status: PaymentWebhookStatus?, before: WebhookEventCursor?, limit: Int): List<PaymentWebhookEvent>

    // --- 목록 읽기 (HTTP · B7). 모두 잠그지 않고 커밋된 줄을 읽는다 — 보여 주기용이지 상태를 바꾸는 판단에 쓰지 않는다. 커서는 "이 줄 다음부터"(그 줄은 빠진다). ---

    /**
     * 한 계정의 주문, **최신순**(만든 시각, 같으면 id 가 큰 쪽이 먼저) — 결제하지 않은 시도(`PENDING`)는 뺀다. `idx_payment_orders_account (account_id, created_at, id)` 를 거꾸로 훑는다.
     * 커서 조건은 MySQL 이 범위 접근을 쓰도록 `created_at < ? or (created_at = ? and id < ?)` 로 푼다.
     */
    fun listAccountOrders(accountId: String, before: OrderCursor?, limit: Int): List<PaymentOrder>

    /**
     * 운영자의 주문 목록, 최신순(만든 시각, id). [scope] 가 훑을 범위를 정하고 [filter] · [before] · [limit] 이 **모든 범위에서 같은 자리(조회 안)에서** 적용된다 —
     * 환불 줄을 통해 찾는 `STALE_REFUND` · `UNAPPLIED_REFUND` 도 마찬가지다(그 줄이 아무리 많아도 가장 오래된 것이 잘려 나가지 않는다).
     * [threshold] 는 `STALE_CONFIRMING`(`confirm_started_at < threshold`) · `UNFULFILLED`(`paid_at < threshold` 이거나 비어 있음) · `STALE_REFUND`(환불 줄 `sent_at < threshold`) ·
     * `UNAPPLIED_REFUND`(환불 줄 `completed_at < threshold` 이거나 비어 있음)에서만 쓴다(다른 범위에서는 무시).
     */
    fun listOrdersForOperator(scope: OperatorOrderScope, filter: OperatorOrderFilter, threshold: Instant?, before: OrderCursor?, limit: Int): List<PaymentOrder>

    /** `provider in (providers)` 와 결제사 결제 번호가 같은 주문 — `(provider, provider_payment_id)` 유니크 인덱스를 탄다(결제사 조건 없이 번호만 찾으면 못 탄다). */
    fun findOrdersByProviderPayment(providers: Collection<String>, providerPaymentId: String): List<PaymentOrder>

    /** id 로 모은 주문, 최신순. 목록이 가리킨 주문(예: 샘플 앱의 응원 기록이 가리키는 주문)을 한 번에 읽는 데 쓴다. */
    fun listOrdersByIds(ids: Collection<String>): List<PaymentOrder>

    /** 여러 주문의 환불 줄 한꺼번에 — 목록 줄마다 묻지 않으려고. 주문별로 오래된 순(만든 시각, 같으면 id)이 되도록 정렬해 돌려준다. */
    fun listRefundsOfOrders(orderIds: Collection<String>): List<PaymentRefund>

    /** 환불 줄 목록, **최신순**(요청한 시각, 같으면 id) — `idx_payment_refunds_status (status, requested_at, id)` 를 거꾸로 훑는다. */
    fun listRefundsByStatus(status: PaymentRefundStatus, before: RefundCursor?, limit: Int): List<PaymentRefund>

    // --- 구독 (스펙 `2026-10-09-subscription-design.md` §4). 모두 잠그지 않고 커밋된 줄을 읽는다 — 상태를 바꾸는 판단은 [PaymentLedgerTx.lockSubscription] 으로. ---

    fun findSubscription(id: String): PaymentSubscription?

    /** 요청 키([PaymentSubscription.dedupeKey])로 구독을 찾는다 — 같은 요청의 재전송이 앞선 줄을 만난다. */
    fun findSubscriptionByDedupeKey(dedupeKey: String): PaymentSubscription?

    /** 결제사 쪽 구독 id([PaymentSubscription.providerSubscriptionId])로 찾는다. */
    fun findSubscriptionByProviderId(provider: String, providerSubscriptionId: String): PaymentSubscription?

    /** 서버가 미리 만든 첫 결제 거래 id([PaymentSubscription.firstTransactionId])로 찾는다. */
    fun findSubscriptionByFirstTransaction(provider: String, transactionId: String): PaymentSubscription?

    /** 구독에 붙은 할인(없으면 null) - 잠그지 않는다. 보여 주기용이다. */
    fun findSubscriptionDiscount(subscriptionId: String): SubscriptionDiscount?

    /** 계정의 **살아 있는** 구독(`active_key` 의 주인) — 없으면 null. */
    fun findLiveSubscription(accountId: String): PaymentSubscription?

    /** 계정의 가장 최근 구독 — 카드 등록 · 첫 결제를 시작하지 않은 `PENDING`(만들기만 한 줄)은 뺀다. 「내 구독」이 끝난 구독도 보여 주려고(실패 · 종료의 유일한 통지). */
    fun findLatestSubscription(accountId: String): PaymentSubscription?

    /** 한 구독이 만든 주문, 최신순(`origin_type = SUBSCRIPTION`, `origin_ref` = 구독 id) — 결제하지 않은 시도(`PENDING`)는 뺀다. */
    fun listSubscriptionOrders(subscriptionId: String, limit: Int): List<PaymentOrder>

    /** 그 계정의 구독 id, **오름차순** — 탈퇴가 하나씩 잠가 닫는다. */
    fun listSubscriptionIdsOfAccount(accountId: String): List<String>

    // --- 계정 탈퇴 (벌크 · 스펙 §8.2). 주문 여럿을 건드리므로 "한 블록 한 주문" 규칙 밖에 있는 한 문장짜리 쓰기다. 둘 다 **멱등**이다. ---

    /** 그 계정의 주문 id, **오름차순** — 한 계정의 모든 주문을 한 문장으로 못 바꾸는 길(이력 · 환불 줄의 계정 id 바꿔 쓰기)이 조각을 나눠 쓴다. 주문을 잠그는 길은 이것을 쓰지 않는다(미결제 주문 닫기는 목록 조회로 한 쪽씩). */
    fun listOrderIdsOfAccount(accountId: String): List<String>

    /**
     * 그 계정의 **모든** 주문 줄에서 `card_issuer` · `card_last4` 를 NULL 로 지우고 `account_erased_at` 을 [erasedAt] 으로 찍는다(이미 찍혀 있으면 그 시각을 지키고 건드리지 않는다).
     * 한 문장 update 다 — 이미 지워진 줄은 바꾸지 않으므로(`updated_at` 도) 두 번째 호출은 0 을 돌려준다. 바뀐 줄 수.
     */
    fun eraseAccountOrders(accountId: String, erasedAt: Instant): Int

    /**
     * (`erasure.account-id=TOMBSTONE`) **그 계정이 산 주문의 줄에서만** 계정 id 를 [replacement] 로 바꾼다: 주문의 `account_id`, 그 주문의 환불 줄 `requested_by` · `decided_by`,
     * 그 주문의 이력 `actor_id` 가운데 값이 그 계정 id 인 것. 운영자로서 **남의 주문**에 남긴 값은 그대로 남는다(누가 환불을 결정했는가의 기록). 주문 줄은 맨 마지막에 바꾼다 —
     * 도중에 끊겨도 다시 부르면 이어진다(주문이 아직 옛 id 를 가리키니 대상을 다시 찾는다). 바뀐 줄 수.
     */
    fun replaceAccountReferences(accountId: String, replacement: String): Int
}

/** [PaymentLedgerStore.inTransaction] 안에서만 쓴다. 한 스레드가 쓴다. */
interface PaymentLedgerTx {
    /**
     * 주문 줄을 `select … for update` 로 잡고 **지금의 값**을 돌려준다(없으면 null). 다른 트랜잭션이 잡고 있으면 그 트랜잭션이 끝날 때까지 기다린다.
     * 잠금은 이 트랜잭션이 끝날 때까지 간다. 같은 줄을 다시 잠그는 것은 그냥 지금 값을 돌려준다.
     * 한 트랜잭션에서 잠그는 주문은 하나다(락 순서가 늘 같아 교착이 없다 — 환불이 더해져도 주문 → 환불 순서).
     * **수신함 줄을 이미 잡은 트랜잭션은 주문을 잠그지 못한다**([IllegalStateException]) — 락 순서는 늘 주문 → 환불 · 수신함 줄이다.
     */
    fun lockOrder(id: String): PaymentOrder?

    /** 이 트랜잭션에서 보이는 줄(커밋된 것 + 내 쓰기). 잠그지 않는다. */
    fun findOrderByDedupeKey(dedupeKey: String): PaymentOrder?

    /** `(provider, provider_payment_id)` 유니크의 주인. 잠그지 않는다. */
    fun findOrderByProviderPayment(provider: String, providerPaymentId: String): PaymentOrder?

    /**
     * 새 줄을 넣는다. **`dedupeKey` 가 이미 있으면 넣지 않고 false** — 동시에 같은 키로 넣는 쪽은 앞선 트랜잭션이 끝날 때까지 기다렸다가 false 를 받는다.
     * (PostgreSQL 에서 유니크 위반은 트랜잭션을 못 쓰게 만들므로 예외 대신 `on conflict do nothing` 형태로 알린다.) 넣은 줄은 이 트랜잭션이 잠근 것으로 본다.
     */
    fun insertOrderIfAbsent(order: PaymentOrder): Boolean

    /**
     * 줄 전체를 바꾼다. 이 트랜잭션이 [lockOrder] 로 잠갔거나 [insertOrderIfAbsent] 로 넣은 줄만 — 아니면 [IllegalStateException].
     * `(provider, provider_payment_id)` 가 다른 줄과 겹치면 [PaymentLedgerConflictException].
     */
    fun updateOrder(order: PaymentOrder)

    fun appendEvent(event: PaymentEvent)

    /** 이 트랜잭션에서 보이는 한 주문의 할인 내역(`seq` 순). 잠그지 않는다. */
    fun listOrderDiscounts(orderId: String): List<OrderDiscount>

    /** 할인 내역 줄을 넣는다. 이 트랜잭션이 [lockOrder] 로 잠갔거나 [insertOrderIfAbsent] 로 넣은 주문의 줄만 - 아니면 [IllegalStateException]. 같은 `(orderId, seq)` 는 던진다(버그). */
    fun insertOrderDiscount(line: OrderDiscount)

    /** 할인 내역 줄의 상태만 바꾼다(주문을 잠갔어야 한다). 줄이 없으면 [IllegalStateException]. */
    fun updateOrderDiscountStatus(orderId: String, seq: Int, status: OrderDiscountStatus, at: Instant)

    /**
     * 환불 줄을 읽는다(없으면 null). **그 줄의 주문을 이 트랜잭션이 이미 잠갔어야 한다**(아니면 [IllegalStateException]) — 락 순서는 늘 주문 → 환불이다.
     * 환불 줄 자체는 따로 잠그지 않는다: 환불 줄을 바꾸는 모든 길이 먼저 주문 줄을 잡으므로 주문 줄 락이 곧 환불 줄 락이다. 낡은 값을 읽지 않도록 지금 커밋된 값을 읽는다.
     */
    fun lockRefund(id: String): PaymentRefund?

    /** 이 트랜잭션에서 보이는 줄(커밋된 것 + 내 쓰기). 잠그지 않는다. */
    fun findRefundByDedupeKey(dedupeKey: String): PaymentRefund?

    /** 이 트랜잭션에서 보이는 한 주문의 환불 줄, 오래된 것부터. 그 주문을 잠갔어야 한다. */
    fun listRefunds(orderId: String): List<PaymentRefund>

    /**
     * 새 환불 줄을 넣는다. **그 줄의 주문을 이 트랜잭션이 잠갔어야 한다**(아니면 [IllegalStateException]).
     * 유니크가 하나라도 겹치면 넣지 않고 false: `dedupe_key`(같은 요청) · `open_key`(주문당 열린 환불 하나) · `(order_id, provider_refund_id)`(결제사의 취소 하나를 두 번 못 넣는다) · id.
     * (PostgreSQL 에서 유니크 위반은 트랜잭션을 못 쓰게 만들므로 예외 대신 "충돌하면 아무것도 하지 않는" 문장으로 알린다.)
     */
    fun insertRefundIfAbsent(refund: PaymentRefund): Boolean

    /**
     * 환불 줄 전체를 바꾼다(`id` · `order_id` 는 그대로). 주문을 잠갔어야 하고 줄이 있어야 한다(아니면 [IllegalStateException]).
     * `open_key` 나 `(order_id, provider_refund_id)` 가 다른 줄과 겹치면 [PaymentLedgerConflictException] — PostgreSQL 에서는 이 트랜잭션을 더 쓸 수 없으니 던져서 되돌린다.
     */
    fun updateRefund(refund: PaymentRefund)

    /**
     * 웹훅 전달 한 번을 센다: `(provider, event_key)` 줄이 없으면 [candidate] 로 넣고(`id` · `deliveries` 는 저장소가 정한다), 있으면 그대로 두고 — 어느 쪽이든 `deliveries` 를 하나 올려 **센 뒤의 줄**을 돌려준다.
     * 줄을 만든 전달만 [RecordedWebhookDelivery.first] 가 true 다(같은 이벤트가 동시에 열여섯 번 도착해도 하나). 이 트랜잭션이 그 줄을 잡은 채다 — [updateWebhookEvent] 로 바로 상태를 바꿀 수 있다.
     * (유니크 위반을 예외로 받지 않는다 — PostgreSQL 은 그러면 트랜잭션을 못 쓰게 된다. 충돌하면 아무것도 하지 않는 문장으로 넣고 올린다.)
     * 한 트랜잭션에서 잡는 수신함 줄은 하나다([IllegalStateException]).
     */
    fun recordWebhookDelivery(candidate: PaymentWebhookEvent): RecordedWebhookDelivery

    /**
     * 이 주문의 수신함 줄 가운데 [resourceType] 이고 [since] **이후에**(그 시각 포함) 받은 것의 수 — 모든 상태 · 모든 `resource_id`(주문이 결제 키를 얻기 전후의 줄을 한 주문으로 센다).
     * 주문 단위 창 상한을 세는 데 쓴다. **그 주문을 이 트랜잭션이 잠갔어야 한다**(아니면 [IllegalStateException]) — 새 줄을 넣는 길은 모두 주문 줄 락 뒤이므로,
     * 잠근 채 세고 넣으면 세기와 넣기 사이에 다른 트랜잭션이 끼어들지 못한다(인스턴스가 여럿이어도 정확히 상한). 구현은 `(order_id, received_at)` 인덱스를 타야 한다.
     */
    fun countWebhookEventsOfOrder(orderId: String, resourceType: String, since: Instant): Int

    /** 수신함 줄을 `select … for update` 로 잡고 지금의 값을 돌려준다(없으면 null). 한 트랜잭션에서 잡는 수신함 줄은 하나다. 주문을 잠그려면 **그 전에**(주문 → 수신함 줄). */
    fun lockWebhookEvent(id: Long): PaymentWebhookEvent?

    /** 이 트랜잭션이 [recordWebhookDelivery] 로 넣었거나 [lockWebhookEvent] 로 잡은 줄만 — 아니면 [IllegalStateException]. 바뀌는 것은 상태 · `last_error` · `processed_at` 뿐이다(나머지는 첫 전달의 값). */
    fun updateWebhookEvent(event: PaymentWebhookEvent)

    // --- 구독. 락 순서는 **주문 → 구독**: 주문 줄을 잠근 채 구독을 잠글 수 있고(지급 고리), 구독을 잠근 채 주문을 잠그지는 않는다(새 주문을 **넣을** 수는 있다). 한 트랜잭션에서 잠그는 구독은 하나다. ---

    /** 구독 줄을 `select … for update` 로 잡고 지금의 값을 돌려준다(없으면 null). 잠금은 이 트랜잭션이 끝날 때까지 간다. */
    fun lockSubscription(id: String): PaymentSubscription?

    /** 새 구독 줄을 넣는다. 같은 id 나 같은 [PaymentSubscription.dedupeKey] 가 이미 있으면 넣지 않고 false(같은 요청의 재전송). 넣은 줄은 이 트랜잭션이 잠근 것으로 본다. */
    fun insertSubscriptionIfAbsent(subscription: PaymentSubscription): Boolean

    /** 이 트랜잭션에서 보이는 구독의 할인(없으면 null) - 구독을 잠근(또는 넣은) 트랜잭션에서 읽는다. */
    fun subscriptionDiscount(subscriptionId: String): SubscriptionDiscount?

    /** 구독 할인을 넣는다(단계 복사 포함). 이 트랜잭션이 잠갔거나 넣은 구독의 것만 - 아니면 [IllegalStateException]. 구독마다 하나. */
    fun insertSubscriptionDiscount(discount: SubscriptionDiscount)

    /** 구독 할인의 [SubscriptionDiscount.periodsUsed] · [SubscriptionDiscount.status] 만 바꾼다(단계 · 수단은 시작 때 박힌 그대로). 이 트랜잭션이 잠근 구독의 것만. */
    fun updateSubscriptionDiscount(discount: SubscriptionDiscount)

    /**
     * 구독 줄 전체를 바꾼다. 이 트랜잭션이 [lockSubscription] 으로 잠갔거나 [insertSubscriptionIfAbsent] 로 넣은 줄만 — 아니면 [IllegalStateException].
     * `active_key` 가 다른 줄과 겹치면 [PaymentLedgerConflictException](계정당 살아 있는 구독 하나) — PostgreSQL 에서는 이 트랜잭션을 더 쓸 수 없으니 던져서 되돌린다.
     */
    fun updateSubscription(subscription: PaymentSubscription)
}

/** 유니크 제약이 막았다(예: 한 결제 키가 두 주문에 붙으려 했다). 저장소가 던진다 — 서비스는 "결제할 수 없는 주문"으로 읽는다. */
class PaymentLedgerConflictException(message: String) : RuntimeException(message)

/** 결제사를 부르는 코드의 입구 검사 — 열린 장부 트랜잭션 안이면 던진다(프로그래밍 오류). [what] 은 호출한 쪽 이름. */
internal fun PaymentLedgerStore.requireNoOpenTransaction(what: String) {
    check(!hasOpenTransaction()) {
        "$what calls the payment provider, so it must not run inside a ledger transaction (an outer @Transactional included): " +
            "a rollback of the outer transaction would undo the claim while the money has already moved"
    }
}
