package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountPatch
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.authorprofile.AuthorAdoption
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

    /** 운영 순서: 선점(claim) → 고리 → 지움(erase). 선점 · 고리 뒤 [between] 을 부른다 */
    private fun erase(id: String, between: () -> Unit = {}) {
        accounts.update(id, AccountPatch(status = AccountStatus.SUSPENDED), T0)
        assertTrue(accounts.claimErasure(id, T0.plusSeconds(1), forced = true))
        AuthorProfileErasureListener(repository).erase(ErasureRequest(id, AccountTombstone.of(id)))
        between()
        assertTrue(accounts.erase(id, T0.plusSeconds(2), forced = true))
    }

    @Test
    fun `in the real order - claim, hook, erase - nothing can put a profile row back after the hook`() {
        val id = newAccount("Moon${System.nanoTime()}")
        service.ensureFirstProfile(id)
        service.create(id, "Second${System.nanoTime()}")
        assertEquals(2, ProfileDb.rows(id))

        erase(id) {
            // 고리가 돈 직후, 지우기 전: 계정은 아직 ERASED 가 아니지만 ACTIVE 도 아니다
            assertEquals(0, ProfileDb.rows(id))
            assertEquals(AuthorProfileErrorCode.NOT_FOUND, assertFailsWith<AuthorProfileException> { service.ensureFirstProfile(id) }.errorCode)
            assertEquals(AuthorProfileErrorCode.NOT_FOUND, assertFailsWith<AuthorProfileException> { service.ensureFirstProfileRow(id) }.errorCode)
            assertEquals(AuthorProfileErrorCode.NOT_FOUND, assertFailsWith<AuthorProfileException> { service.create(id, "Third${System.nanoTime()}") }.errorCode)
            assertEquals(0, AuthorAdoption({ emptyList() }, service).run())
            assertEquals(0, ProfileDb.rows(id), "nothing was written between the hook and the erase")
        }

        assertEquals(AuthorProfileErrorCode.NOT_FOUND, assertFailsWith<AuthorProfileException> { service.ensureFirstProfile(id) }.errorCode)
        assertEquals(0, ProfileDb.rows(id))
        assertNull(repository.findPrimary(id))
    }

    @Test
    fun `the adoption runner leaves a claimed account alone`() {
        val id = newAccount("Moon${System.nanoTime()}")
        accounts.update(id, AccountPatch(status = AccountStatus.SUSPENDED), T0)
        assertTrue(accounts.claimErasure(id, T0, forced = true))
        val calls = mutableListOf<String>()
        val listener = object : dev.sumin.skeleton.common.author.AuthorAdoptionListener {
            private var done = false
            override val name = "probe"
            override fun pendingAccounts(limit: Int, after: String?) = if (done) emptyList() else listOf(id).also { done = true }
            override fun adopt(accountId: String, authorId: String): Int { calls += accountId; return 1 }
        }
        assertEquals(0, AuthorAdoption({ listOf(listener) }, service).run())
        assertTrue(calls.isEmpty())
        assertEquals(0, ProfileDb.rows(id))
    }

    @Test
    fun `deleteByAccount takes the first-profile row lock first - it waits for a transaction that holds it`() {
        val acc = account()
        repository.insert(ProfileDb.profile(acc, primary = true))
        repository.insertWithinCap(ProfileDb.profile(acc), 5, 0, null)
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                ProfileDb.tx.execute {
                    ProfileDb.jdbc.queryForList("select id from author_profiles where primary_of = :a for update", mapOf("a" to acc), String::class.java)
                    holding.countDown()
                    release.await()
                }
            }
            holding.await()
            val deleter = pool.submit<Int> { repository.deleteByAccount(acc) }
            Thread.sleep(500)
            assertTrue(!deleter.isDone, "deleteByAccount must queue behind the anchor lock")
            release.countDown()
            holder.get()
            assertEquals(2, deleter.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `running the hook twice and for an account that never had a profile is harmless`() {
        val id = newAccount(null)
        erase(id)
        AuthorProfileErasureListener(repository).erase(ErasureRequest(id, AccountTombstone.of(id)))
        assertEquals(0, ProfileDb.rows(id))
    }
}
