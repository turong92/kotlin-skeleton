package dev.sumin.skeleton.auth.mfa.jdbc

import dev.sumin.skeleton.auth.mfa.MfaStore
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

/** 조건부 UPDATE 가 두 방언에서 같은 뜻인지 - 같은 걸음 · 같은 복구 코드는 동시에 몰려도 하나만, 대기 → 비밀 옮김은 한 문장, 재등록은 옛 비밀을 지킴, 지움 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [MfaJdbcTestApplication::class])
class MfaStoreDbTest {
    @Autowired lateinit var store: MfaStore

    private val seq = UUID.randomUUID().toString().take(8)
    private val t0 = Instant.parse("2026-10-31T00:00:00.123456Z")
    private fun account(n: Int) = "acc_${seq}_$n"
    private fun hash(n: Int, i: Int) = (seq + n.toString().padStart(2, '0') + i.toString().padStart(4, '0')).padEnd(64, 'b')

    private fun enable(accountId: String, secret: String = "enc-A", step: Long = 100, hashes: List<String>? = null) {
        store.startEnrollment(accountId, secret, t0.plusSeconds(600), t0)
        assertTrue(store.activate(accountId, secret, step, t0.plusSeconds(1), hashes))
    }

    private fun <T> race(threads: Int, work: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures = (1..threads).map { pool.submit<T> { ready.countDown(); go.await(); work() } }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `starting an enrollment writes only the pending side, the second start replaces it, and times round-trip`() {
        val id = account(1)
        assertNull(store.find(id))

        store.startEnrollment(id, "enc-1", t0.plusSeconds(600), t0)
        val first = store.find(id)!!
        assertNull(first.secretEnc)
        assertFalse(first.enabled)
        assertEquals("enc-1", first.pendingSecretEnc)
        assertEquals(t0.plusSeconds(600), first.pendingExpiresAt)

        store.startEnrollment(id, "enc-2", t0.plusSeconds(900), t0.plusSeconds(10))
        assertEquals("enc-2", store.find(id)!!.pendingSecretEnc)
    }

    @Test
    fun `two first starts at once make one row and neither fails`() {
        val id = account(2)
        race(8) { store.startEnrollment(id, "enc-race", t0.plusSeconds(600), t0) }
        assertEquals("enc-race", store.find(id)!!.pendingSecretEnc)
    }

    @Test
    fun `activating moves the pending secret in one statement - only the expected, unexpired one`() {
        val id = account(3)
        store.startEnrollment(id, "enc-1", t0.plusSeconds(600), t0)

        assertFalse(store.activate(id, "other", 7, t0.plusSeconds(1), null), "not the pending secret")
        assertFalse(store.activate(id, "enc-1", 7, t0.plusSeconds(601), null), "expired")
        assertFalse(store.activate(account(99), "enc-1", 7, t0.plusSeconds(1), null), "no such account")
        assertFalse(store.find(id)!!.enabled)

        assertTrue(store.activate(id, "enc-1", 7, t0.plusSeconds(1), listOf(hash(3, 1), hash(3, 2))))
        val on = store.find(id)!!
        assertEquals("enc-1", on.secretEnc)
        assertNull(on.pendingSecretEnc)
        assertNull(on.pendingExpiresAt)
        assertEquals(7L, on.lastUsedStep)
        assertEquals(t0.plusSeconds(1), on.enabledAt)
        assertEquals(2, store.recoveryCodesRemaining(id))
        assertFalse(store.activate(id, "enc-1", 8, t0.plusSeconds(2), null), "the pending side is gone - nothing to activate twice")
    }

    @Test
    fun `two confirmations at once - one wins`() {
        val id = account(4)
        store.startEnrollment(id, "enc-1", t0.plusSeconds(600), t0)
        val results = race(8) { store.activate(id, "enc-1", 5, t0.plusSeconds(1), listOf(hash(4, 1))) }
        assertEquals(1, results.count { it })
        assertEquals(1, store.recoveryCodesRemaining(id))
    }

    @Test
    fun `enrolling again keeps the old secret and the recovery codes until the new one is activated`() {
        val id = account(5)
        enable(id, "enc-old", 10, listOf(hash(5, 1), hash(5, 2), hash(5, 3)))

        store.startEnrollment(id, "enc-new", t0.plusSeconds(700), t0.plusSeconds(5))
        val mid = store.find(id)!!
        assertEquals("enc-old", mid.secretEnc)
        assertEquals("enc-new", mid.pendingSecretEnc)
        assertEquals(10L, mid.lastUsedStep)

        assertTrue(store.activate(id, "enc-new", 12, t0.plusSeconds(6), null))
        assertEquals("enc-new", store.find(id)!!.secretEnc)
        assertEquals(3, store.recoveryCodesRemaining(id), "no hashes given: the existing recovery codes stay")
    }

    @Test
    fun `a step is accepted once - the same or an older step never again`() {
        val id = account(6)
        enable(id, step = 100)

        assertFalse(store.useStep(id, 100, t0))
        assertFalse(store.useStep(id, 99, t0))
        assertTrue(store.useStep(id, 101, t0))
        assertFalse(store.useStep(id, 101, t0))
        assertEquals(101L, store.find(id)!!.lastUsedStep)

        val pendingOnly = account(7)
        store.startEnrollment(pendingOnly, "enc-x", t0.plusSeconds(600), t0)
        assertFalse(store.useStep(pendingOnly, 1, t0), "a secret that is not active has no steps to spend")
    }

    @Test
    fun `sixteen threads with the same step - exactly one gets it`() {
        val id = account(8)
        enable(id, step = 100)
        val results = race(16) { store.useStep(id, 200, t0) }
        assertEquals(1, results.count { it })
        assertEquals(200L, store.find(id)!!.lastUsedStep)
    }

    @Test
    fun `a recovery code is spent once even when two requests race, and the count falls by one`() {
        val id = account(9)
        enable(id, hashes = (1..10).map { hash(9, it) })

        val results = race(16) { store.useRecoveryCode(id, hash(9, 1), t0) }
        assertEquals(1, results.count { it })
        assertEquals(9, store.recoveryCodesRemaining(id))
        assertFalse(store.useRecoveryCode(id, hash(9, 1), t0))
        assertFalse(store.useRecoveryCode(id, hash(9, 99), t0), "not one of ours")
        assertFalse(store.useRecoveryCode(account(98), hash(9, 2), t0), "another account's codes are not reachable")
        assertTrue(store.useRecoveryCode(id, hash(9, 2), t0))
        assertEquals(8, store.recoveryCodesRemaining(id))
    }

    @Test
    fun `regenerating replaces all the codes, used or not`() {
        val id = account(10)
        enable(id, hashes = listOf(hash(10, 1), hash(10, 2)))
        assertTrue(store.useRecoveryCode(id, hash(10, 1), t0))

        store.replaceRecoveryCodes(id, listOf(hash(10, 3), hash(10, 4), hash(10, 5)), t0)
        assertEquals(3, store.recoveryCodesRemaining(id))
        assertFalse(store.useRecoveryCode(id, hash(10, 2), t0))
        assertTrue(store.useRecoveryCode(id, hash(10, 3), t0))
    }

    @Test
    fun `deleting removes the secret and the codes of that account only, and can be repeated`() {
        val a = account(11)
        val b = account(12)
        enable(a, hashes = listOf(hash(11, 1)))
        enable(b, hashes = listOf(hash(12, 1)))

        store.deleteAll(a)
        store.deleteAll(a)

        assertNull(store.find(a))
        assertEquals(0, store.recoveryCodesRemaining(a))
        assertNotNull(store.find(b))
        assertEquals(1, store.recoveryCodesRemaining(b))
    }
}
