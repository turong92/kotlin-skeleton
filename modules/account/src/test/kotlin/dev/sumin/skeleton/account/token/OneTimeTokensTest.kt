package dev.sumin.skeleton.account.token

import dev.sumin.skeleton.account.MutableTime
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneTimeTokensTest {
    private val time = MutableTime()
    private val store = InMemoryOneTimeTokenStore()
    private val tokens = OneTimeTokens(store, time)

    @Test
    fun `the store never sees the raw token, only a 64-char hash`() {
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertEquals(1, store.hashes().size)
        assertEquals(64, store.hashes().single().length)
        assertTrue(store.hashes().none { it.contains(raw) || raw.contains(it) })
        assertTrue(raw.length >= 43)
    }

    @Test
    fun `a token is spent exactly once`() {
        val raw = tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofHours(1))
        val grant = assertNotNull(tokens.consume(TokenPurposes.PASSWORD_RESET, raw))
        assertEquals("acc_1", grant.accountId)
        assertEquals("ann@example.com", grant.subject)
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, raw))
    }

    @Test
    fun `sixteen threads consuming one token give exactly one winner`() {
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", null, Duration.ofHours(1))
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit<Any?> { go.await(); tokens.consume(TokenPurposes.MAGIC_LINK, raw) } }
        go.countDown()
        assertEquals(1, results.count { it.get() != null })
        pool.shutdown()
    }

    @Test
    fun `a token for one purpose cannot be spent for another, and the attempt does not burn it`() {
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, raw))
        assertNull(tokens.peek(TokenPurposes.PASSWORD_RESET, raw))
        assertNotNull(tokens.consume(TokenPurposes.MAGIC_LINK, raw))
    }

    @Test
    fun `expired tokens are dead`() {
        val raw = tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofMinutes(30))
        time.advance(Duration.ofMinutes(31))
        assertNull(tokens.peek(TokenPurposes.PASSWORD_RESET, raw))
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, raw))
    }

    @Test
    fun `peek does not spend`() {
        val raw = tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertNotNull(tokens.peek(TokenPurposes.PASSWORD_RESET, raw))
        assertNotNull(tokens.peek(TokenPurposes.PASSWORD_RESET, raw))
        assertNotNull(tokens.consume(TokenPurposes.PASSWORD_RESET, raw))
    }

    @Test
    fun `issuing a new token for the same purpose and subject closes the older one`() {
        val first = tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofHours(1))
        val second = tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertNotEquals(first, second)
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, first))
        assertNotNull(tokens.consume(TokenPurposes.PASSWORD_RESET, second))
    }

    @Test
    fun `tokens for another subject or purpose are not touched by the invalidation`() {
        val other = tokens.issue(TokenPurposes.PASSWORD_RESET, "bob@example.com", "acc_2", Duration.ofHours(1))
        val verify = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", "acc_1", Duration.ofHours(1))
        tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertNotNull(tokens.consume(TokenPurposes.PASSWORD_RESET, other))
        assertNotNull(tokens.consume(TokenPurposes.MAGIC_LINK, verify))
    }

    @Test
    fun `garbage input is just not a token`() {
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, ""))
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, "x".repeat(500)))
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, "../../etc/passwd"))
    }

    @Test
    fun `payload travels with the token`() {
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "acc_1", "acc_1", Duration.ofHours(1), payload = "new@example.com")
        assertEquals("new@example.com", tokens.consume(TokenPurposes.MAGIC_LINK, raw)?.payload)
    }

    @Test
    fun `sweep forgets tokens that expired long ago and keeps the rest`() {
        val old = tokens.issue(TokenPurposes.PASSWORD_RESET, "a@example.com", "acc_1", Duration.ofMinutes(30))
        time.advance(Duration.ofDays(3))
        val fresh = tokens.issue(TokenPurposes.PASSWORD_RESET, "b@example.com", "acc_2", Duration.ofMinutes(30))
        assertEquals(1, tokens.sweep(Duration.ofDays(1)))
        assertEquals(1, store.hashes().size)
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, old))
        assertNotNull(tokens.consume(TokenPurposes.PASSWORD_RESET, fresh))
    }
}
