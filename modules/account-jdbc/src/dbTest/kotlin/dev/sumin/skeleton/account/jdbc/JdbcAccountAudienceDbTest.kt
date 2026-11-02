package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.common.audience.AudienceSource
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 두 DB 에서 — 공지 대상 `all` · `role` 은 활성 계정만 내고(정지 · 대기 · 삭제 유예 · 지워진 계정 제외), 키셋은 경계에서 빠지거나 겹치지 않는다 */
class JdbcAccountAudienceDbTest {
    private val now = Instant.parse("2026-11-02T00:00:00Z")
    private val all: AudienceSource = JdbcAccountAudiences.all(AccountDb.jdbc)
    private val role: AudienceSource = JdbcAccountAudiences.role(AccountDb.jdbc)

    @BeforeTest fun clean() = AccountDb.clean()

    private fun account(id: String, status: AccountStatus, vararg roles: String) {
        assertTrue(AccountDb.accounts.insert(Account(id, "$id@example.com", true, status, roles.toSet(), null, null, null, now, now), emptyList()))
    }

    private fun walk(source: AudienceSource, param: String?, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var after: String? = null
        while (true) {
            val page = source.page(param, after, limit)
            if (page.isEmpty()) return out
            out += page
            after = page.last()
        }
    }

    @Test
    fun `all pages the active accounts only, in id order, without gaps or overlap at any page size`() {
        account("a1", AccountStatus.ACTIVE, "USER")
        account("a2", AccountStatus.PENDING_VERIFICATION, "USER")
        account("a3", AccountStatus.ACTIVE)
        account("a4", AccountStatus.SUSPENDED, "USER")
        account("a5", AccountStatus.DELETED, "USER")
        account("a6", AccountStatus.ERASED)
        account("a7", AccountStatus.ACTIVE, "USER", "EDITOR")
        val expected = listOf("a1", "a3", "a7")

        assertEquals(3, all.count(null))
        listOf(1, 2, 3, 10).forEach { assertEquals(expected, walk(all, null, it), "limit $it") }
        assertEquals(listOf("a3", "a7"), all.page(null, "a1", 10), "after is exclusive")
        assertEquals(emptyList(), all.page(null, "a7", 10), "the last id ends the walk")
        assertEquals(listOf("a3"), all.page(null, "a2", 1), "a cursor that is no longer an audience member still works")
    }

    @Test
    fun `role pages the active holders of that role only and counts the same people`() {
        account("b1", AccountStatus.ACTIVE, "USER", "EDITOR")
        account("b2", AccountStatus.SUSPENDED, "EDITOR")
        account("b3", AccountStatus.ACTIVE, "USER")
        account("b4", AccountStatus.PENDING_VERIFICATION, "EDITOR")
        account("b5", AccountStatus.ERASED, "EDITOR")
        account("b6", AccountStatus.DELETED, "EDITOR")
        account("b7", AccountStatus.ACTIVE, "EDITOR")

        assertEquals(2, role.count("EDITOR"))
        listOf(1, 2, 10).forEach { assertEquals(listOf("b1", "b7"), walk(role, "EDITOR", it), "limit $it") }
        assertEquals(listOf("b7"), role.page("EDITOR", "b1", 10))
        assertEquals(0, role.count("NOBODY"))
        assertEquals(emptyList(), role.page("NOBODY", null, 10))
    }

    @Test
    fun `validate - all takes no value, role needs a short one`() {
        assertNull(all.validate(null))
        assertNotNull(all.validate("USER"))
        assertNull(role.validate("EDITOR"))
        assertNotNull(role.validate(null))
        assertNotNull(role.validate(" "))
        assertNotNull(role.validate("x".repeat(41)))
    }
}
