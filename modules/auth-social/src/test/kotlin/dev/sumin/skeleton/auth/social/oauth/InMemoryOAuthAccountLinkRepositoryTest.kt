package dev.sumin.skeleton.auth.social.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryOAuthAccountLinkRepositoryTest {
    @Test
    fun `findAccountId resolves provider and provider user id`() {
        val repository = InMemoryOAuthAccountLinkRepository(
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertEquals("acc_user", repository.findAccountId("fake", "fake_user"))
    }

    @Test
    fun `findAccountId normalizes provider but not provider user id`() {
        val repository = InMemoryOAuthAccountLinkRepository(
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertEquals("acc_user", repository.findAccountId(" Fake ", "fake_user"))
        assertNull(repository.findAccountId("fake", "FAKE_USER"))
    }
}
