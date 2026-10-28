package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.context.SmartLifecycle
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 열린 SSE 연결은 끝나지 않는 요청이라 서버의 우아한 종료(graceful shutdown)가 「진행 중인 요청이 끝나기를」 `timeout-per-shutdown-phase`(30초) 꽉 채워 기다린다 —
 * 놀고 있는 서버도 재시작마다 30초가 걸린다. 그래서 종료가 시작되면 **웹 서버의 종료 단계보다 먼저** 열린 연결을 모두 닫는다([stop], 위상 `SmartLifecycle.DEFAULT_PHASE` = 가장 먼저 멈춘다).
 * 클라이언트(`EventSource`)는 알아서 다시 붙는다 — 배포에서는 다른 인스턴스에 붙는다.
 *
 * `heartbeat` 가 켜져 있으면 [start] 가 데몬 스레드 하나를 띄워 열린 연결마다 주기적으로 SSE 주석(`:` 한 줄)을 보낸다. 보내기에 실패한 연결은 그 연결만 닫는다. [stop] 이 스레드도 멈춘다.
 */
class NotificationSseService(
    private val subscriptionRegistry: NotificationSubscriptionRegistry,
    private val properties: NotificationSseProperties,
) : SmartLifecycle {
    private val open: MutableSet<SseEmitter> = ConcurrentHashMap.newKeySet()
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private var heartbeatExecutor: ScheduledExecutorService? = null

    /** 지금 열려 있는 연결 수 (시험용) */
    internal val openConnections: Int get() = open.size

    @Synchronized
    override fun start() {
        stopped.set(false)
        running.set(true)
        val interval = properties.heartbeat ?: return
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, HEARTBEAT_THREAD).apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(::sendHeartbeats, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    /** 열린 연결마다 주석 한 줄. 실패한 연결은 [open] 에서 빠지고 닫힌다 - 한 연결의 실패가 다른 연결의 주기를 막지 않는다 */
    private fun sendHeartbeats() {
        open.toList().forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().comment(""))
            } catch (ex: Exception) {
                open -= emitter
                try {
                    emitter.completeWithError(ex)
                } catch (_: Exception) {
                    // 이미 끊긴 연결 - 닫으려는 것이므로 무시한다
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
        stopped.set(true)
        if (!running.compareAndSet(true, false)) return
        // 복사본을 돈다 — complete() 가 완료 콜백으로 [open] 에서 자기를 뺀다
        open.toList().forEach { emitter ->
            try {
                emitter.complete()
            } catch (_: Exception) {
                // 이미 끊긴 연결 — 닫으려는 것이므로 무시한다
            }
        }
        open.clear()
    }

    override fun isRunning(): Boolean = running.get()

    /** 웹 서버의 종료 단계(`SmartLifecycle.DEFAULT_PHASE` 보다 작다)보다 먼저 멈춘다 */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE

    /**
     * [recipientId] 는 연결한 호출자(`Principal.name`)다. 받는 사람이 정해진 알림(`recipientIds` 가 비어 있지 않음)은
     * 그 사람에게만 흘리고, 받는 사람이 없는 알림(전체 공지)은 모두에게 흘린다. 인증 없는 연결(공개 엔드포인트)은 후자만 받는다.
     */
    fun connect(topics: Collection<String>, recipientId: String? = null): SseEmitter {
        val normalizedTopics = topics.mapNotNullTo(linkedSetOf()) { topic ->
            topic.trim().takeIf { it.isNotBlank() }
        }
        val subscriptionRef = AtomicReference<NotificationSubscription>()
        val emitter = SubscriptionClosingSseEmitter(properties.timeout.toMillis()) {
            subscriptionRef.get()?.close()
        }
        val subscription = subscriptionRegistry.subscribe(normalizedTopics) { event ->
            if (isVisibleTo(event, recipientId)) emitter.sendNotification(event)
        }
        subscriptionRef.set(subscription)

        open += emitter
        // 종료가 이미 시작됐다면(stop 뒤에 들어온 연결) 아무도 닫아 주지 않아 서버의 우아한 종료를 다시 붙잡는다 — 바로 닫는다
        if (stopped.get()) emitter.complete()
        emitter.onCompletion {
            open -= emitter
            emitter.closeSubscription()
        }
        emitter.onTimeout {
            emitter.closeSubscription()
            emitter.complete()
        }
        emitter.onError { _: Throwable ->
            open -= emitter
            emitter.closeSubscription()
        }

        emitter.sendConnectedEvent(normalizedTopics)
        return emitter
    }

    companion object {
        const val HEARTBEAT_THREAD = "notification-sse-heartbeat"

        fun isVisibleTo(event: NotificationEvent, recipientId: String?): Boolean =
            event.recipientIds.isEmpty() || (recipientId != null && recipientId in event.recipientIds)
    }

    private fun SseEmitter.sendNotification(event: NotificationEvent) {
        try {
            send(
                SseEmitter.event()
                    .id(event.id)
                    .name(event.type)
                    .data(event),
            )
        } catch (ex: Exception) {
            completeWithError(ex)
        }
    }

    private fun SseEmitter.sendConnectedEvent(topics: Set<String>) {
        try {
            send(
                SseEmitter.event()
                    .name("connected")
                    .data(NotificationSseConnectedEvent(topics = topics)),
            )
        } catch (ex: Exception) {
            completeWithError(ex)
        }
    }
}

data class NotificationSseConnectedEvent(
    val topics: Set<String>,
    val connectedAt: Instant = Instant.now(),
)

private class SubscriptionClosingSseEmitter(
    timeout: Long,
    private val onClose: () -> Unit,
) : SseEmitter(timeout) {
    private val closed = AtomicBoolean(false)

    fun closeSubscription() {
        if (closed.compareAndSet(false, true)) {
            onClose()
        }
    }

    override fun complete() {
        closeSubscription()
        super.complete()
    }

    override fun completeWithError(ex: Throwable) {
        closeSubscription()
        super.completeWithError(ex)
    }
}
