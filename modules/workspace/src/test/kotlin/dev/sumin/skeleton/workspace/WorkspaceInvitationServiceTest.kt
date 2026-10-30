package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.account.AccountLabels
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 초대 흐름 - 만들기 · 재발송 · 취소 · 미리보기 · 수락. 좌석 경쟁(동시 수락)은 workspace-jdbc 의 dbTest 가 진짜 잠금으로 본다 */
class WorkspaceInvitationServiceTest {
    private val store = FakeWorkspaceStore()
    private val clock = MutableClock()
    private var seatLimit: Int? = null
    private val seats = WorkspaceSeatPolicy { seatLimit }
    private val labels = AccountLabels { ids -> ids.associateWith { "$it@example.com" } }
    private val workspaces = WorkspaceService(store, clock, seats, labels)
    private val sent = mutableListOf<InvitationMail>()
    private val verified = mutableMapOf<String, String>()
    private val facts = object : AccountFacts {
        override fun createdAt(accountId: String) = null
        override fun verifiedEmail(accountId: String) = verified[accountId]
        override fun accountIdByVerifiedEmail(email: String) = verified.entries.firstOrNull { it.value == email }?.key
    }
    private val props = WorkspaceProperties.Invitation(enabled = true)
    private val service = WorkspaceInvitationService(store, clock, seats, workspaces, facts, { sent += it }, props, labels)

    private fun code(block: () -> Unit) = assertFailsWith<WorkspaceException> { block() }.workspaceError
    private fun reason(block: () -> Unit) = (assertFailsWith<WorkspaceException> { block() }.data as Map<*, *>)["reason"]

    private fun team(): String = workspaces.create("acc_o", "Example Team").workspace.id

    private fun join(wsId: String, who: String, role: WorkspaceRole) {
        clock.advance(1)
        store.insertMember(WorkspaceMember(wsId, who, role, clock.now()))
    }

    /** [email] 한 통을 초대하고 메일로 나간 토큰을 돌려준다 */
    private fun invite(wsId: String, email: String, by: String = "acc_o", role: WorkspaceRole = WorkspaceRole.MEMBER): String {
        sent.clear()
        service.invite(by, wsId, listOf(email), role, "en")
        return sent.single().token
    }

    // ---- 초대하기

    @Test
    fun `inviting reports each address, lowercases and dedupes, and mails only the new ones`() {
        val ws = team()
        val first = service.invite("acc_o", ws, listOf(" New@Example.com ", "new@example.com", "b@example.com"), WorkspaceRole.MEMBER, "ko")
        assertEquals(listOf("new@example.com", "b@example.com"), first.invited)
        assertEquals(listOf("new@example.com", "b@example.com"), sent.map { it.to })
        assertEquals("ko", sent.first().locale)
        assertEquals("acc_o@example.com", sent.first().inviterLabel)
        assertEquals("Example Team", sent.first().workspaceName)

        sent.clear()
        val again = service.invite("acc_o", ws, listOf("B@example.com", "c@example.com"), WorkspaceRole.MEMBER, "en")
        assertEquals(listOf("c@example.com"), again.invited)
        assertEquals(listOf("b@example.com"), again.alreadyInvited)
        assertEquals(listOf("c@example.com"), sent.map { it.to })
    }

    @Test
    fun `the seat limit counts members plus live invitations and reports the rest as seatLimited`() {
        val ws = team()
        seatLimit = 3   // 소유자 1 + 초대 2
        val result = service.invite("acc_o", ws, listOf("a@example.com", "b@example.com", "c@example.com"), WorkspaceRole.MEMBER, "en")
        assertEquals(listOf("a@example.com", "b@example.com"), result.invited)
        assertEquals(listOf("c@example.com"), result.seatLimited)
        assertEquals(1, sent.count { it.to == "a@example.com" })
        assertTrue(sent.none { it.to == "c@example.com" })
    }

    @Test
    fun `an expired invitation frees its seat`() {
        val ws = team()
        seatLimit = 2
        service.invite("acc_o", ws, listOf("a@example.com"), WorkspaceRole.MEMBER, "en")
        assertEquals(listOf("b@example.com"), service.invite("acc_o", ws, listOf("b@example.com"), WorkspaceRole.MEMBER, "en").seatLimited)
        clock.advance(props.ttl.seconds + 1)
        // 만료된 초대는 좌석이 아니다 - 새 주소가 들어간다 (같은 주소는 재발송으로)
        assertEquals(listOf("b@example.com"), service.invite("acc_o", ws, listOf("b@example.com"), WorkspaceRole.MEMBER, "en").invited)
    }

