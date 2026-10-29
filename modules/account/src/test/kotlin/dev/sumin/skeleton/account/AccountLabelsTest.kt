package dev.sumin.skeleton.account

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountLabelsTest {
    private val h = AccountHarness()
    private val labels = RepositoryAccountLabels { h.repo }
    private val at = h.time.now()

    private fun put(id: String, email: String?, loginId: String?, status: AccountStatus = AccountStatus.ACTIVE) =
        h.repo.insert(Account(id, email, true, status, setOf("USER"), null, null, null, at, at, loginId = loginId), emptyList())

    @Test
    fun `the email labels an account, the login id stands in when there is none, an erased or missing account has no label`() {
        put("acc_1", "a@example.com", "a_login")
        put("acc_2", null, "b_login")
        put("acc_3", "c@example.com", null, AccountStatus.ERASED)
        assertEquals(mapOf("acc_1" to "a@example.com", "acc_2" to "b_login"), labels.of(listOf("acc_1", "acc_2", "acc_3", "acc_never")))
    }
}
