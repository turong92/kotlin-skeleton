package dev.sumin.skeleton.auth.social.config

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/** 소셜 제공자 환경변수 규칙은 **하나**다 — Map 값 설정(`providers.<코드>.*`)을 쓰는 모듈(auth-social · auth-social-oidc)이 같은 구현을 쓴다. */
class ProviderEnvironmentAliasesTest {
    @Test
    fun `one rule - code is one word, key underscores become dashes, listed nested prefixes become dots, empty values and other names are skipped`() {
        val out = ProviderEnvironmentAliases.fromEnvironment(
            mapOf(
                "X_PROVIDERS_LINE_CLIENT_ID" to "a",
                "X_PROVIDERS_LINE_TOKEN_ENDPOINT_AUTH" to "post",
                "X_PROVIDERS_LINE_CLAIMS_EMAIL_VERIFIED" to "ev",
                "X_PROVIDERS_LINE_ISSUER" to "",
                "X_PROVIDERS_google_CLIENT_ID" to "lowercase code is not an env name",
                "OTHER_PROVIDERS_LINE_CLIENT_ID" to "x",
            ),
            envPrefix = "X_PROVIDERS_", propertyPrefix = "app.providers", nestedPrefixes = setOf("claims"),
        )
        assertEquals(
            mapOf<String, Any>("app.providers.line.client-id" to "a", "app.providers.line.token-endpoint-auth" to "post", "app.providers.line.claims.email-verified" to "ev"),
            out,
        )
    }

    @Test
    fun `the post processor reads the systemEnvironment property source and wins over later sources`() {
        val env = StandardEnvironment()
        env.propertySources.remove("systemEnvironment")
        env.propertySources.addLast(SystemEnvironmentPropertySource("systemEnvironment", mutableMapOf<String, Any>("X_PROVIDERS_LINE_CLIENT_ID" to "from-env")))
        env.propertySources.addLast(MapPropertySource("yml", mapOf("app.providers.line.client-id" to "from-yml")))
        ProviderEnvironmentAliases.apply(env, "X_PROVIDERS_", "app.providers", "testAliases")
        assertEquals("from-env", env.getProperty("app.providers.line.client-id"))
        // 두 번 불러도 같다 (같은 이름의 속성 소스를 갈아 끼운다)
        ProviderEnvironmentAliases.apply(env, "X_PROVIDERS_", "app.providers", "testAliases")
        assertEquals(1, env.propertySources.count { it.name == "testAliases" })
    }
}
