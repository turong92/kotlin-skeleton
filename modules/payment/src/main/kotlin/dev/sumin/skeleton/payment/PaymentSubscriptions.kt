package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.crypto.TextEncryptor
import java.security.SecureRandom
import java.util.Random
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** [billing] 은 카드 등록 창을 여는 값. 같은 요청의 재전송이 이미 시작한 구독이면 null. */
data class StartedSubscription(val subscription: PaymentSubscription, val billing: BillingSetup?, val replayed: Boolean)

/**
 * 구독 서비스(스펙 `2026-10-09-subscription-design.md` §5 · §7) — 만들기 · 카드 등록 + 첫 결제 · 내 구독 · 해지 예약. 모든 전이는 구독 줄을 잠그고 **다시 읽은 뒤** 판단하며,
 * 결제사 호출은 락 · 트랜잭션 밖이다(빌링키 발급 → 자리 선점 + 첫 주문(커밋) → 승인 → 확정). 남의 구독과 없는 구독은 같은 [PaymentSubscriptionNotFoundException] 이다.
 * 빌링키는 [encryptor] 로 암호화해 저장하고, 평문은 결제사를 부르는 동안에만 메모리에 있다.
 */
class PaymentSubscriptions(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val plans: SubscriptionPlans,
    private val encryptor: TextEncryptor,
    private val lifecycle: SubscriptionLifecycle,
    private val charges: SubscriptionCharges,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    private val random: Random = SecureRandom(),
    private val ids: SortableIds = SortableIds.system,
    /** 쿠폰 같은 할인 수단을 모으는 곳 - 없으면 구독의 할인 지정은 모두 거절된다. */
    private val couponDiscounts: PaymentDiscounts? = null,
    /** 구독의 할인 일정(단계 복사 · 회차) - 없으면 할인 없는 구독만. */
    private val scheduleDiscounts: SubscriptionDiscounts? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun plans(): List<SubscriptionPlan> = plans.list()

    /**
     * 이 요청의 통화([currency])로 거른 플랜. 그 통화 플랜이 하나도 없거나 통화를 모르면(결제사를 못 정한 요청 포함) **상품마다 사전순 첫 통화**의 플랜을 낸다 -
     * 공개 카탈로그와 같은 규칙이다. 코드 플랜은 플랜마다 상품이 따로라 거르는 것이 없다.
     */
    fun plansFor(currency: String?): List<SubscriptionPlan> {
        val all = plans.list()
        val wanted = currency?.trim()?.uppercase()
        val inWanted = wanted?.let { w -> all.filter { it.amount.normalizedCurrency == w } }.orEmpty()
        if (inWanted.isNotEmpty()) return inWanted
        val firstCurrency = all.groupBy { it.productCode }.mapValues { (_, plans) -> plans.minOf { it.amount.normalizedCurrency } }
        return all.filter { it.amount.normalizedCurrency == firstCurrency[it.productCode] }
    }

    /** 코드로 찾는다 - 판매가 끝난 가격도 나온다(기존 구독자의 이름을 보이려고). */
    fun plan(code: String): SubscriptionPlan? = plans.find(code)

    /** 이 계정의 가장 최근 구독(끝난 것 포함, 시작하지 않은 시도는 제외). 앱은 이것을 **읽을 때** `status.isInUse` 로 판단한다. */
    fun current(accountId: String): PaymentSubscription? = store.findLatestSubscription(accountId)

    fun recentOrders(subscriptionId: String, limit: Int = RECENT_ORDERS): List<PaymentOrder> = store.listSubscriptionOrders(subscriptionId, limit)

    /** [recentOrders] 에 주문마다 할인 내역(첫 결제의 쿠폰 줄)을 붙인 읽기 모델 - 「내 구독」이 원가 · 할인 줄을 그린다. 한 번의 질의로 모은다. */
    fun recentOrderViews(subscriptionId: String, limit: Int = RECENT_ORDERS): List<OrderView> {
        val orders = recentOrders(subscriptionId, limit)
        val discounts = store.listOrderDiscounts(orders.map { it.id }).groupBy { it.orderId }
        return orders.map { OrderView(it, emptyList(), discounts[it.id].orEmpty()) }
    }

    /** `PENDING` 줄을 만들고 카드 등록 창 값을 돌려준다. 같은 키로 다시 부르면 같은 줄이다. [country] 가 비면 설정의 기본 나라(그것도 없으면 나라 없음). */
    fun start(
        accountId: String,
        planCode: String,
        key: IdempotencyKey,
        country: String? = null,
        provider: String? = null,
        providerClosed: Boolean = false,
        /** 화면이 보낸 origin - 돌아올 주소를 그 origin 으로 맞추는 결제사(카카오페이 · 네이버페이)만 허용 목록 안에서 쓴다. */
        returnOrigin: String? = null,
        /** 서버가 정한 결제사가 아니라 구매자가 고른 결제사다 - 플랜 통화를 받는지 확인한다. */
        providerChosen: Boolean = false,
        /** 쿠폰 같은 할인 수단 지정(첫 판은 하나). 쿠폰은 첫 결제 때 첫 주문 이름으로 쥐고, 단계는 지금 복사한다. */
        discounts: List<DiscountClaim> = emptyList(),
    ): StartedSubscription {
        val dedupeKey = PaymentOrderIds.dedupeKey(accountId, key)
        store.findSubscriptionByDedupeKey(dedupeKey)?.let { return replay(it, planCode, returnOrigin) }
        if (providerClosed) throw PaymentUnavailableException() // 나라 지도가 정한 결제사가 못 열린다 - 다른 결제사로 넘기지 않는다

        val plan = plans.find(planCode)?.takeIf { it.amount.amount > 0 && it.purchasable } ?: throw PaymentItemNotPurchasableException()
        // 나라가 정한 결제사도 플랜 통화를 받아야 한다(결제사를 부르기 전에 거절 - 줄을 만들지 않는다)
        if (provider != null && !router.takesCurrency(provider, plan.amount.currency)) throw PaymentRoutingException("The payment provider of this country does not take the plan currency.")
        SubscriptionPeriod.parse(plan.period)
        // 무료 첫 결제를 선점해 놓고 죽은 구독이 자리를 쥐고 있으면 이어서 끝낸다 - 안 그러면 새 구독이 영영 막힌다(끝나면 ACTIVE 라 아래 판정이 「이미 구독 중」이 된다)
        store.findLiveSubscription(accountId)?.let(::finishFreeFirstCharge)
        if (store.findLiveSubscription(accountId) != null) throw PaymentAlreadySubscribedException()
        val subscriptionId = ids.newId(PaymentOrderIds.SUBSCRIPTION_PREFIX)
        val customerKey = PaymentOrderIds.newCustomerKey(random)
        // 결제사가 갱신까지 하는 결제사(Paddle)를 골랐으면 그 결제창 값, 아니면 카드 등록 창 값
        val hosted = provider?.let(::hostedGateway)
        // 쿠폰 - 결제사가 갱신까지 하는 구독(Paddle)은 금액을 결제사가 정하므로 받지 않는다. 지금은 보기만 한다(아무것도 안 쥔다): 첫 과금액과 단계를 얻는다
        var offer: SubscriptionOffer? = null
        if (discounts.isNotEmpty()) {
            if (hosted != null) throw PaymentDiscountNotSupportedException()
            val collection = couponDiscounts ?: throw PaymentDiscountNotApplicableException("This discount is not offered here.")
            checkNotNull(scheduleDiscounts) { "subscription discounts need SubscriptionDiscounts" }
            offer = collection.subscriptionOffer(discounts, collection.subscriptionTarget(accountId, plan))
        }
        val firstAmount = PaymentAmount(plan.amount.amount - (offer?.firstDiscount ?: 0), plan.amount.currency)
        val gateway: PaymentGateway
        val setup: BillingSetup
        var reference: String? = null
        var window: String? = null
        val creator = hosted?.let { it as? PaymentTransactionGateway ?: throw PaymentUnavailableException() }
        // 결제사 쪽 가격 id 가 없는 플랜은 줄을 넣기 전에 거절한다 - 거래를 만들 수 없는 PENDING 줄이 남지 않게
        if (hosted is PaymentPushGateway && hosted.priceIdOf(plan.code) == null) throw PaymentUnavailableException()
        if (hosted != null) {
            gateway = hosted
            // 결제창 값은 거래 id 가 정해진 뒤에 낸다(아래 — 줄을 넣는 트랜잭션 안에서 거래를 만든다). 설정이 모자라면 여기서 미리 거절하려고 거래 id 없이 한 번 물어 둔다
            setup = hosted.subscriptionSetup(HostedSubscriptionRequest(plan.code, accountId, subscriptionId, customerKey, country?.uppercase(), "pending")) ?: throw PaymentUnavailableException()
        } else {
            // 구매자가 고른 결제사는 플랜 통화도 받아야 한다(주문 만들기와 같다)
            if (providerChosen && provider != null && !router.servesCurrency(provider, plan.amount.currency)) throw PaymentRoutingException("The chosen payment provider does not serve the plan currency.")
            val billing = billingGateway(plan.amount, provider) ?: throw PaymentUnavailableException()
            offer?.let { requireStagesChargeable(billing, plan.amount, it.offer.stages) }
            gateway = billing
            // 서버가 결제사를 먼저 불러 예약해야 창이 열리는 결제사(카카오페이 · 네이버페이)는 여기서(트랜잭션 밖) 예약하고 번호를 줄에 박는다
            val prepared = try {
                billing.prepareBilling(BillingPrepareRequest(subscriptionId, customerKey, plan.name, firstAmount, PaymentOrderIds.initialOrderId(subscriptionId), returnOrigin, accountId))
            } catch (e: Exception) {
                if (e is InterruptedException) throw e
                log.warn("the provider could not prepare a card registration {}", PaymentFailures.describe(e))
                throw PaymentUnavailableException()
            } ?: throw PaymentUnavailableException()
            setup = prepared.setup
            reference = prepared.reference
            window = if (billing.registrationWindowLifetime != null) RegistrationWindows.encode(prepared.setup) else null
        }

        val now = writer.now()
        val subscription = PaymentSubscription(
            id = subscriptionId,
            accountId = accountId,
            provider = gateway.providerId.trim().lowercase(),
            providerMode = gateway.mode,
            status = SubscriptionStatus.PENDING,
            planCode = plan.code,
            amount = plan.amount,
            period = plan.period,
            anchorAt = null,
            periodSeq = 0,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            chargeOrderId = null,
            attempt = 1,
            customerKey = customerKey,
            billingToken = null,
            cardIssuer = null,
            cardLast4 = null,
            activeKey = null,
            pendingPlanCode = null,
            pendingAmount = null,
            endReason = null,
            endedAt = null,
            accountErasedAt = null,
            createdAt = now,
            updatedAt = now,
            country = country?.uppercase() ?: properties.subscription.cancel.defaultCountry,
            dedupeKey = dedupeKey,
            firstTransactionId = reference,
            registrationWindow = window,
            registrationWindowAt = window?.let { now },
            regularMonthlyAmount = RegularMonthlyPrice.of(plans, plan),
        )
        // 줄을 먼저 커밋한다(결제사 호출은 트랜잭션 밖). 같은 요청 키로 동시에 들어온 쪽은 dedupe_key 유니크로 `false` 를 받아 그 줄을 돌려받는다.
        val inserted = store.inTransaction { tx ->
            tx.insertSubscriptionIfAbsent(subscription).also { added -> if (added && offer != null) checkNotNull(scheduleDiscounts).attach(tx, subscription, offer.offer, offer.kind) }
        }
        if (!inserted) {
            return replay(checkNotNull(store.findSubscriptionByDedupeKey(dedupeKey)) { "the subscription for this idempotency key disappeared" }, planCode, returnOrigin)
        }
        if (creator == null) return StartedSubscription(subscription, setup, replayed = false)
        // 거래를 만드는 결제사: 밖에서 만들어 줄에 박는다. 만들기가 실패하면 줄은 거래 id 없이 남는다(다시 열면 그때 만든다 - `PENDING` 은 「내 구독」에 보이지 않고 자리도 쥐지 않는다)
        val started = ensureTransaction(subscription, creator)
        val finalSetup = checkNotNull(hosted).subscriptionSetup(HostedSubscriptionRequest(plan.code, accountId, subscriptionId, customerKey, country?.uppercase(), started.firstTransactionId)) ?: throw PaymentUnavailableException()
        return StartedSubscription(started, finalSetup, replayed = false)
    }

    /**
     * 카드 등록 창이 돌려준 값으로 빌링키를 받고 첫 결제를 한다. **발급(락 밖) → 자리 선점 + 첫 주문 + 조회 작업(한 트랜잭션) → 승인(락 밖) → 확정**.
     * 돌려주는 줄이 `ACTIVE` 면 끝났고 `PENDING` 이면 확인 중이다(HTTP 202). 확정 거절이면 구독을 끝내고 [PaymentDeclinedException].
     * 선점에 지면(동시 요청 · 이미 구독 중) 방금 받은 빌링키를 지운다.
     *
     * **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException]) — 결제사를 부르는 코드다.
     */
    fun activate(accountId: String, subscriptionId: String, providerPayload: Map<String, Any?>): PaymentSubscription {
        store.requireNoOpenTransaction("PaymentSubscriptions.activate")
        val subscription = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        when {
            subscription.status == SubscriptionStatus.ENDED -> throw PaymentSubscriptionNotActivatableException()
            subscription.status != SubscriptionStatus.PENDING || subscription.chargeOrderId != null -> return finishFreeFirstCharge(subscription) // 같은 요청의 재전송 — 이미 시작했거나 확인 중이다
        }
        val gateway = router.gateway(subscription.provider, PaymentBillingGateway::class)
        if (gateway.mode != subscription.providerMode) throw PaymentModeMismatchException()
        val customerKey = checkNotNull(subscription.customerKey)
        if (!gateway.acceptsRegistration(providerPayload, subscription.firstTransactionId)) {
            throw FieldValidationException("providerPayload", "Pattern", "providerPayload is not in the form the payment provider accepts")
        }
        if (gateway.registrationCharges) return activateByFirstCharge(accountId, subscription, gateway, customerKey, providerPayload)

        val issued = try {
            gateway.issue(customerKey, providerPayload, subscription.firstTransactionId)
        } catch (e: Exception) {
            // 같은 authKey 로 겹친 요청 — 결제사가 둘째 발급을 거절해도 첫째가 이미 시작했으면 그 줄이 답이다(「결제 안 됨」으로 거짓을 말하지 않는다)
            store.findSubscription(subscriptionId)?.takeIf { owned(it, accountId) != null && (it.status != SubscriptionStatus.PENDING || it.chargeOrderId != null) }?.let { return it }
            if (PaymentFailures.outcomeOf(e) == PaymentFailureOutcome.DEFINITIVE) {
                throw PaymentDeclinedException(PaymentFailures.providerCodeOf(e), gateway.billingDeclineCategory(PaymentFailures.providerCodeOf(e)))
            }
            throw e
        }

        val claim = try {
            claim(accountId, subscription, gateway, issued)
        } catch (e: PaymentLedgerConflictException) {
            revokeQuietly(gateway, customerKey, issued.token)
            throw PaymentAlreadySubscribedException()
        } catch (e: Exception) {
            revokeQuietly(gateway, customerKey, issued.token)
            throw e
        }
        if (claim.order == null) {
            // 같은 구독을 동시에 시작한 다른 요청이 이겼다 — 그쪽의 빌링키가 쓰인다. 결제사가 같은 빌링키를 또 줬다면 지우지 않는다(이긴 쪽의 카드가 지워진다)
            val winnerToken = claim.subscription.billingToken?.let { runCatching { encryptor.decrypt(it) }.getOrNull() }
            if (winnerToken != issued.token) revokeQuietly(gateway, customerKey, issued.token)
            return claim.subscription
        }

        if (claim.order.provider == PaymentOrders.FREE_PROVIDER) {
            charges.settleFree(claim.order)
            return store.findSubscription(subscriptionId) ?: claim.subscription
        }
        val outcome = charges.charge(gateway, claim.subscription, claim.order, issued.token, PaymentActor.USER)
        val after = store.findSubscription(subscriptionId) ?: claim.subscription
        if (outcome is ChargeOutcome.Declined) {
            throw PaymentDeclinedException(outcome.providerCode, gateway.billingDeclineCategory(outcome.providerCode))
        }
        return after
    }

    /**
     * **등록이 곧 첫 결제**인 결제사(카카오페이)의 `activate`: ① 자리 + 첫 주문(`CONFIRMING`, 결제 키 = 예약 때의 거래 번호, 주문 번호 = 미리 정해진 [PaymentOrderIds.initialOrderId]) + 조회 작업을 커밋하고
     * ② 락 밖에서 [PaymentBillingGateway.registerWithCharge] 를 **한 번** 부르고 ③ 결과로 주문을 확정하면서 같은 트랜잭션에 토큰(암호문) · 카드 표시를 쓴다. 자리에 지면 ②를 부르지 않는다(돈이 안 움직였다).
     */
    private fun activateByFirstCharge(accountId: String, subscription: PaymentSubscription, gateway: PaymentBillingGateway, customerKey: String, providerPayload: Map<String, Any?>): PaymentSubscription {
        val claim = try {
            store.inTransaction { tx ->
                val locked = owned(tx.lockSubscription(subscription.id), accountId) ?: throw PaymentSubscriptionNotFoundException()
                if (locked.status == SubscriptionStatus.ENDED) throw PaymentSubscriptionNotActivatableException()
                if (locked.status != SubscriptionStatus.PENDING || locked.chargeOrderId != null) return@inTransaction Claim(locked, null)
                val planName = plans.find(locked.planCode)?.name ?: locked.planCode
                // 등록이 곧 첫 결제인 결제사는 0원 1회차도 결제사를 거친다(승인 없이 빌링키만 발급 - 카카오페이 공식 문서) - 내부 0원 주문이 아니다
                val order = firstOrder(tx, locked, planName, offProviderWhenFree = false, id = PaymentOrderIds.initialOrderId(locked.id), providerPaymentId = locked.firstTransactionId)
                check(tx.insertOrderIfAbsent(order)) { "the first order of a subscription already exists" }
                reserveCoupon(tx, locked, order)
                lifecycle.chargeStarted(tx, order, PaymentActor.USER, accountId)
                lifecycle.scheduleReconcile(order)
                val claimed = locked.copy(activeKey = accountId, chargeOrderId = order.id, attempt = 1, updatedAt = writer.now())
                tx.updateSubscription(claimed)
                Claim(claimed, order)
            }
        } catch (e: PaymentLedgerConflictException) {
            throw PaymentAlreadySubscribedException()
        }
        val order = claim.order ?: return claim.subscription // 같은 구독을 동시에 시작한 다른 요청이 이겼다 - 결제사 호출은 한 번뿐이다
        val outcome = charges.registerAndCharge(
            gateway, claim.subscription, order,
            RegisterWithChargeRequest(customerKey, providerPayload, claim.subscription.firstTransactionId, order.id, order.orderName, order.amount, IdempotencyKey(order.id), accountId),
            PaymentActor.USER,
        ) { locked, issued ->
            locked.copy(billingToken = encryptor.encrypt(issued.token), cardIssuer = LedgerValues.cardIssuer(issued.card?.issuer), cardLast4 = LedgerValues.cardLast4(issued.card?.last4), updatedAt = writer.now())
        }
        val after = store.findSubscription(subscription.id) ?: claim.subscription
        if (outcome is ChargeOutcome.Declined) throw PaymentDeclinedException(outcome.providerCode, gateway.billingDeclineCategory(outcome.providerCode))
        return after
    }

    /**
     * 할인이 만드는 **모든 단계의 과금액**(0 은 결제사를 안 부르니 제외)이 결제사 최소 금액 이상인가 - 시작 때 한 번 본다. 단건이 주문 만들 때 보는 것과 같은 규칙이다
     * (첫 달만 통과하고 셋째 달 갱신에서야 결제사가 거절해 구독이 `PAST_DUE` 로 떨어지는 일이 없게).
     */
    private fun requireStagesChargeable(billing: PaymentBillingGateway, price: PaymentAmount, stages: List<dev.sumin.skeleton.common.discount.DiscountStage>) {
        val minimum = billing.minimumAmount(price.normalizedCurrency) ?: return
        val scale = try { java.util.Currency.getInstance(price.normalizedCurrency).defaultFractionDigits.coerceAtLeast(0) } catch (_: IllegalArgumentException) { 2 }
        stages.forEach { stage ->
            val charged = price.amount - dev.sumin.skeleton.common.discount.DiscountMath.discount(price.amount, stage, scale)
            if (charged in 1 until minimum) throw PaymentDiscountBelowMinimumException(minimum)
        }
    }

    /**
     * 결제사 없이 끝나는 0원 첫 주문(`free`)을 선점만 해 두고 그 뒤가 끊겼으면(`settleFree` 전에 죽었다) 재전송이 이어서 끝낸다 - 안 그러면 `PENDING` 구독이 자리를 쥔 채 굳고 쿠폰이 영영 `RESERVED` 다.
     * `settleFree` 는 멱등이다(이미 `PAID` 면 지급만 시도한다).
     */
    private fun finishFreeFirstCharge(subscription: PaymentSubscription): PaymentSubscription {
        if (subscription.status != SubscriptionStatus.PENDING) return subscription
        val order = subscription.chargeOrderId?.let(store::findOrder) ?: return subscription
        if (order.provider != PaymentOrders.FREE_PROVIDER || order.status != PaymentOrderStatus.CONFIRMING) return subscription
        charges.settleFree(order)
        return store.findSubscription(subscription.id) ?: subscription
    }

    /** 첫 주문 - 금액은 구독에 복사된 할인의 첫 단계가 정한다(할인이 없으면 플랜 금액). 무료(0원)이고 [offProviderWhenFree] 면 결제사 없는 주문(`free`)으로 만든다. */
    private fun firstOrder(tx: PaymentLedgerTx, locked: PaymentSubscription, planName: String, offProviderWhenFree: Boolean, id: String?, providerPaymentId: String?): PaymentOrder {
        val amount = scheduleDiscounts?.amountFor(tx, locked) ?: locked.amount
        val discounted = amount.amount < locked.amount.amount
        return lifecycle.chargeOrder(
            locked, PaymentOrderKind.SUBSCRIPTION_INITIAL, planName, ids, amount = amount, id = id, providerPaymentId = providerPaymentId,
            originalAmount = locked.amount.amount.takeIf { discounted }, offProvider = offProviderWhenFree && amount.amount == 0L,
        )
    }

    /** 첫 주문 이름으로 쿠폰을 쥐고 내역 줄을 넣는다(구독 줄을 잠근 트랜잭션 안). 시작 때 본 첫 과금액과 달라졌으면 쿠폰이 그사이 바뀐 것이다 - 되돌린다. */
    private fun reserveCoupon(tx: PaymentLedgerTx, locked: PaymentSubscription, order: PaymentOrder) {
        val discount = tx.subscriptionDiscount(locked.id)?.takeIf { it.status == SubscriptionDiscountStatus.ACTIVE } ?: return
        val collection = checkNotNull(couponDiscounts) { "a subscription discount needs the discount collection" }
        val plan = SubscriptionPlan(locked.planCode, locked.planCode, locked.amount, locked.period, 0)
        val reserved = collection.subscriptionOffer(listOf(DiscountClaim(discount.kind, discount.sourceRef)), collection.subscriptionTarget(locked.accountId, plan), holderRef = order.id)
        if (reserved.firstDiscount != locked.amount.amount - order.amount.amount) throw PaymentDiscountUnavailableException()
        val now = writer.now()
        tx.insertOrderDiscount(OrderDiscount(order.id, 1, reserved.kind, reserved.offer.sourceRef, discount.label, reserved.firstDiscount, order.amount.normalizedCurrency, OrderDiscountStatus.RESERVED, now, now))
    }

    /** 이 구독이 지금 과금할 금액 - 재전송이 카드 등록 창을 다시 예약할 때 첫 결제액으로 쓴다. */
    private fun firstAmountOf(subscription: PaymentSubscription): PaymentAmount =
        scheduleDiscounts?.amountFor(subscription, store.findSubscriptionDiscount(subscription.id)) ?: subscription.amount

    /** 「내 구독」에 보이는 할인 - 없거나 끝났으면 null. */
    fun discountOf(subscription: PaymentSubscription): SubscriptionDiscountView? = scheduleDiscounts?.view(subscription)

    /** 해지 예약 — `ACTIVE` → `CANCELING`(기간 끝까지 이용), `PAST_DUE` 는 바로 끝. 다시 눌러도 같은 답. 시작하지 않은 구독은 [PaymentSubscriptionNotCancelableException]. */
    fun cancel(accountId: String, subscriptionId: String): PaymentSubscription {
        asProviderCancel(accountId, subscriptionId)
        return store.inTransaction { tx ->
            val subscription = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            when (subscription.status) {
                SubscriptionStatus.ACTIVE -> lifecycle.save(tx, subscription, SubscriptionStatus.CANCELING) { it.copy(scheduledPlanCode = null, scheduledAmount = null, scheduledPeriod = null) }   // 해지 예약은 갈아타기 예약을 지운다
                SubscriptionStatus.PAST_DUE -> lifecycle.end(tx, subscription, SubscriptionEndReason.USER_CANCELED)
                SubscriptionStatus.CANCELING, SubscriptionStatus.ENDED -> subscription
                SubscriptionStatus.PENDING -> throw PaymentSubscriptionNotCancelableException()
            }
        }
    }

    /**
     * 결제사가 갱신하는 구독이면 **결제사에 먼저 해지를 요청한다**(락 · 트랜잭션 밖) — 요청이 거절되면 던지고 우리 줄은 그대로다. 이미 해지 예약 · 끝난 구독이면 다시 부르지 않는다.
     * 기간 끝 해지는 `ACTIVE` 만, 연체 중(`PAST_DUE`)은 즉시 해지다(수금을 멈춘다). 결과는 웹훅으로도 오고 우리 줄은 곧바로 같은 상태로 간다.
     */
    private fun asProviderCancel(accountId: String, subscriptionId: String) {
        val subscription = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        val providerId = subscription.providerSubscriptionId ?: return
        if (subscription.status != SubscriptionStatus.ACTIVE && subscription.status != SubscriptionStatus.PAST_DUE) return
        store.requireNoOpenTransaction("PaymentSubscriptions.cancel")
        val gateway = hostedGateway(subscription.provider) ?: return
        gateway.cancel(providerId, atPeriodEnd = subscription.status == SubscriptionStatus.ACTIVE)
    }

    private class Claim(val subscription: PaymentSubscription, val order: PaymentOrder?)

    /** ② 자리(`active_key`) 선점 + 빌링키(암호문) + 첫 주문(`CONFIRMING`) + 조회 작업 — 한 트랜잭션. 락을 잡은 줄이 이미 시작됐으면 주문 없이 그 줄을 돌려준다. */
    private fun claim(accountId: String, subscription: PaymentSubscription, gateway: PaymentBillingGateway, issued: BillingIssue): Claim =
        store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscription.id), accountId) ?: throw PaymentSubscriptionNotFoundException()
            if (locked.status == SubscriptionStatus.ENDED) throw PaymentSubscriptionNotActivatableException()
            if (locked.status != SubscriptionStatus.PENDING || locked.chargeOrderId != null) return@inTransaction Claim(locked, null)

            val planName = plans.find(locked.planCode)?.name ?: locked.planCode
            // 첫 기간이 무료면(0원) 결제사를 부르지 않는다 - 카드는 이미 등록했다. 결제사 없이 끝나는 주문(`free`)이라 조회 작업도 없다
            val order = firstOrder(tx, locked, planName, offProviderWhenFree = true, id = null, providerPaymentId = null)
            check(tx.insertOrderIfAbsent(order)) { "the first order of a subscription already exists" }
            reserveCoupon(tx, locked, order)
            lifecycle.chargeStarted(tx, order, PaymentActor.USER, accountId)
            if (order.provider != PaymentOrders.FREE_PROVIDER) lifecycle.scheduleReconcile(order)
            // 상태는 `PENDING` 그대로다(첫 결제가 끝나야 `ACTIVE`) — 자리 · 빌링키 · 과금 주문만 박는다. 자리가 겹치면 저장소가 [PaymentLedgerConflictException] 으로 이 트랜잭션을 되돌린다
            val claimed = locked.copy(
                activeKey = accountId,
                billingToken = encryptor.encrypt(issued.token),
                cardIssuer = LedgerValues.cardIssuer(issued.card?.issuer),
                cardLast4 = LedgerValues.cardLast4(issued.card?.last4),
                chargeOrderId = order.id,
                attempt = 1,
                updatedAt = writer.now(),
            )
            tx.updateSubscription(claimed)
            Claim(claimed, order)
        }

    /**
     * 거래 id 가 없는 줄에 거래를 만들어 박는다 - **결제사 호출은 트랜잭션 밖**이다. 동시에 두 요청이 만들 수 있어, 만든 뒤 줄을 잠그고 아직 비어 있을 때만 박는다:
     * 먼저 박은 쪽이 이기고 진 쪽은 이긴 id 를 돌려준다(진 쪽이 만든 거래는 결제되지 않은 채 결제사에 남는다 - 수용). Paddle API 에는 멱등 키 헤더가 없다.
     */
    private fun ensureTransaction(subscription: PaymentSubscription, creator: PaymentTransactionGateway): PaymentSubscription {
        if (subscription.firstTransactionId != null) return subscription
        store.requireNoOpenTransaction("PaymentSubscriptions.start")
        val transactionId = try {
            creator.createTransaction(TransactionRequest(subscription.planCode, 1, mapOf(PushedEvents.SUBSCRIPTION_KEY to subscription.id, PushedEvents.ACCOUNT_KEY to subscription.accountId)))
        } catch (e: Exception) {
            log.warn("the provider could not create a transaction for a subscription exception={} {}", e.javaClass.simpleName, PaymentFailures.describe(e))
            throw PaymentUnavailableException()
        }
        return store.inTransaction { tx ->
            val locked = checkNotNull(tx.lockSubscription(subscription.id)) { "the subscription disappeared" }
            if (locked.firstTransactionId != null) return@inTransaction locked
            locked.copy(firstTransactionId = transactionId).also(tx::updateSubscription)
        }
    }

    private fun replay(existing: PaymentSubscription, planCode: String, returnOrigin: String? = null): StartedSubscription {
        if (existing.planCode != planCode) throw PaymentIdempotencyConflictException()
        val setup = if (existing.status == SubscriptionStatus.PENDING && existing.chargeOrderId == null) {
            val hosted = hostedGateway(existing.provider)
            if (hosted != null) {
                val ready = (hosted as? PaymentTransactionGateway)?.let { ensureTransaction(existing, it) } ?: existing // 거래 id 가 아직 없는 줄을 다시 열면 그때 만든다
                hosted.subscriptionSetup(HostedSubscriptionRequest(ready.planCode, ready.accountId, ready.id, checkNotNull(ready.customerKey), ready.country, ready.firstTransactionId)) ?: throw PaymentUnavailableException()
            } else {
                val gateway = try {
                    router.gateway(existing.provider, PaymentBillingGateway::class)
                } catch (e: PaymentProviderNotFoundException) {
                    throw PaymentUnavailableException()
                }
                // 창을 저장해 두는 결제사(카카오페이)는 유효시간 안의 재전송에 저장된 첫 창을 그대로 돌려준다 - 예약을 다시 하면 번호가 바뀌어 앞 창의 승인이 실패한다
                val lifetime = gateway.registrationWindowLifetime
                val stored = if (lifetime != null && existing.registrationWindowAt != null && writer.now().isBefore(existing.registrationWindowAt.plus(lifetime))) {
                    RegistrationWindows.decode(existing.registrationWindow, existing.provider, existing.customerKey)
                } else {
                    null
                }
                if (stored != null) return StartedSubscription(existing, stored, replayed = true)
                // 유효시간이 지났거나 저장하지 않는 결제사의 재전송은 다시 예약한다 - 새 번호로 덮는다(앞 창의 결제는 모름이 되어 조회가 닫는 알려진 한계)
                val planName = plans.find(existing.planCode)?.name ?: existing.planCode
                val prepared = try {
                    gateway.prepareBilling(BillingPrepareRequest(existing.id, checkNotNull(existing.customerKey), planName, firstAmountOf(existing), PaymentOrderIds.initialOrderId(existing.id), returnOrigin, existing.accountId))
                } catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    log.warn("the provider could not prepare a card registration {}", PaymentFailures.describe(e))
                    throw PaymentUnavailableException()
                } ?: throw PaymentUnavailableException()
                prepared.reference?.let { reference ->
                    val window = if (lifetime != null) RegistrationWindows.encode(prepared.setup) else null
                    store.inTransaction { tx ->
                        val locked = tx.lockSubscription(existing.id)
                        if (locked != null && locked.status == SubscriptionStatus.PENDING && locked.chargeOrderId == null) {
                            val at = writer.now()
                            tx.updateSubscription(locked.copy(firstTransactionId = reference, registrationWindow = window, registrationWindowAt = window?.let { at }, updatedAt = at))
                        }
                    }
                }
                prepared.setup
            }
        } else {
            null
        }
        return StartedSubscription(existing, setup, replayed = true)
    }

    /** 카드 등록 창을 열 수 있는 결제사 — 없으면 null. */
    private fun billingGateway(amount: PaymentAmount, provider: String? = null): PaymentBillingGateway? =
        try {
            router.resolve(provider, amount, null, PaymentBillingGateway::class)
        } catch (e: PaymentRoutingException) {
            log.warn("no payment provider can register a card: {}", e.message)
            null
        } catch (e: PaymentProviderNotFoundException) {
            log.warn("the payment provider for a card registration is not available: {}", e.providerId)
            null
        } catch (e: PaymentCapabilityUnsupportedException) {
            log.warn("the payment provider cannot register a card: {}", e.message)
            null
        }

    /** 결제사가 갱신까지 하는 결제사 — 그런 능력이 없는 결제사면 null(카드 등록 길로 간다). 없는 · 꺼진 결제사는 [PaymentUnavailableException]. */
    private fun hostedGateway(provider: String): PaymentHostedSubscriptionGateway? =
        try {
            router.gateway(provider, PaymentHostedSubscriptionGateway::class)
        } catch (e: PaymentCapabilityUnsupportedException) {
            null
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentUnavailableException()
        }

    /** 선점에서 진 쪽의 방금 받은 빌링키를 지운다 — 못 지워도 응답은 바꾸지 않는다(토큰은 어디에도 남기지 않으므로 로그에 사실만). */
    private fun revokeQuietly(gateway: PaymentBillingGateway, customerKey: String, token: String) {
        try {
            gateway.revoke(customerKey, token)
        } catch (e: Exception) {
            log.warn("a billing key issued for a lost subscription claim could not be removed exception={}", e.javaClass.simpleName)
        }
    }

    private fun owned(subscription: PaymentSubscription?, accountId: String): PaymentSubscription? = subscription?.takeIf { it.accountId == accountId }

    companion object {
        const val RECENT_ORDERS = 5
    }
}

