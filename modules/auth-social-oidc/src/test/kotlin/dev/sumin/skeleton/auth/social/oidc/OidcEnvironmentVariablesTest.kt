package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.util.function.Supplier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 진짜 환경변수 모양(systemEnvironment 속성 소스)으로 시험한다. Map 값 설정(`providers.<코드>.*`)은 스프링의 느슨한 바인딩만으로는 환경변수에서 묶이지 않는다 —
 * 별칭 후처리기가 `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_<코드>_<키>` 를 점 표기로 다시 적어 준다.
 */
class OidcEnvironmentVariablesTest {
    private val idp = FakeIdp(clientId = "2001234567", clientSecret = "0123456789abcdef0123456789abcdef")
    @AfterTest fun close() = idp.close()

    private fun runner(withAlias: Boolean, vararg env: Pair<String, String>) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OidcAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, Supplier { FakeIdp.httpClient() })
        .withInitializer { ctx ->
            ctx.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(*env)))
            if (withAlias) OidcEnvironmentAliasPostProcessor().postProcessEnvironment(ctx.environment, org.springframework.boot.SpringApplication())
        }

    private val p = "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE"

    @Test
    fun `without the alias the real env names do NOT bind - the finding this module guards against`() {
        runner(false, "${p}_CLIENT_ID" to "2001234567", "${p}_CLIENT_SECRET" to "s").run { ctx ->
            assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `LINE configured only by env vars - CLIENT_ID CLIENT_SECRET REDIRECT_URI SCOPES - becomes a provider`() {
        runner(
            true,
            "${p}_CLIENT_ID" to "2001234567", "${p}_CLIENT_SECRET" to "0123456789abcdef0123456789abcdef",
            "${p}_REDIRECT_URI" to "https://app.example.com/auth/callback", "${p}_SCOPES" to "openid,profile,email",
            // 테스트가 가짜 서버로 돌리려는 칸 (프리셋의 칸을 환경변수가 이긴다)
            "${p}_AUTHORIZATION_ENDPOINT" to "${idp.base}/authorize", "${p}_TOKEN_ENDPOINT" to "${idp.base}/token", "${p}_JWKS_URI" to "${idp.base}/jwks", "${p}_ISSUER" to idp.issuer,
            "${p}_TOKEN_ENDPOINT_AUTH" to "post", "${p}_CLAIMS_EMAIL_VERIFIED" to "ev",
        ).run { ctx ->
            assertEquals(null, ctx.startupFailure)
            val line = ctx.getBeansOfType(OAuthProvider::class.java).values.single()
            assertEquals("line", line.providerId)
            assertEquals("2001234567", line.publicClientId)
            assertEquals("https://app.example.com/auth/callback", line.publicRedirectUri)
            assertEquals(listOf("openid", "profile", "email"), line.authorize?.scopes)
        }
    }

    @Test
    fun `empty variables and other prefixes are ignored`() {
        runner(true, "${p}_CLIENT_ID" to "", "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID" to "g").run { ctx ->
            assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `the alias rule is pure - code is one word, key underscores become dashes, claims_ nests`() {
        assertEquals(
            mapOf(
                "skeleton.auth-social-oidc.providers.microsoft.client-id" to "a",
                "skeleton.auth-social-oidc.providers.microsoft.token-endpoint-auth" to "basic",
                "skeleton.auth-social-oidc.providers.microsoft.claims.email-verified" to "ev",
            ),
            OidcEnvironmentAliases.fromEnvironment(
                mapOf(
                    "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_MICROSOFT_CLIENT_ID" to "a",
                    "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_MICROSOFT_TOKEN_ENDPOINT_AUTH" to "basic",
                    "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_MICROSOFT_CLAIMS_EMAIL_VERIFIED" to "ev",
                    "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_MICROSOFT_ISSUER" to "",
                    "UNRELATED" to "x",
                ),
            ),
        )
    }
}
