package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.workspace.WorkspaceErrorCode
import dev.sumin.skeleton.workspace.WorkspaceException
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspace.WorkspaceStatus
import dev.sumin.skeleton.workspace.WorkspaceStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient

/** 두 방언(postgresTest · mysqlTest)에서: 소유자는 늘 하나(동시 이전 · 역할 변경이 겹쳐도) · slug 는 하나만 이긴다 · 닫힘 · 삭제 안전망 · 초대 표의 유니크 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [WorkspaceJdbcTestApplication::class])
class WorkspaceDbTest {
    @Autowired lateinit var service: WorkspaceService
    @Autowired lateinit var store: WorkspaceStore
    @Autowired lateinit var check: List<AccountDeletionCheck>
    @Autowired lateinit var erasers: List<AccountErasureListener>
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dialect: SqlDialect

    private val seq = UUID.randomUUID().toString().take(8)
    private fun acc(name: String) = "acc_${seq}_$name"
    private fun slug(name: String) = "t$seq-$name"

    private fun ownersOf(wsId: String) = jdbc.sql("select count(*) from workspace_members where workspace_id = ? and role = 'OWNER'").param(wsId).query(Long::class.java).single()
    private fun ownerKeysOf(wsId: String) = jdbc.sql("select count(*) from workspace_members where workspace_id = ? and owner_key is not null").param(wsId).query(Long::class.java).single()

    private fun team(owner: String, name: String, vararg others: Pair<String, WorkspaceRole>): String {
        val id = service.create(owner, name, slug(name.lowercase().replace(' ', '-'))).workspace.id
        others.forEachIndexed { i, (who, role) -> store.insertMember(WorkspaceMember(id, who, role, Instant.now().plusSeconds(i + 1L))) }
        return id
    }

    private fun <T> concurrently(count: Int, block: (Int) -> T): List<Result<T>> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { i -> pool.submit<Result<T>> { start.await(); runCatching { block(i) } } }
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun `create writes the workspace and its single owner, and the boundary reads see only active teams`() {
        val id = team(acc("o"), "Create Team", acc("m") to WorkspaceRole.MEMBER)
        assertEquals(1, ownersOf(id))
        assertEquals(WorkspaceRole.MEMBER, store.activeMembership(id, acc("m"))!!.role)
        assertEquals(listOf(id), service.list(acc("m")).map { it.workspace.id })
        store.closeWorkspace(id, Instant.now())
        assertNull(store.activeMembership(id, acc("m")))
        assertEquals(emptyList(), service.list(acc("m")))
    }

    @Test
    fun `the database refuses a second owner row even when the application would try`() {
        val id = team(acc("o"), "Two Owners", acc("a") to WorkspaceRole.ADMIN)
        assertFailsWith<DuplicateKeyException> { store.setRole(id, acc("a"), WorkspaceRole.OWNER) }
        assertEquals(1, ownerKeysOf(id))
    }

    @Test
    fun `two simultaneous transfers by the owner commit exactly one owner and the loser is refused`() {
        repeat(5) { round ->
            val id = team(acc("o$round"), "Race Transfer $round", acc("b$round") to WorkspaceRole.MEMBER, acc("c$round") to WorkspaceRole.ADMIN)
            val results = concurrently(2) { i -> service.transfer(acc("o$round"), id, if (i == 0) acc("b$round") else acc("c$round")) }
            assertEquals(1, results.count { it.isSuccess }, "round $round: ${results.map { it.exceptionOrNull() }}")
            assertEquals(WorkspaceErrorCode.FORBIDDEN, (results.single { it.isFailure }.exceptionOrNull() as WorkspaceException).workspaceError, "the loser sees the old owner as an admin now")
            assertEquals(1, ownersOf(id))
            assertEquals(1, ownerKeysOf(id))
        }
    }

    @Test
    fun `a transfer racing a role change or a removal never leaves zero or two owners`() {
        repeat(5) { round ->
            val id = team(acc("po$round"), "Race Mixed $round", acc("pa$round") to WorkspaceRole.ADMIN, acc("pm$round") to WorkspaceRole.MEMBER)
            val results = concurrently(3) { i ->
                when (i) {
                    0 -> service.transfer(acc("po$round"), id, acc("pa$round"))
                    1 -> service.setRole(acc("po$round"), id, acc("pm$round"), WorkspaceRole.ADMIN)
                    else -> service.removeMember(acc("po$round"), id, acc("pm$round"))
                }
            }
            results.filter { it.isFailure }.forEach { val e = it.exceptionOrNull(); assertTrue(e is WorkspaceException, "only domain refusals, got $e") }
            assertEquals(1, ownersOf(id), "round $round")
            assertEquals(1, ownerKeysOf(id), "round $round")
        }
    }

    @Test
    fun `the same slug asked by several creators is won by exactly one`() {
        val results = concurrently(4) { i -> service.create(acc("s$i"), "Same Slug", slug("same")) }
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertEquals(WorkspaceErrorCode.SLUG_TAKEN, (it.exceptionOrNull() as WorkspaceException).workspaceError) }
    }

    @Test
    fun `automatic slugs of simultaneous creators with the same name are all different`() {
        val results = concurrently(4) { i -> service.create(acc("n$i"), "Auto Slug $seq").workspace.slug }
        assertEquals(4, results.count { it.isSuccess })
        assertEquals(4, results.map { it.getOrThrow() }.toSet().size)
    }

    @Test
    fun `a retired slug is kept in the history and only its own workspace can take it back`() {
        val a = team(acc("ho"), "History A")
        val b = team(acc("hp"), "History B")
        val old = store.findWorkspace(a)!!.slug
        val moved = slug("moved")
        service.update(acc("ho"), a, null, moved)
        assertEquals(a, store.slugRetiredTo(old))
        assertEquals(WorkspaceErrorCode.SLUG_TAKEN, assertFailsWith<WorkspaceException> { service.update(acc("hp"), b, null, old) }.workspaceError)
        assertEquals(moved, service.bySlug(acc("ho"), old).workspace.slug)

        service.update(acc("ho"), a, null, old)   // 자기 옛 slug 로 되돌린다
        assertNull(store.slugRetiredTo(old))
        assertEquals(a, store.slugRetiredTo(moved))
        assertEquals(old, service.bySlug(acc("ho"), moved).workspace.slug)
    }

    @Test
    fun `the safety net hands an erased owner's workspace over, closes a lone one, and is idempotent`() {
        val shared = team(acc("eo"), "Erase Shared", acc("ea") to WorkspaceRole.ADMIN, acc("em") to WorkspaceRole.MEMBER)
        val alone = team(acc("eo"), "Erase Alone")
        assertEquals(listOf(shared), check.flatMap { it.blockers(acc("eo")) }.flatMap { it.refs })

        val request = ErasureRequest(acc("eo"), AccountTombstone.of(acc("eo")))
        repeat(2) { erasers.forEach { it.erase(request) } }

        assertEquals(WorkspaceRole.OWNER, store.findMember(shared, acc("ea"))!!.role)
        assertEquals(1, ownersOf(shared))
        assertNull(store.findMember(shared, acc("eo")))
        assertEquals(WorkspaceStatus.CLOSED, store.findWorkspace(alone)!!.status)
        assertNotNull(store.findWorkspace(alone)!!.closedAt)
        assertEquals(AccountTombstone.of(acc("eo")), store.findWorkspace(shared)!!.createdBy)
    }

    @Test
    fun `invitation rows count as seats only while pending and unexpired, one pending per address, and erasure blanks who invited`() {
        val id = team(acc("io"), "Invite Rows")
        val now = Instant.now()
        fun invite(invId: String, email: String, status: String, expiresInSeconds: Long, pendingKey: String?) = jdbc.sql(
            "insert into workspace_invitations (id, workspace_id, email, role, token_hash, status, pending_key, invited_by, expires_at, sent_count, last_sent_at, created_at, updated_at) " +
                "values (?, ?, ?, 'MEMBER', ?, ?, ?, ?, ?, 1, ?, ?, ?)",
        ).params(listOf(invId, id, email, invId.padEnd(64, '0'), status, pendingKey, acc("io"), dialect.instantParam(now.plusSeconds(expiresInSeconds)), dialect.instantParam(now), dialect.instantParam(now), dialect.instantParam(now))).update()
        invite("wsi_${seq}_1", "a@example.com", "PENDING", 3600, "$id\na@example.com")
        invite("wsi_${seq}_2", "b@example.com", "PENDING", -3600, "$id\nb@example.com")
        invite("wsi_${seq}_3", "c@example.com", "REVOKED", 3600, null)
        assertEquals(1, store.countLiveInvitations(id, now))
        assertFailsWith<DuplicateKeyException> { invite("wsi_${seq}_4", "a@example.com", "PENDING", 3600, "$id\na@example.com") }

        store.eraseAccount(acc("io"), AccountTombstone.of(acc("io")))
        assertEquals(setOf(AccountTombstone.of(acc("io"))), jdbc.sql("select distinct invited_by from workspace_invitations where workspace_id = ?").param(id).query(String::class.java).list().toSet())
    }
}
