package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountProperties.DisplayName.Uniqueness
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.common.FieldValidationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorProfileServiceTest {
    private fun fieldCode(h: Harness, raw: String?): String {
        val e = assertFailsWith<FieldValidationException> { h.service.create("acc_1", raw) }
        assertEquals("name", e.field, "the field is the profile name, not the account's displayName")
        return e.fieldCode
    }

    @Test
    fun `create stores an active profile with a prf id, the cleaned name and a tag`() {
        val h = Harness()
        val p = h.service.create("acc_1", "  Moon   Light ")
        assertTrue(Regex("prf_[0-9a-f]{32}").matches(p.id), p.id)
        assertEquals("acc_1", p.accountId)
        assertEquals("Moon Light", p.name)
        assertEquals(h.names.key("Moon Light"), p.nameKey)
        assertNotNull(p.tag)
        assertEquals(AuthorProfileStatus.ACTIVE, p.status)
        assertNull(p.primaryOf, "only the first profile owns the primary slot")
        assertEquals(T0, p.createdAt)
        assertEquals(listOf(p), h.service.list("acc_1"))
    }

    @Test
    fun `profile ids are random - two profiles share nothing derived from the account or each other`() {
        val h = Harness()
        val a = h.service.create("acc_1", "A")
        val b = h.service.create("acc_1", "B")
        assertNotEquals(a.id, b.id)
        assertTrue(listOf(a.id, b.id).none { it.contains("acc_1") })
    }

    @Test
    fun `names follow the nickname rules - empty, too long, forbidden characters and reserved words`() {
        val h = Harness(reserved = listOf("admin"))
        assertEquals("Required", fieldCode(h, "   "))
        assertEquals("Required", fieldCode(h, null))
        assertEquals("Size", fieldCode(h, "x".repeat(61)))
        assertEquals("Pattern", fieldCode(h, "a#b"))
        assertEquals("Reserved", fieldCode(h, "A d m i n"))
        assertTrue(h.service.list("acc_1").isEmpty(), "rejected names store nothing")
    }

    @Test
    fun `a rejected name consumes neither a slot nor the create limit`() {
        val h = Harness(properties = AuthorProfileProperties(maxPerAccount = 1, createLimit = 1))
        repeat(3) { assertFailsWith<FieldValidationException> { h.service.create("acc_1", "a@b") } }
        assertEquals("ok", h.service.create("acc_1", "ok").name)
    }

    @Test
    fun `the number of living profiles is capped per account and a deleted one frees its slot`() {
        val h = Harness(properties = AuthorProfileProperties(maxPerAccount = 2, createLimit = 0))
        val first = h.service.create("acc_1", "A")
        h.service.create("acc_1", "B")
        assertEquals(AuthorProfileErrorCode.LIMIT_REACHED, h.failure { h.service.create("acc_1", "C") }.errorCode)
        assertEquals("C", h.service.create("acc_2", "C").name, "the cap is per account")
        h.service.delete("acc_1", first.id)
        assertEquals("C", h.service.create("acc_1", "C").name)
    }

    @Test
    fun `creating is limited per window and deleting then creating again does not get around it`() {
        val h = Harness(properties = AuthorProfileProperties(maxPerAccount = 5, createLimit = 2, createWindow = Duration.ofDays(30)))
        val a = h.service.create("acc_1", "A")
        h.service.delete("acc_1", a.id)
        h.service.create("acc_1", "B")
        val e = assertFailsWith<RateLimitedException> { h.service.create("acc_1", "C") }
        assertEquals(AuthorProfileErrorCode.RATE_LIMITED, e.errorCode)
        assertTrue(e.retryAfterSeconds >= 1)
        assertEquals("C", h.service.create("acc_2", "C").name, "the limit is per account")
    }

    @Test
    fun `create-limit 0 means no limit`() {
        val h = Harness(properties = AuthorProfileProperties(maxPerAccount = 1, createLimit = 0))
        repeat(10) { i -> val p = h.service.create("acc_1", "n$i"); h.service.delete("acc_1", p.id) }
        assertEquals(0, h.service.list("acc_1").size)
    }

    @Test
    fun `the cap is checked before the create limit is spent`() {
        val h = Harness(properties = AuthorProfileProperties(maxPerAccount = 1, createLimit = 2))
        h.service.create("acc_1", "A")
        repeat(5) { assertEquals(AuthorProfileErrorCode.LIMIT_REACHED, h.failure { h.service.create("acc_1", "B") }.errorCode) }
    }

    @Test
    fun `tagged - the same name gets different tags on different profiles`() {
        val h = Harness()
        val tags = (1..5).map { h.service.create("acc_$it", "Same").tag }
        assertEquals(5, tags.toSet().size)
        assertTrue(tags.all { it != null && it != "0000" })
    }

    @Test
    fun `tagged - when every draw clashes the free tag is looked up directly`() {
        // 첫 시도가 모두 같은 꼬리표(0001)로 겹쳐도 빈 꼬리표를 직접 찾는다
        val h = Harness(random = ScriptedRandom(List(40) { 0 }))
        h.service.create("acc_1", "Same")
        val second = h.service.create("acc_2", "Same")
        assertNotEquals("0001", second.tag)
        assertNotNull(second.tag)
    }

    @Test
    fun `the tag draw avoids a tag an account with the same name key already shows`() {
        // 계정 acc_old 는 "Foo#0001" — 아직 프로필로 넘겨받지 않은 옛 작성자. 새 프로필의 첫 추첨이 0001 이어도 피한다
        val h = Harness(random = ScriptedRandom(listOf(0, 0, 0, 0, 0, 0, 0, 0)))
        h.account("acc_old", "Foo", "0001")
        val p = h.service.create("acc_new", "foo")
        assertNotEquals("0001", p.tag)
    }

    @Test
    fun `unique - a taken name is a conflict across accounts and across profiles of one account, and deleting frees it`() {
        val h = Harness(uniqueness = Uniqueness.UNIQUE)
        val a = h.service.create("acc_1", "Moon")
        assertEquals("0000", a.tag)
        assertEquals(AuthorProfileErrorCode.NAME_TAKEN, h.failure { h.service.create("acc_2", "MOON") }.errorCode)
        assertEquals(AuthorProfileErrorCode.NAME_TAKEN, h.failure { h.service.create("acc_1", "moon") }.errorCode)
        h.service.delete("acc_1", a.id)
        assertEquals("MOON", h.service.create("acc_2", "MOON").name)
    }

    @Test
    fun `unique - a name an account without profile already shows is taken too`() {
        val h = Harness(uniqueness = Uniqueness.UNIQUE)
        h.account("acc_old", "Foo", "0000")
        assertEquals(AuthorProfileErrorCode.NAME_TAKEN, h.failure { h.service.create("acc_new", "foo") }.errorCode)
    }

    @Test
    fun `none - the same name can be used by anyone and carries no tag`() {
        val h = Harness(uniqueness = Uniqueness.NONE)
        assertNull(h.service.create("acc_1", "Same").tag)
        assertNull(h.service.create("acc_2", "Same").tag)
    }

    // ---- rename ----

    @Test
    fun `renaming to the same key keeps the tag and a different key draws a new one`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        val cased = h.service.rename("acc_1", p.id, "MOON")
        assertEquals("MOON", cased.name)
        assertEquals(p.tag, cased.tag, "same comparison key - the tag stays")
        val other = h.service.rename("acc_1", p.id, "Sun")
        assertEquals("Sun", other.name)
        assertEquals(h.names.key("Sun"), other.nameKey)
        assertNotNull(other.tag)
        assertEquals(other, h.service.list("acc_1").single())
        assertTrue(other.updatedAt >= p.updatedAt)
    }

    @Test
    fun `renaming to a different key redraws - it is not derived from the old tag`() {
        val h = Harness(random = ScriptedRandom(listOf(41, 8000)))   // 첫 추첨 0042, 두 번째 8001
        val p = h.service.create("acc_1", "Moon")
        assertEquals("0042", p.tag)
        assertEquals("8001", h.service.rename("acc_1", p.id, "Sun").tag)
    }

    @Test
    fun `renaming to the same value is a no-op that spends no change limit`() {
        val h = Harness(changeLimit = 1)
        val p = h.service.create("acc_1", "Moon")
        repeat(4) { assertEquals(p, h.service.rename("acc_1", p.id, " Moon ")) }
        assertEquals("Sun", h.service.rename("acc_1", p.id, "Sun").name)
    }

    @Test
    fun `the rename limit is the account's display-name limit counted over all profiles together`() {
        val h = Harness(changeLimit = 2)
        val a = h.service.create("acc_1", "A")
        val b = h.service.create("acc_1", "B")
        h.service.rename("acc_1", a.id, "A2")
        h.service.rename("acc_1", b.id, "B2")
        val e = assertFailsWith<RateLimitedException> { h.service.rename("acc_1", a.id, "A3") }
        assertEquals(AuthorProfileErrorCode.RATE_LIMITED, e.errorCode)
        assertEquals("X", h.service.rename("acc_2", h.service.create("acc_2", "Y").id, "X").name, "other accounts have their own count")
    }

    @Test
    fun `a rejected rename spends nothing and changes nothing`() {
        val h = Harness(changeLimit = 1, reserved = listOf("admin"))
        val p = h.service.create("acc_1", "Moon")
        assertFailsWith<FieldValidationException> { h.service.rename("acc_1", p.id, "admin") }
        assertEquals("Moon", h.service.list("acc_1").single().name)
        assertEquals("Sun", h.service.rename("acc_1", p.id, "Sun").name)
    }

    @Test
    fun `unique - renaming onto a taken name is a conflict and keeps the old name`() {
        val h = Harness(uniqueness = Uniqueness.UNIQUE)
        h.service.create("acc_1", "Moon")
        val p = h.service.create("acc_2", "Sun")
        assertEquals(AuthorProfileErrorCode.NAME_TAKEN, h.failure { h.service.rename("acc_2", p.id, "moon") }.errorCode)
        assertEquals("Sun", h.service.list("acc_2").single().name)
    }

    @Test
    fun `renaming someone else's, a missing and a deleted profile all fail the same way`() {
        val h = Harness()
        val mine = h.service.create("acc_1", "Mine")
        val gone = h.service.create("acc_1", "Gone").also { h.service.delete("acc_1", it.id) }
        val errors = listOf(
            h.failure { h.service.rename("acc_2", mine.id, "X") },
            h.failure { h.service.rename("acc_1", "prf_" + "0".repeat(32), "X") },
            h.failure { h.service.rename("acc_1", gone.id, "X") },
            h.failure { h.service.rename("acc_1", "acc_1", "X") },
        )
        errors.forEach { assertEquals(AuthorProfileErrorCode.NOT_FOUND, it.errorCode) }
        assertEquals(1, errors.map { it.message }.toSet().size, "the same wording too")
        assertEquals("Mine", h.service.list("acc_1").single().name)
    }

    // ---- delete ----

    @Test
    fun `delete is immediate - the row stays as DELETED without name, key and tag`() {
        val h = Harness()
        val p = h.service.create("acc_1", "Moon")
        h.service.delete("acc_1", p.id)
        val row = h.repository.find(p.id)!!
        assertEquals(AuthorProfileStatus.DELETED, row.status)
        assertNull(row.name); assertNull(row.nameKey); assertNull(row.tag)
        assertEquals(T0, row.deletedAt)
        assertEquals("acc_1", row.accountId)
        assertTrue(h.service.list("acc_1").isEmpty())
    }

    @Test
    fun `deleting someone else's, a missing and an already deleted profile all fail the same way`() {
        val h = Harness()
        val mine = h.service.create("acc_1", "Mine")
        val gone = h.service.create("acc_1", "Gone").also { h.service.delete("acc_1", it.id) }
        val errors = listOf(
            h.failure { h.service.delete("acc_2", mine.id) },
            h.failure { h.service.delete("acc_1", "prf_" + "0".repeat(32)) },
            h.failure { h.service.delete("acc_1", gone.id) },
        )
        errors.forEach { assertEquals(AuthorProfileErrorCode.NOT_FOUND, it.errorCode) }
        assertEquals(1, errors.map { it.message }.toSet().size)
        assertEquals(1, h.service.list("acc_1").size)
    }

    // ---- first profile ----

    @Test
    fun `the first profile copies the account's name and tag once`() {
        val h = Harness()
        h.account("acc_1", "Moon", "0417")
        val first = h.service.ensureFirstProfile("acc_1")
        assertEquals("acc_1", first.primaryOf)
        assertEquals("Moon", first.name)
        assertEquals("0417", first.tag)
        assertEquals(h.names.key("Moon"), first.nameKey)
        assertNull(first.adoptedAt)
        assertTrue(first.id.startsWith("prf_"))
        assertNotEquals("acc_1", first.id)
    }

    @Test
    fun `ensuring the first profile again returns the same one and creates nothing`() {
        val h = Harness()
        h.account("acc_1", "Moon", "0417")
        val first = h.service.ensureFirstProfile("acc_1")
        repeat(3) { assertEquals(first, h.service.ensureFirstProfile("acc_1")) }
        assertEquals(1, h.repository.all().size)
    }

    @Test
    fun `a colliding tag is drawn anew for the first profile`() {
        val h = Harness()
        h.service.create("acc_other", "Moon").also { h.repository.rename(it.id, "acc_other", "Moon", h.names.key("Moon"), "0417", T0) }
        h.account("acc_1", "Moon", "0417")
        val first = h.service.ensureFirstProfile("acc_1")
        assertEquals("Moon", first.name)
        assertNotEquals("0417", first.tag)
        assertNotNull(first.tag)
    }

    @Test
    fun `an account without a name gets a first profile without a name`() {
        val h = Harness()
        h.account("acc_1")
        val first = h.service.ensureFirstProfile("acc_1")
        assertNull(first.name); assertNull(first.nameKey); assertNull(first.tag)
        assertEquals(AuthorProfileStatus.ACTIVE, first.status)
    }

    @Test
    fun `a tag that no longer fits the current mode is drawn anew`() {
        val h = Harness(uniqueness = Uniqueness.UNIQUE)
        h.account("acc_1", "Moon", "0417")   // 방식을 바꾸기 전에 저장된 꼬리표
        assertEquals("0000", h.service.ensureFirstProfile("acc_1").tag)
    }

    @Test
    fun `a deleted first profile keeps its place - the first profile is not made again`() {
        val h = Harness()
        h.account("acc_1", "Moon", "0417")
        val first = h.service.ensureFirstProfile("acc_1")
        h.service.delete("acc_1", first.id)
        val again = h.service.ensureFirstProfile("acc_1")
        assertEquals(first.id, again.id)
        assertEquals(AuthorProfileStatus.DELETED, again.status)
        assertEquals(1, h.repository.all().size)
    }

    @Test
    fun `the first profile does not use up the create limit or the cap check`() {
        val h = Harness(properties = AuthorProfileProperties(maxPerAccount = 1, createLimit = 1))
        h.account("acc_1", "Moon", "0417")
        h.service.ensureFirstProfile("acc_1")
        assertEquals(AuthorProfileErrorCode.LIMIT_REACHED, h.failure { h.service.create("acc_1", "Other") }.errorCode)
    }

    @Test
    fun `an unknown or erased account has no first profile`() {
        val h = Harness()
        h.account("acc_gone", "Moon", "0001", status = dev.sumin.skeleton.account.AccountStatus.ERASED)
        assertEquals(AuthorProfileErrorCode.NOT_FOUND, h.failure { h.service.ensureFirstProfile("acc_missing") }.errorCode)
        assertEquals(AuthorProfileErrorCode.NOT_FOUND, h.failure { h.service.ensureFirstProfile("acc_gone") }.errorCode)
        assertTrue(h.repository.all().isEmpty())
    }

    @Test
    fun `an account in its deletion grace or suspended still gets its profile`() {
        val h = Harness()
        h.account("acc_1", "Moon", "0001", status = dev.sumin.skeleton.account.AccountStatus.SUSPENDED)
        assertEquals("Moon", h.service.ensureFirstProfile("acc_1").name)
    }

    @Test
    fun `marking the first profile adopted is recorded once`() {
        val h = Harness()
        h.account("acc_1", "Moon", "0001")
        h.service.ensureFirstProfile("acc_1")
        h.service.markAdopted("acc_1")
        assertEquals(T0, h.repository.findPrimary("acc_1")!!.adoptedAt)
    }
}