/**
 * `payment-subscription-revoke` — 끝난 구독의 빌링키를 결제사에서 지운 뒤 줄의 빌링키 · 고객 키를 비운다. 지우기가 실패하면 줄은 그대로 두고 던져 큐가 다시 한다(결제사에 살아 있는 카드를 잊지 않는다).
 * 이미 없는 키는 결제사 능력이 정상으로 돌려준다([PaymentBillingGateway.revoke]). **바깥 트랜잭션 안에서 부르면 던진다** — 결제사를 부르는 코드다.
 */
class SubscriptionRevokeTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val encryptor: TextEncryptor,
    private val writer: PaymentLedgerWriter,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.SUBSCRIPTION_REVOKE

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The billing key removal task")
        val id = run.subjectId
        val subscription = store.findSubscription(id) ?: return
        val cipher = subscription.billingToken ?: return
        if (subscription.status != SubscriptionStatus.ENDED) return // 살아 있는 구독의 빌링키는 지우지 않는다
        val gateway = try {
            router.gateway(subscription.provider, PaymentBillingGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentTaskRetryException("No payment provider is available to remove the billing key of subscription $id")
        } catch (e: PaymentCapabilityUnsupportedException) {
            throw PaymentTaskPermanentException("The payment provider of subscription $id cannot remove billing keys")
        }
        try {
            gateway.revoke(subscription.customerKey.orEmpty(), encryptor.decrypt(cipher))
        } catch (e: Exception) {
            log.warn("billing key removal failed subscription={} attempt={}/{} exception={}", id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw PaymentTaskRetryException("Billing key removal failed for subscription $id")
        }
        store.inTransaction { tx ->
            val locked = tx.lockSubscription(id) ?: return@inTransaction
            if (locked.status == SubscriptionStatus.ENDED && locked.billingToken != null) {
                tx.updateSubscription(locked.copy(billingToken = null, customerKey = null, updatedAt = writer.now()))
            }
        }
    }
}

