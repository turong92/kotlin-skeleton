package dev.sumin.skeleton.account

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 메모리 저장소의 아이디 연산 — JDBC 구현(`JdbcLoginIdDbTest`)과 같은 약속을 지킨다 */
class InMemoryLoginIdRepositoryTest {
    private val repo = InMemoryAccountRepository()
    private val now = Instant.parse("2026-10-09T00:00:00Z")
    private val blocks = AccountBlocks.local(MutableTime())

    private fun account(id: String, email: String? = "$id@example.com", status: AccountStatus = AccountStatus.ACTIVE, loginId: String? = null) =
        Account(id, email, true, status, setOf("USER"), null, null, null, now, now, loginId = loginId)

    private fun hash(id: String) = blocks.loginIdHash(id)

    @Test
    fun `an insert with a login id and its hash can be found by the id and the hash is used`() {
        assertTrue(repo.insert(account("a1", loginId = "ann"), emptyList(), hash("ann")))
        assertEquals("a1", repo.findByLoginId("ann")?.id)
        assertTrue(repo.loginIdUsed(hash("ann")))
        assertFalse(repo.loginIdUsed(hash("bob")))
        assertNull(repo.findByLoginId("bob"))
    }

    @Test
    fun `an insert without a hash keeps working as before and leaves the login id empty`() {
        assertTrue(repo.insert(account("a1"), emptyList()))
        assertNull(repo.findById("a1")?.loginId)
    }

    @Test
    fun `an insert whose login id or hash is taken is refused and writes nothing`() {
        assertTrue(repo.insert(account("a1", loginId = "ann"), emptyList(), hash("ann")))
        assertFalse(repo.insert(account("a2", loginId = "ann"), emptyList(), hash("ann")))
        assertNull(repo.findById("a2"))
        // 해시만 겹쳐도 (살아 있는 줄은 없지만 쓰인 아이디) 거절
        assertFalse(repo.insert(account("a4", loginId = "other"), emptyList(), hash("ann")))
        assertNull(repo.findByLoginId("other"))
    }

    @Test
    fun `claiming gives the five results`() {
        repo.insert(account("a1"), emptyList())
        repo.insert(account("a2", status = AccountStatus.SUSPENDED), emptyList())
        repo.insert(account("a3"), emptyList())
        assertEquals(ClaimLoginIdResult.CLAIMED, repo.claimLoginId("a1", "ann", hash("ann"), now))
        assertEquals("ann", repo.findById("a1")?.loginId)
        assertEquals(ClaimLoginIdResult.ALREADY_SET, repo.claimLoginId("a1", "ann2", hash("ann2"), now))
        assertFalse(repo.loginIdUsed(hash("ann2")), "a refused claim leaves no hash behind")
        assertEquals(ClaimLoginIdResult.TAKEN, repo.claimLoginId("a3", "ann", hash("ann"), now))
        assertEquals(ClaimLoginIdResult.NOT_ACTIVE, repo.claimLoginId("a2", "bob", hash("bob"), now))
        assertFalse(repo.loginIdUsed(hash("bob")))
        assertEquals(ClaimLoginIdResult.NOT_FOUND, repo.claimLoginId("nope", "cat", hash("cat"), now))
    }

    @Test
    fun `erasing empties the login id but the hash stays so nobody can take it again`() {
        repo.insert(account("a2"), emptyList())
        // a3 은 아이디를 정한 뒤 탈퇴 유예가 끝난 계정이다
        repo.insert(account("a3", status = AccountStatus.DELETED, loginId = "cat").copy(purgeAfter = now.minusSeconds(1)), emptyList(), hash("cat"))
        assertTrue(repo.erase("a3", now))
        assertNull(repo.findById("a3")?.loginId)
        assertNull(repo.findByLoginId("cat"))
        assertTrue(repo.loginIdUsed(hash("cat")))
        assertEquals(ClaimLoginIdResult.TAKEN, repo.claimLoginId("a2", "cat", hash("cat"), now))
    }

    @Test
    fun `a purged row also leaves the hash`() {
        repo.insert(account("a1", loginId = "ann", status = AccountStatus.DELETED).copy(purgeAfter = now.minusSeconds(1)), emptyList(), hash("ann"))
        assertTrue(repo.purge("a1", now))
        assertNull(repo.findById("a1"))
        assertTrue(repo.loginIdUsed(hash("ann")))
    }

    @Test
    fun `an erased row cannot claim`() {
        repo.insert(account("a1", status = AccountStatus.DELETED).copy(purgeAfter = now.minusSeconds(1)), emptyList())
        repo.erase("a1", now)
        assertEquals(ClaimLoginIdResult.NOT_FOUND, repo.claimLoginId("a1", "ann", hash("ann"), now))
    }

    @Test
    fun `search matches a login id exactly and combines with the other conditions`() {
        repo.insert(account("a1", loginId = "ann"), emptyList(), hash("ann"))
        repo.insert(account("a2", loginId = "ann2"), emptyList(), hash("ann2"))
        assertEquals(listOf("a1"), repo.search(null, null, 0, 10, loginId = "ann").items.map { it.id })
        assertEquals(emptyList(), repo.search(null, null, 0, 10, loginId = "an").items)
        assertEquals(emptyList(), repo.search("a2@", null, 0, 10, loginId = "ann").items)
        assertEquals(2L, repo.search(null, null, 0, 10).total)
        assertNotNull(repo.search(null, AccountStatus.ACTIVE, 0, 10, loginId = "ann2").items.singleOrNull())
    }
}
