package dev.sumin.skeleton.workspace

import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/** 메모리 저장소 — 트랜잭션은 전역 잠금 하나로 직렬화한다 (잠금 · 롤백의 진짜 동작은 workspace-jdbc 의 dbTest 가 본다) */
class FakeWorkspaceStore : WorkspaceStore {
    private val lock = ReentrantLock()
    private val workspaces = LinkedHashMap<String, Workspace>()
    private val history = LinkedHashMap<String, String>()
    private val members = ArrayList<WorkspaceMember>()
    private val invitations = LinkedHashMap<String, WorkspaceInvitation>()
    /** 줄 없이 살아 있는 초대 수를 더 얹는다(상세의 `pendingInvitations` 시험) */
    var liveInvitations = 0

    fun clear() = lock.withLock { workspaces.clear(); history.clear(); members.clear(); invitations.clear(); liveInvitations = 0 }
    fun invitationRows() = lock.withLock { invitations.values.toList() }
    fun all() = lock.withLock { workspaces.values.toList() }
    fun membersIncludingAll() = lock.withLock { members.toList() }

    private inline fun <T> ReentrantLock.withLock(block: () -> T): T { lock(); try { return block() } finally { unlock() } }

    override fun <T> inTransaction(block: () -> T): T = lock.withLock { block() }

    override fun insertWorkspace(workspace: Workspace): Boolean = lock.withLock {
        if (workspaces.values.any { it.slug == workspace.slug } || workspace.slug in history) return false
        workspaces[workspace.id] = workspace
        true
    }

    override fun findWorkspace(id: String) = lock.withLock { workspaces[id] }
    override fun lockWorkspace(id: String) = findWorkspace(id)
    override fun findWorkspaceBySlug(slug: String) = lock.withLock { workspaces.values.firstOrNull { it.slug == slug } }
    override fun slugRetiredTo(slug: String) = lock.withLock { history[slug] }

    override fun updateWorkspace(id: String, name: String, slug: String, at: Instant): Boolean = lock.withLock {
        val current = workspaces[id] ?: return false
        if (workspaces.values.any { it.id != id && it.slug == slug }) return false
        workspaces[id] = current.copy(name = name, slug = slug, updatedAt = at)
        true
    }

    override fun retireSlug(slug: String, workspaceId: String, at: Instant) { lock.withLock { history[slug] = workspaceId } }
    override fun releaseSlug(slug: String) { lock.withLock { history.remove(slug) } }

    override fun closeWorkspace(id: String, at: Instant): Boolean = lock.withLock {
        val current = workspaces[id]?.takeIf { it.status == WorkspaceStatus.ACTIVE } ?: return false
        workspaces[id] = current.copy(status = WorkspaceStatus.CLOSED, closedAt = at, updatedAt = at)
        true
    }

    override fun insertMember(member: WorkspaceMember): Boolean = lock.withLock {
        if (members.any { it.workspaceId == member.workspaceId && it.accountId == member.accountId }) return false
        check(member.role != WorkspaceRole.OWNER || members.none { it.workspaceId == member.workspaceId && it.role == WorkspaceRole.OWNER }) { "two owners" }
        members += member
        true
    }

    override fun findMember(workspaceId: String, accountId: String) = lock.withLock { members.firstOrNull { it.workspaceId == workspaceId && it.accountId == accountId } }

    override fun activeMembership(workspaceId: String, accountId: String) = lock.withLock {
        findMember(workspaceId, accountId)?.takeIf { workspaces[workspaceId]?.status == WorkspaceStatus.ACTIVE }
    }

    override fun listMembers(workspaceId: String) = lock.withLock { members.filter { it.workspaceId == workspaceId }.sortedWith(compareBy({ it.joinedAt }, { it.accountId })) }
    override fun countMembers(workspaceId: String) = listMembers(workspaceId).size
    override fun countLiveInvitations(workspaceId: String, now: Instant) = lock.withLock {
        liveInvitations + invitations.values.count { it.workspaceId == workspaceId && it.status == InvitationStatus.PENDING && it.expiresAt.isAfter(now) }
    }

    override fun insertInvitation(invitation: WorkspaceInvitation) {
        lock.withLock {
            check(invitations.values.none { it.status == InvitationStatus.PENDING && invitation.status == InvitationStatus.PENDING && it.workspaceId == invitation.workspaceId && it.email == invitation.email }) { "pending twice" }
            check(invitations.values.none { it.tokenHash == invitation.tokenHash }) { "token twice" }
            invitations[invitation.id] = invitation
        }
    }

