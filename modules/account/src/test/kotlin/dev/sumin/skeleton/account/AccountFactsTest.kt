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
}
