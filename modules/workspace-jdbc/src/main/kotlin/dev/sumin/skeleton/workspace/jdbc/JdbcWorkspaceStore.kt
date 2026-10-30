package dev.sumin.skeleton.workspace.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.workspace.InvitationStatus
import dev.sumin.skeleton.workspace.MyWorkspace
import dev.sumin.skeleton.workspace.Workspace
import dev.sumin.skeleton.workspace.WorkspaceInvitation
import dev.sumin.skeleton.workspace.WorkspaceListRow
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.WorkspaceStatus
import dev.sumin.skeleton.workspace.WorkspaceStore
import java.sql.ResultSet
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 표 넷(`workspaces` · `workspace_slug_history` · `workspace_members` · `workspace_invitations`).
 *
 * **소유자는 정확히 하나**: `workspace_members.owner_key` 는 OWNER 줄에만 워크스페이스 id 가 있고 유니크라서, 소유자 둘은 커밋될 수 없다([setRole] 이 소유자를 만들 때
 * 이미 다른 소유자가 있으면 유니크 위반이 그대로 올라와 트랜잭션이 되돌려진다). 「최소 하나」는 서비스가 모든 변경 앞에 [lockWorkspace] (`for update`) 로 줄을 잡아 지킨다 -
 * 멤버 줄을 쓰는 호출은 모두 그 잠금 아래라서 [insertMember] 의 「이미 있나」 검사가 경쟁하지 않는다.
 * 넣기는 [SqlDialect.insertIgnore] 이고 넣었는지는 내가 만든 id 가 있는지로 본다(PG 에서 유니크 위반 예외는 트랜잭션 전체를 abort 시키고, MySQL 은 이미 있는 행에도 1 을 돌려준다).
 */
class JdbcWorkspaceStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : WorkspaceStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }

    // 워크스페이스

    override fun insertWorkspace(workspace: Workspace): Boolean {
        jdbc.update(
            dialect.insertIgnore("workspaces", WORKSPACE_COLUMNS, emptyList()),   // slug 유니크 충돌을 무시한다
            MapSqlParameterSource()
                .addValue("id", workspace.id)
                .addValue("name", workspace.name)
                .addValue("slug", workspace.slug)
                .addValue("status", workspace.status.name)
                .addValue("created_by", workspace.createdBy)
                .addValue("created_at", instant(workspace.createdAt))
                .addValue("updated_at", instant(workspace.updatedAt))
                .addValue("closed_at", instant(workspace.closedAt)),
        )
        return count("select count(*) from workspaces where id = :id", "id" to workspace.id) == 1L
    }

    override fun findWorkspace(id: String): Workspace? = jdbc.query("select * from workspaces where id = :id", mapOf("id" to id)) { rs, _ -> rs.toWorkspace() }.firstOrNull()

    override fun lockWorkspace(id: String): Workspace? =
        jdbc.query("select * from workspaces where id = :id for update", mapOf("id" to id)) { rs, _ -> rs.toWorkspace() }.firstOrNull()

    override fun findWorkspaceBySlug(slug: String): Workspace? =
        jdbc.query("select * from workspaces where slug = :slug", mapOf("slug" to slug)) { rs, _ -> rs.toWorkspace() }.firstOrNull()

    override fun slugRetiredTo(slug: String): String? =
        jdbc.query("select workspace_id from workspace_slug_history where slug = :slug", mapOf("slug" to slug)) { rs, _ -> rs.getString("workspace_id") }.firstOrNull()

    override fun updateWorkspace(id: String, name: String, slug: String, at: Instant): Boolean = try {
        jdbc.update("update workspaces set name = :name, slug = :slug, updated_at = :at where id = :id", mapOf("id" to id, "name" to name, "slug" to slug, "at" to instant(at))) == 1
    } catch (e: DuplicateKeyException) {
        false   // 호출자는 곧바로 예외를 던져 트랜잭션을 되돌린다(PG 는 이 뒤에 문장을 더 받지 않는다)
    }

    override fun retireSlug(slug: String, workspaceId: String, at: Instant) {
        jdbc.update(
            dialect.insertIgnore("workspace_slug_history", listOf("slug", "workspace_id", "retired_at"), listOf("slug")),
            mapOf("slug" to slug, "workspace_id" to workspaceId, "retired_at" to instant(at)),
        )
    }

    override fun releaseSlug(slug: String) {
        jdbc.update("delete from workspace_slug_history where slug = :slug", mapOf("slug" to slug))
    }

    override fun closeWorkspace(id: String, at: Instant): Boolean =
        jdbc.update("update workspaces set status = 'CLOSED', closed_at = :at, updated_at = :at where id = :id and status = 'ACTIVE'", mapOf("id" to id, "at" to instant(at))) == 1

    // 멤버

    override fun insertMember(member: WorkspaceMember): Boolean {
        if (findMember(member.workspaceId, member.accountId) != null) return false
        jdbc.update(
            "insert into workspace_members (workspace_id, account_id, role, owner_key, joined_at, invited_by) values (:workspace_id, :account_id, :role, :owner_key, :joined_at, :invited_by)",
            MapSqlParameterSource()
                .addValue("workspace_id", member.workspaceId)
                .addValue("account_id", member.accountId)
                .addValue("role", member.role.name)
                .addValue("owner_key", ownerKey(member.workspaceId, member.role))
                .addValue("joined_at", instant(member.joinedAt))
                .addValue("invited_by", member.invitedBy),
        )
        return true
    }

    override fun findMember(workspaceId: String, accountId: String): WorkspaceMember? =
        jdbc.query("select * from workspace_members where workspace_id = :w and account_id = :a", mapOf("w" to workspaceId, "a" to accountId)) { rs, _ -> rs.toMember() }.firstOrNull()

    override fun activeMembership(workspaceId: String, accountId: String): WorkspaceMember? = jdbc.query(
        "select m.* from workspace_members m join workspaces w on w.id = m.workspace_id where m.workspace_id = :w and m.account_id = :a and w.status = 'ACTIVE'",
        mapOf("w" to workspaceId, "a" to accountId),
    ) { rs, _ -> rs.toMember() }.firstOrNull()

    override fun listMembers(workspaceId: String): List<WorkspaceMember> =
        jdbc.query("select * from workspace_members where workspace_id = :w order by joined_at, account_id", mapOf("w" to workspaceId)) { rs, _ -> rs.toMember() }

    override fun countMembers(workspaceId: String): Int = count("select count(*) from workspace_members where workspace_id = :w", "w" to workspaceId).toInt()

    override fun countLiveInvitations(workspaceId: String, now: Instant): Int = count(
        "select count(*) from workspace_invitations where workspace_id = :w and status = 'PENDING' and expires_at > :now",
        "w" to workspaceId, "now" to instant(now),
    ).toInt()

    // 초대

    override fun insertInvitation(invitation: WorkspaceInvitation) {
        jdbc.update(
            "insert into workspace_invitations (id, workspace_id, email, role, token_hash, status, pending_key, invited_by, expires_at, sent_count, last_sent_at, accepted_by, accepted_at, created_at, updated_at) " +
                "values (:id, :workspace_id, :email, :role, :token_hash, :status, :pending_key, :invited_by, :expires_at, :sent_count, :last_sent_at, :accepted_by, :accepted_at, :created_at, :updated_at)",
            MapSqlParameterSource()
                .addValue("id", invitation.id)
                .addValue("workspace_id", invitation.workspaceId)
                .addValue("email", invitation.email)
                .addValue("role", invitation.role.name)
                .addValue("token_hash", invitation.tokenHash)
                .addValue("status", invitation.status.name)
                .addValue("pending_key", pendingKey(invitation.workspaceId, invitation.email, invitation.status))
                .addValue("invited_by", invitation.invitedBy)
                .addValue("expires_at", instant(invitation.expiresAt))
                .addValue("sent_count", invitation.sentCount)
                .addValue("last_sent_at", instant(invitation.lastSentAt))
                .addValue("accepted_by", invitation.acceptedBy)
                .addValue("accepted_at", instant(invitation.acceptedAt))
                .addValue("created_at", instant(invitation.createdAt))
                .addValue("updated_at", instant(invitation.updatedAt)),
        )
    }

    override fun findInvitation(id: String): WorkspaceInvitation? =
        jdbc.query("select * from workspace_invitations where id = :id", mapOf("id" to id)) { rs, _ -> rs.toInvitation() }.firstOrNull()

    override fun findInvitationByTokenHash(tokenHash: String): WorkspaceInvitation? =
        jdbc.query("select * from workspace_invitations where token_hash = :h", mapOf("h" to tokenHash)) { rs, _ -> rs.toInvitation() }.firstOrNull()

    override fun findPendingInvitation(workspaceId: String, email: String): WorkspaceInvitation? = jdbc.query(
        "select * from workspace_invitations where workspace_id = :w and email = :e and status = 'PENDING'", mapOf("w" to workspaceId, "e" to email),
    ) { rs, _ -> rs.toInvitation() }.firstOrNull()

    override fun listPendingInvitations(workspaceId: String): List<WorkspaceInvitation> = jdbc.query(
        "select * from workspace_invitations where workspace_id = :w and status = 'PENDING' order by created_at, id", mapOf("w" to workspaceId),
    ) { rs, _ -> rs.toInvitation() }

    override fun countPendingInvitations(workspaceId: String): Int =
        count("select count(*) from workspace_invitations where workspace_id = :w and status = 'PENDING'", "w" to workspaceId).toInt()

    override fun renewInvitation(id: String, tokenHash: String, expiresAt: Instant, at: Instant): Boolean = jdbc.update(
        "update workspace_invitations set token_hash = :h, expires_at = :exp, sent_count = sent_count + 1, last_sent_at = :at, updated_at = :at where id = :id and status = 'PENDING'",
        MapSqlParameterSource().addValue("h", tokenHash).addValue("exp", instant(expiresAt)).addValue("at", instant(at)).addValue("id", id),
    ) == 1

    override fun closeInvitation(id: String, status: InvitationStatus, acceptedBy: String?, at: Instant): Boolean = jdbc.update(
        "update workspace_invitations set status = :status, pending_key = null, accepted_by = :by, accepted_at = :acc, updated_at = :at where id = :id and status = 'PENDING'",
        MapSqlParameterSource().addValue("status", status.name).addValue("by", acceptedBy).addValue("acc", instant(if (status == InvitationStatus.ACCEPTED) at else null))
            .addValue("at", instant(at)).addValue("id", id),
    ) == 1

    override fun searchWorkspaces(query: String?, before: String?, limit: Int): List<WorkspaceListRow> {
        val where = ArrayList<String>()
        val params = MapSqlParameterSource().addValue("limit", limit)
        if (query != null) {
            // % · _ 는 글자 그대로 - 지정한 문자(!)로 빠져나가게 한다
            where += "(lower(w.name) like :like escape '!' or lower(w.slug) like :like escape '!' or w.id = :query)"
            params.addValue("like", "%" + query.lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%").addValue("query", query)
        }
        if (before != null) {
            where += "w.id < :before"
            params.addValue("before", before)
        }
        val sql = "select w.*, (select count(*) from workspace_members m where m.workspace_id = w.id) as member_count, " +
            "(select min(o.account_id) from workspace_members o where o.workspace_id = w.id and o.role = 'OWNER') as owner_id " +
            "from workspaces w" + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) + " order by w.id desc limit :limit"
        return jdbc.query(sql, params) { rs, _ -> WorkspaceListRow(rs.toWorkspace(), rs.getString("owner_id"), rs.getInt("member_count")) }
    }

    override fun setRole(workspaceId: String, accountId: String, role: WorkspaceRole): Boolean = jdbc.update(
        "update workspace_members set role = :role, owner_key = :owner_key where workspace_id = :w and account_id = :a",
        MapSqlParameterSource().addValue("role", role.name).addValue("owner_key", ownerKey(workspaceId, role)).addValue("w", workspaceId).addValue("a", accountId),
    ) == 1

    override fun deleteMember(workspaceId: String, accountId: String): Boolean =
        jdbc.update("delete from workspace_members where workspace_id = :w and account_id = :a", mapOf("w" to workspaceId, "a" to accountId)) == 1

    override fun workspacesOf(accountId: String): List<MyWorkspace> = jdbc.query(
        "select w.*, m.role as member_role from workspace_members m join workspaces w on w.id = m.workspace_id where m.account_id = :a and w.status = 'ACTIVE' order by m.joined_at, w.id",
        mapOf("a" to accountId),
    ) { rs, _ -> MyWorkspace(rs.toWorkspace(), WorkspaceRole.valueOf(rs.getString("member_role"))) }

    override fun ownedWorkspaceIds(accountId: String): List<String> = jdbc.query(
        "select m.workspace_id from workspace_members m join workspaces w on w.id = m.workspace_id where m.account_id = :a and m.role = 'OWNER' and w.status = 'ACTIVE' order by m.joined_at, m.workspace_id",
        mapOf("a" to accountId),
    ) { rs, _ -> rs.getString("workspace_id") }

    override fun successorCandidate(workspaceId: String, excluding: String): WorkspaceMember? = jdbc.query(
        "select * from workspace_members where workspace_id = :w and account_id <> :ex and role in ('ADMIN', 'MEMBER') " +
            "order by case role when 'ADMIN' then 0 else 1 end, joined_at, account_id limit 1",
        mapOf("w" to workspaceId, "ex" to excluding),
    ) { rs, _ -> rs.toMember() }.firstOrNull()

    override fun eraseAccount(accountId: String, tombstone: String): Int {
        val args = mapOf("a" to accountId, "t" to tombstone)
        return jdbc.update("delete from workspace_members where account_id = :a", args) +
            jdbc.update("update workspaces set created_by = :t where created_by = :a", args) +
            jdbc.update("update workspace_members set invited_by = :t where invited_by = :a", args) +
            jdbc.update("update workspace_invitations set invited_by = :t where invited_by = :a", args) +
            jdbc.update("update workspace_invitations set accepted_by = :t where accepted_by = :a", args)
    }

    // 도우미

    private fun instant(value: Instant?): Any? = dialect.instantParam(value)

    private fun pendingKey(workspaceId: String, email: String, status: InvitationStatus): String? = if (status == InvitationStatus.PENDING) "$workspaceId\n$email" else null

    private fun ownerKey(workspaceId: String, role: WorkspaceRole): String? = if (role == WorkspaceRole.OWNER) workspaceId else null

    private fun count(sql: String, vararg args: Pair<String, Any?>): Long = jdbc.queryForObject(sql, args.toMap(), Long::class.java) ?: 0L

    private fun ResultSet.toWorkspace() = Workspace(
        id = getString("id"),
        name = getString("name"),
        slug = getString("slug"),
        status = WorkspaceStatus.valueOf(getString("status")),
        createdBy = getString("created_by"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
        closedAt = dialect.readInstant(this, "closed_at"),
    )

    private fun ResultSet.toMember() = WorkspaceMember(
        workspaceId = getString("workspace_id"),
        accountId = getString("account_id"),
        role = WorkspaceRole.valueOf(getString("role")),
        joinedAt = dialect.readInstant(this, "joined_at")!!,
        invitedBy = getString("invited_by"),
    )

    private fun ResultSet.toInvitation() = WorkspaceInvitation(
        id = getString("id"),
        workspaceId = getString("workspace_id"),
        email = getString("email"),
        role = WorkspaceRole.valueOf(getString("role")),
        tokenHash = getString("token_hash").trim(),
        status = InvitationStatus.valueOf(getString("status")),
        invitedBy = getString("invited_by"),
        expiresAt = dialect.readInstant(this, "expires_at")!!,
        sentCount = getInt("sent_count"),
        lastSentAt = dialect.readInstant(this, "last_sent_at")!!,
        acceptedBy = getString("accepted_by"),
        acceptedAt = dialect.readInstant(this, "accepted_at"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
    )

    private companion object {
        val WORKSPACE_COLUMNS = listOf("id", "name", "slug", "status", "created_by", "created_at", "updated_at", "closed_at")
    }
}