    override fun findInvitation(id: String) = lock.withLock { invitations[id] }
    override fun findInvitationByTokenHash(tokenHash: String) = lock.withLock { invitations.values.firstOrNull { it.tokenHash == tokenHash } }
    override fun findPendingInvitation(workspaceId: String, email: String) =
        lock.withLock { invitations.values.firstOrNull { it.workspaceId == workspaceId && it.email == email && it.status == InvitationStatus.PENDING } }
    override fun listPendingInvitations(workspaceId: String) =
        lock.withLock { invitations.values.filter { it.workspaceId == workspaceId && it.status == InvitationStatus.PENDING }.sortedBy { it.createdAt } }
    override fun countPendingInvitations(workspaceId: String) = listPendingInvitations(workspaceId).size

    override fun renewInvitation(id: String, tokenHash: String, expiresAt: Instant, at: Instant): Boolean = lock.withLock {
        val current = invitations[id]?.takeIf { it.status == InvitationStatus.PENDING } ?: return false
        invitations[id] = current.copy(tokenHash = tokenHash, expiresAt = expiresAt, sentCount = current.sentCount + 1, lastSentAt = at, updatedAt = at)
        true
    }

    override fun closeInvitation(id: String, status: InvitationStatus, acceptedBy: String?, at: Instant): Boolean = lock.withLock {
        val current = invitations[id]?.takeIf { it.status == InvitationStatus.PENDING } ?: return false
        invitations[id] = current.copy(status = status, acceptedBy = acceptedBy, acceptedAt = if (status == InvitationStatus.ACCEPTED) at else null, updatedAt = at)
        true
    }

    override fun searchWorkspaces(query: String?, before: String?, limit: Int): List<WorkspaceListRow> = lock.withLock {
        workspaces.values.filter { w ->
            (query == null || w.name.contains(query, ignoreCase = true) || w.slug.contains(query, ignoreCase = true) || w.id == query) && (before == null || w.id < before)
        }.sortedByDescending { it.id }.take(limit).map { w ->
            WorkspaceListRow(w, members.firstOrNull { it.workspaceId == w.id && it.role == WorkspaceRole.OWNER }?.accountId, members.count { it.workspaceId == w.id })
        }
    }

    override fun setRole(workspaceId: String, accountId: String, role: WorkspaceRole): Boolean = lock.withLock {
        val i = members.indexOfFirst { it.workspaceId == workspaceId && it.accountId == accountId }
        if (i < 0) return false
        check(role != WorkspaceRole.OWNER || members.none { it.workspaceId == workspaceId && it.role == WorkspaceRole.OWNER && it.accountId != accountId }) { "two owners" }
        members[i] = members[i].copy(role = role)
        true
    }

    override fun deleteMember(workspaceId: String, accountId: String) = lock.withLock { members.removeIf { it.workspaceId == workspaceId && it.accountId == accountId } }

    override fun workspacesOf(accountId: String) = lock.withLock {
        members.filter { it.accountId == accountId }.sortedBy { it.joinedAt }
            .mapNotNull { m -> workspaces[m.workspaceId]?.takeIf { it.status == WorkspaceStatus.ACTIVE }?.let { MyWorkspace(it, m.role) } }
    }

    override fun ownedWorkspaceIds(accountId: String) = workspacesOf(accountId).filter { it.role == WorkspaceRole.OWNER }.map { it.workspace.id }

    override fun successorCandidate(workspaceId: String, excluding: String) = listMembers(workspaceId).filter { it.accountId != excluding }.let { rest ->
        rest.firstOrNull { it.role == WorkspaceRole.ADMIN } ?: rest.firstOrNull { it.role == WorkspaceRole.MEMBER }
    }

    override fun eraseAccount(accountId: String, tombstone: String): Int = lock.withLock {
        var n = 0
        if (members.removeIf { it.accountId == accountId }) n++
        workspaces.replaceAll { _, w -> if (w.createdBy == accountId) { n++; w.copy(createdBy = tombstone) } else w }
        members.replaceAll { m -> if (m.invitedBy == accountId) { n++; m.copy(invitedBy = tombstone) } else m }
        n
    }
}

/** 시험이 시각을 쥔다 */
class MutableClock(private var at: Instant = Instant.parse("2026-10-30T00:00:00Z")) : dev.sumin.skeleton.common.time.TimeProvider {
    override fun now(): Instant = at
    fun advance(seconds: Long) { at = at.plusSeconds(seconds) }
}