    @Test
    fun `an admin invites members only, a member cannot invite, and the owner can invite admins`() {
        val ws = team()
        join(ws, "acc_a", WorkspaceRole.ADMIN)
        join(ws, "acc_m", WorkspaceRole.MEMBER)
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.invite("acc_a", ws, listOf("x@example.com"), WorkspaceRole.ADMIN, "en") })
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.invite("acc_m", ws, listOf("x@example.com"), WorkspaceRole.MEMBER, "en") })
        assertEquals(listOf("x@example.com"), service.invite("acc_a", ws, listOf("x@example.com"), WorkspaceRole.MEMBER, "en").invited)
        assertEquals(listOf("y@example.com"), service.invite("acc_o", ws, listOf("y@example.com"), WorkspaceRole.ADMIN, "en").invited)
        assertEquals(WorkspaceErrorCode.INVALID, code { service.invite("acc_o", ws, listOf("z@example.com"), WorkspaceRole.OWNER, "en") })
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.invite("acc_stranger", ws, listOf("z@example.com"), WorkspaceRole.MEMBER, "en") })
    }

    @Test
    fun `bad addresses, an empty list and more than 20 addresses are refused whole`() {
        val ws = team()
        assertEquals(WorkspaceErrorCode.INVALID, code { service.invite("acc_o", ws, emptyList(), WorkspaceRole.MEMBER, "en") })
        assertEquals(WorkspaceErrorCode.INVALID, code { service.invite("acc_o", ws, listOf("ok@example.com", "not-an-address"), WorkspaceRole.MEMBER, "en") })
        assertEquals(WorkspaceErrorCode.INVALID, code { service.invite("acc_o", ws, (1..21).map { "u$it@example.com" }, WorkspaceRole.MEMBER, "en") })
        assertTrue(sent.isEmpty())
        assertTrue(store.invitationRows().isEmpty())
    }

    @Test
    fun `too many pending invitations is refused and nothing of that request is kept`() {
        val ws = team()
        val small = WorkspaceInvitationService(store, clock, seats, workspaces, facts, { sent += it }, WorkspaceProperties.Invitation(enabled = true, maxPending = 2), labels)
        small.invite("acc_o", ws, listOf("a@example.com"), WorkspaceRole.MEMBER, "en")
        sent.clear()
        // 둘째는 들어가지만 셋째에서 넘는다 - 요청 전체가 없던 일이 되고 메일도 안 나간다
        assertEquals(WorkspaceErrorCode.TOO_MANY_PENDING, code { small.invite("acc_o", ws, listOf("b@example.com", "c@example.com"), WorkspaceRole.MEMBER, "en") })
        assertEquals(1, store.countPendingInvitations(ws))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an address that already belongs to a member is refused as alreadyMember and takes no seat or invitation row`() {
        val ws = team()
        join(ws, "acc_m", WorkspaceRole.MEMBER)
        verified["acc_m"] = "m@example.com"
        verified["acc_o"] = "o@example.com"
        seatLimit = 3
        val result = service.invite("acc_o", ws, listOf("m@example.com", "O@example.com", "new@example.com"), WorkspaceRole.MEMBER, "en")
        assertEquals(listOf("m@example.com", "o@example.com"), result.alreadyMember)
        assertEquals(listOf("new@example.com"), result.invited)
        assertEquals(1, store.invitationRows().size)
        assertEquals(listOf("new@example.com"), sent.map { it.to })
        assertEquals(emptyList(), result.seatLimited, "the member addresses took no seat")
    }

    @Test
    fun `only the owner can resend or revoke an invitation to admin`() {
        val ws = team()
        join(ws, "acc_a", WorkspaceRole.ADMIN)
        invite(ws, "boss@example.com", role = WorkspaceRole.ADMIN)
        invite(ws, "peer@example.com")
        val adminInvite = store.findPendingInvitation(ws, "boss@example.com")!!.id
        val memberInvite = store.findPendingInvitation(ws, "peer@example.com")!!.id
        clock.advance(props.resendInterval.seconds)
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.resend("acc_a", ws, adminInvite, "en") })
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.revoke("acc_a", ws, adminInvite) })
        service.resend("acc_a", ws, memberInvite, "en")
        service.revoke("acc_a", ws, memberInvite)
        service.resend("acc_o", ws, adminInvite, "en")
        service.revoke("acc_o", ws, adminInvite)
    }

    // ---- 토큰은 해시로만

    @Test
    fun `only a sha256 of the token is stored, the token itself exists only in the mail`() {
        val ws = team()
        val token = invite(ws, "a@example.com")
        val row = store.invitationRows().single()
        assertEquals(InvitationTokens.hash(token), row.tokenHash)
        assertEquals(64, row.tokenHash.length)
        assertNotEquals(token, row.tokenHash)
        assertFalse(row.toString().contains(token), "toString leaks the token")
        assertFalse(row.toString().contains("a@example.com"), "toString leaks the address")
        assertFalse(sent.single().toString().contains(token), "the mail object's toString leaks the token")
        assertTrue(token.length >= 43, "32 random bytes in base64url")
    }

    // ---- 미리보기

    @Test
    fun `preview shows the team, the inviter, a masked address, the role and the expiry`() {
        val ws = team()
        val token = invite(ws, "alice@example.com", role = WorkspaceRole.ADMIN)
        val preview = service.preview(token)
        assertEquals("Example Team", preview.workspaceName)
        assertEquals("acc_o@example.com", preview.inviterLabel)
        assertEquals("a***@e***.com", preview.emailMasked)
        assertEquals(WorkspaceRole.ADMIN, preview.role)
        assertEquals(clock.now().plus(props.ttl), preview.expiresAt)
    }

    @Test
    fun `unknown, expired, used, revoked and closed-team tokens are all refused with a reason`() {
        val ws = team()
        assertEquals("INVALID", reason { service.preview("nope") })
        assertEquals("INVALID", reason { service.preview("x".repeat(500)) })

        val expired = invite(ws, "e@example.com")
        clock.advance(props.ttl.seconds + 1)
        assertEquals(WorkspaceErrorCode.INVITATION_INVALID, code { service.preview(expired) })
        assertEquals("EXPIRED", reason { service.preview(expired) })
        assertEquals("EXPIRED", reason { service.accept("acc_e", expired) })

        val used = invite(ws, "u@example.com")
        verified["acc_u"] = "u@example.com"
        service.accept("acc_u", used)
        assertEquals("USED", reason { service.preview(used) })
        assertEquals("USED", reason { service.accept("acc_u", used) })

        val revoked = invite(ws, "r@example.com")
        service.revoke("acc_o", ws, store.findPendingInvitation(ws, "r@example.com")!!.id)
        assertEquals("INVALID", reason { service.preview(revoked) })

        val gone = invite(ws, "g@example.com")
        store.closeWorkspace(ws, clock.now())
        assertEquals("INVALID", reason { service.preview(gone) })
        assertEquals("INVALID", reason { service.accept("acc_g", gone) })
    }

    // ---- 재발송 · 취소

    @Test
    fun `resending gives a new token, the old link stops working, and the expiry restarts`() {
        val ws = team()
        val old = invite(ws, "a@example.com")
        val id = store.invitationRows().single().id
        clock.advance(props.ttl.seconds - 10)
        clock.advance(props.resendInterval.seconds)

        sent.clear()
        service.resend("acc_o", ws, id, "ko")
        val fresh = sent.single().token
        assertNotEquals(old, fresh)
        assertEquals("ko", sent.single().locale)
        assertEquals("INVALID", reason { service.preview(old) }, "the old link is dead at once")
        assertEquals("INVALID", reason { service.accept("acc_a", old) })
        assertEquals(clock.now().plus(props.ttl), service.preview(fresh).expiresAt)
        assertEquals(2, store.findInvitation(id)!!.sentCount)
    }

    @Test
    fun `resending within the interval is 429 and keeps the old token`() {
        val ws = team()
        val token = invite(ws, "a@example.com")
        val id = store.invitationRows().single().id
        sent.clear()
        clock.advance(props.resendInterval.seconds - 1)
        assertEquals(WorkspaceErrorCode.RESEND_TOO_SOON, code { service.resend("acc_o", ws, id, "en") })
        assertTrue(sent.isEmpty())
        assertEquals("Example Team", service.preview(token).workspaceName)
        clock.advance(1)
        service.resend("acc_o", ws, id, "en")
        assertEquals(1, sent.size)
    }

    @Test
    fun `revoking frees the address and the seat, and a revoked or foreign invitation cannot be resent`() {
        val ws = team()
        val other = workspaces.create("acc_x", "Other Team").workspace.id
        seatLimit = 2
        invite(ws, "a@example.com")
        val id = store.invitationRows().single().id
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.revoke("acc_x", ws, id) })
        assertEquals(WorkspaceErrorCode.INVITATION_NOT_FOUND, code { service.revoke("acc_x", other, id) }, "the id belongs to another workspace")

        service.revoke("acc_o", ws, id)
        assertEquals(InvitationStatus.REVOKED, store.findInvitation(id)!!.status)
        assertEquals(WorkspaceErrorCode.INVITATION_NOT_FOUND, code { service.resend("acc_o", ws, id, "en") })
        assertEquals(WorkspaceErrorCode.INVITATION_NOT_FOUND, code { service.revoke("acc_o", ws, id) })
        assertEquals(listOf("a@example.com"), service.invite("acc_o", ws, listOf("a@example.com"), WorkspaceRole.MEMBER, "en").invited, "revoked, so the same address can be invited again")
    }

    @Test
    fun `the pending list is for admins and marks expired ones`() {
        val ws = team()
        join(ws, "acc_a", WorkspaceRole.ADMIN)
        join(ws, "acc_m", WorkspaceRole.MEMBER)
        invite(ws, "a@example.com")
        clock.advance(props.ttl.seconds + 1)
        invite(ws, "b@example.com")
        val rows = service.list("acc_a", ws)
        assertEquals(listOf("a@example.com", "b@example.com"), rows.map { it.invitation.email })
        assertEquals(listOf(true, false), rows.map { it.expired })
        assertEquals(WorkspaceErrorCode.FORBIDDEN, code { service.list("acc_m", ws) })
        assertEquals(WorkspaceErrorCode.NOT_FOUND, code { service.list("acc_stranger", ws) })
    }

    // ---- 수락

    @Test
    fun `accepting needs the verified address to match, then joins with the invited role and closes the invitation`() {
        val ws = team()
        val token = invite(ws, "alice@example.com", role = WorkspaceRole.ADMIN)

        assertEquals(WorkspaceErrorCode.EMAIL_UNVERIFIED, code { service.accept("acc_alice", token) })
        verified["acc_alice"] = "bob@example.com"
        assertEquals(WorkspaceErrorCode.INVITATION_EMAIL_MISMATCH, code { service.accept("acc_alice", token) })
        assertNull(store.findMember(ws, "acc_alice"), "a refused accept changes nothing")
        assertEquals(InvitationStatus.PENDING, store.invitationRows().single().status)

        verified["acc_alice"] = " Alice@Example.com "
        val joined = service.accept("acc_alice", token)
        assertEquals(ws, joined.workspace.id)
        assertEquals(WorkspaceRole.ADMIN, joined.role)
        assertEquals(WorkspaceRole.ADMIN, store.findMember(ws, "acc_alice")!!.role)
        assertEquals("acc_o", store.findMember(ws, "acc_alice")!!.invitedBy)
        val row = store.invitationRows().single()
        assertEquals(InvitationStatus.ACCEPTED, row.status)
        assertEquals("acc_alice", row.acceptedBy)
        assertEquals(1, store.countMembers(ws) - 1)
    }

    @Test
    fun `a member who accepts again gets 409 and the invitation is closed anyway`() {
        val ws = team()
        join(ws, "acc_alice", WorkspaceRole.MEMBER)
        val token = invite(ws, "alice@example.com")
        verified["acc_alice"] = "alice@example.com"
        val e = assertFailsWith<WorkspaceException> { service.accept("acc_alice", token) }
        assertEquals(WorkspaceErrorCode.ALREADY_MEMBER, e.workspaceError)
        assertEquals(WorkspaceRole.MEMBER, store.findMember(ws, "acc_alice")!!.role, "the role is not changed")
        assertEquals(InvitationStatus.ACCEPTED, store.invitationRows().single().status)
    }

    @Test
    fun `a full team refuses the accept with 409 and the invitation stays pending`() {
        val ws = team()
        val token = invite(ws, "alice@example.com")
        join(ws, "acc_other", WorkspaceRole.MEMBER)
        seatLimit = 2   // 소유자 + 다른 멤버 = 가득
        verified["acc_alice"] = "alice@example.com"
        assertEquals(WorkspaceErrorCode.SEAT_LIMIT, code { service.accept("acc_alice", token) })
        assertEquals(InvitationStatus.PENDING, store.invitationRows().single().status)
        seatLimit = 3
        assertEquals(WorkspaceRole.MEMBER, service.accept("acc_alice", token).role)
    }

    @Test
    fun `the default ttl is seven days and the resend interval a minute`() {
        assertEquals(Duration.ofDays(7), WorkspaceProperties.Invitation().ttl)
        assertEquals(Duration.ofSeconds(60), WorkspaceProperties.Invitation().resendInterval)
        assertEquals(100, WorkspaceProperties.Invitation().maxPending)
        assertEquals(20, WorkspaceProperties.Invitation().maxPerRequest)
        assertFalse(WorkspaceProperties.Invitation().enabled, "off until the app opts in (it needs AccountFacts and a link address)")
    }
}
