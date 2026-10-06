package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이메일은 **글자 그대로** 같을 때만 같은 주소다. MySQL 8 의 기본 정렬(`utf8mb4_0900_ai_ci`)은 `victim@gmäil.com` 과 `victim@gmail.com` 을 같게 본다 —
 * 그 정렬이 조회 · 유니크 키 · 토큰 주인 비교에 새면 남의 계정이 내려오고 남의 열린 링크가 닫힌다 (두 DB 묶음이 같은 단정을 돈다).
 */
class EmailExactMatchDbTest {
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private val tokens = OneTimeTokens(AccountDb.tokens, TimeProvider.fixed(now))

    @BeforeTest fun clean() = AccountDb.clean()

    private fun account(id: String, email: String) = Account(id, email, true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now)
    private fun password(id: String, accountId: String, email: String) = Identity(id, accountId, "password", email, true, "{bcrypt}x", null, now)

    @Test
    fun `an accent variant of an address is another address - lookup and unique key agree`() {
        assertTrue(repo.insert(account("acc_1", "victim@gmail.com"), listOf(password("idn_1", "acc_1", "victim@gmail.com"))))
        assertNull(repo.findByEmail("victim@gmäil.com"), "the accent variant must not find the victim's account")
        assertNull(repo.findIdentity("password", "victim@gmäil.com"))
        assertTrue(repo.insert(account("acc_2", "victim@gmäil.com"), listOf(password("idn_2", "acc_2", "victim@gmäil.com"))), "the variant is a different address, so it is not a unique-key collision")
        assertEquals("acc_1", repo.findByEmail("victim@gmail.com")!!.id)
        assertEquals("acc_2", repo.findByEmail("victim@gmäil.com")!!.id)
    }

    @Test
    fun `the stored address is compared as written - case is the caller's normalisation, not the database's`() {
        assertTrue(repo.insert(account("acc_1", "ann@example.com"), listOf(password("idn_1", "acc_1", "ann@example.com"))))
        assertNull(repo.findByEmail("ANN@example.com"))
        assertNotNull(repo.findByEmail("ann@example.com"))
    }

    @Test
    fun `a token owner that differs by accent or case does not close the other owner's open link`() {
        val raw = tokens.issue(TokenPurposes.PASSWORD_RESET, "victim@gmail.com", "acc_1", Duration.ofMinutes(30))
        tokens.issue(TokenPurposes.PASSWORD_RESET, "victim@gmäil.com", "acc_2", Duration.ofMinutes(30))
        tokens.issue(TokenPurposes.PASSWORD_RESET, "VICTIM@gmail.com", "acc_3", Duration.ofMinutes(30))
        assertNotNull(tokens.peek(TokenPurposes.PASSWORD_RESET, raw), "someone else's request for a look-alike address closed the victim's link")
    }
}
