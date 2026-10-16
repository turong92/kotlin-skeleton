package dev.sumin.skeleton.payment

import java.security.MessageDigest
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * 웹훅 수신함의 입구(스펙 §6.5) — 주소 확인 · 줄 넣기 + 처리 작업 넣기 · 운영자의 다시 처리와 목록.
 *
 * **받은 것을 믿지 않는다**: 본문의 상태 · 금액은 어디에도 쓰지 않는다. 결제사 모듈([PaymentWebhookGateway.parse])이 결제 키 · 주문 번호만 꺼내 주고,
 * 사실은 처리 작업([PaymentWebhookTaskHandler])이 결제사에 조회해서 얻는다. 모르는 주문은 저장하지 않는다. 수신은 DB 쓰기 한 번(줄 + 작업, 한 트랜잭션)이고 결제사를 부르지 않는다 —
 * 토스는 10초 안에 200 을 받지 못하면 다시 보낸다.
 */
class PaymentWebhookInbox(
    private val store: PaymentLedgerStore,
    private val tasks: PaymentTasks,
    private val router: PaymentProviderRouter,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    /** 켜져 있으면 수신이 커밋된 뒤 같은 요청 안에서 처리를 한 번 바로 해 본다(`webhook.inline`). null 이면 지금까지처럼 작업만 넣는다. */
    private val inline: PaymentWebhookInline? = null,
) {
    /**
     * 주소 확인 — 컨트롤러가 본문을 읽기 **전에** 부른다. 모르는 결제사 · 웹훅 능력이 없는 결제사 · 꺼진 결제사 · (서명 없는 결제사에서) 비밀 조각이 없거나 틀림은
     * 모두 같은 [PaymentWebhookNotFoundException] 이다(어느 쪽인지 알리지 않는다). 비밀 조각 비교는 상수 시간이고, 설정된 조각이 비어 있으면 그 결제사의 경로는 닫혀 있다.
     * 서명이 있는 결제사는 조각을 보지 않는다 — 진위는 그 모듈이 원문 바이트로 서명을 확인한다.
     */
    fun admit(provider: String, token: String?): PaymentWebhookGateway {
        val gateway = try {
            router.gateway(provider, PaymentWebhookGateway::class)
        } catch (_: PaymentProviderNotFoundException) {
            throw PaymentWebhookNotFoundException()
        } catch (_: PaymentCapabilityUnsupportedException) {
            throw PaymentWebhookNotFoundException()
        }
        if (!gateway.signed && !WebhookPathToken.matches(properties.webhook.pathToken, token)) throw PaymentWebhookNotFoundException()
        return gateway
    }

    /**
     * 웹훅 한 건을 받는다. 진위가 틀리거나 읽을 수 없으면 [PaymentWebhookRejectedException](저장 없음). 아는 주문의 결제 이벤트만 줄이 된다 — 줄 넣기와 처리 작업 넣기는 한 트랜잭션이다.
     * 같은 `(provider, event_key)` 가 다시 오면 `deliveries` 만 올린다. 줄이 `FAILED` · `DEAD` 일 때만 — 그리고 이번 전달을 센 `deliveries` 가 `webhook.redelivery-requeue-limit` 이하일 때만 —
     * 새 작업을 바로 넣고 줄을 `RECEIVED` 로 되돌린다: 결제사의 재전송은 자동 재시도의 대기 · 시도 예산과 무관하다(스펙 §3 의 11: 원인을 고치고 다시 보냈는데 대기 시간에 삼켜지면 안 된다).
     * 실패한 작업은 백오프에 살아 있어서 상한 없이 얹으면 줄당 작업이 끝없이 늘므로 상한을 넘으면 `DUPLICATE` 다(운영자의 [retry] 는 상한과 무관).
     * `RECEIVED` 는 작업이 이미 기다리는 중이라, `PROCESSED` · `IGNORED` 는 끝났으니 아무것도 하지 않는다 — 같은 배달이 동시에 열여섯 번 와도 줄 하나 · 작업 하나다.
     *
     * **새 이벤트 키는 주문 단위 창 상한을 탄다**(`webhook.per-order-limit`): 호출자가 정하는 전달 id 로 줄 · 작업 · 결제사 조회를 마음대로 늘리지 못하게 하는 제동이다.
     * 세기와 넣기는 **주문 줄 락 안에서 한 트랜잭션**이라(락 순서: 주문 → 수신함 줄) 인스턴스가 여럿이어도 정확히 상한이다. 상한에 걸린 신호는 **버리지 않는다** —
     * 주문당 창마다 **표식 줄 하나**(`event_key = limited:<주문 id>:<창 번호>`, [WebhookMarkers])와 **창 끝으로 미룬 처리 작업 하나**를 건다. 그 작업이 결제사에 조회해 맞추므로,
     * 상한에 걸린 진짜 신호(예: 결제사 콘솔의 취소)도 창이 끝나면 장부에 닿는다. 표식 줄은 상한 계산에 세지 않는다.
     */
    fun receive(gateway: PaymentWebhookGateway, delivery: WebhookDelivery): WebhookReceipt {
        val queued = QueuedNow()
        val receipt = receiveOnce(gateway, delivery, queued)
        logReceipt(receipt)
        // 커밋된 뒤에만 - 처리 작업이 방금 넣은 줄을 보려면 수신 트랜잭션이 끝나 있어야 한다(바깥 트랜잭션 안이면 아직 아니다)
        queued.eventId?.takeIf { !store.hasOpenTransaction() }?.let { inline?.attempt(it) }
        return receipt
    }

    /** 이번 수신이 **바로 돌 수 있게** 넣은 처리 작업의 줄 id(대기 시각이 있는 작업은 아니다). 인라인 시도의 대상. */
    private class QueuedNow {
        var eventId: Long? = null
    }

    /** 결과의 종류만 한 줄 — 식별자(주문 id · 결제 키 · 전달 id · 주소 조각)는 싣지 않는다. 토스 본문 가정이 틀리면 전부 `UNKNOWN_ORDER` 로 떨어지는 것을 실측에서 볼 수 있다. */
    private fun logReceipt(receipt: WebhookReceipt) {
        if (receipt == WebhookReceipt.LIMITED) {
            val limit = properties.webhook.perOrderLimit
            log.warn("payment webhook result={} reason=per-order-limit capacity={} window={}", receipt, limit.capacity, limit.window)
        } else {
            log.info("payment webhook result={}", receipt)
        }
    }

    private fun receiveOnce(gateway: PaymentWebhookGateway, delivery: WebhookDelivery, queued: QueuedNow): WebhookReceipt {
        val envelope = gateway.parse(delivery)
        val resourceType = envelope.resourceType.trim().uppercase()
        if (resourceType == PushedEvents.RESOURCE_TYPE) return receivePushed(gateway, delivery, envelope, queued)
        if (resourceType != PAYMENT) {
            // 무엇이 안 받아졌는지 알 수 있게 이벤트 종류만 남긴다(모양이 이상하면 생략 - 식별자는 싣지 않는다)
            log.info("payment webhook ignored eventType={}", envelope.eventType.takeIf { EVENT_TYPE.matches(it) } ?: "-")
            return WebhookReceipt.UNSUPPORTED
        }
        val eventKey = envelope.eventKey.takeIf { EVENT_KEY.matches(it) && !it.startsWith(WebhookMarkers.KEY_PREFIX) }
        val eventType = envelope.eventType.takeIf { EVENT_TYPE.matches(it) }
        val resourceId = envelope.resourceId.takeIf { EVENT_KEY.matches(it) }
        if (eventKey == null || eventType == null || resourceId == null) throw PaymentWebhookRejectedException()

        val provider = gateway.providerId.trim().lowercase()
        val order = envelope.orderId?.takeIf { ORDER_ID.matches(it) }?.let(store::findOrder)?.takeIf { it.provider == provider }
            ?: store.findOrderByProviderPayment(provider, resourceId)
            ?: return WebhookReceipt.UNKNOWN_ORDER

        // 줄이 가리키는 결제는 **우리 주문의 것**이다 — 본문이 댄 결제 키로 센 창을 바꿔 상한을 비켜 가지 못하게 한다. 아직 결제 키가 없는 주문은 주문 id 로 센다
        val ownResource = order.providerPaymentId ?: "order:${order.id}"

        return store.inTransaction { tx ->
            // 주문 줄 락 먼저(락 순서: 주문 → 수신함 줄). 새 줄을 넣는 길은 모두 이 락 뒤라서, 아래의 세기와 넣기 사이에 같은 주문의 다른 수신이 끼어들지 못한다.
            tx.lockOrder(order.id) ?: return@inTransaction WebhookReceipt.UNKNOWN_ORDER
            val now = writer.now()
            val limit = properties.webhook.perOrderLimit
            if (store.findWebhookEventByKey(provider, eventKey) == null &&
                tx.countWebhookEventsOfOrder(order.id, PAYMENT, now.minus(limit.window)) >= limit.capacity
            ) {
                markLimited(tx, provider, order, ownResource, now)
                return@inTransaction WebhookReceipt.LIMITED
            }

            val candidate = PaymentWebhookEvent(
                id = 0, provider = provider, eventKey = eventKey, eventType = eventType, resourceType = resourceType, resourceId = ownResource,
                orderId = order.id, payload = envelope.storedPayload, payloadHash = sha256Hex(delivery.body), status = PaymentWebhookStatus.RECEIVED,
                deliveries = 0, lastError = null, receivedAt = now, processedAt = null,
            )
            val recorded = tx.recordWebhookDelivery(candidate)
            val event = recorded.event
            when {
                recorded.first -> {
                    enqueueNow(queued, event.id)
                    WebhookReceipt.STORED
                }
                // 끝난 줄(PROCESSED · IGNORED)은 할 일이 없고, RECEIVED 는 작업이 이미 기다리는 중이다 — 전달 횟수만 올랐다
                event.status.isFinished || event.status == PaymentWebhookStatus.RECEIVED -> WebhookReceipt.DUPLICATE
                // FAILED · DEAD 인데 재전송이 이미 상한만큼 작업을 얹었다 — 더 얹지 않는다(백오프에 사는 실패한 작업 위에 계속 쌓이지 않게)
                event.deliveries > properties.webhook.redeliveryRequeueLimit -> WebhookReceipt.DUPLICATE
                else -> {
                    // FAILED · DEAD: 새 작업을 바로 넣고 줄을 RECEIVED 로 되돌린다 — 다음 재전송은 "기다리는 작업이 있다"를 본다(재전송이 연달아 와도 줄마다 작업 하나)
                    tx.updateWebhookEvent(event.copy(status = PaymentWebhookStatus.RECEIVED))
                    enqueueNow(queued, event.id)
                    WebhookReceipt.REQUEUED
                }
            }
        }
    }

    /**
     * 서명된 본문이 곧 사실인 결제사의 사건 — 주문이 아직 없을 수 있어(구독의 갱신 결제) 주문 단위 상한은 없고, 서명 · 요청 수 한도가 제동이다.
     * 줄 하나 + 처리 작업 하나(한 트랜잭션), 같은 사건이 다시 와도 줄은 하나다. 원문은 서명을 통과한 것만 줄에 남는다.
     */
    private fun receivePushed(gateway: PaymentWebhookGateway, delivery: WebhookDelivery, envelope: WebhookEnvelope, queued: QueuedNow): WebhookReceipt {
        val eventKey = envelope.eventKey.takeIf { EVENT_KEY.matches(it) && !it.startsWith(WebhookMarkers.KEY_PREFIX) }
        val eventType = envelope.eventType.takeIf { EVENT_TYPE.matches(it) }
        val resourceId = envelope.resourceId.takeIf { EVENT_KEY.matches(it) }
        val payload = envelope.storedPayload
        if (eventKey == null || eventType == null || resourceId == null || payload == null) throw PaymentWebhookRejectedException()
        val provider = gateway.providerId.trim().lowercase()
        return store.inTransaction { tx ->
            val candidate = PaymentWebhookEvent(
                id = 0, provider = provider, eventKey = eventKey, eventType = eventType, resourceType = PushedEvents.RESOURCE_TYPE, resourceId = resourceId,
                orderId = null, payload = payload, payloadHash = sha256Hex(delivery.body), status = PaymentWebhookStatus.RECEIVED,
                deliveries = 0, lastError = null, receivedAt = writer.now(), processedAt = null,
            )
            val recorded = tx.recordWebhookDelivery(candidate)
            val event = recorded.event
            when {
                recorded.first -> {
                    enqueueNow(queued, event.id)
                    WebhookReceipt.STORED
                }
                event.status.isFinished || event.status == PaymentWebhookStatus.RECEIVED -> WebhookReceipt.DUPLICATE
                event.deliveries > properties.webhook.redeliveryRequeueLimit -> WebhookReceipt.DUPLICATE
                else -> {
                    tx.updateWebhookEvent(event.copy(status = PaymentWebhookStatus.RECEIVED))
                    enqueueNow(queued, event.id)
                    WebhookReceipt.REQUEUED
                }
            }
        }
    }

    /**
     * 상한에 걸린 신호를 **조회 한 번**으로 미룬다: 주문당 창마다 표식 줄 하나(유니크 `(provider, event_key)` 가 여러 대에서도 하나로 묶는다 — 줄을 만든 전달만 작업을 넣는다) +
     * 창 끝에 도는 처리 작업 하나. `deliveries` 는 그 창에서 상한에 걸린 신호의 수다. 표식이 이미 처리됐는데(`RECEIVED` 가 아님) 같은 창에서 신호가 또 걸리면 표식을 `RECEIVED` 로 되돌리고 창 끝 작업을 다시 건다. 창 번호는 받은 시각을 창 길이로 나눈 몫이다(고정 구간 — 세는 창은 미끄러지지만 표식은 구간마다 하나).
     */
    private fun markLimited(tx: PaymentLedgerTx, provider: String, order: PaymentOrder, ownResource: String, now: Instant) {
        val windowMs = properties.webhook.perOrderLimit.window.toMillis().coerceAtLeast(1)
        val window = now.toEpochMilli() / windowMs
        val key = WebhookMarkers.key(order.id, window)
        val marker = PaymentWebhookEvent(
            id = 0, provider = provider, eventKey = key, eventType = WebhookMarkers.EVENT_TYPE, resourceType = WebhookMarkers.RESOURCE_TYPE, resourceId = ownResource,
            orderId = order.id, payload = null, payloadHash = sha256Hex(key.toByteArray()), status = PaymentWebhookStatus.RECEIVED,
            deliveries = 0, lastError = null, receivedAt = now, processedAt = null,
        )
        val recorded = tx.recordWebhookDelivery(marker)
        val windowEnd = Instant.ofEpochMilli((window + 1) * windowMs)
        when {
            recorded.first -> enqueue(recorded.event.id, runAt = windowEnd)
            // 표식이 RECEIVED 가 아니다 = 이 창 안에서 이미 처리됐다(운영자의 「다시 처리」 · 시계가 어긋난 인스턴스의 작업). 그 뒤에 상한에 걸린 이 신호는
            // 처리된 조회에 없었을 수 있으니 표식을 되돌리고 창 끝 작업을 다시 건다. RECEIVED 인 동안 더 걸린 신호는 그 대기 작업이 맡아 작업을 더 걸지 않는다
            recorded.event.status != PaymentWebhookStatus.RECEIVED -> {
                tx.updateWebhookEvent(recorded.event.copy(status = PaymentWebhookStatus.RECEIVED, processedAt = null))
                enqueue(recorded.event.id, runAt = windowEnd)
            }
        }
    }

    /**
     * 운영자의 「다시 처리」(HTTP 는 B7). 없는 id → [PaymentWebhookEventNotFoundException]. 이미 `PROCESSED` 인 줄 → 줄 그대로(아무 일도 없다).
     * 그 밖(`RECEIVED` · `FAILED` · `DEAD` · `IGNORED`)은 줄을 `RECEIVED` 로 되돌리고 새 작업을 바로 넣은 뒤 그 줄을 돌려준다(`last_error` 는 다음 결과가 덮을 때까지 남는다).
     * `RECEIVED` 도 포함이다 — 작업이 죽었는데 줄 표시가 안 찍힌 막다른 길(DEAD 커밋과 알림 사이에 프로세스가 죽음 · 알림이 DB 오류로 실패)과, 긴 백오프에 작업이 있는 줄을 운영자가 당기는 길이다.
     * 운영자는 인증된 사람이라 연타해도 막지 않는다(작업은 멱등이다) · 재전송 상한([PaymentProperties.Webhook.redeliveryRequeueLimit])과도 무관하다.
     */
    fun retry(id: Long): PaymentWebhookEvent =
        store.inTransaction { tx ->
            val event = tx.lockWebhookEvent(id) ?: throw PaymentWebhookEventNotFoundException()
            if (event.status == PaymentWebhookStatus.PROCESSED) return@inTransaction event
            val queued = event.copy(status = PaymentWebhookStatus.RECEIVED, processedAt = null)
            tx.updateWebhookEvent(queued)
            enqueue(id)
            queued
        }

    /** 수신함 목록, 최신순. [size] 는 1~100 으로 맞춘다. 다음 쪽이 있으면 [WebhookEventPage.next] 가 그 커서다. */
    fun list(status: PaymentWebhookStatus?, before: WebhookEventCursor?, size: Int): WebhookEventPage {
        val limit = size.coerceIn(1, MAX_PAGE)
        val rows = store.listWebhookEvents(status, before, limit + 1)
        val page = rows.take(limit)
        return WebhookEventPage(page, if (rows.size > limit) page.last().let { WebhookEventCursor(it.receivedAt, it.id) } else null)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private fun enqueueNow(queued: QueuedNow, eventId: Long) {
        enqueue(eventId)
        queued.eventId = eventId
    }

    private fun enqueue(eventId: Long, runAt: Instant? = null) = tasks.enqueue(PaymentTask(PaymentTaskTypes.WEBHOOK, eventId.toString(), runAt = runAt))

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PAYMENT = "PAYMENT"
        const val MAX_PAGE = 100
        val EVENT_KEY = Regex("[A-Za-z0-9._:-]{1,200}")
        val EVENT_TYPE = Regex("[A-Za-z0-9_.-]{1,64}")
        val ORDER_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** 주소의 비밀 조각 — 비교와 세기 검사. 값은 어디에도 찍지 않는다. */
internal object WebhookPathToken {
    const val MIN_LENGTH = 24
    const val MIN_DISTINCT = 8
    private val URL_SAFE = Regex("[A-Za-z0-9._~-]+")

    /** 상수 시간 비교(길이도 새지 않게 해시끼리 견준다). 설정된 조각이 비어 있으면 늘 false — 비밀이 없는 경로는 닫혀 있다. */
    fun matches(configured: String, given: String?): Boolean {
        val expected = configured.trim()
        if (expected.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(digest.digest(expected.toByteArray()), digest.digest((given ?: "").toByteArray()))
    }

    fun isEmpty(configured: String): Boolean = configured.isBlank()

    /** 비어 있지 않은데 약한가: 짧다 · 주소에 못 쓰는 글자 · 글자 가짓수가 적다. */
    fun isWeak(configured: String): Boolean {
        val token = configured.trim()
        return token.isNotEmpty() && (token.length < MIN_LENGTH || !URL_SAFE.matches(token) || token.toSet().size < MIN_DISTINCT)
    }
}