/**
 * 카드 등록 창 값([BillingSetup])을 구독 줄의 한 칸에 담고 꺼낸다(JSON: 종류 · 모드 · 공개 키 · 창 주소들). 고객 키는 줄에 따로 있다.
 * 칸에 들어가지 않을 만큼 길면 저장하지 않는다(null) - 재전송은 지금처럼 다시 예약한다. 창 주소는 로그에 싣지 않는다.
 */
internal object RegistrationWindows {
    private const val MAX = 1000
    private val mapper = JsonMapper.builder().build()

    fun encode(setup: BillingSetup): String? =
        mapper.writeValueAsString(mapOf("kind" to setup.kind, "testMode" to setup.testMode, "clientKey" to setup.clientKey, "attributes" to setup.attributes)).takeIf { it.length <= MAX }

    /** 못 읽으면 null - 호출한 쪽이 다시 예약한다. */
    fun decode(stored: String?, provider: String, customerKey: String?): BillingSetup? {
        if (stored == null || customerKey == null) return null
        return try {
            val node = mapper.readTree(stored)
            val attributes = node.get("attributes")?.properties()?.associate { it.key to it.value.asString() }.orEmpty()
            BillingSetup(provider, node.get("kind").asString(), node.get("testMode").asBoolean(), node.get("clientKey")?.takeIf { it.isString }?.asString(), customerKey, attributes)
        } catch (_: Exception) {
            null
        }
    }
}
