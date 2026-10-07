package dev.sumin.skeleton.authorprofile.jdbc

import dev.sumin.skeleton.authorprofile.AuthorProfileStatus
import dev.sumin.skeleton.authorprofile.CapInsert
import dev.sumin.skeleton.authorprofile.RenameResult
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.T0
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.account
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.profile
import dev.sumin.skeleton.authorprofile.jdbc.ProfileDb.repository
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcAuthorProfileRepositoryDbTest {
    @Test
    fun `insert stores the first profile and every column comes back unchanged`() {
        val acc = account()
        val p = profile(acc, primary = true, name = "Moon Å", key = "moon å", tag = "0042")
        assertTrue(repository.insert(p))
        assertEquals(p, repository.find(p.id))
        assertEquals(p, repository.findPrimary(acc))
    }

    @Test
    fun `a second first profile for the same account is refused and changes nothing`() {
        val acc = account()
        val first = profile(acc, primary = true)
        assertTrue(repository.insert(first))
        assertFalse(repository.insert(profile(acc, primary = true)))
        assertEquals(1, ProfileDb.rows(acc))
        assertEquals(first, repository.findPrimary(acc))
    }

    @Test
    fun `insert is refused when the key and tag are taken by someone else - whichever column clashed`() {
        val a = profile(account(), primary = true, name = "Same", key = "same-k1", tag = "0007")
        assertTrue(repository.insert(a))
        val b = profile(account(), primary = true, name = "Same", key = "same-k1", tag = "0007")
        assertFalse(repository.insert(b))
        assertNull(repository.find(b.id))
    }

    @Test
    fun `an unnamed first profile can be stored - name key and tag are all NULL`() {
        val acc = account()
        val p = profile(acc, primary = true, name = null, key = null, tag = null)
        assertTrue(repository.insert(p))
        assertEquals(p, repository.findPrimary(acc))
    }

    @Test
    fun `the key is compared exactly - an accent or a case difference is a different key`() {
        val tag = "0099"
        val base = "kk${System.nanoTime()}"
        assertTrue(repository.insert(profile(account(), primary = true, key = "${base}cafe", tag = tag)))
        assertTrue(repository.insert(profile(account(), primary = true, key = "${base}café", tag = tag)))
        assertTrue(repository.insert(profile(account(), primary = true, key = "${base}Cafe", tag = tag)))
        assertFalse(repository.insert(profile(account(), primary = true, key = "${base}cafe", tag = tag)))
    }

    @Test
    fun `rows whose key or tag is NULL never clash with each other - NONE mode and unnamed profiles`() {
        val k = "none${System.nanoTime()}"
        // 같은 키 + 꼬리표 NULL 둘 (NONE 방식): 겹치지 않는다
        assertTrue(repository.insert(profile(account(), primary = true, key = k, tag = null)))
        assertTrue(repository.insert(profile(account(), primary = true, key = k, tag = null)))
        // 키 NULL + 같은 꼬리표 둘 (이름 없는 첫 프로필이 꼬리표를 들고 있을 때)
        assertTrue(repository.insert(profile(account(), primary = true, name = null, key = null, tag = "0001")))
        assertTrue(repository.insert(profile(account(), primary = true, name = null, key = null, tag = "0001")))
    }

    @Test
    fun `find returns null for an unknown id and findAll returns only the rows that exist - deleted ones included`() {
        val acc = account()
        val a = profile(acc, primary = true)
        val b = profile(acc)
        repository.insert(a)
        repository.insertWithinCap(b, 5)
        repository.markDeleted(b.id, acc, T0.plusSeconds(1))
        assertNull(repository.find("prf_missing"))
        val found = repository.findAll(listOf(a.id, b.id, "prf_missing"))
        assertEquals(setOf(a.id, b.id), found.keys)
        assertEquals(AuthorProfileStatus.DELETED, found.getValue(b.id).status)
        assertEquals(emptyMap(), repository.findAll(emptyList()))
    }

    @Test
    fun `findAll is one query for a normal list and chunks a huge one`() {
        val acc = account()
        val a = profile(acc, primary = true)
        repository.insert(a)
        val few = List(50) { "prf_x$it" } + a.id
        assertEquals(1, ProfileDb.dataSource.count { assertEquals(setOf(a.id), repository.findAll(few).keys) })
        val many = List(2500) { "prf_y$it" } + a.id
        assertEquals(setOf(a.id), repository.findAll(many).keys)
    }

    @Test
    fun `findActive lists living profiles in creation order and the id breaks a tie`() {
        val acc = account()
        val first = profile(acc, primary = true, at = T0)
        val late = profile(acc, at = T0.plusSeconds(5), id = "prf_aaa-late-$acc")
        val tie1 = profile(acc, at = T0.plusSeconds(2), id = "prf_b-$acc")
        val tie2 = profile(acc, at = T0.plusSeconds(2), id = "prf_a-$acc")
        val gone = profile(acc, at = T0.plusSeconds(3))
        repository.insert(first)
        listOf(late, tie1, tie2, gone).forEach { repository.insertWithinCap(it, 10) }
        repository.markDeleted(gone.id, acc, T0.plusSeconds(9))
        assertEquals(listOf(first.id, tie2.id, tie1.id, late.id), repository.findActive(acc).map { it.id })
        assertEquals(4, repository.countActive(acc))
        assertEquals(emptyList(), repository.findActive(account()))
        assertEquals(0, repository.countActive(account()))
    }

    @Test
    fun `findPrimary still returns a deleted first profile`() {
        val acc = account()
        val first = profile(acc, primary = true)
        repository.insert(first)
        assertTrue(repository.markDeleted(first.id, acc, T0))
        val back = assertNotNull(repository.findPrimary(acc))
        assertEquals(AuthorProfileStatus.DELETED, back.status)
        assertEquals(acc, back.primaryOf)
        assertNull(repository.findPrimary(account()))
    }

    @Test
    fun `tagsOf and primariesByKey look at living profiles only`() {
        val key = "tk${System.nanoTime()}"
        val a = profile(account(), primary = true, key = key, tag = "0001")
        val b = profile(account(), primary = true, key = key, tag = "0002")
        val front = profile(account(), primary = true, name = "front", key = "front-$key", tag = "0001")
        val c = profile(front.accountId, key = key, tag = "0003")   // 첫 프로필이 아닌 줄 — 꼬리표는 쓰지만 primariesByKey 에는 없다
        val other = profile(account(), primary = true, key = "$key-other", tag = "0004")
        listOf(a, b, front, other).forEach { assertTrue(repository.insert(it)) }
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(c, 9))
        assertEquals(setOf("0001", "0002", "0003"), repository.tagsOf(key))
        assertEquals(setOf(a.id, b.id), repository.primariesByKey(key).map { it.id }.toSet())
        // 지우면 꼬리표가 풀리고 키 조회에서 빠진다
        repository.markDeleted(b.id, b.accountId, T0)
        assertEquals(setOf("0001", "0003"), repository.tagsOf(key))
        assertEquals(listOf(a.id), repository.primariesByKey(key).map { it.id })
    }

    @Test
    fun `rename changes name key and tag together and leaves everything else alone`() {
        val acc = account()
        val p = profile(acc, primary = true, name = "Old", key = "old-k", tag = "0001")
        repository.insert(p)
        val now = T0.plusSeconds(60)
        assertEquals(RenameResult.DONE, repository.rename(p.id, acc, "New", "new-k${p.id}", "0002", now))
        val back = repository.find(p.id)!!
        assertEquals(p.copy(name = "New", nameKey = "new-k${p.id}", tag = "0002", updatedAt = now), back)
        // 꼬리표를 비운다 (NONE)
        assertEquals(RenameResult.DONE, repository.rename(p.id, acc, "New", "new-k${p.id}", null, now))
        assertNull(repository.find(p.id)!!.tag)
    }

    @Test
    fun `rename onto a taken key and tag is TAKEN and changes nothing - a rename to its own values is fine`() {
        val key = "rk${System.nanoTime()}"
        val a = profile(account(), primary = true, key = key, tag = "0001")
        val b = profile(account(), primary = true, name = "B", key = "b-$key", tag = "0001")
        repository.insert(a); repository.insert(b)
        assertEquals(RenameResult.TAKEN, repository.rename(b.id, b.accountId, "A", key, "0001", T0.plusSeconds(1)))
        assertEquals(b, repository.find(b.id))
        assertEquals(RenameResult.DONE, repository.rename(b.id, b.accountId, "B2", "b-$key", "0001", T0.plusSeconds(2)))
    }

    @Test
    fun `rename of someone else's or a missing or a deleted profile is NOT_FOUND`() {
        val acc = account()
        val p = profile(acc, primary = true)
        val gone = profile(acc)
        repository.insert(p); repository.insertWithinCap(gone, 5)
        repository.markDeleted(gone.id, acc, T0)
        assertEquals(RenameResult.NOT_FOUND, repository.rename(p.id, account(), "X", "x${p.id}", "0001", T0))
        assertEquals(RenameResult.NOT_FOUND, repository.rename("prf_missing", acc, "X", "x", "0001", T0))
        assertEquals(RenameResult.NOT_FOUND, repository.rename(gone.id, acc, "X", "x${gone.id}", "0001", T0))
        assertEquals(p, repository.find(p.id))
    }

    @Test
    fun `markDeleted blanks name key and tag, stamps deleted_at, keeps primary_of, and frees the tag for a new profile`() {
        val acc = account()
        val key = "dk${System.nanoTime()}"
        val first = profile(acc, primary = true, key = key, tag = "0005")
        repository.insert(first)
        val at = T0.plusSeconds(30)
        assertTrue(repository.markDeleted(first.id, acc, at))
        val back = repository.find(first.id)!!
        assertEquals(AuthorProfileStatus.DELETED, back.status)
        assertNull(back.name); assertNull(back.nameKey); assertNull(back.tag)
        assertEquals(at, back.deletedAt)
        assertEquals(at, back.updatedAt)
        assertEquals(acc, back.primaryOf)
        // 같은 이름 · 꼬리표로 다시 만들 수 있다
        val again = profile(acc, key = key, tag = "0005")
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(again, 5))
        // 지워진 첫 프로필 자리는 남아 첫 프로필을 두 번 만들 수 없다
        assertFalse(repository.insert(profile(acc, primary = true)))
    }

    @Test
    fun `markDeleted is false for someone else's or a missing or an already deleted profile`() {
        val acc = account()
        val p = profile(acc, primary = true)
        repository.insert(p)
        assertFalse(repository.markDeleted(p.id, account(), T0))
        assertFalse(repository.markDeleted("prf_missing", acc, T0))
        assertTrue(repository.markDeleted(p.id, acc, T0))
        assertFalse(repository.markDeleted(p.id, acc, T0.plusSeconds(5)))
        assertEquals(T0, repository.find(p.id)!!.deletedAt)
    }

    @Test
    fun `markAdopted fills adopted_at once and never overwrites it`() {
        val acc = account()
        assertFalse(repository.markAdopted(acc, T0))
        val p = profile(acc, primary = true)
        repository.insert(p)
        assertTrue(repository.markAdopted(acc, T0.plusSeconds(1)))
        assertFalse(repository.markAdopted(acc, T0.plusSeconds(99)))
        assertEquals(T0.plusSeconds(1), repository.find(p.id)!!.adoptedAt)
    }

    @Test
    fun `countCreatedSince counts created profiles including deleted ones, never the first profile, and only this account`() {
        val acc = account()
        repository.insert(profile(acc, primary = true, at = T0))
        val old = profile(acc, at = T0.minus(Duration.ofDays(40)))
        val edge = profile(acc, at = T0)
        val recent = profile(acc, at = T0.plusSeconds(10))
        listOf(old, edge, recent).forEach { repository.insertWithinCap(it, 9) }
        val neighbour = account()
        repository.insert(profile(neighbour, primary = true))
        repository.insertWithinCap(profile(neighbour, at = T0.plusSeconds(10)), 9)
        repository.markDeleted(recent.id, acc, T0.plusSeconds(11))
        assertEquals(2, repository.countCreatedSince(acc, T0), "edge (>=) and the deleted recent count; the old one and the first do not")
        assertEquals(3, repository.countCreatedSince(acc, T0.minus(Duration.ofDays(60))))
        assertEquals(0, repository.countCreatedSince(acc, T0.plusSeconds(100)))
        assertEquals(0, repository.countCreatedSince(account(), T0.minus(Duration.ofDays(60))))
    }

    @Test
    fun `a name clash that fails to insert is not counted - no row appears`() {
        val acc = account()
        val key = "ck${System.nanoTime()}"
        repository.insert(profile(acc, primary = true, key = "first-$key", tag = "0001"))
        assertEquals(CapInsert.INSERTED, repository.insertWithinCap(profile(acc, key = key, tag = "0001"), 9))
        assertEquals(CapInsert.NAME_TAKEN, repository.insertWithinCap(profile(acc, key = key, tag = "0001"), 9))
        assertEquals(1, repository.countCreatedSince(acc, T0.minusSeconds(1)))
    }

    @Test
    fun `deleteByAccount removes every row of the account - deleted ones too - and is idempotent`() {
        val acc = account()
        val keep = account()
        repository.insert(profile(acc, primary = true))
        val gone = profile(acc)
        repository.insertWithinCap(gone, 9)
        repository.insertWithinCap(profile(acc), 9)
        repository.markDeleted(gone.id, acc, T0)
        repository.insert(profile(keep, primary = true))
        assertEquals(3, repository.deleteByAccount(acc))
        assertEquals(0, ProfileDb.rows(acc))
        assertNull(repository.find(gone.id))
        assertEquals(0, repository.deleteByAccount(acc))
        assertEquals(0, repository.deleteByAccount("acc_nobody"))
        assertEquals(1, ProfileDb.rows(keep))
        // 지운 뒤에는 첫 프로필 자리도 비어 다시 만들 수 있다 (새 계정으로 가입한 것과 같다)
        assertTrue(repository.insert(profile(acc, primary = true)))
    }
}
