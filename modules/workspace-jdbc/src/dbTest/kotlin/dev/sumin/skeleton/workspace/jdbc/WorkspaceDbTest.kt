package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.workspace.InvitationMail
import dev.sumin.skeleton.workspace.InvitationStatus
import dev.sumin.skeleton.workspace.WorkspaceInvitationService
import dev.sumin.skeleton.workspace.WorkspaceProperties
import dev.sumin.skeleton.workspace.WorkspaceSeatPolicy
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

    // ---- 초대 (진짜 잠금 · 유니크)

    private fun invitations(seats: WorkspaceSeatPolicy, sent: MutableList<InvitationMail>, verified: Map<String, String> = emptyMap()) = WorkspaceInvitationService(
        store, TimeProvider.systemUtc(), seats, service,
        object : AccountFacts {
            override fun createdAt(accountId: String): Instant? = null
            override fun verifiedEmail(accountId: String) = verified[accountId]
        },
        { m -> synchronized(sent) { sent += m } }, WorkspaceProperties.Invitation(enabled = true),
    )

    private fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `two simultaneous accepts of the last seat let exactly one in and leave the other invitation pending`() {
        repeat(5) { round ->
            val owner = acc("so$round")
            val id = team(owner, "Race Seat $round")
            val sent = mutableListOf<InvitationMail>()
            var limit: Int? = 3
            val verified = mapOf(acc("sa$round") to "a$round@example.com", acc("sb$round") to "b$round@example.com")
            val svc = invitations({ limit }, sent, verified)
            svc.invite(owner, id, listOf("a$round@example.com", "b$round@example.com"), WorkspaceRole.MEMBER, "en")   // 소유자 1 + 대기 2 = 3
            limit = 2   // 상한이 줄었다 - 자리는 하나만 남는다
            val tokens = mapOf(acc("sa$round") to sent.single { it.to.startsWith("a") }.token, acc("sb$round") to sent.single { it.to.startsWith("b") }.token)

            val results = concurrently(2) { i -> val who = if (i == 0) acc("sa$round") else acc("sb$round"); svc.accept(who, tokens.getValue(who)) }

            assertEquals(1, results.count { it.isSuccess }, "round $round: ${results.map { it.exceptionOrNull() }}")
            assertEquals(WorkspaceErrorCode.SEAT_LIMIT, (results.single { it.isFailure }.exceptionOrNull() as WorkspaceException).workspaceError)
            assertEquals(2, store.countMembers(id), "round $round")
            assertEquals(1, store.listPendingInvitations(id).size, "the refused invitation stays pending")
        }
    }

    @Test
    fun `a token is stored only as its hash, a resend replaces it, and revoking or accepting frees the address`() {
        val owner = acc("to")
        val id = team(owner, "Token Rows")
        val sent = mutableListOf<InvitationMail>()
        val svc = invitations({ null }, sent, mapOf(acc("ta") to "ta@example.com"))
        svc.invite(owner, id, listOf("ta@example.com"), WorkspaceRole.MEMBER, "en")
        val first = sent.single().token
        val row = store.findPendingInvitation(id, "ta@example.com")!!

        val everyColumn = jdbc.sql("select * from workspace_invitations where id = ?").param(row.id).query { rs, _ -> (1..rs.metaData.columnCount).map { rs.getString(it).orEmpty() } }.single()
        assertTrue(everyColumn.none { it.contains(first) }, "a column holds the plain token")
        assertEquals(sha256(first), row.tokenHash)
        assertEquals(row.id, store.findInvitationByTokenHash(row.tokenHash)!!.id)

        assertFailsWith<DuplicateKeyException> { store.insertInvitation(row.copy(id = "wsi_${seq}_dup", tokenHash = "f".repeat(64))) }   // 같은 주소에 대기 초대는 하나

        val renewed = Instant.now().plusSeconds(60)
        assertTrue(store.renewInvitation(row.id, "e".repeat(64), renewed, Instant.now()))
        assertNull(store.findInvitationByTokenHash(row.tokenHash), "the old token hash is gone")
        assertEquals(2, store.findInvitation(row.id)!!.sentCount)

        assertTrue(store.closeInvitation(row.id, InvitationStatus.REVOKED, null, Instant.now()))
        assertEquals(false, store.closeInvitation(row.id, InvitationStatus.REVOKED, null, Instant.now()), "closing twice changes nothing")
        assertEquals(false, store.renewInvitation(row.id, "d".repeat(64), renewed, Instant.now()), "a closed invitation cannot be renewed")
        assertEquals(listOf("ta@example.com"), svc.invite(owner, id, listOf("ta@example.com"), WorkspaceRole.MEMBER, "en").invited, "the address is free again")

        val accepted = sent.last().token
        svc.accept(acc("ta"), accepted)
        assertEquals(InvitationStatus.ACCEPTED, store.findInvitationByTokenHash(sha256(accepted))!!.status)
        assertEquals(0, store.listPendingInvitations(id).size)
    }

    // ---- 운영자 검색

    @Test
    fun `the operator search matches name, address or id on both dialects, escapes wildcards, shows closed teams and pages newest first`() {
        val a = team(acc("qa"), "Zq${seq}x Alpha", acc("qm") to WorkspaceRole.MEMBER)
        val b = service.create(acc("qb"), "Zq${seq}x 100% Beta_1", slug("qb")).workspace.id
        val c = team(acc("qc"), "Zq${seq}x Gamma")
        store.closeWorkspace(c, Instant.now())

        val all = store.searchWorkspaces("zq${seq}x", null, 10)
        assertEquals(listOf(c, b, a), all.map { it.workspace.id }, "newest id first, closed ones included")
        assertEquals(listOf(acc("qc"), acc("qb"), acc("qa")), all.map { it.ownerId }, "owner is the OWNER row")
        assertEquals(2, all.single { it.workspace.id == a }.memberCount)
        assertEquals(WorkspaceStatus.CLOSED, all.first().workspace.status)

        assertEquals(listOf(a), store.searchWorkspaces("alpha", null, 10).filter { it.workspace.id == a }.map { it.workspace.id })
        assertEquals(listOf(b), store.searchWorkspaces("100% beta_1", null, 10).map { it.workspace.id }, "% and _ are literal")
        assertEquals(emptyList(), store.searchWorkspaces("zq${seq}x 100_ beta", null, 10).map { it.workspace.id }, "_ is not a wildcard")
        assertEquals(listOf(a), store.searchWorkspaces(a, null, 10).map { it.workspace.id }, "an exact id")
        assertEquals(listOf(store.findWorkspace(a)!!.slug), store.searchWorkspaces(store.findWorkspace(a)!!.slug, null, 10).map { it.workspace.slug })

        assertEquals(listOf(c, b), store.searchWorkspaces("zq${seq}x", null, 2).map { it.workspace.id })
        assertEquals(listOf(a), store.searchWorkspaces("zq${seq}x", b, 2).map { it.workspace.id })
    }

    @Test
    fun `erasing an account never deletes its owner row, so a transfer racing the erasure cannot leave a team without an owner`() {
        val id = team(acc("xo"), "Erase Race", acc("xm") to WorkspaceRole.MEMBER)
        store.eraseAccount(acc("xo"), AccountTombstone.of(acc("xo")))   // 안전망이 넘기기 전에 도는 경우
        assertEquals(1, ownersOf(id))
        assertNotNull(store.findMember(id, acc("xo")))
        erasers.forEach { it.erase(ErasureRequest(acc("xo"), AccountTombstone.of(acc("xo")))) }
        assertEquals(WorkspaceRole.OWNER, store.findMember(id, acc("xm"))!!.role)
        assertNull(store.findMember(id, acc("xo")))
        assertEquals(1, ownersOf(id))
    }

    @Test
    fun `a slug freed by one rename cannot be taken by another team's simultaneous rename`() {
        repeat(8) { round ->
            val a = team(acc("ra$round"), "Slug Race A $round")
            val b = team(acc("rb$round"), "Slug Race B $round")
            val old = store.findWorkspace(a)!!.slug
            val results = concurrently(2) { i -> if (i == 0) service.update(acc("ra$round"), a, null, slug("moved-$round")) else service.update(acc("rb$round"), b, null, old) }
            if (results[0].isSuccess) {
                assertTrue(results[1].isFailure, "round $round: the team that left '$old' keeps it reserved")
                assertEquals(WorkspaceErrorCode.SLUG_TAKEN, (results[1].exceptionOrNull() as WorkspaceException).workspaceError)
                assertEquals(a, store.slugRetiredTo(old))
            }
            assertTrue(!(store.findWorkspaceBySlug(old) != null && store.slugRetiredTo(old) != null), "round $round: a slug is never both live and retired")
        }
    }
}
