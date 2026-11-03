package dev.sumin.skeleton.booking

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean

/**
 * 풀에 들어가기 전 문 — 자리를 더할 때마다, 그리고 배치도 흐름을 열 때 부른다. 막으려면 던진다(예: 대기실 입장권이 없으면 `403 WAITING_ROOM.ENTRY_REQUIRED`).
 * 기본은 **늘 허용**이다 — 대기열 없이 작게 쓰는 모드는 아무것도 붙이지 않는 것이다. **정확성(이중 판매 0)은 이 문과 무관하다** — 문을 빼도 자리는 한 사람에게만 간다.
 */
fun interface BookingAdmission {
    fun check(pool: BookingPool, accountId: String, entryToken: String?)
}

object AllowAllBookingAdmission : BookingAdmission {
    override fun check(pool: BookingPool, accountId: String, entryToken: String?) = Unit
}

fun interface BookingSeatListener {
    fun onChanges(changes: List<SeatChange>)

    /** 이 인스턴스가 사건을 놓쳤을 수 있다(중계가 끊겼다 다시 붙었다) - 연결은 스스로 닫고 화면이 다시 붙어 스냅샷으로 맞춘다 */
    fun onMissed() {}
}

/**
 * 돈만 받고 자리를 못 준 주문의 환불 작업 넣기 - **부르는 쪽 트랜잭션에 참여한다**(보류 줄이 커밋되면 작업도 있고 롤백되면 함께 사라진다).
 * `payment` 와 `job-queue-jdbc` 가 있으면 `booking-refund` 작업을 넣는 구현이 저절로 선다.
 */
fun interface BookingRefundQueue {
    fun enqueue(orderId: String, reason: String)
}

/**
 * 자리 사건의 중계 — 자리 상태를 바꾼 트랜잭션이 **커밋된 뒤에** [publish] 를 부른다. 받는 쪽([subscribe])은 배치도 흐름 연결들이다.
 * 여러 대를 잇는 구현(Redis pub/sub 등)은 이 자리를 바꾼다. 보내기 실패는 업무를 실패시키지 않는다 — 놓친 사건은 화면이 다시 붙을 때 스냅샷으로 맞춘다.
 */
interface BookingSeatRelay {
    fun publish(poolId: String, changes: List<SeatChange>)

    /** 이 인스턴스에서 그 풀의 사건을 받는다. 돌려준 것을 닫으면 끝난다 */
    fun subscribe(poolId: String, listener: BookingSeatListener): AutoCloseable
}

/**
 * 기본 중계 — 이 인스턴스의 그 풀 연결들에만 넘긴다. 보내기는 요청 스레드가 아니라 **실행기 하나**에서 한다(클릭 응답이 보는 사람 수에 묶이지 않게, 사건 순서는 보낸 순서 그대로).
 * 한 연결의 실패가 다른 연결을 막지 않는다.
 */
class LocalBookingSeatRelay : BookingSeatRelay, DisposableBean {
    private val log = LoggerFactory.getLogger(LocalBookingSeatRelay::class.java)
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<BookingSeatListener>>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, THREAD).apply { isDaemon = true } }

    override fun publish(poolId: String, changes: List<SeatChange>) {
        if (changes.isEmpty()) return
        try {
            executor.execute { deliver(poolId, changes) }
        } catch (_: RejectedExecutionException) {
            // 종료 중 — 연결도 곧 닫힌다
        }
    }

    override fun subscribe(poolId: String, listener: BookingSeatListener): AutoCloseable {
        listeners.computeIfAbsent(poolId) { CopyOnWriteArraySet() }.add(listener)
        return AutoCloseable { listeners[poolId]?.remove(listener) }
    }

    /** 이 스레드에서 바로 넘긴다 - 다른 인스턴스에서 온 사건이 부른다 */
    internal fun deliver(poolId: String, changes: List<SeatChange>) {
        listeners[poolId]?.forEach { listener ->
            try {
                listener.onChanges(changes)
            } catch (ex: Exception) {
                log.warn("booking seat listener failed: {}", ex.toString())
            }
        }
    }

    /** 사건을 놓쳤을 수 있다 - 이 인스턴스의 모든 연결에 알린다 */
    internal fun missed() {
        listeners.values.forEach { set ->
            set.forEach { listener ->
                try {
                    listener.onMissed()
                } catch (ex: Exception) {
                    log.warn("booking seat listener failed: {}", ex.toString())
                }
            }
        }
    }

    override fun destroy() {
        executor.shutdown()
    }

    companion object {
        const val THREAD = "booking-seat-relay"
    }
}
