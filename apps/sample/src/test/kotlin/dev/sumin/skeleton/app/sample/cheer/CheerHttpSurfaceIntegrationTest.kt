package dev.sumin.skeleton.app.sample.cheer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.sumin.skeleton.payment.web.PaymentController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** 견본 앱의 진짜 컨텍스트에서 `RequestMappingHandlerMapping` 이 아는 결제 · 응원 매핑 하나 — 메서드 · 경로 패턴. */
data class Mapped(val method: HttpMethod, val pattern: String) {
    /** 경로 변수를 아무 값으로 채운 요청 경로 — 보안 체인과 인터셉터는 값이 아니라 모양으로 판단한다. */
    val path: String get() = pattern.replace(Regex("\\{[^}]+}"), "x1")
    override fun toString() = "$method $pattern"
}

/** 운영자 핸들러 아홉 — 꺼진 컨텍스트에서는 매핑이 없어 열거할 수 없으므로 켜진 컨텍스트가 이 목록과 같음을 단정한다(목록이 낡으면 거기서 깨진다). */
val ADMIN_SURFACE: Set<Mapped> = setOf(
    Mapped(HttpMethod.GET, "$ADMIN/orders"),
    Mapped(HttpMethod.GET, "$ADMIN/orders/{id}"),
    Mapped(HttpMethod.POST, "$ADMIN/orders/{id}/refunds"),
    Mapped(HttpMethod.GET, "$ADMIN/refunds"),
    Mapped(HttpMethod.POST, "$ADMIN/refunds/{id}/approve"),
    Mapped(HttpMethod.POST, "$ADMIN/refunds/{id}/reject"),
    Mapped(HttpMethod.POST, "$ADMIN/orders/{id}/sync"),
    Mapped(HttpMethod.GET, "$ADMIN/webhook-events"),
    Mapped(HttpMethod.POST, "$ADMIN/webhook-events/{id}/retry"),
)

/**
 * 결제 · 응원 HTTP 표면 **전체**를 진짜 보안 체인에서 훑는다(B7 리뷰가 B8 로 넘긴 것) — 경로를 손으로 고르지 않고 컨텍스트의 `RequestMappingHandlerMapping` 이 아는 매핑을 모아 돈다.
 * 새 핸들러가 생기면 이 시험이 자동으로 포함한다: 익명은 401(웹훅 POST 두 모양만 익명으로 닿는다), 로그인한 일반 계정은 운영자 핸들러 전부 403, 공개 API 문서에 웹훅 경로가 없다.
 */
class CheerHttpSurfaceIntegrationTest : CheerIntegrationBase() {
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var handlerMapping: RequestMappingHandlerMapping

    private fun surface(): List<Mapped> = handlerMapping.handlerMethods.entries.flatMap { (info, handler) ->
        // 거르는 기준은 경로가 아니라 핸들러 빈이 사는 패키지다 - 결제 모듈과 견본 cheer 의 핸들러는 경로 모양과 상관없이 전부 들어온다
        if (!SURFACE_PACKAGES.any { handler.beanType.packageName.startsWith(it) }) return@flatMap emptyList()
        val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
        val methods = info.methodsCondition.methods.map { HttpMethod.valueOf(it.name) }
        assertTrue(methods.isNotEmpty(), "$handler maps $patterns without an HTTP method - the sweep cannot tell what it would call")
        patterns.flatMap { p -> methods.map { Mapped(it, p) } }
    }.sortedBy { it.toString() }

    private fun call(m: Mapped, token: String?): ResultActionsDsl = mvc.request(m.method, m.path) {
        token?.let { header("Authorization", "Bearer $it") }
        if (m.method == HttpMethod.POST) {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
            header("Idempotency-Key", "surface-key")
        }
    }

    private val webhookShapes = setOf(Mapped(HttpMethod.POST, "$PAYMENTS/webhooks/{provider}"), Mapped(HttpMethod.POST, "$PAYMENTS/webhooks/{provider}/{token}"))

