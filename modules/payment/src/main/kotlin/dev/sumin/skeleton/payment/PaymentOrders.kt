package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.discount.DiscountClaim
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
    /** 결제사를 지정한다(예: 화면 언어 · 나라가 정한 `paddle`). 비우면 통화 · 나라 · 기본 결제사로 서버가 고른다. */
    val provider: String? = null,
    /** 나라 지도가 정한 결제사가 못 열린다 - 다른 결제사로 넘기지 않고 새 주문은 [PaymentUnavailableException]. 같은 키의 재전송은 만든 때의 줄을 돌려준다. */
    val providerClosed: Boolean = false,
    /** [provider] 는 구매자가 `choices` 에서 고른 것이다(서버 몫이 아니다) - 그 결제사가 견적 통화를 받는지 본다. 서버가 정한 결제사는 보지 않는다(Paddle 은 자기 가격 통화로 바꾼다). */
    val providerChosen: Boolean = false,
    /** 결제를 시작한 화면의 origin - 돌아올 주소를 그 origin 으로 맞추는 결제사(카카오페이 · 네이버페이)에게만 [CheckoutOrder.returnOrigin] 으로 간다. */
    val returnOrigin: String? = null,
    /** 쿠폰 같은 할인 수단 지정(첫 판은 하나). 금액은 서버가 계산한다 - 여기에는 어떤 수단의 어느 것인지만 있다. */
    val discounts: List<DiscountClaim> = emptyList(),
    /** 호출자가 결제 면제 권한을 가졌다(컨트롤러가 요청의 `Authentication` 으로 [PaymentExemption] 에 물어 채운다). 면제가 이기므로 [discounts] 는 쓰지 않는다. */
    val exempt: Boolean = false,
)

/** 주문을 만들지 않고 금액을 본다([PaymentOrders.quote]) - 만들 때와 같은 계산이다. 할인 지정이 없으면 일반 견적과 같다. */
data class QuoteCommand(
    val accountId: String,
    val itemType: String,
    val itemRef: String,
    val quantity: Int = 1,
    val country: String? = null,
    val provider: String? = null,
    val discounts: List<DiscountClaim> = emptyList(),
    val exempt: Boolean = false,
)

/**
 * 견적 - [originalAmount] 는 카탈로그 가격, [amount] 는 할인 뒤 최종가(면제면 0), [settlement] 는 이 주문이 어떻게 끝나는지(`FREE` · `EXEMPT` 면 결제창이 없다).
 * 면제가 아닌 [discounts] 의 금액 합이 `originalAmount - amount` 다.
 */
