package dev.sumin.skeleton.auth.social.x

import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.reactive.function.client.WebClient

/**
 * 진짜 환경변수 모양(systemEnvironment 속성 소스, 대문자 · 밑줄)으로 묶이는지 — 점 표기 TestPropertyValues 가 가리는 문제를 잡는다.
 * 확인한 사실: X 의 설정은 Map 이 아니라 평범한 속성 클래스라 스프링의 느슨한 바인딩이 `SKELETON_AUTH_SOCIAL_X_CLIENT_ID` 를 그대로 묶는다 — 별칭 후처리기가 필요 없다
 * (Map 값인 auth-social · auth-social-oidc 의 `providers.<코드>.*` 와 다르다).
 */
class XEnvironmentVariablesTest {
    private fun runner(vararg env: Pair<String, String>) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(XAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, Supplier { DefaultExternalHttpClient(WebClient.builder(), OutboundHttpProperties(), DefaultExternalHttpErrorMapper(), emptyList()) })
        .withInitializer { ctx -> ctx.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(*env))) }

    @Test
    fun `the documented env names reach the properties as they are and turn the provider on`() {
        runner("SKELETON_AUTH_SOCIAL_X_CLIENT_ID" to "env-id", "SKELETON_AUTH_SOCIAL_X_CLIENT_SECRET" to "env-secret", "SKELETON_AUTH_SOCIAL_X_REDIRECT_URI" to "http://127.0.0.1:5173/auth/callback", "SKELETON_AUTH_SOCIAL_X_REQUEST_EMAIL" to "true").run { ctx ->
            assertEquals(null, ctx.startupFailure)
            val provider = ctx.getBean(OAuthProvider::class.java)
            assertEquals("env-id", provider.publicClientId)
            assertEquals("http://127.0.0.1:5173/auth/callback", provider.publicRedirectUri, "the host is passed through untouched (127.0.0.1 stays 127.0.0.1)")
            assertTrue("users.email" in provider.authorize!!.scopes)
        }
    }

    @Test
    fun `empty variables mean not configured`() {
        runner("SKELETON_AUTH_SOCIAL_X_CLIENT_ID" to "", "SKELETON_AUTH_SOCIAL_X_CLIENT_SECRET" to "").run { assertTrue(it.getBeansOfType(OAuthProvider::class.java).isEmpty()) }
    }
}