    @Test
    fun `the swept surface is the whole payment and cheer surface - the nine operator handlers, the user handlers, the two webhook shapes`() {
        val all = surface()
        assertEquals(ADMIN_SURFACE, all.filter { it.pattern.startsWith("$ADMIN/") }.toSet(), "the operator handlers this test knows are the ones the context has")
        assertTrue(all.containsAll(webhookShapes), "the two webhook shapes are mapped: $all")
        assertTrue(all.any { it.pattern == "/api/v1/cheers/options" } && all.any { it.pattern == "/api/v1/cheers/me" } && all.any { it.pattern == "$PAYMENTS/orders" && it.method == HttpMethod.POST }, "$all")
        println("MEASURED swept mappings: ${all.size} (operator ${ADMIN_SURFACE.size}, webhook ${webhookShapes.size}) - $all")
    }

    @Test
    fun `anonymous callers get 401 on every user and operator mapping - only the two webhook POST shapes are reached without a login`() {
        val all = surface()
        val gated = all.filter { it !in webhookShapes }
        assertTrue(gated.size >= ADMIN_SURFACE.size + 6, "the sweep found only ${gated.size} gated mappings: $gated")
        gated.forEach { m -> call(m, null).andExpect { status { isUnauthorized() } } }
        webhookShapes.forEach { m -> assertEquals(404, call(m, null).andReturn().response.status, "$m is public (the controller answers) - an unknown provider or a wrong token is a 404") }
        assertEquals(0, gateway.refundCalls.size + gateway.confirmCalls.size)
    }

    @Test
    fun `a signed-in account without the role gets 403 on every operator handler, and nothing reaches the provider or the ledger`() {
        val orderId = buy(user)
        val before = jdbc.sql("select count(*) from payment_events").query(Long::class.java).single()
        ADMIN_SURFACE.forEach { m ->
            call(m, user).andExpect { status { isForbidden() } }
            call(m, stranger).andExpect { status { isForbidden() } }   // a MODERATOR is not an ADMIN
        }
        assertEquals(0, gateway.refundCalls.size, "no refund reached the provider")
        assertEquals(before, jdbc.sql("select count(*) from payment_events").query(Long::class.java).single(), "no ledger event was written")
        assertEquals("PAID", jdbc.sql("select status from payment_orders where id = :i").param("i", orderId).query(String::class.java).single())
        get("$ADMIN/orders", operator).andExpect { status { isOk() } }   // the operator is let in - the sweep is not 403 for everyone
    }

    /**
     * 공개 문서(`/api/v1/docs`, 익명으로 열린다): 운영자 경로를 켠 이 컨텍스트에서도 200 이고 JSON 이 읽힌다. 운영자 경로는 문서에 **나온다** — 레포의 관례(계정 관리자 `Account admin` 도 숨기지 않는다)이고
     * 문서가 열려 있는 것은 접근을 열어 주는 것이 아니다(호출은 여전히 역할이 있어야 한다). 웹훅 수신 경로만 숨긴다.
     */
    @Test
    fun `the public API document builds with the operator paths on - it lists them like every other admin surface and never lists a webhook path`() {
        val response = mvc.get("/api/v1/docs").andReturn().response
        val body = response.contentAsString
        assertEquals(200, response.status, "the document builds: ${body.take(300)}")
        assertTrue(body.contains("/api/v1/admin/payments/orders"), "the operator paths are in the document")
        assertTrue(body.contains("Payment admin"), "the operator tag is in the document")
        assertFalse(body.contains("/webhooks/"), "the webhook paths are not in the public document")
    }

    private companion object {
        val SURFACE_PACKAGES = listOf(PaymentController::class.java.packageName.removeSuffix(".web"), CheerHttpSurfaceIntegrationTest::class.java.packageName)
    }
}
