package dev.sumin.skeleton.booking

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.SubscriptionListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/**
 * 여러 대를 잇는 자리 사건 중계 — Redis pub/sub 채널 하나([channel])에 **인스턴스 id 를 달아** 발행하고, 받는 쪽은 자기가 보낸 것을 건너뛴다.
 * 이 인스턴스의 연결들에는 Redis 를 거치지 않고 [local] 로 바로 넘긴다 — 그래서 Redis 가 죽어도 한 대 안의 반영은 멈추지 않는다(발행 실패는 로그 한 줄). 발행은 요청 스레드가 아니라 실행기 하나에서 한다.
 *
 * pub/sub 는 많아야 한 번이다: 이 인스턴스의 **구독이 끊겼다 다시 붙으면** 그사이 사건을 놓쳤을 수 있으므로 이 인스턴스의 흐름 연결을 모두 닫는다([BookingSeatListener.onMissed]) —
 * 화면이 다시 붙어 스냅샷으로 맞춘다. 정확성은 영향이 없다(찍는 순간 DB 가 판정한다). 메시지에는 자리 id · 상태 · 보류 끝 시각 · 버전만 실린다(계정 · 보류 id 없음).
 *
 * **기동 때 Redis 가 없어도 앱은 뜬다**: 구독 컨테이너([container], 스프링이 자동으로 시작하지 않는 것)를 이 클래스가 백그라운드에서 붙을 때까지 다시 시도하며 시작한다.
 * 그동안(그리고 처음 붙은 순간까지) 다른 인스턴스의 사건은 못 받으므로, 시작이 한 번이라도 실패했다면 처음 붙은 순간도 「놓쳤다」로 친다.
 */
class RedisBookingSeatRelay(
    private val redis: StringRedisTemplate,
    private val container: RedisMessageListenerContainer,
    private val channel: String,
    private val local: LocalBookingSeatRelay = LocalBookingSeatRelay(),
    private val instanceId: String = UUID.randomUUID().toString(),
    private val retryEvery: Duration = Duration.ofSeconds(5),
) : BookingSeatRelay, SmartLifecycle, DisposableBean {
    private val log = LoggerFactory.getLogger(RedisBookingSeatRelay::class.java)
    private val sender = Executors.newSingleThreadExecutor { task -> Thread(task, THREAD).apply { isDaemon = true } }
    private val receiver = Receiver()
    private val running = AtomicBoolean(false)
    @Volatile private var subscriber: Thread? = null

    init {
        container.addMessageListener(receiver, ChannelTopic(channel))
    }

    /** 구독 컨테이너를 붙을 때까지 다시 시도하며 시작한다 - 앱의 기동을 막지 않는다 */
    override fun start() {
        if (!running.compareAndSet(false, true)) return
        subscriber = Thread({
            while (running.get()) {
                try {
                    container.start()
                    return@Thread
                } catch (ex: Exception) {
                    log.warn("booking seat relay cannot subscribe to Redis yet, retrying: {}", ex.toString())
                    receiver.gap()
                    runCatching { container.stop() }
                    try {
                        Thread.sleep(retryEvery.toMillis())
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }, SUBSCRIBER_THREAD).apply { isDaemon = true; start() }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        subscriber?.interrupt()
        runCatching { container.stop() }
    }

    override fun isRunning(): Boolean = running.get()

    override fun publish(poolId: String, changes: List<SeatChange>) {
        if (changes.isEmpty()) return
        local.publish(poolId, changes)
        val message = encode(instanceId, poolId, changes)
        try {
            sender.execute {
                try {
                    redis.convertAndSend(channel, message)
                } catch (ex: Exception) {
                    log.warn("booking seat event not sent to the other instances: {}", ex.toString())
                }
            }
        } catch (_: RejectedExecutionException) {
            // 종료 중
        }
    }

    override fun subscribe(poolId: String, listener: BookingSeatListener): AutoCloseable = local.subscribe(poolId, listener)

    override fun destroy() {
        stop()
        sender.shutdown()
        local.destroy()
    }

    private inner class Receiver : MessageListener, SubscriptionListener {
        private val subscribedBefore = AtomicBoolean(false)

        /** 붙기 전에 사건을 놓쳤을 수 있다 - 처음 붙는 순간도 다시 붙는 것으로 친다 */
        fun gap() = subscribedBefore.set(true)

        override fun onMessage(message: Message, pattern: ByteArray?) {
            val received = try {
                decode(String(message.body, Charsets.UTF_8))
            } catch (ex: RuntimeException) {
                log.warn("booking seat message ignored: {}", ex.toString())
                return
            }
            if (received.origin == instanceId) return   // 내가 보낸 것은 이미 바로 넘겼다
            local.deliver(received.poolId, received.changes)
        }

        /** 처음 구독은 평범하고, 그 뒤 또 불리면 끊겼다 다시 붙은 것이다 */
        override fun onChannelSubscribed(channel: ByteArray, count: Long) {
            if (subscribedBefore.getAndSet(true)) {
                log.warn("booking seat relay resubscribed; closing this instance's seat streams so the screens re-read the snapshot")
                local.missed()
            }
        }

        override fun onChannelUnsubscribed(channel: ByteArray, count: Long) = Unit
    }

    internal data class Received(val origin: String, val poolId: String, val changes: List<SeatChange>)

    companion object {
        const val THREAD = "booking-seat-redis-sender"
        const val SUBSCRIBER_THREAD = "booking-seat-redis-subscriber"

        /** `<인스턴스>|<풀>|<자리>:<O|T>:<보류 끝 밀리초|->:<v>;…` - id 는 영숫자와 `_` 뿐이라 구분자가 섞이지 않는다 */
        internal fun encode(origin: String, poolId: String, changes: List<SeatChange>): String =
            "$origin|$poolId|" + changes.joinToString(";") { "${it.seatId}:${if (it.state == SeatState.OPEN) "O" else "T"}:${it.heldUntil?.toEpochMilli() ?: "-"}:${it.v}" }

        internal fun decode(text: String): Received {
            val (origin, poolId, body) = text.split('|', limit = 3)
            val changes = body.split(';').filter { it.isNotEmpty() }.map { part ->
                val (seat, state, until, v) = part.split(':')
                SeatChange(seat, if (state == "O") SeatState.OPEN else SeatState.TAKEN, until.takeIf { it != "-" }?.let { Instant.ofEpochMilli(it.toLong()) }, v.toLong())
            }
            return Received(origin, poolId, changes)
        }
    }
}
