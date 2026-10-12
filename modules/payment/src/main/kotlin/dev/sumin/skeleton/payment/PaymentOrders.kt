package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.id.SortableIds
import org.slf4j.LoggerFactory

data class CreateOrderCommand(
    val accountId: String,
    val itemType: String,
    val itemRef: String,
    /** 같은 키로 다시 부르면 같은 주문이다(`Idempotency-Key` 헤더 값). */
    val idempotencyKey: IdempotencyKey,
    val quantity: Int = 1,
    val country: String? = null,
)

/**
 * [checkout] 은 결제창에 넘길 값 — 주문이 `PENDING` 이 아니거나 결제 가능 시간이 지났으면(같은 키로 다시 불렀는데 이미 끝났다 · 만료 작업이 아직 안 돌았다) null.
 * [refunds] 는 그 주문의 환불 줄 — 새 주문은 비었고, 같은 키의 재전송은 이미 환불이 열린 주문일 수 있어 응답의 요약(`refundPending` · `refundableAmount`)이 이것으로 계산된다.
 */
data class CreatedOrder(
    val order: PaymentOrder,
    val checkout: CheckoutInstruction?,
    val replayed: Boolean,
    val refunds: List<PaymentRefund> = emptyList(),
)

/**
 * 주문 만들기 · 승인(선점 → 호출 → 확정) · 그만둠 · 읽기(스펙 §6.2). 모든 전이는 주문 줄을 잠그고 **상태를 다시 읽은 뒤** 판단하며,
 * 결제사 호출은 락 · 트랜잭션 밖이다. 남의 주문과 없는 주문은 같은 [PaymentOrderNotFoundException] 이다.
 *
 * @param catalog 없으면 주문을 만들 수 없다([PaymentUnavailableException]) — 나머지는 그대로 쓴다.
 */
