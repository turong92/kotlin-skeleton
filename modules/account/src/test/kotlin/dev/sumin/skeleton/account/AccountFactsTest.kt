package dev.sumin.skeleton.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountFactsTest {
    private val h = AccountHarness()
    private val facts = RepositoryAccountFacts { h.repo }

    @Test
    fun `an existing account answers its sign-up time`() {
        val at = h.time.now()
        h.repo.insert(Account("acc_1", "a@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, at, at), emptyList())
        assertEquals(at, facts.createdAt("acc_1"))
    }

    @Test
    fun `a missing account answers null`() = assertNull(facts.createdAt("acc_never"))

    private fun insert(id: String, email: String?, verified: Boolean, status: AccountStatus = AccountStatus.ACTIVE) {
        val at = h.time.now()
        h.repo.insert(Account(id, email, verified, status, setOf("USER"), null, null, null, at, at), emptyList())
    }

    @Test
    fun `verifiedEmail answers the address only when it is confirmed`() {
        insert("acc_v", "v@example.com", true)
        insert("acc_u", "u@example.com", false, AccountStatus.PENDING_VERIFICATION)
        insert("acc_n", null, false)
        assertEquals("v@example.com", facts.verifiedEmail("acc_v"))
        assertNull(facts.verifiedEmail("acc_u"), "an unconfirmed address proves nothing")
        assertNull(facts.verifiedEmail("acc_n"), "no address")
        assertNull(facts.verifiedEmail("acc_never"))
    }

    @Test
    fun `accountIdByVerifiedEmail finds only a confirmed, live account by its address`() {
        insert("acc_v", "v@example.com", true)
        insert("acc_u", "u@example.com", false, AccountStatus.PENDING_VERIFICATION)
        insert("acc_e", "e@example.com", true, AccountStatus.ERASED)
        assertEquals("acc_v", facts.accountIdByVerifiedEmail(" V@Example.com "))
        assertNull(facts.accountIdByVerifiedEmail("u@example.com"))
        assertNull(facts.accountIdByVerifiedEmail("e@example.com"))
        assertNull(facts.accountIdByVerifiedEmail("nobody@example.com"))
    }

    @Test
    fun `verifiedEmail of an erased account is null`() {
        insert("acc_e", "e@example.com", true, AccountStatus.ERASED)
        assertNull(facts.verifiedEmail("acc_e"))
    }
}
