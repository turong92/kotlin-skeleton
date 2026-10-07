package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorProfileErasureTest {
    private fun request(account: String) = ErasureRequest(account, AccountTombstone.of(account))

    @Test
    fun `erasing an account deletes all its profile rows - living and deleted - and no other account's`() {
        val h = Harness()
        h.account("acc_1", "Moon", "0001")
        h.service.ensureFirstProfile("acc_1")
        val second = h.service.create("acc_1", "Second")
        h.service.delete("acc_1", second.id)
        val other = h.service.create("acc_2", "Other")
        val listener = AuthorProfileErasureListener(h.repository)

        listener.erase(request("acc_1"))

        assertTrue(h.repository.all().none { it.accountId == "acc_1" }, "no row keeps the siblings together")
        assertNull(h.repository.find(second.id))
        assertEquals(listOf(other), h.repository.all())
    }

    @Test
    fun `erasing is idempotent`() {
        val h = Harness()
        h.service.create("acc_1", "Moon")
        val listener = AuthorProfileErasureListener(h.repository)
        listener.erase(request("acc_1"))
        listener.erase(request("acc_1"))
        listener.erase(request("acc_nobody"))
        assertTrue(h.repository.all().isEmpty())
    }

    @Test
    fun `the listener is named after the module`() {
        assertEquals("author-profile", AuthorProfileErasureListener(FakeAuthorProfileRepository()).name)
    }

    @Test
    fun `a profile id after erasure resolves to nothing - the board rows keep the tombstone elsewhere`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        AuthorProfileErasureListener(h.repository).erase(request("acc_1"))
        assertTrue(AuthorProfileDirectory(h.repository, dev.sumin.skeleton.common.author.AuthorDirectory.NONE)
            .resolve(listOf(p.id), dev.sumin.skeleton.common.author.AuthorContext("board")).isEmpty())
        assertEquals(false, AuthorProfileIdentities(h.repository).allows("acc_1", p.id, dev.sumin.skeleton.common.author.AuthorContext("board")))
    }
}
