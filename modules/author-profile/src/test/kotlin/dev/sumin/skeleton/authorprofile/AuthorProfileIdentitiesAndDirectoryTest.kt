package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountProperties.DisplayName.Uniqueness
import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorProfileIdentitiesAndDirectoryTest {
    private val ctx = AuthorContext("board", "general")

    // ---- AuthorIdentities ----

    @Test
    fun `allows is true for a living profile of the caller`() {
        val h = Harness()
        val mine = h.service.create("acc_1", "Mine")
        assertTrue(AuthorProfileIdentities(h.repository).allows("acc_1", mine.id, ctx))
    }

    @Test
    fun `someone else's, a missing, a deleted and a non-profile id all get the same false`() {
        val h = Harness()
        val mine = h.service.create("acc_1", "Mine")
        val gone = h.service.create("acc_1", "Gone").also { h.service.delete("acc_1", it.id) }
        val identities = AuthorProfileIdentities(h.repository)
        val answers = listOf(
            identities.allows("acc_2", mine.id, ctx),                 // 남의 id
            identities.allows("acc_1", "prf_" + "0".repeat(32), ctx), // 없는 id
            identities.allows("acc_1", gone.id, ctx),                 // 지워진 id
            identities.allows("acc_1", "acc_1", ctx),                 // 계정 id 를 공개 작성자 id 로 내밀기
            identities.allows("acc_1", "", ctx),
            identities.allows("acc_1", "deleted:abc", ctx),
        )
        assertEquals(List(answers.size) { false }, answers)
    }

    @Test
    fun `the context alone does not change the answer`() {
        val h = Harness()
        val mine = h.service.create("acc_1", "Mine")
        val identities = AuthorProfileIdentities(h.repository)
        assertTrue(identities.allows("acc_1", mine.id, AuthorContext("board")))
        assertTrue(identities.allows("acc_1", mine.id, AuthorContext("other", "x")))
    }

    // ---- AuthorDirectory ----

    private class RecordingDirectory : AuthorDirectory {
        val asked = mutableListOf<List<String>>()
        override fun resolve(ids: Collection<String>, context: AuthorContext): Map<String, AuthorCard> {
            asked += ids.toList()
            return ids.associateWith { AuthorCard("fallback-$it") }
        }
    }

    @Test
    fun `a living profile resolves to its name and tag`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        val cards = AuthorProfileDirectory(h.repository, AuthorDirectory.NONE).resolve(listOf(p.id), ctx)
        assertEquals(AuthorCard("Moon", p.tag), cards[p.id])
    }

    @Test
    fun `a unique-mode profile shows no tag`() {
        val h = Harness(uniqueness = Uniqueness.UNIQUE)
        val p = h.service.create("acc_1", "Moon")
        assertEquals(AuthorCard("Moon", null), AuthorProfileDirectory(h.repository, AuthorDirectory.NONE).resolve(listOf(p.id), ctx)[p.id])
    }

    @Test
    fun `a deleted profile resolves to a deleted card without a name`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        h.service.delete("acc_1", p.id)
        val cards = AuthorProfileDirectory(h.repository, AuthorDirectory.NONE).resolve(listOf(p.id), ctx)
        assertEquals(AuthorCard(null, null, deleted = true), cards[p.id])
    }

    @Test
    fun `an unknown profile id and a nameless first profile are left out`() {
        val h = Harness()
        h.account("acc_1")
        val nameless = h.service.ensureFirstProfile("acc_1")
        val cards = AuthorProfileDirectory(h.repository, AuthorDirectory.NONE).resolve(listOf(nameless.id, "prf_" + "f".repeat(32)), ctx)
        assertTrue(cards.isEmpty(), cards.toString())
    }

    @Test
    fun `ids that are not profile ids go to the fallback unchanged and profile ids never do`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        val fallback = RecordingDirectory()
        val cards = AuthorProfileDirectory(h.repository, fallback).resolve(listOf("acc_old", p.id, "acc_old2"), ctx)
        assertEquals(listOf(listOf("acc_old", "acc_old2")), fallback.asked)
        assertEquals(AuthorCard("fallback-acc_old"), cards["acc_old"])
        assertEquals(AuthorCard("Moon", p.tag), cards[p.id])
        assertEquals(setOf("acc_old", "acc_old2", p.id), cards.keys)
    }

    @Test
    fun `the fallback is not asked when every id is a profile id or when there are no ids`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        val fallback = RecordingDirectory()
        val directory = AuthorProfileDirectory(h.repository, fallback)
        directory.resolve(listOf(p.id), ctx)
        assertTrue(directory.resolve(emptyList(), ctx).isEmpty())
        assertTrue(fallback.asked.isEmpty())
    }

    @Test
    fun `profiles are read with one repository call`() {
        val h = Harness()
        val ids = (1..4).map { h.service.create("acc_$it", "N$it").id }
        var calls = 0
        val counting = object : AuthorProfileRepository by h.repository {
            override fun findAll(ids: Collection<String>): Map<String, AuthorProfile> { calls++; return h.repository.findAll(ids) }
            override fun find(id: String): AuthorProfile? = error("the directory must not read one by one")
        }
        assertEquals(4, AuthorProfileDirectory(counting, AuthorDirectory.NONE).resolve(ids, ctx).size)
        assertEquals(1, calls)
    }

    @Test
    fun `a deleted card carries nothing of the old profile`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        h.service.delete("acc_1", p.id)
        val card = AuthorProfileDirectory(h.repository, AuthorDirectory.NONE).resolve(listOf(p.id), ctx).getValue(p.id)
        assertNull(card.name); assertNull(card.tag); assertTrue(card.deleted)
    }
}
