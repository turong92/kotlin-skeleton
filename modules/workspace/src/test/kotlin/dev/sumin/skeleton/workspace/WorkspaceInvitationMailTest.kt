package dev.sumin.skeleton.workspace

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.workspace.mail.DefaultWorkspaceInvitationMail
import dev.sumin.skeleton.workspace.mail.LogOnlyWorkspaceMailTransport
import dev.sumin.skeleton.workspace.mail.TemplatedWorkspaceInvitationMailer
import dev.sumin.skeleton.workspace.mail.WorkspaceLinks
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/** 메일 틀(ko · en)과 「토큰은 로그에 없다」 - 메일 모듈이 없을 때의 길(로그만)에서도 */
class WorkspaceInvitationMailTest {
    private val links = WorkspaceLinks { "https://app.example.com/" }
    private val expires = Instant.parse("2026-11-06T09:30:00Z")

    private fun mail(locale: String?, role: WorkspaceRole = WorkspaceRole.MEMBER, inviter: String? = "Dana", token: String = "tok-123") =
        InvitationMail("alice@example.com", locale, "Example Team", inviter, role, expires, token)

    private fun logsDuring(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = root.level
        root.level = Level.ALL
        root.addAppender(appender)
        try { block() } finally { root.detachAppender(appender); root.level = before }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `the built-in template speaks ko or en by the requested language and always carries the accept link`() {
        val templates = DefaultWorkspaceInvitationMail("en")
        val link = links.invite("tok-123")
        assertEquals("https://app.example.com/invite?token=tok-123", link)

        val ko = templates.render(mail("ko-KR,ko;q=0.9", WorkspaceRole.ADMIN), link)
        assertTrue(ko.subject.contains("Example Team") && ko.text.contains("Dana님이") && ko.text.contains("관리자") && ko.text.contains(link) && ko.text.contains("2026-11-06 09:30 UTC"), ko.text)
        val en = templates.render(mail("en-US,en;q=0.9"), link)
        assertTrue(en.text.contains("Dana invited you") && en.text.contains("as a member") && en.text.contains(link), en.text)
        assertTrue(templates.render(mail("fr"), link).text.contains("invited you"), "an unknown language falls back to the default locale")
        assertTrue(DefaultWorkspaceInvitationMail("ko").render(mail(null), link).text.contains("초대했어요"))
        assertTrue(templates.render(mail("en", inviter = null), link).text.startsWith("Someone invited you"))
    }

    @Test
    fun `the token request body prints without its token`() {
        assertFalse(dev.sumin.skeleton.workspace.web.InvitationTokenRequest("secret-token-C").toString().contains("secret-token-C"))
    }

    @Test
    fun `without a mail module the token stays out of the log, unless a local app chose to show links`() {
        val hidden = logsDuring { TemplatedWorkspaceInvitationMailer(DefaultWorkspaceInvitationMail(), LogOnlyWorkspaceMailTransport(false), links).send(mail("en", token = "secret-token-A")) }
        assertTrue(hidden.isNotEmpty())
        assertTrue(hidden.none { it.contains("secret-token-A") || it.contains("alice@example.com") }, hidden.toString())

        val shown = logsDuring { TemplatedWorkspaceInvitationMailer(DefaultWorkspaceInvitationMail(), LogOnlyWorkspaceMailTransport(true), links).send(mail("en", token = "secret-token-B")) }
        assertTrue(shown.any { it.contains("https://app.example.com/invite?token=secret-token-B") }, "local development reads the link from the log")
        assertTrue(shown.none { it.contains("alice@example.com") }, "the address is masked either way")
    }

    @Test
    fun `a whole invite flow through the log-only path leaves no token in any log line`() {
        val store = FakeWorkspaceStore()
        val clock = MutableClock()
        val seats = WorkspaceSeatPolicy { null }
        val workspaces = WorkspaceService(store, clock, seats)
        val facts = AccountFacts { null }
        val mailer = TemplatedWorkspaceInvitationMailer(DefaultWorkspaceInvitationMail(), LogOnlyWorkspaceMailTransport(false), links)
        val seen = mutableListOf<String>()
        val service = WorkspaceInvitationService(store, clock, seats, workspaces, facts, { m -> seen += m.token; mailer.send(m) }, WorkspaceProperties.Invitation(enabled = true))
        val ws = workspaces.create("acc_o", "Example Team").workspace.id

        val logs = logsDuring {
            service.invite("acc_o", ws, listOf("alice@example.com"), WorkspaceRole.MEMBER, "en")
            clock.advance(61)
            service.resend("acc_o", ws, store.invitationRows().single().id, "ko")
        }
        assertEquals(2, seen.size)
        assertFalse(logs.any { line -> seen.any { line.contains(it) } }, logs.toString())
    }
}
