package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentQueries
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.PaymentWebhookInbox
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asOperator
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** 운영자 컨트롤러를 앱이 상속해 갈아 끼운 빈 — 인터셉터가 "정확히 같은 타입"만 보면 건너뛴다. */
class AppAdminController(queries: PaymentQueries, refunds: PaymentRefunds, inbox: PaymentWebhookInbox) : PaymentAdminController(queries, refunds, inbox)

@TestConfiguration(proxyBeanMethods = false)
class SubclassedAdminBeans {
    @Bean fun appAdminController(queries: PaymentQueries, refunds: PaymentRefunds, inbox: PaymentWebhookInbox): PaymentAdminController = AppAdminController(queries, refunds, inbox)
}

/** `admin.enabled` 없이 같은 클래스의 빈을 직접 등록한 앱 — 인터셉터도 정책 빈도 없다. */
@TestConfiguration(proxyBeanMethods = false)
class DirectAdminBeans {
    @Bean fun directAdminController(queries: PaymentQueries, refunds: PaymentRefunds, inbox: PaymentWebhookInbox) = PaymentAdminController(queries, refunds, inbox)
}

private val ADMIN_ENDPOINTS = listOf(
    HttpMethod.GET to "/api/v1/admin/payments/orders",
    HttpMethod.GET to "/api/v1/admin/payments/orders/ord_x",
    HttpMethod.POST to "/api/v1/admin/payments/orders/ord_x/refunds",
    HttpMethod.GET to "/api/v1/admin/payments/refunds",
    HttpMethod.POST to "/api/v1/admin/payments/refunds/rfd_x/approve",
    HttpMethod.POST to "/api/v1/admin/payments/refunds/rfd_x/reject",
    HttpMethod.POST to "/api/v1/admin/payments/orders/ord_x/sync",
    HttpMethod.GET to "/api/v1/admin/payments/webhook-events",
    HttpMethod.POST to "/api/v1/admin/payments/webhook-events/1/retry",
)

private fun call(method: HttpMethod, path: String): MockHttpServletRequestBuilder =
    request(method, path).contentType(MediaType.APPLICATION_JSON).content("{}").header("Idempotency-Key", "k-assembly")

/** 앱이 운영자 컨트롤러를 **상속한 빈으로 갈아 끼워도** 접근 규칙이 걸린다(인터셉터는 하위 타입도 본다). */
@SpringBootTest(classes = [PaymentWebTestApplication::class], properties = ["skeleton.payment.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, SubclassedAdminBeans::class)
class PaymentAdminSubclassHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping

    @Test
    fun `the subclassed controller really is the one serving the operator paths`() {
        val types = mappings.handlerMethods.values.map { it.beanType }.filter { PaymentAdminController::class.java.isAssignableFrom(it) }.toSet()
        assertEquals(setOf(AppAdminController::class.java), types)
    }

    @Test
    fun `every handler of a subclassed operator controller is 403 for a caller without the role and 401 for an anonymous one`() {
        ADMIN_ENDPOINTS.forEach { (method, path) ->
            mvc.perform(call(method, path).asUser("acc_plain", "USER")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
            mvc.perform(call(method, path)).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `the operator is let in through a subclassed controller`() {
        mvc.perform(request(HttpMethod.GET, "/api/v1/admin/payments/orders").asOperator()).andExpect(status().isOk)
    }
}

/** 인터셉터가 없는 조립(`admin.enabled` 없이 컨트롤러 빈만 · `WebMvcConfigurationSupport` 로 설정자를 읽지 않는 앱 — 둘 다 인터셉터가 안 걸린다)에서는 아홉 핸들러가 전부 닫힌다. */
@SpringBootTest(classes = [PaymentWebTestApplication::class])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, DirectAdminBeans::class)
class PaymentAdminWithoutInterceptorHttpTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `without the interceptor all nine operator handlers are closed - 403 even for a caller who has the role, and no data comes out`() {
        assertEquals(9, ADMIN_ENDPOINTS.size)
        ADMIN_ENDPOINTS.forEach { (method, path) ->
            val body = mvc.perform(call(method, path).asOperator()).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN")).andReturn().response.contentAsString
            assertEquals(false, body.contains("providerPaymentId") || body.contains("\"values\""), "no data in a closed answer: $body")
        }
    }

    @Test
    fun `an operator attribute that is not a string does not open the second lock`() {
        // 인터셉터가 아닌 누가 같은 이름으로 다른 타입을 실어도 "있다"로 읽지 않는다 - 핸들러의 `String` 인자와 같은 기준
        ADMIN_ENDPOINTS.forEach { (method, path) ->
            mvc.perform(call(method, path).asOperator().requestAttr(PaymentOperatorInterceptor.OPERATOR_ATTRIBUTE, Any())).andExpect(status().isForbidden)
        }
    }
}

/** 둘째 잠금이 하위 클래스의 재정의로 꺼지지 않는다 - kotlin-spring(allopen)은 `@RestController` 의 멤버를 열어 두므로 `final` 을 직접 붙였다. */
class PaymentAdminSecondLockIsFinalTest {
    @Test
    fun `requireOperator cannot be overridden by an app that subclasses the controller`() {
        val method = PaymentAdminController::class.java.getDeclaredMethod("requireOperator", jakarta.servlet.http.HttpServletRequest::class.java)
        kotlin.test.assertTrue(java.lang.reflect.Modifier.isFinal(method.modifiers), "requireOperator must be final: a subclass that overrides it would switch the second lock off")
    }
}
