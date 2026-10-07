package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.account.MagicLinkIssuer
import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

/** `skeleton.auth-magic-link.http.enabled=false` removes the redeem endpoint — so the issuer that puts a one-time link into the already-registered mail must go with it (S-5d): a link nobody can redeem is a dead link in a mail */
@SpringBootTest(classes = [MagicLinkTestApplication::class], properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4", "skeleton.auth-magic-link.http.enabled=false"])
@Import(MagicLinkTestBeans::class)
class MagicLinkHttpOffTest {
    @Autowired lateinit var ctx: ApplicationContext

    @Test
    fun `without the http endpoints there is no MagicLinkIssuer, no controller, but the sign-in method stays`() {
        assertEquals(0, ctx.getBeansOfType(MagicLinkIssuer::class.java).size)
        assertEquals(0, ctx.getBeansOfType(MagicLinkController::class.java).size)
        assertTrue(ctx.getBeansOfType(MagicLinkService::class.java).isNotEmpty())
    }
}

@SpringBootTest(classes = [MagicLinkTestApplication::class], properties = ["skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.account.password.bcrypt-strength=4"])
@Import(MagicLinkTestBeans::class)
class MagicLinkHttpOnTest {
    @Autowired lateinit var ctx: ApplicationContext

    @Test
    fun `with the endpoints on (the default) the issuer is there`() {
        assertEquals(1, ctx.getBeansOfType(MagicLinkIssuer::class.java).size)
    }
}
