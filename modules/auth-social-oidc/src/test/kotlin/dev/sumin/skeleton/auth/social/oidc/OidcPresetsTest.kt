package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.NonceMode
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import kotlin.test.Test
import kotlin.test.assertEquals

class OidcPresetsTest {
    private val line = OidcPresets.resolve("line", OidcProperties.Provider(clientId = "1234567890", clientSecret = "channel-secret"))

    @Test
    fun `the LINE preset needs only the channel id and secret and carries LINE's documented endpoints`() {
        assertEquals("https://access.line.me", line.issuer)
        assertEquals("https://access.line.me/oauth2/v2.1/authorize", line.authorizationEndpoint)
        assertEquals("https://api.line.me/oauth2/v2.1/token", line.tokenEndpoint)
        assertEquals("https://api.line.me/oauth2/v2.1/certs", line.jwksUri)
        assertEquals("https://api.line.me/oauth2/v2.1/userinfo", line.userinfoEndpoint)
        assertEquals(OidcProperties.ClientAuth.POST, line.clientAuth)
    }

    @Test
    fun `LINE defaults - PKCE and nonce required, web login HS256 plus native ES256, email never verified, no userinfo call`() {
        assertEquals(PkceMode.REQUIRED, line.pkce)
        assertEquals(NonceMode.REQUIRED, line.nonce)
        assertEquals(listOf("HS256", "ES256"), line.algorithms.map { it.name })
        assertEquals(OidcProperties.EmailTrust.NEVER, line.emailTrust)
        assertEquals(OidcProperties.UserinfoMode.NEVER, line.userinfo)
    }

    @Test
    fun `LINE asks only openid and profile by default because the email scope needs the console permission - email is an explicit opt in`() {
        assertEquals(listOf("openid", "profile"), line.scopes)
        val withEmail = OidcPresets.resolve("line", OidcProperties.Provider(clientId = "1", clientSecret = "s", scopes = listOf("openid", "profile", "email")))
        assertEquals(listOf("openid", "profile", "email"), withEmail.scopes)
    }

    @Test
    fun `explicit properties win over the preset and another code can borrow the preset`() {
        val custom = OidcPresets.resolve("line-jp", OidcProperties.Provider(preset = "line", clientId = "1", clientSecret = "s", pkce = PkceMode.SUPPORTED))
        assertEquals(PkceMode.SUPPORTED, custom.pkce)
        assertEquals("https://api.line.me/oauth2/v2.1/token", custom.tokenEndpoint)
        assertEquals("line-jp", custom.code)
    }

    @Test
    fun `an unknown code gets the generic defaults`() {
        val g = OidcPresets.resolve("microsoft", OidcProperties.Provider(issuer = "https://login.microsoftonline.com/common/v2.0", clientId = "1", clientSecret = "s"))
        assertEquals(PkceMode.SUPPORTED, g.pkce)
        assertEquals(NonceMode.SUPPORTED, g.nonce)
        assertEquals(listOf("RS256"), g.algorithms.map { it.name })
        assertEquals(OidcProperties.ClientAuth.BASIC, g.clientAuth)
        assertEquals(listOf("openid", "profile", "email"), g.scopes)
        assertEquals(OidcProperties.EmailTrust.CLAIM, g.emailTrust)
        assertEquals(OidcProperties.UserinfoMode.FALLBACK, g.userinfo)
    }
}