class PaymentOrders(
    private val store: PaymentLedgerStore,
    private val tasks: PaymentTasks,
    private val router: PaymentProviderRouter,
    private val catalog: PaymentCatalog?,
    private val convergence: PaymentConvergence,
    private val fulfillment: PaymentFulfillment,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    private val ids: SortableIds = SortableIds.system,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(command: CreateOrderCommand): CreatedOrder {
        val catalog = catalog ?: throw PaymentUnavailableException()
        validate(command)
        val dedupeKey = PaymentOrderIds.dedupeKey(command.accountId, command.idempotencyKey)
        store.findOrderByDedupeKey(dedupeKey)?.let { return replay(it, command) }

        val quote = catalog.quote(PurchaseRequest(command.accountId, command.itemType, command.itemRef, command.quantity, command.country))
        if (quote.amount.amount <= 0) throw PaymentItemNotPurchasableException()
        check(quote.orderName.isNotBlank()) { "The payment catalog returned a blank order name" }
        val checkout = findOpenableCheckout(quote.amount, command.country, warn = true) ?: throw PaymentUnavailableException()

        val now = writer.now()
        val order = PaymentOrder(
            id = ids.newId(PaymentOrderIds.ORDER_PREFIX),
            accountId = command.accountId,
            kind = PaymentOrderKind.ONE_TIME,
            status = PaymentOrderStatus.PENDING,
            provider = checkout.providerId.trim().lowercase(),
            providerMode = checkout.mode,
            providerPaymentId = null,
            amount = quote.amount,
            paidAmount = null,
            taxAmount = null,
            refundedAmount = 0,
            itemType = command.itemType,
            itemRef = command.itemRef,
            quantity = command.quantity,
            orderName = quote.orderName.trim().take(ORDER_NAME_MAX),
            originType = PaymentOriginType.CHECKOUT,
            originRef = null,
            dedupeKey = dedupeKey,
            method = null,
            cardIssuer = null,
            cardLast4 = null,
            failureCode = null,
            reviewReason = null,
            checkoutExpiresAt = now.plus(properties.checkoutTtl),
            confirmStartedAt = null,
            paidAt = null,
            fulfilledAt = null,
            closedAt = null,
            accountErasedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = store.inTransaction { tx ->
            if (!tx.insertOrderIfAbsent(order)) return@inTransaction false
            tx.appendEvent(writer.event(order.id, PaymentEventType.ORDER_CREATED, PaymentActor.USER, command.accountId, to = order.status))
            tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_EXPIRE, order.id, runAt = order.checkoutExpiresAt, maxAttempts = properties.reconcile.maxAttempts))
            true
        }
        if (!inserted) {
            // 동시에 같은 키로 들어온 다른 요청이 먼저 넣었다 — 그 줄이 곧 답이다
            val winner = checkNotNull(store.findOrderByDedupeKey(dedupeKey)) { "the order for this idempotency key disappeared" }
            return replay(winner, command)
        }
        return CreatedOrder(order, checkout.checkout(CheckoutOrder(order.id, order.orderName, order.amount)), replayed = false)
    }

    /**
     * 결제창에서 돌아온 결제를 승인한다. **선점(커밋) → 호출(락 밖) → 확정(커밋)**. 돌려주는 줄이 `PAID` 면 끝났고 `CONFIRMING` 이면 확인 중이다(HTTP 202).
     * 금액은 비교만 한다 — 결제사에는 줄의 금액이 간다. 확정 거절이면 줄을 `FAILED` 로 닫고 [PaymentDeclinedException].
     *
     * **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException], 프로그래밍 오류) — 선점이 커밋되지 않은 채 결제사가 불리고, 바깥이 롤백되면 선점과
     * 조회 작업이 사라지는데 돈은 이미 나간다([PaymentLedgerStore.hasOpenTransaction]). 앱은 `@Transactional` 밖에서 부른다.
     */
    fun confirm(accountId: String, orderId: String, providerPaymentId: String, amount: PaymentAmount): PaymentOrder {
        store.requireNoOpenTransaction("PaymentOrders.confirm")
        if (providerPaymentId.isBlank() || providerPaymentId.length > PAYMENT_ID_MAX) {
            throw FieldValidationException("providerPaymentId", "Size", "providerPaymentId must be 1 to $PAYMENT_ID_MAX characters")
        }
        val claim = claim(accountId, orderId, providerPaymentId, amount)
        val gateway = claim.gateway ?: return claim.order
        val order = claim.order
        val request = PaymentConfirmRequest(
            providerPaymentId = providerPaymentId,
            amount = order.amount, // 요청의 금액이 아니라 줄의 금액
            merchantReferenceId = order.id,
            provider = order.provider,
            idempotencyKey = IdempotencyKey(order.id), // 같은 주문의 재전송이 생겨도 결제사가 한 번으로 본다
        )
        val context = ConvergeContext(gateway.mode, ConvergeSource.CONFIRM, PaymentActor.USER, accountId)
        val result = try {
            gateway.confirm(request)
        } catch (e: Exception) {
            // 호출 자체의 실패만 여기서 가른다 — 확정(저장) 쪽 버그를 "모름"으로 삼키지 않으려고 settle 은 try 밖이다
            val after = when (PaymentFailures.outcomeOf(e)) {
                PaymentFailureOutcome.DEFINITIVE -> decline(order, gateway, PaymentFailures.providerCodeOf(e), context)
                PaymentFailureOutcome.UNKNOWN -> unknown(order, e, context)
            }
            return after
        }
        val settled = settle(order, providerPaymentId, result, context)
        if (settled.status.isPaidFamily) fulfillment.tryFulfil(settled.id)
        return store.findOrder(settled.id) ?: settled
    }

    /** 결제창에서 그만뒀다 — `PENDING` 일 때만 `CANCELED`. 이미 끝난 주문이면 지금 상태 그대로(오류가 아니다). */
    fun abandon(accountId: String, orderId: String): PaymentOrder =
        store.inTransaction { tx ->
            val order = ownedOrNull(tx.lockOrder(orderId), accountId) ?: throw PaymentOrderNotFoundException()
            if (order.status != PaymentOrderStatus.PENDING) return@inTransaction order
            writer.transition(tx, order, PaymentOrderStatus.CANCELED, PaymentEventType.CANCELED, PaymentActor.USER, accountId) {
                it.copy(closedAt = writer.now())
            }
        }

    fun get(accountId: String, orderId: String): PaymentOrder =
        ownedOrNull(store.findOrder(orderId), accountId) ?: throw PaymentOrderNotFoundException()

    private class Claim(val order: PaymentOrder, val gateway: PaymentConfirmGateway?)

    /** ① 선점. 락 안에서 모든 조건을 다시 보고, 통과하면 `CONFIRMING` + 결제 키 + 조회 작업을 한 트랜잭션에 커밋한다. */
    private fun claim(accountId: String, orderId: String, providerPaymentId: String, amount: PaymentAmount): Claim =
        store.inTransaction { tx ->
            val order = ownedOrNull(tx.lockOrder(orderId), accountId) ?: throw PaymentOrderNotFoundException()
            when {
                order.status.isPaidFamily ->
                    if (order.providerPaymentId == providerPaymentId) return@inTransaction Claim(order, null) else throw PaymentOrderNotPayableException()
                order.status == PaymentOrderStatus.CONFIRMING -> return@inTransaction Claim(order, null) // 이미 결제사를 불렀거나 부르는 중 — 다시 부르지 않는다
                order.status != PaymentOrderStatus.PENDING -> throw PaymentOrderNotPayableException() // FAILED · EXPIRED · CANCELED 는 클라이언트 호출로 열리지 않는다
            }
            val now = writer.now()
            if (now.isAfter(order.checkoutExpiresAt)) throw PaymentOrderNotPayableException()
            if (!amount.sameAs(order.amount)) throw PaymentAmountMismatchException() // 결제사를 부르지 않는다
            val gateway = router.gateway(order.provider, PaymentConfirmGateway::class)
            if (gateway.mode != order.providerMode) throw PaymentModeMismatchException()
            // 모양이 틀린 키는 선점 전에 거절한다 — 선점된 뒤에 결제사 호출이 거절하면 주문이 CONFIRMING 에 갇히고 조회 작업이 같은 키로 끝까지 실패한다
            if (!gateway.acceptsPaymentKey(providerPaymentId)) {
                throw FieldValidationException("providerPaymentId", "Pattern", "providerPaymentId is not in a form the payment provider accepts")
            }
            val owner = tx.findOrderByProviderPayment(order.provider, providerPaymentId)
            if (owner != null && owner.id != order.id) throw PaymentOrderNotPayableException() // 한 결제 키가 두 주문에 붙지 못한다

            val claimed = try {
                writer.transition(tx, order, PaymentOrderStatus.CONFIRMING, PaymentEventType.CONFIRM_STARTED, PaymentActor.USER, accountId) {
                    it.copy(providerPaymentId = providerPaymentId, confirmStartedAt = now)
                }
            } catch (e: PaymentLedgerConflictException) {
                throw PaymentOrderNotPayableException()
            }
            tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_RECONCILE, order.id, runAt = now.plus(properties.reconcile.firstDelay), maxAttempts = properties.reconcile.maxAttempts))
            Claim(claimed, gateway)
        }

    /** ③ 확정 — 승인 응답이 있을 때. 응답에서 만든 스냅샷으로 [PaymentConvergence.converge] 한다(응답을 해석할 수 없으면 모름). */
    private fun settle(order: PaymentOrder, providerPaymentId: String, result: PaymentOperationResult, context: ConvergeContext): PaymentOrder {
        val snapshot = snapshotOf(order, providerPaymentId, result) ?: return unknown(order, null, context)
        return store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            if (locked.status != PaymentOrderStatus.CONFIRMING) return@inTransaction locked // 그 사이 조회 작업이 먼저 끝냈다
            convergence.converge(tx, locked, snapshot, context).order
        }
    }

    private fun decline(order: PaymentOrder, gateway: PaymentConfirmGateway, providerCode: String?, context: ConvergeContext): PaymentOrder {
        val closed = store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            convergence.declined(tx, locked, providerCode, context).order
        }
        if (closed.status == PaymentOrderStatus.FAILED) throw PaymentDeclinedException(closed.failureCode, gateway.confirmDeclineCategory(closed.failureCode))
        return closed // 그 사이 다른 길이 먼저 끝냈다 — 늦은 거절은 받은 돈을 되돌리지 못한다
    }

    /** 모름 — 줄의 상태를 바꾸지 않는다(이력 한 줄만). 조회 작업이 결제사가 끝 상태를 말할 때까지 묻는다. */
    private fun unknown(order: PaymentOrder, cause: Exception?, context: ConvergeContext): PaymentOrder {
        log.warn("payment confirm outcome unknown order={} exception={}", order.id, cause?.javaClass?.simpleName ?: "unreadable response")
        store.inTransaction { tx ->
            tx.appendEvent(writer.event(order.id, PaymentEventType.CONFIRM_UNKNOWN, context.actor, context.actorId, from = order.status, to = order.status))
        }
        return store.findOrder(order.id) ?: order
    }

    /**
     * 승인 응답 → 스냅샷. **승인(`CONFIRMED`)이고 같은 결제 키이며 총액이 있을 때만** 만든다 — 그 밖의 응답은 해석하지 않고 모름으로 둔다.
     * 응답이 카드 · 승인 시각을 싣고 있으면 그것을 쓰고 더 묻지 않는다. 둘 다 없으면 결제사에 한 번 조회해 채운다(실패해도 승인 결과에는 영향이 없다 — 총액 · 상태는 응답의 것을 쓴다).
     */
    private fun snapshotOf(order: PaymentOrder, providerPaymentId: String, result: PaymentOperationResult): PaymentSnapshot? {
        if (result.status != PaymentOperationStatus.CONFIRMED || result.providerPaymentId != providerPaymentId) return null
        val total = result.amount ?: return null
        val fromResponse = PaymentSnapshot(
            providerPaymentId = providerPaymentId,
            orderId = result.trace.metadata["orderId"],
            status = ProviderPaymentStatus.PAID,
            total = total,
            remaining = result.remaining ?: total,
            method = result.attributes["method"],
            paidAt = result.paidAt,
            refunds = emptyList(),
            card = result.card,
        )
        if (result.card != null || result.paidAt != null) return fromResponse
        val looked = try {
            router.gateway(order.provider, PaymentLookupGateway::class).lookup(PaymentLookupQuery(providerPaymentId, null))
        } catch (e: Exception) {
            log.debug("could not enrich the confirm response order={} exception={}", order.id, e.javaClass.simpleName)
            null
        }
        if (looked == null || looked.providerPaymentId != providerPaymentId || (looked.orderId != null && looked.orderId != order.id)) return fromResponse
        return fromResponse.copy(card = looked.card, method = fromResponse.method ?: looked.method, paidAt = looked.paidAt)
    }

    /**
     * 결제창을 **열 수 있는** 결제사 — 없으면 `null`(결제사가 없다 · 못 찾는다 · 결제창 값을 못 낸다 — [PaymentCheckoutGateway.canOpenCheckout]). `GET /payments/config` 의 `available` 과
     * 주문 만들기가 둘 다 이것으로 판정한다. 금액 · 국가를 아는 쪽(주문 만들기)과 모르는 쪽(`config` 는 둘 다 `null`)이 같은 고르기를 거치지만 **같은 결제사가 뽑힌다는 보장은 없다** —
     * 결제사가 하나인 1단계에서는 같고, 금액 · 국가로 갈리는 결제사가 늘면 `config` 가 「가능」이라 해도 어떤 주문은 503 일 수 있다(반대도).
     * **조용히 묻는다** — 이유는 `debug` 로만 남는다(`GET /config` 가 호출마다 `WARN` 을 찍지 않게). 주문 만들기만 이유를 `WARN` 으로 말한다.
     */
    fun openableCheckout(amount: PaymentAmount?, country: String?): PaymentCheckoutGateway? = findOpenableCheckout(amount, country, warn = false)

    /** [openableCheckout] 에 이유를 로그로 남길지 고른 것 — `GET /config` 는 요청 한도가 없어 조용히(`debug`) 묻고, 주문 만들기만 이유를 `WARN` 으로 말한다. */
    private fun findOpenableCheckout(amount: PaymentAmount?, country: String?, warn: Boolean): PaymentCheckoutGateway? {
        val gateway = try {
            router.resolve(null, amount, country, PaymentCheckoutGateway::class)
        } catch (e: PaymentRoutingException) {
            if (warn) log.warn("no payment provider can open a checkout: {}", e.message) else log.debug("no payment provider can open a checkout: {}", e.message)
            return null
        } catch (e: PaymentProviderNotFoundException) {
            if (warn) log.warn("the payment provider for a checkout is not available: {}", e.providerId) else log.debug("the payment provider for a checkout is not available: {}", e.providerId)
            return null
        }
        // 라우터의 단일 결제사 지름길은 `enabled` 를 안 본다(호환) — 꺼진 결제사의 결제창을 열면 고객이 인증까지 마친 뒤 승인이 막힌다. 승인 경로와 같은 문(`gateway`)으로 한 번 더 확인한다
        try {
            router.gateway(gateway.providerId, PaymentCheckoutGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            if (warn) log.warn("the payment provider for a checkout is not available: {}", e.providerId) else log.debug("the payment provider for a checkout is not available: {}", e.providerId)
            return null
        }
        if (!gateway.canOpenCheckout()) {
            if (warn) {
                log.warn("the payment provider {} cannot hand out its checkout values (check its public key and mode)", gateway.providerId)
            } else {
                log.debug("the payment provider {} cannot hand out its checkout values (check its public key and mode)", gateway.providerId)
            }
            return null
        }
        return gateway
    }

    private fun replay(existing: PaymentOrder, command: CreateOrderCommand): CreatedOrder {
        if (existing.itemType != command.itemType || existing.itemRef != command.itemRef || existing.quantity != command.quantity) {
            throw PaymentIdempotencyConflictException()
        }
        // 결제 가능 시간이 지난 PENDING 은 만료 작업이 아직 안 돌았어도 결제창 값을 주지 않는다 — 열어 줘도 승인이 거절된다
        val payable = existing.status == PaymentOrderStatus.PENDING && !writer.now().isAfter(existing.checkoutExpiresAt)
        val checkout = if (payable) {
            val gateway = try {
                router.gateway(existing.provider, PaymentCheckoutGateway::class)
            } catch (e: PaymentProviderNotFoundException) {
                log.warn("the payment provider of a replayed order is not available: {}", e.providerId)
                throw PaymentUnavailableException()
            } catch (e: PaymentCapabilityUnsupportedException) {
                log.warn("the payment provider of a replayed order cannot open a checkout: {}", e.message)
                throw PaymentUnavailableException()
            }
            if (!gateway.canOpenCheckout()) {
                log.warn("the payment provider of a replayed order cannot hand out its checkout values: {}", gateway.providerId)
                throw PaymentUnavailableException()
            }
            gateway.checkout(CheckoutOrder(existing.id, existing.orderName, existing.amount))
        } else {
            null
        }
        return CreatedOrder(existing, checkout, replayed = true, refunds = store.listRefunds(existing.id))
    }

    private fun validate(command: CreateOrderCommand) {
        if (command.itemType.isBlank() || command.itemType.length > ITEM_TYPE_MAX ||
            command.itemRef.isBlank() || command.itemRef.length > ITEM_REF_MAX || command.quantity < 1
        ) {
            throw PaymentItemNotPurchasableException()
        }
    }

    private fun ownedOrNull(order: PaymentOrder?, accountId: String): PaymentOrder? = order?.takeIf { it.accountId == accountId }

    private companion object {
        const val ORDER_NAME_MAX = 100
        const val PAYMENT_ID_MAX = 200
        const val ITEM_TYPE_MAX = 40
        const val ITEM_REF_MAX = 128
    }
}
