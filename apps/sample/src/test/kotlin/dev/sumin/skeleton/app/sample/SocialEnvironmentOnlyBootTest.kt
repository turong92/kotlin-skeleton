package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.StandardEnvironment

/**
 * 소셜 제공자를 **환경변수로만** 설정해 `apps/sample` 을 진짜로 기동한다 — 한 규칙(`<P>_AUTH_SOCIAL_[OIDC_]PROVIDERS_<코드>_<키>` · X 는 `<P>_AUTH_SOCIAL_X_<키>`)이
 * google · line · x 모두에 통하는지, 그리고 `GET /auth/methods` 가 셋 다 client id 와 함께 보여 주는지.
 * 환경은 systemEnvironment 속성 소스 모양(대문자 · 밑줄 이름)으로 갈아 끼운다 — 점 표기 속성으로 주면 이 문제(Map 값의 `_` 쪼개짐)를 가린다.
 */
@ExtendWith(OutputCaptureExtension::class)
class SocialEnvironmentOnlyBootTest {
    private val env = mapOf(
        "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_ENABLED" to "true",
        "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_ID" to "google-env-id.apps.googleusercontent.com",
        "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET" to "google-secret",
        "SKELETON_AUTH_SOCIAL_PROVIDERS_GOOGLE_REDIRECT_URI" to "http://localhost:5173/auth/callback",
        "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_ID" to "2001234567",
        "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_CLIENT_SECRET" to "0123456789abcdef0123456789abcdef",
        "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_LINE_REDIRECT_URI" to "https://app.example.com/auth/callback",
        "SKELETON_AUTH_SOCIAL_X_CLIENT_ID" to "x-env-id",
        "SKELETON_AUTH_SOCIAL_X_CLIENT_SECRET" to "x-secret",
        "SKELETON_AUTH_SOCIAL_X_REDIRECT_URI" to "http://127.0.0.1:5173/auth/callback",
    )

    @Test
    fun `google line and x configured only by environment variables are all offered with their client ids and named in the startup line`(output: CapturedOutput) {
        val environment = object : StandardEnvironment() {
            override fun getSystemEnvironment(): MutableMap<String, Any> = (super.getSystemEnvironment() + env).toMutableMap()
        }
        val ctx = SpringApplicationBuilder(SampleApplication::class.java, TestcontainersConfiguration::class.java)
            .environment(environment)
            .web(WebApplicationType.SERVLET)
            .properties("server.port=0", "spring.config.import=classpath:test-seeds.yml")
            .run()
        ctx.use {
            val port = ctx.environment.getProperty("local.server.port")
            val body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/auth/methods")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, body.statusCode())
            val ids = JsonPath.read<List<Map<String, Any?>>>(body.body(), "$.value.social")
                .associate { it["provider"] as String to it["clientId"] as String? }
            val summary = output.all.lines().single { "account sign-in methods:" in it }
            for (p in listOf("google", "line", "x")) assertTrue("$p(clientId=set" in summary, "startup summary names $p: $summary")
            assertEquals(mapOf("google" to "google-env-id.apps.googleusercontent.com", "line" to "2001234567", "x" to "x-env-id"), ids)
        }
    }
}
