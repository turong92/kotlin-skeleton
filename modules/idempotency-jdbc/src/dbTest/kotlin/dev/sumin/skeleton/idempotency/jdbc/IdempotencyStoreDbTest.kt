package dev.sumin.skeleton.idempotency.jdbc

import dev.sumin.skeleton.idempotency.IdempotencyRequest
import dev.sumin.skeleton.idempotency.IdempotencyReservation
import dev.sumin.skeleton.idempotency.IdempotencyStore
import dev.sumin.skeleton.idempotency.StoredIdempotencyResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** 두 방언에서 같은 뜻인지 - 동시에 온 같은 키는 한 번만 시작 · 첫 응답 재생(바이트까지) · 지문이 다르면 충돌 · 만료한 키는 새로 시작 · 풀면 새로 시작 · 청소 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [IdempotencyJdbcTestApplication::class])
class IdempotencyStoreDbTest {
    @Autowired lateinit var store: IdempotencyStore
    @Autowired lateinit var dataSource: javax.sql.DataSource
    @Autowired lateinit var dialect: dev.sumin.skeleton.persistence.jdbc.SqlDialect

    private val seq = UUID.randomUUID().toString().take(8)

    private fun request(n: Int, fingerprint: String = "fp", expiresAt: Instant = Instant.now().plusSeconds(3600)) =
        IdempotencyRequest(scopedKey = "acc_$seq:key-$n", rawKey = "key-$n", fingerprint = fingerprint, expiresAt = expiresAt)

    @Test
    fun `the default store is the JDBC one`() {
        assertIs<JdbcIdempotencyStore>(store)
    }

    @Test
    fun `first request starts, a repeat while running is in progress, a different fingerprint conflicts`() {
        val r = request(1)
        assertIs<IdempotencyReservation.Started>(store.reserve(r))
        assertSame(IdempotencyReservation.InProgress, store.reserve(r))
        assertSame(IdempotencyReservation.Conflict, store.reserve(request(1, fingerprint = "other")))
    }

    @Test
    fun `a completed response replays with status, headers and body bytes intact`() {
        val r = request(2)
        store.reserve(r)
        val body = byteArrayOf(0, 1, 2, -1, 127, -128)
        store.complete(r.scopedKey, StoredIdempotencyResponse(201, "application/json", mapOf("Location" to listOf("/api/v1/x/1")), body))

        val replay = assertIs<IdempotencyReservation.Replay>(store.reserve(r)).response
        assertEquals(201, replay.status)
        assertEquals("application/json", replay.contentType)
        assertEquals(mapOf("Location" to listOf("/api/v1/x/1")), replay.headers)
        assertContentEquals(body, replay.body)
        assertSame(IdempotencyReservation.Conflict, store.reserve(request(2, fingerprint = "other")))
    }

    @Test
    fun `release lets the same key start again`() {
        val r = request(3)
        store.reserve(r)
        store.release(r.scopedKey)
        assertIs<IdempotencyReservation.Started>(store.reserve(r))
    }

    @Test
    fun `an expired key starts again and the purge removes expired rows only`() {
        val expired = request(4, expiresAt = Instant.now().minusSeconds(5))
        val live = request(5)
        store.reserve(expired)
        store.reserve(live)

        assertTrue((store as JdbcIdempotencyStore).purgeExpired() >= 1)
        assertIs<IdempotencyReservation.Started>(store.reserve(expired))   // 청소가 지웠으니 새로 시작
        assertSame(IdempotencyReservation.InProgress, store.reserve(live))   // 살아 있는 키는 그대로
        // 만료한 줄이 남아 있어도 같은 키의 예약이 먼저 지우고 새로 시작한다
        store.release(expired.scopedKey)
        store.reserve(request(4, expiresAt = Instant.now().minusSeconds(5)))
        assertIs<IdempotencyReservation.Started>(store.reserve(request(4)))
    }

    @Test
    fun `an in-progress reservation whose request died restarts after the lease, a finished one never does`() {
        var now = Instant.parse("2026-11-11T00:00:00Z")
        val leased = JdbcIdempotencyStore(
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource), dialect,
            dev.sumin.skeleton.common.time.TimeProvider { now }, java.time.Duration.ofMinutes(5),
        )
        val died = request(7, expiresAt = now.plusSeconds(86_400))
        val done = request(8, expiresAt = now.plusSeconds(86_400))
        assertIs<IdempotencyReservation.Started>(leased.reserve(died))
        assertIs<IdempotencyReservation.Started>(leased.reserve(done))
        leased.complete(done.scopedKey, StoredIdempotencyResponse(200, null, emptyMap(), ByteArray(0)))

        now = now.plusSeconds(120)
        assertSame(IdempotencyReservation.InProgress, leased.reserve(died))   // 임대 안 - 아직 도는 요청일 수 있다
        now = now.plusSeconds(240)
        assertIs<IdempotencyReservation.Started>(leased.reserve(died))        // 임대 지남 - 죽은 요청의 예약을 새로 시작
        assertIs<IdempotencyReservation.Replay>(leased.reserve(done))         // 끝난 응답은 TTL 까지 그대로
    }

    @Test
    fun `scoped keys differ by case on both databases`() {
        assertIs<IdempotencyReservation.Started>(store.reserve(request(9).copy(scopedKey = "acc_$seq:Key-9")))
        assertIs<IdempotencyReservation.Started>(store.reserve(request(9).copy(scopedKey = "acc_$seq:key-9")))
    }

    @Test
    fun `many callers racing on one key - exactly one starts`() {
        val r = request(6)
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val results = (1..8).map { pool.submit<IdempotencyReservation> { go.await(); store.reserve(r) } }
        go.countDown()
        val all = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, all.count { it is IdempotencyReservation.Started })
        assertEquals(7, all.count { it === IdempotencyReservation.InProgress })
    }
}
