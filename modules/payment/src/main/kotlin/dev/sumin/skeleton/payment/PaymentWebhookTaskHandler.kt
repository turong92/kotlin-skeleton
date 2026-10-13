package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * `payment-webhook` — 웹훅을 받을 때 같은 트랜잭션에서 넣은 작업(스펙 §6.3 · §6.5 ⑤). 수신함 줄이 가리키는 주문을 **결제사에 조회**하고 [PaymentConvergence.converge] 한다
 * (`source=WEBHOOK`, 행위자 `PROVIDER`). 웹훅 본문이 뭐라고 했든 쓰지 않는다 — 몇 번을 어떤 순서로 돌려도 늘 "지금 결제사의 상태"로 맞춘다.
 *
 * - 조회는 락 · 트랜잭션 밖이다 — 바깥 트랜잭션 안에서 부르면 던진다([PaymentLedgerStore.hasOpenTransaction]). 조회를 시작한 시각을 [ConvergeContext.snapshotAt] 에 넘긴다
 *   (환불 확정과 겹친 낡은 스냅샷이 거짓 `REFUND_MISMATCH` 를 찍지 않게). 웹훅의 맞추기는 `PENDING` 환불 줄을 `NOT_APPLIED` 로 닫지 않는다 — 그것은 환불 정산([RefundSettlement])의 일이다.
 * - 줄은 한 트랜잭션에서 맞추기와 함께 `PROCESSED` 가 된다(주문 → 수신함 줄 순서로 잠근다). 결제사가 그 결제를 모르면 `IGNORED`.
 * - 실패 사유는 **우리 코드만**([WebhookCodes]) 줄의 `last_error` 와 예외 메시지에 쓴다 — 결제사 응답 · 예외 메시지 · 본문 조각은 어디에도 쓰지 않는다.
 * - 줄의 모드와 지금 결제사의 모드가 다르면 조회하지 않고 줄을 바로 `DEAD` 로 한다(영구 실패). 승인을 선점한 `CONFIRMING` 주문이면 `MODE_MISMATCH` 도 표시한다 — 선점한 적 없는 `PENDING` 과 끝난 주문에는 표시하지 않는다.
 */
class PaymentWebhookTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val convergence: PaymentConvergence,
    private val writer: PaymentLedgerWriter,
    /** 서명된 웹훅이 곧 사실인 결제사([PaymentPushGateway])의 줄을 옮기는 곳. */
    private val pushed: PaymentPushedEvents? = null,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.WEBHOOK

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The webhook task")
        val id = run.subjectId.toLongOrNull() ?: throw PaymentTaskPermanentException("The webhook task has an unreadable subject")
        val event = store.findWebhookEvent(id) ?: return
        if (event.status.isFinished) return
        if (event.resourceType == PushedEvents.RESOURCE_TYPE) return handlePushed(run, id, event)
        val order = event.orderId?.let(store::findOrder)
        if (order == null) {
            conclude(id, PaymentWebhookStatus.IGNORED, WebhookCodes.UNKNOWN_ORDER)
            return
        }
        val gateway = try {
            router.gateway(order.provider, PaymentLookupGateway::class)
        } catch (_: PaymentProviderNotFoundException) {
            log.warn("no payment provider bean to look up webhook event={} provider={}", id, order.provider)
            throw retry(id, WebhookCodes.PROVIDER_UNAVAILABLE, "No payment provider is available to look up webhook event $id")
        } catch (_: PaymentCapabilityUnsupportedException) {
            fail(id, WebhookCodes.LOOKUP_UNSUPPORTED, dead = true)
            throw PaymentTaskPermanentException("The payment provider of webhook event $id cannot be looked up")
        }
        val context = ConvergeContext(gateway.mode, ConvergeSource.WEBHOOK, PaymentActor.PROVIDER, snapshotAt = writer.now())
        if (gateway.mode != order.providerMode) {
            store.inTransaction { tx ->
                // `CONFIRMING` 에만 표시한다(돈이 움직였을 수 있는 줄 — 조회 작업과 같은 기준). `PENDING` 은 선점한 적이 없어 만료 작업이 조회 없이 닫으니 표시 · 경보 없이 줄만 닫고,
                // 끝난 주문도 줄만 DEAD 로 두고 사람 목록에 올리지 않는다
                tx.lockOrder(order.id)?.takeIf { it.status == PaymentOrderStatus.CONFIRMING }
                    ?.let { convergence.flagReview(tx, it, PaymentReviewReason.MODE_MISMATCH, context) }
                mark(tx, id, PaymentWebhookStatus.DEAD, WebhookCodes.MODE_MISMATCH)
            }
            throw PaymentTaskPermanentException("Webhook event $id belongs to an order created under another payment mode")
        }
        val snapshot = try {
            gateway.lookup(PaymentLookupQuery(providerPaymentId = order.providerPaymentId, orderId = if (order.providerPaymentId == null) order.id else null))
        } catch (e: Exception) {
            log.warn("payment lookup failed for webhook event={} attempt={}/{} exception={}", id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw retry(id, WebhookCodes.LOOKUP_UNAVAILABLE, "Payment lookup is unavailable for webhook event $id")
        }
        try {
            store.inTransaction { tx ->
                val locked = tx.lockOrder(order.id)
                when {
                    locked == null -> mark(tx, id, PaymentWebhookStatus.IGNORED, WebhookCodes.UNKNOWN_ORDER)
                    snapshot == null -> mark(tx, id, PaymentWebhookStatus.IGNORED, WebhookCodes.NO_PAYMENT)
                    else -> {
                        convergence.converge(tx, locked, snapshot, context)
                        mark(tx, id, PaymentWebhookStatus.PROCESSED, null)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("settling webhook event={} failed attempt={}/{} exception={}", id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw retry(id, WebhookCodes.PROCESSING_FAILED, "Settling webhook event $id failed")
        }
    }

    /** 서명이 진위를 보증한 줄 — 결제사에 묻지 않고 저장된 원문을 사건으로 읽어 장부에 옮긴다. 줄을 닫는 일은 같은 트랜잭션이다. */
    private fun handlePushed(run: PaymentTaskRun, id: Long, event: PaymentWebhookEvent) {
        val gateway = try {
            router.gateway(event.provider, PaymentPushGateway::class)
        } catch (_: PaymentProviderNotFoundException) {
            throw retry(id, WebhookCodes.PROVIDER_UNAVAILABLE, "No payment provider is available to apply webhook event $id")
        } catch (_: PaymentCapabilityUnsupportedException) {
            fail(id, WebhookCodes.LOOKUP_UNSUPPORTED, dead = true)
            throw PaymentTaskPermanentException("The payment provider of webhook event $id does not push events")
        }
        if (pushed == null) {
            fail(id, WebhookCodes.LOOKUP_UNSUPPORTED, dead = true)
            throw PaymentTaskPermanentException("No pushed event handler is wired for webhook event $id")
        }
        val pushedEvent = try {
            event.payload?.let(gateway::interpret)?.let { withBuyerCountry(gateway, it) }
        } catch (e: Exception) {
            log.warn("pushed webhook event={} could not be read exception={}", id, e.javaClass.simpleName)
            fail(id, PAYLOAD_UNREADABLE, dead = true)
            throw PaymentTaskPermanentException("Webhook event $id has an unreadable payload")
        }
        if (pushedEvent == null) {
            conclude(id, PaymentWebhookStatus.IGNORED, PaymentPushedEvents.NOT_HANDLED)
            return
        }
        try {
            try {
                store.inTransaction { tx ->
                    val reason = pushed.apply(tx, gateway, pushedEvent)
                    mark(tx, id, if (reason == null) PaymentWebhookStatus.PROCESSED else PaymentWebhookStatus.IGNORED, reason)
                }
            } catch (e: PaymentLedgerConflictException) {
                // 이 계정에 이미 살아 있는 구독이 있다 - 재시도해도 같다. 결제는 기록해 「확인 필요」로 남기고 결제사 구독은 해지한다(조용히 죽어 결제사가 계속 과금하지 않게)
                store.inTransaction { tx -> mark(tx, id, PaymentWebhookStatus.IGNORED, pushed.applyConflicted(tx, gateway, pushedEvent)) }
            }
        } catch (e: Exception) {
            log.warn("applying pushed webhook event={} failed attempt={}/{} exception={}", id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw retry(id, WebhookCodes.PROCESSING_FAILED, "Applying webhook event $id failed")
        }
    }

    /** 결제 본문에 없는 구매자 주소의 나라를 락 · 트랜잭션 밖에서 묻는다 - 실패해도 결제는 그대로 반영하고 나라만 모른다. */
    private fun withBuyerCountry(gateway: PaymentPushGateway, event: PushedPaymentEvent): PushedPaymentEvent {
        if (event !is PushedPayment || gateway !is PaymentBuyerCountryGateway) return event
        val customer = event.customerId ?: return event
        val address = event.addressId ?: return event
        val country = try {
            gateway.buyerCountry(customer, address)
        } catch (e: Exception) {
            log.warn("could not look up the buyer address country exception={}", e.javaClass.simpleName)
            null
        }
        return event.copy(buyerCountry = country)
    }

    /**
     * 웹훅 작업이 죽었다 — 줄이 아직 끝나지 않았으면(`RECEIVED` · `FAILED`) `DEAD`. 앞선 시도의 사유가 있으면 그대로 두고 없으면 [WebhookCodes.RETRIES_EXHAUSTED].
     * 마지막 시도에 표시하는 길에 기대지 않는다: 워커가 죽어 멈춘 채 시도를 다 쓴 작업은 처리기를 거치지 않고 DEAD 가 된다. 끝난 줄 · 이미 `DEAD` 인 줄 · 없는 줄은 건드리지 않는다 — 두 번 불려도 한 번.
     */
    override fun onDead(run: PaymentTaskRun) {
        val id = run.subjectId.toLongOrNull() ?: return
        store.inTransaction { tx ->
            val event = tx.lockWebhookEvent(id) ?: return@inTransaction
            if (event.status == PaymentWebhookStatus.RECEIVED || event.status == PaymentWebhookStatus.FAILED) {
                tx.updateWebhookEvent(event.copy(status = PaymentWebhookStatus.DEAD, lastError = event.lastError ?: WebhookCodes.RETRIES_EXHAUSTED))
            }
        }
    }

    /** 이번 시도가 실패했다 — 줄에 우리 코드를 남기고 작업이 다시 하도록 던질 예외를 돌려준다(줄을 못 적어도 던진다). */
    private fun retry(id: Long, code: String, message: String): PaymentTaskRetryException {
        runCatching { fail(id, code, dead = false) }.onFailure { log.warn("could not record the failure of webhook event={} exception={}", id, it.javaClass.simpleName) }
        return PaymentTaskRetryException(message)
    }

    private companion object {
        const val PAYLOAD_UNREADABLE = "PAYLOAD_UNREADABLE"
    }

    private fun fail(id: Long, code: String, dead: Boolean) {
        store.inTransaction { tx -> mark(tx, id, if (dead) PaymentWebhookStatus.DEAD else PaymentWebhookStatus.FAILED, code) }
    }

    private fun conclude(id: Long, status: PaymentWebhookStatus, code: String?) {
        store.inTransaction { tx -> mark(tx, id, status, code) }
    }

    /**
     * 줄을 [status] 로 적는다 — 락 안에서 지금 줄을 다시 읽고, 이미 끝난 줄은 그 결과를 지키고(먼저 끝난 쪽이 이긴다) `DEAD` 인 줄은 `FAILED` 로 되돌리지 않는다.
     * `PROCESSED` 는 이전의 실패 코드를 지운다.
     */
    private fun mark(tx: PaymentLedgerTx, id: Long, status: PaymentWebhookStatus, code: String?) {
        val event = tx.lockWebhookEvent(id) ?: return
        if (event.status.isFinished) return
        if (event.status == PaymentWebhookStatus.DEAD && status == PaymentWebhookStatus.FAILED) return
        tx.updateWebhookEvent(event.copy(status = status, lastError = code, processedAt = if (status.isFinished) writer.now() else event.processedAt))
    }
}
