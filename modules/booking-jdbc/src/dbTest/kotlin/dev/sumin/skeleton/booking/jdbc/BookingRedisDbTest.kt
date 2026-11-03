package dev.sumin.skeleton.booking.jdbc

import dev.sumin.skeleton.booking.AllowAllBookingAdmission
import dev.sumin.skeleton.booking.BookingHolds
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.BookingSeatListener
import dev.sumin.skeleton.booking.RedisBookingSeatRelay
import dev.sumin.skeleton.booking.SeatChange
import dev.sumin.skeleton.booking.SeatState
import dev.sumin.skeleton.booking.toChange
import dev.sumin.skeleton.booking.web.BookingSeatStreamService
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.lettuce.core.KillArgs
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * 여러 대의 배치도 반영 (스펙 §7 시험 8 · 9). 같은 DB 를 보는 독립 컨텍스트 둘 + 한 Redis. 「연결」은 풀을 구독한 리스너 하나이고 화면처럼 스냅샷에 사건을 `v` 가 큰 쪽으로 합친다.
 * 시험마다 Redis 컨테이너를 따로 띄우고 쓰고 바로 내린다.
 */
class BookingRedisDbTest {
    /** 한 인스턴스: 자기 Redis 연결 · 구독 컨테이너 · 중계 · 서비스. DB 는 [BookingDb] 의 둘 중 하나를 나눠 쓴다 */
    private class Node(name: String, redis: GenericContainer<*>, channel: String, base: BookingInstance) : AutoCloseable {
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379))).also { it.afterPropertiesSet() }
        val template = StringRedisTemplate(factory).also { it.afterPropertiesSet() }
        val listeners = RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            setRecoveryInterval(300L)
            afterPropertiesSet()
        }
        val relay = RedisBookingSeatRelay(template, listeners, channel, instanceId = name).also { it.start() }
        val holds = BookingHolds(base.store, relay, AllowAllBookingAdmission, BookingDb.time)
        val pools = BookingPools(base.store, relay, BookingDb.time)
        val streams = BookingSeatStreamService(relay)

        /** Lettuce 의 원시 명령 - Spring 이 감싸지 않은 `PUBSUB NUMSUB` · `CLIENT KILL` 용 */
        fun <T> commands(block: (RedisClusterAsyncCommands<ByteArray, ByteArray>) -> T): T =
            template.execute { block(it.nativeConnection as RedisClusterAsyncCommands<ByteArray, ByteArray>) }!!

        override fun close() {
            runCatching { streams.stop() }
            runCatching { relay.destroy() }
            runCatching { listeners.destroy() }
            runCatching { factory.destroy() }
        }
    }

    /** 배치도를 연 화면 하나: 구독 → 스냅샷 → 사건을 자리마다 `v` 가 큰 쪽으로 합친다 */
    private class Screen(node: Node, val pool: String) {
        val view = ConcurrentHashMap<String, SeatChange>()
        var missed = 0
        private val subscription = node.relay.subscribe(pool, object : BookingSeatListener {
            override fun onChanges(changes: List<SeatChange>) = changes.forEach(::merge)

            override fun onMissed() {
                missed++
            }
        })

        init {
            node.pools.snapshot(pool).seats.forEach { merge(it.toChange()) }
        }

        private fun merge(change: SeatChange) {
            view.merge(change.seatId, change) { old, new -> if (new.v > old.v) new else old }
        }

        fun close() = subscription.close()
    }

    private fun dbView(pool: String): Map<String, SeatChange> =
        BookingDb.first.pools.snapshot(pool).seats.associate { it.id to it.toChange() }

    private fun sameAsDb(screen: Screen, truth: Map<String, SeatChange>) =
        truth.all { (id, db) -> screen.view[id]?.let { it.v == db.v && it.state == db.state && (it.heldUntil != null) == (db.heldUntil != null) } == true }

    private fun awaitScreens(screens: List<Screen>, pool: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val truth = dbView(pool)
            if (screens.all { sameAsDb(it, truth) }) return
            Thread.sleep(100)
        }
        val truth = dbView(pool)
        val wrong = screens.count { !sameAsDb(it, truth) }
        assertEquals(0, wrong, "screens whose snapshot + events differ from the database")
    }

    private fun awaitSubscribers(node: Node, channel: String, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (node.commands { it.pubsubNumsub(channel.toByteArray()).get().values.sum() } >= expected) return
            Thread.sleep(50)
        }
        error("subscribers of $channel never reached $expected")
    }

    private fun <T> withRedis(block: (GenericContainer<*>, String) -> T): T {
        val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
        redis.start()
        try {
            return block(redis, "test:booking:seats:${System.nanoTime()}")
        } finally {
            redis.stop()
        }
    }

    @Test
    fun `8 - two instances sharing a Redis, a hundred stream connections each - every connection ends up equal to the database`() = withRedis { redis, channel ->
        Node("node-a", redis, channel, BookingDb.first).use { nodeA ->
            Node("node-b", redis, channel, BookingDb.second).use { nodeB ->
                awaitSubscribers(nodeA, channel, 2)
                val (pool, seats) = BookingDb.newPool(seats = 30, maxPerHold = 3)
                val screens = (0 until 100).map { Screen(nodeA, pool) } + (0 until 100).map { Screen(nodeB, pool) }

                // 찍기 폭주를 두 대에 반반 - 일부는 한 자리를 다시 눌러 풀어 사건을 다양하게 한다
                val random = Random(20261103)
                val asks = (0 until 400).map { seats.shuffled(random).take(1 + random.nextInt(2)) }
                val outcomes = raceAll(400) { i ->
                    val node = if (i % 2 == 0) nodeA else nodeB
                    attempt {
                        val hold = node.holds.addSeats(pool, "acc-$i", asks[i])
                        if (i % 5 == 0) node.holds.removeSeat(pool, "acc-$i", asks[i].first())
                        hold.id
                    }
                }
                assertEquals(emptyList(), outcomes.filterIsInstance<Outcome.Failed>().map { it.error.toString() })
                assertTrue(outcomes.any { it is Outcome.Ok })

                awaitScreens(screens, pool)
                assertInvariants(BookingDb.first.jdbc, pool)
                screens.forEach { it.close() }
            }
        }
    }

    @Test
    fun `9 - a resubscribed instance closes its stream connections and the reconnecting screen matches the database, and with Redis gone one instance still gets its own events`() = withRedis { redis, channel ->
        Node("node-a", redis, channel, BookingDb.first).use { nodeA ->
            awaitSubscribers(nodeA, channel, 1)
            // 이 시점에 Redis 의 구독 연결은 A 의 것 하나뿐이다 - 그것을 끊는다
            val aClient = pubsubClientIds(nodeA).single()
            Node("node-b", redis, channel, BookingDb.second).use { nodeB ->
                awaitSubscribers(nodeA, channel, 2)
                val (pool, seats) = BookingDb.newPool(seats = 6, maxPerHold = 3)
                val streamsA = (0 until 3).map { nodeA.streams.connect(pool) }
                val streamsB = (0 until 3).map { nodeB.streams.connect(pool) }
                val screenA = Screen(nodeA, pool)
                val screenB = Screen(nodeB, pool)
                assertEquals(3, nodeA.streams.openConnections)

                // 사건이 오는지 먼저 본다 - A 에서 바꾼 것이 B 에 닿는다
                nodeA.holds.addSeats(pool, "first", listOf(seats[0]))
                awaitScreens(listOf(screenA, screenB), pool)

                // A 의 구독 연결을 끊는다. 다시 붙는 사이 B 에서 자리를 바꾼다 - A 는 그 사건을 놓칠 수 있다
                kill(nodeB, aClient)
                nodeB.holds.addSeats(pool, "second", listOf(seats[1], seats[2]))
                nodeB.holds.addSeats(pool, "third", listOf(seats[3]))

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (nodeA.streams.openConnections > 0 && System.nanoTime() < deadline) Thread.sleep(100)
                assertEquals(0, nodeA.streams.openConnections, "the instance that resubscribed closes all of its stream connections")
                assertTrue(screenA.missed > 0)
                assertEquals(3, nodeB.streams.openConnections, "the other instance keeps its connections")
                assertEquals(0, screenB.missed)

                // 다시 붙는 화면: 새 연결 + 스냅샷이면 DB 와 같다
                val reconnected = Screen(nodeA, pool)
                awaitScreens(listOf(reconnected, screenB), pool)
                assertInvariants(BookingDb.first.jdbc, pool)

                // Redis 가 아예 없어도 한 대 안의 사건은 계속 온다
                awaitSubscribers(nodeA, channel, 2)
                redis.stop()
                nodeA.holds.addSeats(pool, "fourth", listOf(seats[4]))
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (reconnected.view[seats[4]]?.state != SeatState.TAKEN && System.nanoTime() < until) Thread.sleep(50)
                assertEquals(SeatState.TAKEN, reconnected.view[seats[4]]?.state, "own-instance events do not need Redis")
                listOf(screenA, screenB, reconnected).forEach { it.close() }
            }
        }
    }

    /** Redis 에서 구독 중인 연결(`flags` 에 P)의 id */
    private fun pubsubClientIds(node: Node): List<Long> =
        node.template.execute { it.serverCommands().clientList }.orEmpty().filter { 'P' in (it.get("flags") ?: "") }.map { it.get("id")!!.toLong() }

    private fun kill(by: Node, clientId: Long) {
        by.commands { it.clientKill(KillArgs.Builder.id(clientId)).get() }
    }
}
