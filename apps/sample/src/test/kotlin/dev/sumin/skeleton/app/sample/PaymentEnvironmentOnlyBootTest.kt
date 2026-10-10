package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.payment.PaymentProperties
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment

/**
 * 결제를 **환경변수로만** 켜서 `apps/sample` 을 진짜로 기동한다 — 프런트 e2e 가 백엔드에 넘기는 이름 그대로(`SKELETON_PAYMENT_TOSS_{ENABLED,BASE_URL,SECRET_KEY,CLIENT_KEY}` ·
 * `SKELETON_PAYMENT_ADMIN_ENABLED` · `SPRING_APPLICATION_JSON` 의 `skeleton.payment` · `skeleton.http.clients.payment-toss`)와 벤더 표준 별칭(`TOSS_PAYMENTS_*`),
 * 웹훅 비밀 조각(`SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN`). 환경은 systemEnvironment 속성 소스 모양(대문자 · 밑줄 이름)으로 갈아 끼운다 —
 * 점 표기 속성으로 주면 이름 변환(`payment-toss` ↔ `PAYMENT_TOSS`)이 맞는지를 가린다. 가짜 토스 주소는 아무도 듣지 않는 포트다 — 기동에는 토스를 부르지 않는다.
 */
class PaymentEnvironmentOnlyBootTest {
    private fun boot(env: Map<String, String>, block: (ConfigurableApplicationContext, (String, String?) -> HttpResponse<String>) -> Unit) {
        val environment = object : StandardEnvironment() {
            override fun getSystemEnvironment(): MutableMap<String, Any> = (super.getSystemEnvironment() + env).toMutableMap()
        }
        val ctx = SpringApplicationBuilder(SampleApplication::class.java, TestcontainersConfiguration::class.java)
            .environment(environment)
            .web(WebApplicationType.SERVLET)
            .properties("spring.config.import=classpath:test-seeds.yml")
            .run("--server.port=0")
        ctx.use {
            val port = ctx.environment.getProperty("local.server.port")
            val http = HttpClient.newHttpClient()
            fun get(path: String, bearer: String?): HttpResponse<String> =
                http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build(), HttpResponse.BodyHandlers.ofString())
            block(ctx, ::get)
        }
    }

    private fun login(ctx: ConfigurableApplicationContext, email: String): String {
        val port = ctx.environment.getProperty("local.server.port")
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email","password":"password"}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        return JsonPath.read(response.body(), "$.value.accessToken")
    }

    @Test
    fun `the names the frontend e2e sends reach the ledger, the provider and the operator paths`() {
        // 중첩 JSON 의 뿌리 키는 따옴표 안이라 `rename-skeleton.sh` 가 못 잡아 찍은 프로젝트에서 값이 조용히 무시된다 — 점 표기 평면 키는 `skeleton.` 규칙이 잡는다
        val e2eJson = """{"skeleton.payment.checkout-ttl":"40s","skeleton.payment.reconcile.first-delay":"11s","skeleton.http.clients.payment-toss.response-timeout":"10s","skeleton.payment.http.order-rate-limit.account-capacity":"1000","skeleton.payment.http.order-rate-limit.ip-capacity":"1000"}"""
        boot(
            mapOf(
                "SKELETON_PAYMENT_TOSS_ENABLED" to "true",
                "SKELETON_PAYMENT_TOSS_BASE_URL" to "http://127.0.0.1:9",
                "SKELETON_PAYMENT_TOSS_SECRET_KEY" to "test_sk_envonlyenvonlyenvonly",
                "SKELETON_PAYMENT_TOSS_CLIENT_KEY" to "test_ck_envonlyenvonlyenvonly",
                "SKELETON_PAYMENT_ADMIN_ENABLED" to "true",
                "SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN" to "env-only-webhook-token-0123456789-abc",
                "SPRING_APPLICATION_JSON" to e2eJson,
            ),
        ) { ctx, get ->
            val user = login(ctx, "user@example.com")
            val config = get("/api/v1/payments/config", user)
            assertEquals(200, config.statusCode())
            assertEquals(true, JsonPath.read<Boolean>(config.body(), "$.value.available"), config.body())
            assertEquals(true, JsonPath.read<Boolean>(config.body(), "$.value.testMode"))
            assertEquals(true, JsonPath.read<Boolean>(config.body(), "$.value.refundRequestsEnabled"))
            val payment = ctx.getBean(PaymentProperties::class.java)
            assertEquals(Duration.ofSeconds(11), payment.reconcile.firstDelay)
            assertEquals(Duration.ofSeconds(40), payment.checkoutTtl)
            assertEquals(1000, payment.http.orderRateLimit.accountCapacity)
            assertEquals(1000, payment.http.orderRateLimit.ipCapacity)
            assertEquals("env-only-webhook-token-0123456789-abc", payment.webhook.pathToken)
            assertEquals(Duration.ofSeconds(10), ctx.getBean(OutboundHttpProperties::class.java).clients.getValue("payment-toss").responseTimeout)
            assertEquals(200, get("/api/v1/admin/payments/orders", login(ctx, "admin@example.com")).statusCode(), "the operator paths are open for the administrator")
            assertEquals(403, get("/api/v1/admin/payments/orders", user).statusCode())
        }
    }


    /**
     * 실측(B6 리뷰가 남긴 미실측): 웹훅 경로에 **multipart 본문**을 보내면 컨테이너 · 스프링이 컨트롤러(= 비밀 조각 확인) 전에 본문을 읽는가. 진짜 서버로 잰다 —
     * 한도(`spring.servlet.multipart` 기본: 파일 1MB · 요청 10MB)를 넘는 2MB 파일 조각을 틀린 비밀 조각의 주소로 보낸다. 결과는 한도에 걸린 4xx 이고 수신함에는 아무것도 남지 않는다 —
     * 즉 읽기는 스프링의 multipart 한도가 막고(결제 모듈이 더한 것이 아니라 모든 경로가 같다), 큰 본문이 결제사 조회나 줄이 되지는 않는다.
     */
    @Test
    fun `a 2MB multipart body to the webhook path is refused with a 4xx whichever secret piece it carries, and no inbox line is made`() {
        boot(
            mapOf(
                "SKELETON_PAYMENT_TOSS_ENABLED" to "true",
                "SKELETON_PAYMENT_TOSS_BASE_URL" to "http://127.0.0.1:9",
                "SKELETON_PAYMENT_TOSS_SECRET_KEY" to "test_sk_envonlyenvonlyenvonly",
                "SKELETON_PAYMENT_TOSS_CLIENT_KEY" to "test_ck_envonlyenvonlyenvonly",
                "SKELETON_PAYMENT_WEBHOOK_PATH_TOKEN" to "env-only-webhook-token-0123456789-abc",
            ),
        ) { ctx, _ ->
            val port = ctx.environment.getProperty("local.server.port")
            val boundary = "----cheerboundary"
            val big = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
            val body = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\n").toByteArray() + big + "\r\n--$boundary--\r\n".toByteArray()
            fun send(token: String) = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/payments/webhooks/toss/$token")).header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val wrong = send("wrong-token-wrong-token-wrong-token")
            val right = send("env-only-webhook-token-0123456789-abc")
            println("MEASURED multipart 2MB to the webhook path: wrong token -> ${wrong.statusCode()}, right token -> ${right.statusCode()}")
            assertTrue(wrong.statusCode() in 400..499, "wrong token: ${wrong.statusCode()}")
            assertTrue(right.statusCode() in 400..499, "right token: ${right.statusCode()}")
            val received = ctx.getBean(org.springframework.jdbc.core.simple.JdbcClient::class.java).sql("select count(*) from payment_webhook_events").query(Long::class.java).single()
            assertEquals(0L, received, "no inbox line came from a multipart body")
        }
    }
}
