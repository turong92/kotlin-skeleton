package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcTokenAndAuditDbTest {
    private val now = Instant.parse("2026-10-06T00:00:00.654321Z")
    private val tokens = OneTimeTokens(AccountDb.tokens, TimeProvider.fixed(now))

    @BeforeTest fun clean() = AccountDb.clean()

    @Test
    fun `a token is stored as a hash and a payload and spent once even under a 16-way race`() {
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", null, Duration.ofMinutes(15), payload = "p")
        assertEquals(0, AccountDb.jdbc.queryForObject("select count(*) from skeleton_account_tokens where token_hash = :t", mapOf("t" to raw), Int::class.java))
        val pool = Executors.newFixedThreadPool(16)
        val go = CountDownLatch(1)
        val results = (1..16).map { pool.submit<Any?> { go.await(); tokens.consume(TokenPurposes.MAGIC_LINK, raw) } }
        go.countDown()
        val won = results.mapNotNull { it.get() }
        pool.shutdown()
        assertEquals(1, won.size)
        assertEquals("p", (won.single() as dev.sumin.skeleton.account.token.TokenGrant).payload)
    }

    @Test
    fun `purpose mismatch does not burn the token, expiry and replacement behave`() {
        val raw = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertNull(tokens.consume(TokenPurposes.PASSWORD_RESET, raw))
        assertNotNull(tokens.peek(TokenPurposes.MAGIC_LINK, raw))
        val newer = tokens.issue(TokenPurposes.MAGIC_LINK, "ann@example.com", "acc_1", Duration.ofHours(1))
        assertNull(tokens.consume(TokenPurposes.MAGIC_LINK, raw))
        assertNotNull(tokens.consume(TokenPurposes.MAGIC_LINK, newer))

        val late = OneTimeTokens(AccountDb.tokens, TimeProvider.fixed(now.plus(Duration.ofHours(2))))
        val expiring = tokens.issue(TokenPurposes.PASSWORD_RESET, "ann@example.com", "acc_1", Duration.ofMinutes(30))
        assertNull(late.consume(TokenPurposes.PASSWORD_RESET, expiring))
        assertTrue(AccountDb.tokens.purgeExpired(now.plus(Duration.ofHours(3))) >= 1)
    }

    @Test
    fun `the open token of a purpose and owner is found with its payload, and not once spent or expired`() {
        val raw = tokens.issue(TokenPurposes.PASSWORD_RESET, "acc_1", "acc_1", Duration.ofMinutes(30), payload = "new@example.com")
        val open = AccountDb.tokens.findOpen(TokenPurposes.PASSWORD_RESET, "acc_1", now)!!
        assertEquals("new@example.com", open.payload)
        assertEquals(now.plus(Duration.ofMinutes(30)), open.expiresAt)
        assertNull(AccountDb.tokens.findOpen(TokenPurposes.PASSWORD_RESET, "acc_2", now))
        assertNull(AccountDb.tokens.findOpen(TokenPurposes.PASSWORD_RESET, "acc_1", now.plus(Duration.ofMinutes(31))))
        tokens.consume(TokenPurposes.PASSWORD_RESET, raw)
        assertNull(AccountDb.tokens.findOpen(TokenPurposes.PASSWORD_RESET, "acc_1", now))
    }

    @Test
    fun `audit rows keep the type, account, ip and a short detail`() {
        AccountDb.audit.on(AccountEvent(AccountEventType.LOGIN_FAILURE, null, now, "203.0.113.4", mapOf("reason" to "BAD_PASSWORD", "id" to "abc")))
        AccountDb.audit.on(AccountEvent(AccountEventType.PASSWORD_CHANGED, "acc_1", now, null, emptyMap()))
        val rows = AccountDb.jdbc.queryForList("select type, account_id, ip, detail from skeleton_account_audit order by id", emptyMap<String, Any>())
        assertEquals(listOf("LOGIN_FAILURE", "PASSWORD_CHANGED"), rows.map { it["type"] })
        assertNull(rows[0]["account_id"])
        assertEquals("203.0.113.4", rows[0]["ip"])
        assertTrue((rows[0]["detail"] as String).contains("reason=BAD_PASSWORD"))
    }
}