data class OrderQuote(
    val originalAmount: Long,
    val amount: Long,
    val currency: String,
    val discounts: List<DiscountDraft>,
    val settlement: PaymentSettlement,
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
    /** 이 주문에 붙은 할인 내역 - 새 주문은 방금 쥔 줄, 같은 키의 재전송은 저장된 줄. */
    val discounts: List<OrderDiscount> = emptyList(),
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
    /** 할인 수단을 모으는 곳 - 없으면 할인 지정은 모두 거절된다. */
    private val discounts: PaymentDiscounts? = null,
    /** 결제 면제 판정 - 없으면 [CreateOrderCommand.exempt] 만 본다. */
    private val exemption: PaymentExemption? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 주문을 만들지 않고 금액을 본다 - 부수 효과가 없다. 할인 오류(`PAYMENT.DISCOUNT_*`)는 주문 만들기와 같다. */
    fun quote(command: QuoteCommand): OrderQuote {
        val catalog = catalog ?: throw PaymentUnavailableException()
        if (command.itemType.isBlank() || command.itemType.length > ITEM_TYPE_MAX || command.itemRef.isBlank() || command.itemRef.length > ITEM_REF_MAX || command.quantity < 1) {
            throw PaymentItemNotPurchasableException()
        }
        val plan = plan(catalog, command.accountId, command.itemType, command.itemRef, command.quantity, command.country, command.discounts, command.exempt)
        if (plan.settlement == PaymentSettlement.PROVIDER && plan.discount.lines.isNotEmpty()) {
            // 결제사를 조용히 찾는다 - 못 찾아도 견적은 낸다(주문 만들기가 그때 503 로 답한다)
            findOpenableCheckout(command.provider, plan.charged, command.country, warn = false)?.let { requireDiscountable(it, plan.charged) }
        }
        return OrderQuote(plan.discount.originalAmount, plan.discount.amount, plan.discount.currency, plan.discount.lines, plan.settlement)
    }

    /** 이 호출이 면제 계정의 것인가 - 요청이 이미 판정했거나(`command.exempt`) 계정 id 로 물어 면제다. */
    private fun isExempt(accountId: String, claimed: Boolean): Boolean = claimed || exemption?.isExempt(accountId) == true

    private class Plan(val purchase: PurchaseQuote, val discount: DiscountQuote, val settlement: PaymentSettlement) {
        /** 결제사에 청구할 금액(면제 · 0원이면 0). */
        val charged: PaymentAmount get() = PaymentAmount(discount.amount, purchase.amount.currency)
    }

    /** 카탈로그 가격 → 면제 또는 할인 → 최종가. 만들기와 견적이 같은 계산을 쓴다. */
    private fun plan(catalog: PaymentCatalog, accountId: String, itemType: String, itemRef: String, quantity: Int, country: String?, claims: List<DiscountClaim>, exemptClaimed: Boolean): Plan {
        val purchase = catalog.quote(PurchaseRequest(accountId, itemType, itemRef, quantity, country))
        if (purchase.amount.amount <= 0) throw PaymentItemNotPurchasableException()
        check(purchase.orderName.isNotBlank()) { "The payment catalog returned a blank order name" }
        val original = purchase.amount
        if (isExempt(accountId, exemptClaimed)) {
            // 면제가 이긴다 - 쿠폰은 보지도 잡지도 않는다
            return Plan(purchase, DiscountQuote(original.amount, 0, original.normalizedCurrency, emptyList()), PaymentSettlement.EXEMPT)
        }
        val quoted = if (claims.isEmpty()) {
            DiscountQuote(original.amount, original.amount, original.normalizedCurrency, emptyList())
        } else {
            val source = discounts ?: throw PaymentDiscountNotApplicableException("This discount is not offered here.")
            source.quote(claims, source.targetFor(accountId, itemType, itemRef, original))
        }
        return Plan(purchase, quoted, if (quoted.amount == 0L) PaymentSettlement.FREE else PaymentSettlement.PROVIDER)
    }

    /** 할인이 붙은 주문이 갈 결제사가 받을 수 있는가 - 금액을 직접 정하는 결제사 · 최소 금액 미만. */
    private fun requireDiscountable(checkout: PaymentCheckoutGateway, charged: PaymentAmount) {
        if (checkout is PaymentTransactionGateway) throw PaymentDiscountNotSupportedException()
        val minimum = checkout.minimumAmount(charged.normalizedCurrency)
        if (minimum != null && charged.amount < minimum) throw PaymentDiscountBelowMinimumException(minimum)
    }

    fun create(command: CreateOrderCommand): CreatedOrder {
        val catalog = catalog ?: throw PaymentUnavailableException()
        validate(command)
        val dedupeKey = PaymentOrderIds.dedupeKey(command.accountId, command.idempotencyKey)
        store.findOrderByDedupeKey(dedupeKey)?.let { return replay(it, command) }
        // 결제사 없이 끝날 수 있는 주문(면제 · 할인)은 닫힌 결제사와 상관없다 - 그 밖의 주문은 지금까지처럼 카탈로그를 부르기도 전에 거절한다
        val mayBeOffProvider = command.discounts.isNotEmpty() || isExempt(command.accountId, command.exempt)
        if (command.providerClosed && !mayBeOffProvider) throw PaymentUnavailableException()

        val plan = plan(catalog, command.accountId, command.itemType, command.itemRef, command.quantity, command.country, command.discounts, command.exempt)
        val quote = plan.purchase
        if (plan.settlement != PaymentSettlement.PROVIDER) return createOffProvider(command, plan, dedupeKey)
        if (command.providerClosed) throw PaymentUnavailableException()
        if (command.providerChosen && (command.provider == null || !router.servesCurrency(command.provider, quote.amount.currency))) {
            throw PaymentRoutingException("The chosen payment provider does not take the currency of this purchase.")
        }
        val checkout = findOpenableCheckout(command.provider, plan.charged, command.country, warn = true) ?: throw PaymentUnavailableException()
        if (plan.discount.lines.isNotEmpty()) requireDiscountable(checkout, plan.charged)
        val orderId = ids.newId(PaymentOrderIds.ORDER_PREFIX)
        val creator = checkout as? PaymentTransactionGateway

        val now = writer.now()
        val order = PaymentOrder(
            id = orderId,
            accountId = command.accountId,
            kind = PaymentOrderKind.ONE_TIME,
            status = PaymentOrderStatus.PENDING,
            provider = checkout.providerId.trim().lowercase(),
            providerMode = checkout.mode,
            providerPaymentId = null,
            amount = plan.charged,
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
            country = command.country?.uppercase(),
            originalAmount = quote.amount.amount.takeIf { plan.discount.lines.isNotEmpty() },
            discountAmount = plan.discount.discountAmount,
        )
        // 주문을 먼저 커밋한다(결제사 호출은 트랜잭션 밖) - 같은 요청 키로 동시에 들어온 쪽은 dedupe_key 유니크로 `false` 를 받아 그 주문을 돌려받는다.
        // 할인은 줄을 넣은 **뒤에** 쥔다 - 같은 키의 동시 요청이 쿠폰을 두고 다투지 않고 승자의 주문을 돌려받는다
        var held: List<OrderDiscount> = emptyList()
        val inserted = store.inTransaction { tx ->
            if (!tx.insertOrderIfAbsent(order)) return@inTransaction false
            tx.appendEvent(writer.event(order.id, PaymentEventType.ORDER_CREATED, PaymentActor.USER, command.accountId, to = order.status))
            held = reserve(tx, order, command, plan)
            tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_EXPIRE, order.id, runAt = order.checkoutExpiresAt, maxAttempts = properties.reconcile.maxAttempts))
            true
        }
        if (!inserted) {
            // 동시에 같은 키로 들어온 다른 요청이 먼저 넣었다 — 그 줄이 곧 답이다
            val winner = checkNotNull(store.findOrderByDedupeKey(dedupeKey)) { "the order for this idempotency key disappeared" }
            return replay(winner, command)
        }
        // 거래를 먼저 만드는 결제사(Paddle): 밖에서 서버가 정한 가격 · 수량 · 우리 줄 id 로 거래를 만들어 주문의 결제 키 자리에 박는다. 만들기가 실패하면 주문은 거래 id 없이 남는다
        // (만료 작업이 닫는다; 같은 키로 다시 열면 그때 만든다)
        val created = if (creator != null) ensureTransaction(order, creator) else order
        val instruction = checkout.checkout(CheckoutOrder(created.id, created.orderName, created.amount, created.itemRef, created.accountId, created.providerPaymentId, returnOrigin = command.returnOrigin))
        return CreatedOrder(remember(created, instruction), instruction, replayed = false, discounts = held)
    }

    /**
     * 결제사 없이 끝나는 주문 - 쿠폰으로 최종가가 0원(`free`)이거나 결제 면제 계정(`exempt`). 한 트랜잭션에서 줄을 넣고 · 할인을 쥐고 · `PENDING → PAID` 로 옮기고 · 지급 작업을 넣는다.
     * 결제창도 만료 작업도 없다. 지급 훅은 평소처럼 돈다(상품은 나간다) - 커밋 뒤 요청 스레드가 한 번 시도하고, 실패하면 지급 작업이 재시도한다.
     */
    private fun createOffProvider(command: CreateOrderCommand, plan: Plan, dedupeKey: String): CreatedOrder {
        val exempt = plan.settlement == PaymentSettlement.EXEMPT
        val now = writer.now()
        val order = PaymentOrder(
            id = ids.newId(PaymentOrderIds.ORDER_PREFIX),
            accountId = command.accountId,
            kind = PaymentOrderKind.ONE_TIME,
            status = PaymentOrderStatus.PENDING,
            provider = if (exempt) EXEMPT_PROVIDER else FREE_PROVIDER,
            providerMode = offProviderMode(command, plan),
            providerPaymentId = null,
            amount = plan.charged,
            paidAmount = null,
            taxAmount = null,
            refundedAmount = 0,
            itemType = command.itemType,
            itemRef = command.itemRef,
            quantity = command.quantity,
            orderName = plan.purchase.orderName.trim().take(ORDER_NAME_MAX),
            originType = PaymentOriginType.CHECKOUT,
            originRef = null,
            dedupeKey = dedupeKey,
            method = null,
            cardIssuer = null,
            cardLast4 = null,
            failureCode = null,
            reviewReason = null,
            checkoutExpiresAt = now,
            confirmStartedAt = null,
            paidAt = null,
            fulfilledAt = null,
            closedAt = null,
            accountErasedAt = null,
            createdAt = now,
            updatedAt = now,
            country = command.country?.uppercase(),
            originalAmount = plan.purchase.amount.amount,
            discountAmount = if (exempt) 0 else plan.discount.discountAmount,   // 면제는 할인이 아니다
        )
        var held: List<OrderDiscount> = emptyList()
        val inserted = store.inTransaction { tx ->
            if (!tx.insertOrderIfAbsent(order)) return@inTransaction false
            tx.appendEvent(writer.event(order.id, PaymentEventType.ORDER_CREATED, PaymentActor.USER, command.accountId, to = order.status))
            if (exempt) tx.appendEvent(writer.event(order.id, PaymentEventType.EXEMPTED, PaymentActor.USER, command.accountId, PaymentPermissions.EXEMPT))
            held = reserve(tx, order, command, plan)
            writer.transition(tx, order, PaymentOrderStatus.PAID, PaymentEventType.CONVERGED, PaymentActor.SYSTEM, null, if (exempt) "EXEMPT_ORDER" else "FREE_ORDER") {
                it.copy(paidAmount = 0, paidAt = writer.now())
            }
            tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
            true
        }
        if (!inserted) return replay(checkNotNull(store.findOrderByDedupeKey(dedupeKey)) { "the order for this idempotency key disappeared" }, command)
        fulfillment.tryFulfil(order.id)
        return CreatedOrder(store.findOrder(order.id) ?: order, null, replayed = false, discounts = store.listOrderDiscounts(listOf(order.id)).ifEmpty { held })
    }

    /** 결제사 없이 끝나는 주문의 모드 - 결제사가 열 수 있으면 그 모드(기본 결제사의 모드), 하나도 없으면 LIVE(진짜 돈이 아니어도 시험 키 앱의 대시보드는 열린 결제사의 모드를 따른다). */
    private fun offProviderMode(command: CreateOrderCommand, plan: Plan): PaymentMode =
        findOpenableCheckout(command.provider, plan.purchase.amount, command.country, warn = false)?.mode ?: PaymentMode.LIVE

    /** 할인을 쥐고 내역 줄을 넣는다(주문 줄을 넣은 트랜잭션 안). 지정이 없거나 면제면 아무것도 하지 않는다. 미리 본 금액과 달라졌으면 쿠폰이 그사이 바뀐 것이다 - 되돌린다. */
    private fun reserve(tx: PaymentLedgerTx, order: PaymentOrder, command: CreateOrderCommand, plan: Plan): List<OrderDiscount> {
        if (plan.settlement == PaymentSettlement.EXEMPT || command.discounts.isEmpty()) return emptyList()
        val source = checkNotNull(discounts) { "a discount was planned without a discount collection" }
        val reserved = source.reserve(command.discounts, source.targetFor(order.accountId, order.itemType, order.itemRef, plan.purchase.amount), order.id)
        if (reserved.amount != plan.discount.amount) throw PaymentDiscountUnavailableException()
        val now = writer.now()
        return reserved.lines.mapIndexed { index, line ->
            OrderDiscount(order.id, index + 1, line.kind, line.sourceRef, line.label, line.amount, order.amount.normalizedCurrency, OrderDiscountStatus.RESERVED, now, now)
        }.onEach(tx::insertOrderDiscount)
    }

    /**
     * 결제창에서 돌아온 결제를 승인한다. **선점(커밋) → 호출(락 밖) → 확정(커밋)**. 돌려주는 줄이 `PAID` 면 끝났고 `CONFIRMING` 이면 확인 중이다(HTTP 202).
     * 금액은 비교만 한다 — 결제사에는 줄의 금액이 간다. 확정 거절이면 줄을 `FAILED` 로 닫고 [PaymentDeclinedException].
     *
     * **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException], 프로그래밍 오류) — 선점이 커밋되지 않은 채 결제사가 불리고, 바깥이 롤백되면 선점과
     * 조회 작업이 사라지는데 돈은 이미 나간다([PaymentLedgerStore.hasOpenTransaction]). 앱은 `@Transactional` 밖에서 부른다.
     */
    fun confirm(accountId: String, orderId: String, providerPaymentId: String, amount: PaymentAmount, approvalToken: String? = null): PaymentOrder {
        store.requireNoOpenTransaction("PaymentOrders.confirm")
        if (providerPaymentId.isBlank() || providerPaymentId.length > PAYMENT_ID_MAX) {
            throw FieldValidationException("providerPaymentId", "Size", "providerPaymentId must be 1 to $PAYMENT_ID_MAX characters")
        }
        val claim = claim(accountId, orderId, providerPaymentId, amount, approvalToken)
        val gateway = claim.gateway ?: return claim.order
        val order = claim.order
        val request = PaymentConfirmRequest(
            providerPaymentId = providerPaymentId,
            amount = order.amount, // 요청의 금액이 아니라 줄의 금액
            merchantReferenceId = order.id,
            provider = order.provider,
            idempotencyKey = IdempotencyKey(order.id), // 같은 주문의 재전송이 생겨도 결제사가 한 번으로 본다
            approvalToken = approvalToken,
            accountId = accountId,
        )
        val context = ConvergeContext(gateway.mode, ConvergeSource.CONFIRM, PaymentActor.USER, accountId)
        val result = try {
            gateway.confirm(request)
        } catch (e: Exception) {
            // 호출 자체의 실패만 여기서 가른다 — 확정(저장) 쪽 버그를 "모름"으로 삼키지 않으려고 settle 은 try 밖이다
            val after = when (PaymentFailures.outcomeOf(e)) {
                PaymentFailureOutcome.DEFINITIVE -> decline(order, gateway, PaymentFailures.providerCodeOf(e), context, PaymentFailures.moneyReturnedOf(e))
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
    private fun claim(accountId: String, orderId: String, providerPaymentId: String, amount: PaymentAmount, approvalToken: String?): Claim =
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
            // 승인 토큰도 같은 자리에서 - 선점 뒤에 거절하면 「모름」으로 굳는다
            if (!gateway.acceptsApprovalToken(approvalToken)) {
                throw FieldValidationException("approvalToken", "Pattern", "approvalToken is not in a form the payment provider accepts")
            }
            // 결제창을 열 때 결제사가 거래 번호를 먼저 정했으면(`CheckoutInstruction.providerPaymentId`) 그 번호로만 승인한다 - 브라우저가 다른 번호를 끼워 넣지 못한다
            if (order.providerPaymentId != null && order.providerPaymentId != providerPaymentId) throw PaymentOrderNotPayableException()
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

    private fun decline(order: PaymentOrder, gateway: PaymentConfirmGateway, providerCode: String?, context: ConvergeContext, moneyReturned: Boolean = false): PaymentOrder {
        val closed = store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            convergence.declined(tx, locked, providerCode, context, clearReceivedMarks = moneyReturned).order
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
            easyPayBrand = result.attributes["easyPayBrand"],
        )
        if (result.card != null || result.paidAt != null) return fromResponse
        val looked = try {
            router.gateway(order.provider, PaymentLookupGateway::class).lookup(PaymentLookupQuery(providerPaymentId, null))
        } catch (e: Exception) {
            log.debug("could not enrich the confirm response order={} exception={}", order.id, e.javaClass.simpleName)
            null
        }
        if (looked == null || looked.providerPaymentId != providerPaymentId || (looked.orderId != null && looked.orderId != order.id)) return fromResponse
        return fromResponse.copy(card = looked.card, easyPayBrand = fromResponse.easyPayBrand ?: looked.easyPayBrand, method = fromResponse.method ?: looked.method, paidAt = looked.paidAt)
    }

    /**
     * 결제창을 **열 수 있는** 결제사 — 없으면 `null`(결제사가 없다 · 못 찾는다 · 결제창 값을 못 낸다 — [PaymentCheckoutGateway.canOpenCheckout]). `GET /payments/config` 의 `available` 과
     * 주문 만들기가 둘 다 이것으로 판정한다. 금액 · 국가를 아는 쪽(주문 만들기)과 모르는 쪽(`config` 는 둘 다 `null`)이 같은 고르기를 거치지만 **같은 결제사가 뽑힌다는 보장은 없다** —
     * 결제사가 하나인 1단계에서는 같고, 금액 · 국가로 갈리는 결제사가 늘면 `config` 가 「가능」이라 해도 어떤 주문은 503 일 수 있다(반대도).
     * **조용히 묻는다** — 이유는 `debug` 로만 남는다(`GET /config` 가 호출마다 `WARN` 을 찍지 않게). 주문 만들기만 이유를 `WARN` 으로 말한다.
     */
    fun openableCheckout(amount: PaymentAmount?, country: String?, provider: String? = null): PaymentCheckoutGateway? = findOpenableCheckout(provider, amount, country, warn = false)

    /** 결제창을 열 수 있는 결제사 id(`GET /config` 의 `providers`) - 서버가 접속 나라로 고르는 후보. */
    fun openableProviders(): List<String> = router.providerIds(PaymentCheckoutGateway::class).filter { openableCheckout(null, null, it) != null }

    /** [openableCheckout] 에 이유를 로그로 남길지 고른 것 — `GET /config` 는 요청 한도가 없어 조용히(`debug`) 묻고, 주문 만들기만 이유를 `WARN` 으로 말한다. */
    private fun findOpenableCheckout(provider: String?, amount: PaymentAmount?, country: String?, warn: Boolean): PaymentCheckoutGateway? {
        val gateway = try {
            router.resolve(provider, amount, country, PaymentCheckoutGateway::class)
        } catch (e: PaymentRoutingException) {
            if (warn) log.warn("no payment provider can open a checkout: {}", e.message) else log.debug("no payment provider can open a checkout: {}", e.message)
            return null
        } catch (e: PaymentProviderNotFoundException) {
            if (warn) log.warn("the payment provider for a checkout is not available: {}", e.providerId) else log.debug("the payment provider for a checkout is not available: {}", e.providerId)
            return null
        } catch (e: PaymentCapabilityUnsupportedException) {
            if (warn) log.warn("the payment provider cannot open a checkout: {}", e.message) else log.debug("the payment provider cannot open a checkout: {}", e.message)
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

    /**
     * 거래 id 가 없는 주문에 거래를 만들어 박는다 - **결제사 호출은 트랜잭션 밖**. 동시에 두 요청이 만들 수 있어 만든 뒤 주문을 잠그고 아직 비어 있을 때만 박는다:
     * 먼저 박은 쪽이 이기고 진 쪽은 이긴 id 를 돌려준다(진 쪽의 거래는 결제되지 않은 채 결제사에 남는다 - 수용). Paddle API 에는 멱등 키 헤더가 없다.
     */
    private fun ensureTransaction(order: PaymentOrder, creator: PaymentTransactionGateway): PaymentOrder {
        if (order.providerPaymentId != null) return order
        store.requireNoOpenTransaction("PaymentOrders.create")
        val transactionId = try {
            creator.createTransaction(TransactionRequest(order.itemRef, order.quantity, mapOf(PushedEvents.ORDER_KEY to order.id, PushedEvents.ACCOUNT_KEY to order.accountId)))
        } catch (e: Exception) {
            log.warn("the provider could not create a transaction for an order exception={} {}", e.javaClass.simpleName, PaymentFailures.describe(e))
            recordTransactionFailure(order, e)
            throw PaymentUnavailableException()
        }
        return store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            if (locked.providerPaymentId != null) return@inTransaction locked
            locked.copy(providerPaymentId = transactionId).also(tx::updateOrder)
        }
    }

    /**
     * 결제창이 결제사가 먼저 정한 거래 번호를 줬으면([CheckoutInstruction.providerPaymentId]) 주문에 박는다 - 결제창에서 돌아온 승인이 그 번호로만 열린다.
     * 같은 `PENDING` 주문을 다시 열 때마다 결제사가 새 번호를 주고, **마지막으로 저장한 번호가 남는다**(동시에 두 번 열면 늦게 커밋한 쪽). 승인은 이 번호로 나간다. 화면이 거래 번호를 안 싣는 클라이언트(react)가 **먼저 연 창**에서 돌아오면 새 번호로 승인이 나가
     * 결제사가 옛 창의 토큰을 거절하고(카카오페이 -780) 주문이 `CONFIRMING` 에 굳는다(돈은 안 움직인다); 409 는 번호를 직접 보내는 클라이언트일 때만 난다. 구매자는 가장 나중에 받은 창으로 결제해야 한다. 그 사이 주문이 `PENDING` 을 벗어났으면 건드리지 않는다. 결제사 호출은 이미 끝났다(트랜잭션 밖).
     */
    private fun remember(order: PaymentOrder, instruction: CheckoutInstruction): PaymentOrder {
        val id = instruction.providerPaymentId ?: return order
        if (order.providerPaymentId == id) return order
        return store.inTransaction { tx ->
            val locked = tx.lockOrder(order.id) ?: return@inTransaction order
            if (locked.status != PaymentOrderStatus.PENDING) return@inTransaction locked
            locked.copy(providerPaymentId = id).also(tx::updateOrder)
        }
    }

    /** 거절의 이유(상태 + 결제사 코드)를 그 주문의 이력에 남긴다 - 이력을 못 남겨도 원래 실패(503)는 그대로 나간다. */
    private fun recordTransactionFailure(order: PaymentOrder, e: Exception) {
        try {
            store.inTransaction { tx -> tx.appendEvent(writer.event(order.id, PaymentEventType.TRANSACTION_FAILED, PaymentActor.SYSTEM, providerCode = PaymentFailures.eventCodeOf(e), from = order.status, to = order.status)) }
        } catch (recording: Exception) {
            log.warn("could not record the transaction failure on the order history exception={}", recording.javaClass.simpleName)
        }
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
            val ready = (gateway as? PaymentTransactionGateway)?.let { ensureTransaction(existing, it) } ?: existing // 거래 id 가 아직 없는 주문을 다시 열면 그때 만든다
            gateway.checkout(CheckoutOrder(ready.id, ready.orderName, ready.amount, ready.itemRef, ready.accountId, ready.providerPaymentId, returnOrigin = command.returnOrigin))
        } else {
            null
        }
        val shown = if (checkout != null) remember(existing, checkout) else existing
        return CreatedOrder(shown, checkout, replayed = true, refunds = store.listRefunds(existing.id), discounts = store.listOrderDiscounts(listOf(existing.id)))
    }

    private fun validate(command: CreateOrderCommand) {
        if (command.itemType.isBlank() || command.itemType.length > ITEM_TYPE_MAX ||
            command.itemRef.isBlank() || command.itemRef.length > ITEM_REF_MAX || command.quantity < 1
        ) {
            throw PaymentItemNotPurchasableException()
        }
    }

    private fun ownedOrNull(order: PaymentOrder?, accountId: String): PaymentOrder? = order?.takeIf { it.accountId == accountId }

    companion object {
        /** 예약 결제사 id - 쿠폰으로 최종가가 0원이라 결제사 없이 끝난 주문. 라우터가 고르지 않는다. */
        const val FREE_PROVIDER = "free"

        /** 예약 결제사 id - 결제 면제 계정의 주문(결제사 없이 끝남, 할인이 아니다). 앱이 면제 주문을 가르고 싶으면 `order.provider == EXEMPT_PROVIDER`. */
        const val EXEMPT_PROVIDER = "exempt"

        private const val ORDER_NAME_MAX = 100
        private const val PAYMENT_ID_MAX = 200
        private const val ITEM_TYPE_MAX = 40
        private const val ITEM_REF_MAX = 128
    }
}
