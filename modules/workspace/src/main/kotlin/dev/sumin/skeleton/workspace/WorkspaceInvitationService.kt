package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 초대 — 만들기 · 재발송 · 취소 · 미리보기 · 수락. 쓰기는 모두 [WorkspaceService.mutate] 와 같은 길(워크스페이스 줄을 `for update` 로 잡고 현재 값으로 판정)이라
 * 좌석 상한을 동시 수락으로 넘지 못하고, 재발송이 겹친 수락의 옛 토큰을 잠금 아래에서 다시 읽어 거절한다.
 *
 * **토큰**: 무작위 32바이트, 저장은 sha256 만. 평문은 메일([InvitationMail])에만 있고 응답 · 로그 · `toString` 에 없다.
 * **수락**: 호출자의 *확인된* 계정 이메일([AccountFacts.verifiedEmail])이 초대 주소와 같아야 한다.
 * 메일은 커밋 **뒤에** 보낸다(보내기가 실패해도 초대는 남고 재발송이 있다).
 */
class WorkspaceInvitationService(
    private val store: WorkspaceStore,
    private val time: TimeProvider,
    private val seats: WorkspaceSeatPolicy,
    private val workspaces: WorkspaceService,
    private val facts: AccountFacts,
    private val mailer: WorkspaceInvitationMailer,
    private val props: WorkspaceProperties.Invitation,
    private val labels: AccountLabels? = null,
    private val ids: SortableIds = SortableIds.system,
) {
    private class Issued(val invitation: WorkspaceInvitation, val token: String)

    // 초대하기 — ADMIN 이상 (ADMIN 은 MEMBER 로만)

    fun invite(accountId: String, workspaceId: String, emails: List<String>, role: WorkspaceRole, locale: String?): InviteResult {
        if (role != WorkspaceRole.ADMIN && role != WorkspaceRole.MEMBER) throw invalid("role is ADMIN or MEMBER")
        val addresses = emails.map(::normalize).distinct()
        if (addresses.isEmpty() || addresses.size > props.maxPerRequest) throw invalid("emails: 1-${props.maxPerRequest} addresses")
        addresses.firstOrNull { !plausible(it) }?.let { throw invalid("not an email address") }

        val issued = ArrayList<Issued>()
        val invited = ArrayList<String>()
        val already = ArrayList<String>()
        val limited = ArrayList<String>()
        val context = workspaces.mutate(accountId, workspaceId, WorkspaceRole.ADMIN) { workspace, me ->
            if (role == WorkspaceRole.ADMIN && me.role != WorkspaceRole.OWNER) throw WorkspaceException(WorkspaceErrorCode.FORBIDDEN)
            val now = time.now()
            val limit = seats.limitOf(workspace.id)
            var seatsUsed = store.countMembers(workspace.id) + store.countLiveInvitations(workspace.id, now)
            for (email in addresses) {
                if (store.findPendingInvitation(workspace.id, email) != null) { already += email; continue }
                if (limit != null && seatsUsed >= limit) { limited += email; continue }
                val token = InvitationTokens.newToken()
                val invitation = WorkspaceInvitation(
                    ids.newId("wsi_"), workspace.id, email, role, InvitationTokens.hash(token), InvitationStatus.PENDING, accountId,
                    now.plus(props.ttl), 1, now, createdAt = now, updatedAt = now,
                )
                issued += Issued(invitation, token)
                invited += email
                seatsUsed++
            }
            // 넘으면 요청 전체를 거절한다 - 줄은 아직 하나도 쓰지 않았다
            if (store.countPendingInvitations(workspace.id) + issued.size > props.maxPending) throw WorkspaceException(WorkspaceErrorCode.TOO_MANY_PENDING)
            issued.forEach { store.insertInvitation(it.invitation) }
            workspace.name
        }
        deliver(issued, context, accountId, locale)
        return InviteResult(invited, already, limited)
    }

    // 목록 · 재발송 · 취소 — ADMIN 이상

    fun list(accountId: String, workspaceId: String): List<InvitationView> {
        val me = store.activeMembership(workspaceId, accountId) ?: throw notFound()
        if (!me.role.atLeast(WorkspaceRole.ADMIN)) throw WorkspaceException(WorkspaceErrorCode.FORBIDDEN)
        val now = time.now()
        return store.listPendingInvitations(workspaceId).map { InvitationView(it, it.expired(now)) }
    }

    /** 새 토큰(옛 링크는 바로 무효) · 만료 다시 [WorkspaceProperties.Invitation.ttl]. 같은 초대를 [WorkspaceProperties.Invitation.resendInterval] 안에 또 보내면 429 */
    fun resend(accountId: String, workspaceId: String, invitationId: String, locale: String?) {
        val issued = workspaces.mutate(accountId, workspaceId, WorkspaceRole.ADMIN) { workspace, _ ->
            val invitation = pendingOf(workspaceId, invitationId)
            val now = time.now()
            if (now.isBefore(invitation.lastSentAt.plus(props.resendInterval))) throw WorkspaceException(WorkspaceErrorCode.RESEND_TOO_SOON)
            val token = InvitationTokens.newToken()
            val expiresAt = now.plus(props.ttl)
            store.renewInvitation(invitation.id, InvitationTokens.hash(token), expiresAt, now)
            Issued(invitation.copy(expiresAt = expiresAt), token) to workspace.name
        }
        deliver(listOf(issued.first), issued.second, accountId, locale)
    }

    fun revoke(accountId: String, workspaceId: String, invitationId: String) {
        workspaces.mutate(accountId, workspaceId, WorkspaceRole.ADMIN) { _, _ ->
            store.closeInvitation(pendingOf(workspaceId, invitationId).id, InvitationStatus.REVOKED, null, time.now())
            Unit
        }
    }

    private fun pendingOf(workspaceId: String, invitationId: String): WorkspaceInvitation =
        store.findInvitation(invitationId)?.takeIf { it.workspaceId == workspaceId && it.status == InvitationStatus.PENDING }
            ?: throw WorkspaceException(WorkspaceErrorCode.INVITATION_NOT_FOUND)

    // 미리보기 — 로그인 없이

    fun preview(token: String): InvitationPreview {
        val (invitation, workspace) = lookup(token)
        return InvitationPreview(
            workspace.name, labels?.of(listOf(invitation.invitedBy))?.get(invitation.invitedBy), LogMasker.maskEmail(invitation.email), invitation.role, invitation.expiresAt,
        )
    }

    /** 토큰 → 살아 있는 초대와 그 워크스페이스. 아니면 [WorkspaceErrorCode.INVITATION_INVALID] (`data.reason` = `EXPIRED` | `USED` | `INVALID`) */
    private fun lookup(token: String): Pair<WorkspaceInvitation, Workspace> {
        val invitation = (if (token.length > MAX_TOKEN) null else store.findInvitationByTokenHash(InvitationTokens.hash(token))) ?: throw invalidInvitation("INVALID")
        val workspace = store.findWorkspace(invitation.workspaceId)?.takeIf { it.status == WorkspaceStatus.ACTIVE } ?: throw invalidInvitation("INVALID")
        requireLive(invitation)
        return invitation to workspace
    }

    private fun requireLive(invitation: WorkspaceInvitation) {
        when {
            invitation.status == InvitationStatus.ACCEPTED -> throw invalidInvitation("USED")
            invitation.status == InvitationStatus.REVOKED -> throw invalidInvitation("INVALID")
            invitation.expired(time.now()) -> throw invalidInvitation("EXPIRED")
        }
    }

    // 수락 — 로그인

    private sealed interface Outcome {
        class Joined(val workspace: MyWorkspace) : Outcome
        class AlreadyMember(val workspace: Workspace) : Outcome
    }

    fun accept(accountId: String, token: String): MyWorkspace {
        // 잠금 밖에서: 토큰이 살아 있나(빠른 거절) · 호출자의 확인된 이메일. 판정은 잠금 아래에서 다시 한다
        val first = lookup(token).first
        val verified = facts.verifiedEmail(accountId)?.let(::normalize)
        val outcome = store.inTransaction {
            store.lockWorkspace(first.workspaceId)?.takeIf { it.status == WorkspaceStatus.ACTIVE }?.let { workspace ->
                // 줄을 잡은 뒤 다시 읽는다 - 그 사이 재발송 · 취소 · 다른 수락이 있었으면 여기서 거절된다
                val invitation = store.findInvitationByTokenHash(InvitationTokens.hash(token)) ?: throw invalidInvitation("INVALID")
                requireLive(invitation)
                if (verified == null) throw WorkspaceException(WorkspaceErrorCode.EMAIL_UNVERIFIED)
                if (verified != invitation.email) throw WorkspaceException(WorkspaceErrorCode.INVITATION_EMAIL_MISMATCH)
                val now = time.now()
                if (store.findMember(workspace.id, accountId) != null) {
                    store.closeInvitation(invitation.id, InvitationStatus.ACCEPTED, accountId, now)
                    return@inTransaction Outcome.AlreadyMember(workspace)
                }
                val limit = seats.limitOf(workspace.id)
                if (limit != null && store.countMembers(workspace.id) >= limit) throw WorkspaceException(WorkspaceErrorCode.SEAT_LIMIT)
                store.insertMember(WorkspaceMember(workspace.id, accountId, invitation.role, now, invitation.invitedBy))
                store.closeInvitation(invitation.id, InvitationStatus.ACCEPTED, accountId, now)
                Outcome.Joined(MyWorkspace(workspace, invitation.role))
            } ?: throw invalidInvitation("INVALID")
        }
        return when (outcome) {
            is Outcome.Joined -> outcome.workspace
            is Outcome.AlreadyMember -> throw WorkspaceException(WorkspaceErrorCode.ALREADY_MEMBER, data = mapOf("id" to outcome.workspace.id, "slug" to outcome.workspace.slug))
        }
    }

    // 메일 - 커밋 뒤

    private fun deliver(issued: List<Issued>, workspaceName: String, inviterId: String, locale: String?) {
        if (issued.isEmpty()) return
        val inviter = labels?.of(listOf(inviterId))?.get(inviterId)
        issued.forEach { InvitationMail(it.invitation.email, locale, workspaceName, inviter, it.invitation.role, it.invitation.expiresAt, it.token).let(mailer::send) }
    }

    private fun normalize(email: String) = email.trim().lowercase()

    private fun plausible(email: String) = email.length <= MAX_EMAIL && EMAIL.matches(email)

    private fun invalid(message: String) = WorkspaceException(WorkspaceErrorCode.INVALID, message)

    private fun invalidInvitation(reason: String) = WorkspaceException(WorkspaceErrorCode.INVITATION_INVALID, data = mapOf("reason" to reason))

    private fun notFound() = WorkspaceException(WorkspaceErrorCode.NOT_FOUND)

    private companion object {
        const val MAX_TOKEN = 200
        const val MAX_EMAIL = 254
        val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$")
    }
}
