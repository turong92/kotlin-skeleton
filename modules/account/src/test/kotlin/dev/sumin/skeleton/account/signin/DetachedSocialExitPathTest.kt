package dev.sumin.skeleton.account.signin

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.ReauthInput
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * 막다른 길의 출구 (docs/accounts.md "소셜을 끊은 뒤 다시 연결"): 계정 A 가 소셜을 끊은 뒤 그 소셜로 로그인하면 새 계정 B 가 생기고, A 는 그 소셜을 다시 붙이지 못한다 (IDENTITY_TAKEN).
 * 길: B 로 로그인 → 비밀번호를 정해 수단을 둘로 → B 에서 그 소셜을 끊는다 → A 에서 연결.
 */
class DetachedSocialExitPathTest {
    private object Google : SignInMethod { override val code = "google"; override val userRemovable = true }

    private val h = AccountHarness(
        AccountProperties(
            social = AccountProperties.Social(signUp = true),
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
        ),
    )
    private val registry = SignInMethodRegistry(listOf(PasswordSignInMethod(), Google))
    private val signIn = AccountSignInService(h.core, registry)
    private val ids = IdentityService(h.core, registry)
    private val PW = ReauthInput("tangerine-42-moon")
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    @Test
    fun `after A detaches a social, B (made by signing in with it) sets a password and detaches it - then A can attach it again`() {
        val a = h.activeAccount("ann@example.com")
        val link = ids.link(a.id, "google", "g-shared", verified = true, expectEmailVerified = a.emailVerified)
        ids.unlink(a.id, link.id, "ses_a", PW)

        // signing in with the detached social makes a NEW account B (its own verified address)
        val b = h.repo.findById(assertNotNull(signIn.signIn(SignInProof("google", "g-shared", "bob@example.com", true, "Bob", "ko", "203.0.113.1", true))).accountId)!!
        assertEquals(setOf("google"), h.repo.identitiesOf(b.id).map { it.method }.toSet())

        // A cannot take it back while B holds it
        assertEquals("ACCOUNT.IDENTITY_TAKEN", code { ids.link(a.id, "google", "g-shared", verified = true, expectEmailVerified = a.emailVerified) })

        // B has only that one method, so it cannot detach it yet
        val bGoogle = h.repo.identitiesOf(b.id).single { it.method == "google" }
        h.reauth.requestConfirmation(b.id, "ses_b")
        val mailed = h.mailer.of(MailKind.REAUTH_CODE).last { it.to == "bob@example.com" }.vars.getValue("code")
        assertEquals("ACCOUNT.LAST_SIGN_IN_METHOD", code { ids.unlink(b.id, bGoogle.id, "ses_b", ReauthInput(confirmationCode = mailed)) })

        // B sets its first password (proved by the mailed code), now having two methods, and detaches the social with that password
        h.passwords.change(b.id, null, "tangerine-42-moon", "ses_b", mailed)
        ids.unlink(b.id, bGoogle.id, "ses_b", PW)

        // the way is free: A attaches it
        ids.link(a.id, "google", "g-shared", verified = true, expectEmailVerified = a.emailVerified)
        assertEquals(a.id, h.repo.findIdentity("google", "g-shared")!!.accountId)
    }
}
