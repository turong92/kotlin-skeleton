package dev.sumin.skeleton.auth.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountIdentifierTest {
    @Test
    fun `from preserves account id identifier`() {
        assertEquals(
            AccountIdentifier(accountId = "acc_123"),
            AccountIdentifier.from(accountId = " acc_123 ", username = null, email = null),
        )
    }

    @Test
    fun `from normalizes email identifier`() {
        assertEquals(
            AccountIdentifier(email = "sumin@example.com"),
            AccountIdentifier.from(accountId = null, username = null, email = " Sumin@Example.com "),
        )
    }

    @Test
    fun `from preserves username identifier`() {
        assertEquals(
            AccountIdentifier(username = "sumin"),
            AccountIdentifier.from(accountId = null, username = " sumin ", email = null),
        )
    }

    @Test
    fun `from preserves identifier priority through fields`() {
        assertEquals(
            AccountIdentifier(accountId = "acc_123", username = "sumin", email = "sumin@example.com"),
            AccountIdentifier.from(accountId = " acc_123 ", username = " sumin ", email = " Sumin@Example.com "),
        )
    }

    @Test
    fun `from rejects missing identifier`() {
        assertFailsWith<IllegalArgumentException> {
            AccountIdentifier.from(accountId = null, username = " ", email = null)
        }
    }
}

class InMemoryAuthAccountRepositoryTest {
    @Test
    fun `findBy resolves account id before email and username`() {
        val accountIdMatch = authAccount(accountId = "acc_id", username = "id-user", email = "id@example.com")
        val emailMatch = authAccount(accountId = "acc_email", username = "email-user", email = "target@example.com")
        val usernameMatch = authAccount(accountId = "acc_username", username = "target-user", email = "username@example.com")
        val repository = InMemoryAuthAccountRepository(listOf(accountIdMatch, emailMatch, usernameMatch))

        assertEquals(
            accountIdMatch,
            repository.findBy(
                AccountIdentifier(accountId = "acc_id", username = "target-user", email = "target@example.com"),
            ),
        )
    }

    @Test
    fun `findBy resolves email before username when account id is absent`() {
        val emailMatch = authAccount(accountId = "acc_email", username = "email-user", email = "target@example.com")
        val usernameMatch = authAccount(accountId = "acc_username", username = "target-user", email = "username@example.com")
        val repository = InMemoryAuthAccountRepository(listOf(emailMatch, usernameMatch))

        assertEquals(
            emailMatch,
            repository.findBy(AccountIdentifier(username = "target-user", email = "target@example.com")),
        )
    }

    @Test
    fun `findBy resolves username when stronger identifiers are absent`() {
        val usernameMatch = authAccount(accountId = "acc_username", username = "target-user", email = "username@example.com")
        val repository = InMemoryAuthAccountRepository(listOf(usernameMatch))

        assertEquals(
            usernameMatch,
            repository.findBy(AccountIdentifier(username = "target-user")),
        )
    }

    private fun authAccount(
        accountId: String,
        username: String,
        email: String,
    ): AuthAccount = AuthAccount(
        accountId = accountId,
        username = username,
        email = email,
        passwordHash = "hash",
        roles = setOf("USER"),
    )
}
