package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.authorprofile.AuthorProfileErrorCode
import dev.sumin.skeleton.authorprofile.AuthorProfileErasureListener
import dev.sumin.skeleton.authorprofile.AuthorProfileException
import dev.sumin.skeleton.authorprofile.AuthorProfileProperties
import dev.sumin.skeleton.authorprofile.AuthorProfileService
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.T0
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.account
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.repository
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 탈퇴 고리와 서비스를 진짜 표 위에서 맞물려 본다 — 리뷰 지적: 고리가 돈 뒤 ensureFirstProfile 이 지워진 계정에 첫 프로필을 되살리면 안 된다. */
class JdbcErasureOrderDbTest {
    private val accounts = InMemoryAccountRepository()
    private val time = TimeProvider.fixed(T0)
    private val names = DisplayNames(AccountProperties.DisplayName(uniqueness = AccountProperties.DisplayName.Uniqueness.TAGGED))
    private val service = AuthorProfileService(
        repository, { accounts }, names, AccountRateLimits({ InMemoryFixedWindowRateLimitStore() }, time), AuthorProfileProperties(), time,
    )

    private fun newAccount(name: String?): String {
        val id = account()
        val key = name?.let(names::key)
        check(accounts.insert(Account(id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), name, null, null, T0, T0, displayNameKey = key, displayTag = name?.let { "0001" }), emptyList()))
        return id
    }

    private fun erase(id: String) {
        accounts.update(id, AccountPatch(status = AccountStatus.SUSPENDED), T0)
        assertTrue(accounts.erase(id, T0.plusSeconds(1), forced = true))
        AuthorProfileErasureListener(repository).erase(ErasureRequest(id, AccountTombstone.of(id)))
    }

    @Test
    fun `after the erasure hook ran, ensureFirstProfile cannot put a first profile on the erased account`() {
        val id = newAccount("Moon${System.nanoTime()}")
        service.ensureFirstProfile(id)
        service.create(id, "Second${System.nanoTime()}")
        assertEquals(2, ProfileDb.rows(id))

        erase(id)

        assertEquals(0, ProfileDb.rows(id))
        val failure = assertFailsWith<AuthorProfileException> { service.ensureFirstProfile(id) }
        assertEquals(AuthorProfileErrorCode.NOT_FOUND, failure.errorCode)
        assertFailsWith<AuthorProfileException> { service.create(id, "Third${System.nanoTime()}") }
        assertEquals(0, ProfileDb.rows(id), "nothing was written for the erased account")
        assertNull(repository.findPrimary(id))
    }

    @Test
    fun `running the hook twice and for an account that never had a profile is harmless`() {
        val id = newAccount(null)
        erase(id)
        AuthorProfileErasureListener(repository).erase(ErasureRequest(id, AccountTombstone.of(id)))
        assertEquals(0, ProfileDb.rows(id))
    }
}
