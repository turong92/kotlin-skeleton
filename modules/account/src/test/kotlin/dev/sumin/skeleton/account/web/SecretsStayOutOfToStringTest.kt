package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.social.LinkSocialRequest
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.PasswordLoginRequest
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spring MVC logs handler arguments and bodies with `toString()` at DEBUG/TRACE (实测: `Read "application/json" to [SignUpRequest(... password=...)]`).
 * A request or response that carries a secret must never print it — otherwise turning the log level up leaks passwords and one-time tokens.
 */
class SecretsStayOutOfToStringTest {
    private val secret = "S3cr3t-Value-123"

    private fun assertHidden(value: Any) {
        val text = value.toString()
        assertFalse(secret in text, "${value.javaClass.simpleName}.toString() leaked a secret: $text")
        assertTrue(value.javaClass.simpleName in text, "toString should still say what it is")
    }

    @Test
    fun `account request bodies hide passwords and tokens`() {
        assertHidden(SignUpRequest("a@b.co", secret))
        assertHidden(ResetPasswordRequest(secret, secret))
        assertHidden(TokenRequest(secret))
        assertHidden(ChangePasswordRequest(secret, secret))
        assertHidden(ChangeEmailRequest("a@b.co", secret, secret, SocialReauthRequest("kakao", secret)))
        assertHidden(DeleteAccountRequest(secret, secret, SocialReauthRequest("kakao", secret)))
        assertHidden(ReauthRequest(secret, secret, SocialReauthRequest("kakao", secret)))
        assertHidden(SocialReauthRequest("kakao", secret))
        assertHidden(VerifyEmailRequest(secret, secret))
        assertHidden(ResendRequest(secret))
        assertHidden(CodeRequest(secret))
        assertHidden(SignUpResponse("VERIFICATION_SENT", secret))
        assertHidden(LinkSocialRequest(secret, null, secret, secret, SocialReauthRequest("kakao", secret)))
    }

    @Test
    fun `commands, sessions and seed accounts hide secrets too`() {
        assertHidden(dev.sumin.skeleton.account.ReauthInput(secret, secret, dev.sumin.skeleton.account.SocialReauth("kakao", secret)))
        assertHidden(dev.sumin.skeleton.account.SocialReauth("kakao", secret))
        assertHidden(dev.sumin.skeleton.account.MailboxProof(setOf("idn_1"), secret))
        assertHidden(dev.sumin.skeleton.account.challenge.ChallengeRow("id", "sign_up", "a@b.co", null, null, null, secret, "hash", 5, 0, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null))
        assertHidden(dev.sumin.skeleton.account.SignUpCommand("a@b.co", secret, null, null, null, null, null))
        assertHidden(dev.sumin.skeleton.auth.session.OpenedSession("ses_1", secret, Instant.EPOCH))
        assertHidden(dev.sumin.skeleton.account.AccountProperties.SeedAccount(email = "a@b.co", password = secret))
    }

    @Test
    fun `account 202 and 201 answers are declared with the platform operation annotations so OpenAPI says 202 not 200`() {
        fun annotated(type: Class<*>, name: String, annotation: Class<out Annotation>) = type.methods.single { it.name == name }.isAnnotationPresent(annotation)
        val accepted = dev.sumin.skeleton.common.openapi.AcceptedOperation::class.java
        assertTrue(annotated(AccountPublicController::class.java, "resend", accepted))
        assertTrue(annotated(AccountPublicController::class.java, "forgot", accepted))
        assertTrue(annotated(AccountController::class.java, "changeEmail", accepted))
        assertTrue(annotated(AccountController::class.java, "delete", accepted))
        assertTrue(annotated(dev.sumin.skeleton.account.social.SocialIdentityController::class.java, "link", dev.sumin.skeleton.common.openapi.CreatedOperation::class.java))
    }

    @Test
    fun `the login request and the token response hide credentials`() {
        assertHidden(PasswordLoginRequest(email = "a@b.co", password = secret))
        assertHidden(AuthTokenResponse(secret, expiresAt = Instant.EPOCH, principal = CurrentPrincipal("acc_1"), refreshToken = secret))
    }

    @Test
    fun `stored identities and auth accounts hide password hashes`() {
        assertHidden(dev.sumin.skeleton.account.Identity("idn_1", "acc_1", "password", "a@b.co", true, secret = secret, createdAt = Instant.EPOCH))
        assertHidden(dev.sumin.skeleton.auth.account.AuthAccount("acc_1", "a@b.co", "a@b.co", secret, setOf("USER")))
    }
}
