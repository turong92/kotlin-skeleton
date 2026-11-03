package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspace.WorkspaceStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** 두 방언에서 — 공지 대상 `workspace` 는 열린 워크스페이스의 멤버만 id 순으로 내고, 떠난 멤버 · 닫힌 워크스페이스는 내지 않고, 키셋은 빠지거나 겹치지 않는다 */
@Import(DbTestcontainers::class, WorkspaceAudienceDbTest.Facts::class)
@SpringBootTest(classes = [WorkspaceJdbcTestApplication::class])
class WorkspaceAudienceDbTest {
    @Autowired lateinit var service: WorkspaceService
    @Autowired lateinit var store: WorkspaceStore
    @Autowired lateinit var sources: List<AudienceSource>

    /** 계정 모듈 대신 — 이름이 `_off` 로 끝나는 계정은 정지 상태다 */
    @TestConfiguration(proxyBeanMethods = false)
    class Facts {
        @Bean
        fun accountFacts(): AccountFacts = object : AccountFacts {
            override fun createdAt(accountId: String): Instant? = Instant.EPOCH
            override fun activeAmong(accountIds: Collection<String>): Set<String> = accountIds.filterNot { it.endsWith("_off") }.toSet()
        }
    }

    private val seq = UUID.randomUUID().toString().take(8)
    private fun acc(name: String) = "acc_${seq}_$name"
    private val audience get() = sources.single { it.kind == "workspace" }

    private fun team(name: String, owner: String, vararg members: String): String {
        val id = service.create(owner, name, "a$seq-$name").workspace.id
        members.forEachIndexed { i, who -> store.insertMember(WorkspaceMember(id, who, WorkspaceRole.MEMBER, Instant.now().plusSeconds(i + 1L))) }
        return id
    }

    private fun walk(param: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var after: String? = null
        while (true) {
            val page = audience.page(param, after, limit)
            if (page.isEmpty()) return out
            out += page
            after = page.last()
        }
    }

    @Test
    fun `members of an open team only, in id order, without gaps or overlap, and a departed member drops out`() {
        val id = team("one", acc("c"), acc("e"), acc("a"), acc("d"), acc("b"))
        team("other", acc("z"), acc("a"))
        val expected = listOf("a", "b", "c", "d", "e").map(::acc)

        assertEquals(5, audience.count(id))
        listOf(1, 2, 5, 10).forEach { assertEquals(expected, walk(id, it), "limit $it") }
        assertEquals(listOf(acc("d"), acc("e")), audience.page(id, acc("c"), 10), "after is exclusive")

        store.deleteMember(id, acc("c"))
        assertEquals(4, audience.count(id))
        assertEquals(expected - acc("c"), walk(id, 2))
    }

    @Test
    fun `validate refuses a missing, unknown or closed team, and a closed team has no audience`() {
        val id = team("shut", acc("o"), acc("m"))
        assertNull(audience.validate(id))
        assertNotNull(audience.validate(null))
        assertNotNull(audience.validate("no-such-team"))

        store.closeWorkspace(id, Instant.now())
        assertNotNull(audience.validate(id))
        assertEquals(0, audience.count(id))
        assertEquals(emptyList(), audience.page(id, null, 10))
    }

    @Test
    fun `suspended members are left out of the count and the pages, even when a whole page of them comes first`() {
        val id = team("mixed", acc("a"), acc("b_off"), acc("c_off"), acc("d_off"), acc("e"), acc("f_off"), acc("g"))
        val expected = listOf("a", "e", "g").map(::acc)

        assertEquals(3, audience.count(id))
        listOf(1, 2, 10).forEach { assertEquals(expected, walk(id, it), "limit $it") }
        assertEquals(listOf(acc("e")), audience.page(id, acc("a"), 1), "the page fills past the suspended rows")
    }
}
