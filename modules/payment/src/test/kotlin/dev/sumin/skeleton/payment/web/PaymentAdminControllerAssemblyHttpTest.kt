package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.PaymentQueries
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.payment.PaymentWebhookInbox
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
class AppAdminController(queries: PaymentQueries, refunds: PaymentRefunds, inbox: PaymentWebhookInbox, callers: PaymentCallers) : PaymentAdminController(queries, refunds, inbox, callers)

@TestConfiguration(proxyBeanMethods = false)
class SubclassedAdminBeans {
    @Bean fun appAdminController(queries: PaymentQueries, refunds: PaymentRefunds, inbox: PaymentWebhookInbox, callers: PaymentCallers): PaymentAdminController = AppAdminController(queries, refunds, inbox, callers)
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
    fun `every handler of a subclassed operator controller is 403 for a caller without the role and 401 for an anonymous one`() {
        val types = mappings.handlerMethods.values.map { it.beanType }.filter { PaymentAdminController::class.java.isAssignableFrom(it) }.toSet()
        assertEquals(setOf(AppAdminController::class.java), types)
        ADMIN_ENDPOINTS.forEach { (method, path) ->
            mvc.perform(call(method, path).asUser("acc_plain", "USER")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
            mvc.perform(call(method, path)).andExpect(status().isUnauthorized)
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
