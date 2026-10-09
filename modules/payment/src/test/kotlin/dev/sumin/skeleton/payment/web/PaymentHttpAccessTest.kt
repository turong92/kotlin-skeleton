package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * 접근 규칙을 **등록된 매핑 전부**에 건다 — 핸들러를 하나 더하면 자동으로 이 시험에 들어온다.
 * 운영자 컨트롤러의 모든 핸들러: 역할 없음 403 · 익명 401 (타입이 틀린 쿼리여도 역할 검사가 먼저). 사용자 컨트롤러의 모든 핸들러: 익명 401.
 */
@SpringBootTest(
    classes = [PaymentWebTestApplication::class],
    properties = [
        "skeleton.payment.admin.enabled=true",
        "skeleton.payment.http.order-rate-limit.account-capacity=100000", "skeleton.payment.http.order-rate-limit.ip-capacity=100000",
    ],
)
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentHttpAccessTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping

    private data class Endpoint(val method: HttpMethod, val path: String, val handler: String)

    private fun endpointsOf(vararg controllers: Class<*>): List<Endpoint> =
        mappings.handlerMethods.flatMap { (info, handler) ->
            if (handler.beanType !in controllers) return@flatMap emptyList()
            val paths = info.pathPatternsCondition!!.patternValues
            val methods = info.methodsCondition.methods.ifEmpty { setOf(org.springframework.web.bind.annotation.RequestMethod.GET) }
            paths.flatMap { path -> methods.map { Endpoint(HttpMethod.valueOf(it.name), path.replace(Regex("\\{[^}]+}"), "1"), "${handler.beanType.simpleName}.${handler.method.name}") } }
        }

    /** 본문이 필요한 경로도 본문 읽기에서 먼저 막히지 않도록 빈 JSON 객체를 싣는다 — 이 시험이 재는 것은 접근 규칙이다. */
    private fun call(endpoint: Endpoint, query: String = ""): MockHttpServletRequestBuilder =
        request(endpoint.method, endpoint.path + query).contentType(MediaType.APPLICATION_JSON).content("{}")

    @Test
    fun `the mappings of the operator controller are found - the all-mappings tests below would be vacuous without them`() {
        val operator = endpointsOf(PaymentAdminController::class.java)
        val user = endpointsOf(PaymentController::class.java, PaymentOrderController::class.java)

        assertEquals(9, operator.size, "operator handlers: ${operator.map { it.handler }}")
        assertEquals(8, user.size, "user handlers: ${user.map { it.handler }}")
    }

    @Test
    fun `every operator handler is 403 for a caller without the role`() {
        endpointsOf(PaymentAdminController::class.java).forEach { endpoint ->
            mvc.perform(call(endpoint).asUser("acc_plain", "USER")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        }
    }

    @Test
    fun `every operator handler is 401 for an anonymous caller`() {
        endpointsOf(PaymentAdminController::class.java).forEach { endpoint ->
            mvc.perform(call(endpoint)).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `a query of the wrong type from a caller without the role is 403, not 400 - the role is checked before the query is read`() {
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/payments/orders?size=abc").asUser("acc_plain", "USER")).andExpect(status().isForbidden)
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/payments/orders?review=maybe").asUser("acc_plain", "USER")).andExpect(status().isForbidden)
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/payments/orders?size=abc")).andExpect(status().isUnauthorized)
        mvc.perform(request(HttpMethod.POST, "/api/v1/admin/payments/webhook-events/abc/retry").asUser("acc_plain", "USER")).andExpect(status().isForbidden)
    }

    @Test
    fun `every user handler is 401 for an anonymous caller`() {
        val endpoints = endpointsOf(PaymentController::class.java, PaymentOrderController::class.java)
        assertTrue(endpoints.isNotEmpty())
        endpoints.forEach { endpoint ->
            mvc.perform(call(endpoint)).andExpect(status().isUnauthorized)
        }
    }
}
