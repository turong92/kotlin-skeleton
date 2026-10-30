package dev.sumin.skeleton.apikey.jdbc

import dev.sumin.skeleton.apikey.ApiKey
import dev.sumin.skeleton.apikey.ApiKeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** 조건부 UPDATE 가 두 방언에서 같은 뜻인지 - 회수는 한 번만 · 마지막 사용은 1분에 한 번만(동시에 몰려도) · 활성 수 · 목록 순서 · 탈퇴 때 지움 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [ApiKeyJdbcTestApplication::class])
class ApiKeyStoreDbTest {
    @Autowired lateinit var store: ApiKeyStore

    private val seq = UUID.randomUUID().toString().take(8)
    private val t0 = Instant.parse("2026-10-31T00:00:00Z")

    private fun key(n: Int, owner: String = "acc_${seq}_1", createdAt: Instant = t0.plusSeconds(n.toLong()), expiresAt: Instant? = null) = ApiKey(
        id = "key_${seq}_$n", ownerType = "ACCOUNT", ownerId = owner, createdBy = owner, name = "k$n",
        tokenHash = (seq + n.toString().padStart(2, '0')).padEnd(64, 'a'), tokenHint = "apk…abcd", scopes = setOf("notes.write", "notes.read"),
        expiresAt = expiresAt, revokedAt = null, revokedBy = null, revokedReason = null, lastUsedAt = null, lastUsedIp = null, createdAt = createdAt,
    )

    @Test
    fun `a row round-trips and is found by id and by token hash, scopes as a set`() {
        val k = key(1, expiresAt = t0.plusSeconds(3600))
        store.insert(k)

        assertEquals(k, store.findById(k.id))
        assertEquals(k, store.findByTokenHash(k.tokenHash))
        assertEquals(setOf("notes.read", "notes.write"), store.findById(k.id)!!.scopes)
        assertNull(store.findByTokenHash("f".repeat(64)))
    }

    @Test
    fun `revoking wins once - the second revoke and a concurrent pair change one row`() {
        val k = key(2).also(store::insert)

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = (1..8).map { n -> pool.submit<Boolean> { start.await(); store.revoke(k.id, "by_$n", null, t0.plusSeconds(10)) } }
        start.countDown()
        val won = wins.count { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, won)
        assertFalse(store.revoke(k.id, "again", "x", t0.plusSeconds(20)))
        val row = store.findById(k.id)!!
        assertEquals(t0.plusSeconds(10), row.revokedAt)
        assertNotNull(row.revokedBy)
    }

    @Test
    fun `last use is written once a minute even when many requests race`() {
        val k = key(3).also(store::insert)

        assertTrue(store.touchLastUsed(k.id, t0, "203.0.113.7", t0.minusSeconds(60)))
        assertFalse(store.touchLastUsed(k.id, t0.plusSeconds(30), "203.0.113.8", t0.minusSeconds(30)))
        assertEquals(t0, store.findById(k.id)!!.lastUsedAt)
        assertEquals("203.0.113.7", store.findById(k.id)!!.lastUsedIp)

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val writes = (1..8).map { pool.submit<Boolean> { start.await(); store.touchLastUsed(k.id, t0.plusSeconds(61), "203.0.113.9", t0.plusSeconds(1)) } }
        start.countDown()
        assertEquals(1, writes.count { it.get(30, TimeUnit.SECONDS) })
        pool.shutdown()
        assertEquals(t0.plusSeconds(61), store.findById(k.id)!!.lastUsedAt)
    }

    @Test
    fun `active count skips revoked and expired, the list is newest first, erasure deletes only that owner`() {
        val owner = "acc_${seq}_2"
        val other = "acc_${seq}_3"
        store.insert(key(10, owner, t0.plusSeconds(1)))
        store.insert(key(11, owner, t0.plusSeconds(2), expiresAt = t0.plusSeconds(100)))
        store.insert(key(12, owner, t0.plusSeconds(3), expiresAt = t0.plusSeconds(5)))
        store.insert(key(13, owner, t0.plusSeconds(4)))
        store.insert(key(14, other, t0.plusSeconds(5)))
        store.revoke("key_${seq}_13", owner, null, t0.plusSeconds(6))

        assertEquals(2, store.countActive("ACCOUNT", owner, t0.plusSeconds(50)))
        assertEquals(listOf(13, 12, 11, 10).map { "key_${seq}_$it" }, store.listByOwner("ACCOUNT", owner).map { it.id })

        assertEquals(4, store.deleteByOwner("ACCOUNT", owner))
        assertEquals(0, store.deleteByOwner("ACCOUNT", owner))
        assertEquals(listOf("key_${seq}_14"), store.listByOwner("ACCOUNT", other).map { it.id })
    }
}
