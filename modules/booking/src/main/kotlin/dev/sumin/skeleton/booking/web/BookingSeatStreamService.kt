package dev.sumin.skeleton.booking.web

import dev.sumin.skeleton.booking.BookingSeatListener
import dev.sumin.skeleton.booking.BookingSeatRelay
import dev.sumin.skeleton.booking.SeatChange
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.context.SmartLifecycle
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 배치도 흐름 — 풀 하나의 자리 사건을 연결마다 흘린다(`notification-sse` 와 같은 방식, 따로 연다).
 * 연결은 **그 풀 하나에만** 묶이고, 구독을 등록한 **뒤에** 첫 사건 `ready` 를 보낸다 — 화면은 `ready` 를 받은 뒤 스냅샷을 읽는다(연결 전 변화는 스냅샷에, 연결 뒤 변화는 흐름에 있어 빠지는 것이 없다).
 *
 * 종료가 시작되면 **웹 서버의 종료 단계보다 먼저** 열린 연결을 모두 닫는다([stop]) — 열린 SSE 는 끝나지 않는 요청이라 안 그러면 서버의 우아한 종료가 30초를 꽉 채워 기다린다.
 * 15초마다 주석 한 줄(프록시 유휴 끊김 막이), 연결 30분이면 서버가 닫는다(화면이 다시 붙고 스냅샷으로 맞춘다). 두 값은 관행 값으로 고정하고 설정으로 열지 않는다.
 * 보내기에 실패한 연결은 그 연결만 닫는다.
 */
class BookingSeatStreamService(
    private val relay: BookingSeatRelay,
    private val heartbeat: Duration = HEARTBEAT,
    private val maxAge: Duration = MAX_AGE,
) : SmartLifecycle {
    private val open: MutableSet<SseEmitter> = ConcurrentHashMap.newKeySet()
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var heartbeatExecutor: ScheduledExecutorService? = null

    /** 지금 열려 있는 연결 수 (시험용) */
    val openConnections: Int get() = open.size

    fun connect(poolId: String): SseEmitter {
        val emitter = StreamEmitter(maxAge.toMillis())
        emitter.subscription = relay.subscribe(poolId, object : BookingSeatListener {
            override fun onChanges(changes: List<SeatChange>) = emitter.sendSeats(changes)

            // 사건을 놓쳤을 수 있다 - 연결을 닫으면 화면이 다시 붙어 스냅샷으로 맞춘다
            override fun onMissed() {
                open -= emitter
                emitter.complete()
            }
        })
        open += emitter
        emitter.onCompletion { drop(emitter) }
        emitter.onTimeout { emitter.complete() }
        emitter.onError { _: Throwable -> drop(emitter) }
        // 종료가 이미 시작됐다면(stop 뒤에 들어온 연결) 아무도 닫아 주지 않아 서버의 우아한 종료를 다시 붙잡는다 — 바로 닫는다
        if (stopped.get()) {
            emitter.complete()
            return emitter
        }
        try {
            emitter.send(SseEmitter.event().name("ready").data(ReadyEvent(poolId)))
        } catch (ex: Exception) {
            fail(emitter, ex)
        }
        return emitter
    }

    private fun drop(emitter: StreamEmitter) {
        open -= emitter
        emitter.closeSubscription()
    }

    @Synchronized
    override fun start() {
        stopped.set(false)
        running.set(true)
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, HEARTBEAT_THREAD).apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::sendHeartbeats, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS) }
    }

    private fun sendHeartbeats() {
        open.toList().forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().comment(""))
            } catch (ex: Exception) {
                fail(emitter, ex)
            }
        }
    }

    private fun fail(emitter: SseEmitter, ex: Exception) {
        open -= emitter
        try {
            emitter.completeWithError(ex)
        } catch (_: Exception) {
            // 이미 끊긴 연결 - 닫으려는 것이므로 무시한다
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

    private inner class StreamEmitter(timeout: Long) : SseEmitter(timeout) {
        private val closed = AtomicBoolean(false)
        var subscription: AutoCloseable? = null

        fun closeSubscription() {
            if (closed.compareAndSet(false, true)) subscription?.close()
        }

        fun sendSeats(changes: List<SeatChange>) {
            try {
                send(SseEmitter.event().name("seats").data(SeatsEvent(changes.map(SeatEvent::of))))
            } catch (ex: Exception) {
                fail(this, ex)
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

    companion object {
        const val HEARTBEAT_THREAD = "booking-seat-stream-heartbeat"
        val HEARTBEAT: Duration = Duration.ofSeconds(15)
        val MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}
