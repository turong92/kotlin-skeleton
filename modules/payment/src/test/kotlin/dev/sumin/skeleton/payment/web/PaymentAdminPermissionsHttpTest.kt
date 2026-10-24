package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.payment.PaymentOperatorPolicy
import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asUser
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** 계정마다 지금 가진 권한을 바꿀 수 있는 대역 — 토큰은 그대로 두고 회수를 흉내 낸다 */
class MutableOperatorAccess : OperatorAccess {
    val grants = ConcurrentHashMap<String, Set<String>>()
    override fun permissionsOf(ref: OperatorRef): Set<String> = grants[ref.accountId] ?: emptySet()
}

@TestConfiguration(proxyBeanMethods = false)
class MutableAccessBeans {
    @Bean fun mutableOperatorAccess() = MutableOperatorAccess()
}

/** `admin.enabled` 없이 컨트롤러 빈과 정책 빈을 직접 등록한 앱(인터셉터 없음) — 권한 검사는 컨트롤러 쪽이 한다 */
@TestConfiguration(proxyBeanMethods = false)
class DirectAdminWithAccessBeans {
    @Bean fun directPolicy(access: OperatorAccess): PaymentOperatorPolicy = OperatorAccessPaymentOperatorPolicy(access)

    @Bean fun directAdminController(queries: dev.sumin.skeleton.payment.PaymentQueries, refunds: dev.sumin.skeleton.payment.PaymentRefunds, inbox: dev.sumin.skeleton.payment.PaymentWebhookInbox, callers: PaymentCallers) =
        PaymentAdminController(queries, refunds, inbox, callers)
}

private val READ = setOf("payment.read")
private val CS = setOf("payment.read", "payment.refund")

private data class AdminCall(val method: HttpMethod, val path: String, val permission: String)

private val ADMIN_CALLS = listOf(
    AdminCall(HttpMethod.GET, "/api/v1/admin/payments/orders", "payment.read"),
    AdminCall(HttpMethod.GET, "/api/v1/admin/payments/orders/ord_x", "payment.read"),
    AdminCall(HttpMethod.GET, "/api/v1/admin/payments/refunds", "payment.read"),
    AdminCall(HttpMethod.GET, "/api/v1/admin/payments/webhook-events", "payment.read"),
    AdminCall(HttpMethod.POST, "/api/v1/admin/payments/orders/ord_x/refunds", "payment.refund"),
    AdminCall(HttpMethod.POST, "/api/v1/admin/payments/refunds/rfd_x/approve", "payment.refund"),
    AdminCall(HttpMethod.POST, "/api/v1/admin/payments/refunds/rfd_x/reject", "payment.refund"),
    AdminCall(HttpMethod.POST, "/api/v1/admin/payments/orders/ord_x/sync", "payment.operate"),
    AdminCall(HttpMethod.POST, "/api/v1/admin/payments/orders/ord_x/close", "payment.operate"),
    AdminCall(HttpMethod.POST, "/api/v1/admin/payments/webhook-events/1/retry", "payment.operate"),
)

private fun MockMvc.send(call: AdminCall, auth: (MockHttpServletRequestBuilder) -> MockHttpServletRequestBuilder) =
    perform(auth(request(call.method, call.path).contentType(MediaType.APPLICATION_JSON).content("{}").header("Idempotency-Key", "k-perm")))

/** 「환불만」 역할(CS)은 환불 줄만 만지고 sync · close · retry 는 403 — 인터셉터가 걸린 조립 */
@SpringBootTest(classes = [PaymentWebTestApplication::class], properties = ["skeleton.payment.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, MutableAccessBeans::class)
class PaymentAdminPermissionsHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: MutableOperatorAccess

    // 토큰의 역할은 USER 뿐 — 판정은 OperatorAccess 가 한다
    private fun staff(permissions: Set<String>) = { b: MockHttpServletRequestBuilder -> access.grants["acc_cs"] = permissions; b.asUser("acc_cs", "USER") }

    @Test
    fun `a refund-only role passes exactly the read and refund handlers and gets 403 on sync, close and retry`() {
        ADMIN_CALLS.forEach { call ->
            val result = mvc.send(call, staff(CS))
            if (call.permission in CS) assertTrue(result.andReturn().response.status != 403, "${call.method} ${call.path} should pass the permission check")
            else result.andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("COMMON.FORBIDDEN"))
        }
    }

    @Test
    fun `a read-only role passes only the read handlers`() {
        ADMIN_CALLS.forEach { call ->
            val code = mvc.send(call, staff(READ)).andReturn().response.status
            if (call.permission == "payment.read") assertNotEquals(403, code, "${call.method} ${call.path}") else assertEquals(403, code, "${call.method} ${call.path}")
        }
    }

    @Test
    fun `revoking the permission closes the next call with the same token`() {
        val list = ADMIN_CALLS.first()
        assertNotEquals(403, mvc.send(list, staff(READ)).andReturn().response.status)
        access.grants.remove("acc_cs")
        mvc.send(list) { it.asUser("acc_cs", "USER") }.andExpect(status().isForbidden)
    }

    @Test
    fun `the super role passes every handler`() {
        ADMIN_CALLS.forEach { call -> assertNotEquals(403, mvc.send(call, staff(setOf("*"))).andReturn().response.status, "${call.method} ${call.path}") }
    }

    @Test
    fun `anonymous is 401 on every handler`() {
        ADMIN_CALLS.forEach { call -> mvc.send(call) { it }.andExpect(status().isUnauthorized) }
    }
}

/** 인터셉터가 없는 조립에서도 같은 표가 컨트롤러에서 걸린다 — 둘째 겹 */
@SpringBootTest(classes = [PaymentWebTestApplication::class])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, MutableAccessBeans::class, DirectAdminWithAccessBeans::class)
class PaymentAdminPermissionsWithoutInterceptorHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: MutableOperatorAccess

    @Test
    fun `without the interceptor a refund-only role still passes only read and refund`() {
        access.grants["acc_cs"] = CS
        ADMIN_CALLS.forEach { call ->
            val code = mvc.send(call) { it.asUser("acc_cs", "USER") }.andReturn().response.status
            if (call.permission in CS) assertNotEquals(403, code, "${call.method} ${call.path}") else assertEquals(403, code, "${call.method} ${call.path}")
        }
    }

    @Test
    fun `without the interceptor a caller with no permission, and an anonymous one, are refused on every handler`() {
        ADMIN_CALLS.forEach { call ->
            mvc.send(call) { it.asUser("acc_nobody", "USER") }.andExpect(status().isForbidden)
            mvc.send(call) { it }.andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `a request attribute planted by someone else does not open anything`() {
        ADMIN_CALLS.forEach { call ->
            mvc.send(call) { it.asUser("acc_nobody", "USER").requestAttr(PaymentOperatorInterceptor.OPERATOR_ATTRIBUTE, "acc_nobody") }.andExpect(status().isForbidden)
        }
    }
}

/** 옛 교체 지점 — 앱이 람다 정책을 두면 모든 운영 경로가 그 판정을 따른다 */
@TestConfiguration(proxyBeanMethods = false)
class AllowAllPolicyBeans {
    @Bean fun legacyPolicy(): PaymentOperatorPolicy = PaymentOperatorPolicy { true }
}

@TestConfiguration(proxyBeanMethods = false)
class DenyAllPolicyBeans {
    @Bean fun legacyPolicy(): PaymentOperatorPolicy = PaymentOperatorPolicy { false }
}

@SpringBootTest(classes = [PaymentWebTestApplication::class], properties = ["skeleton.payment.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, AllowAllPolicyBeans::class)
class PaymentLegacyPolicyAllowHttpTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `a legacy lambda that says yes opens every operator handler, whatever the token roles`() {
        ADMIN_CALLS.forEach { call -> assertNotEquals(403, mvc.send(call) { it.asUser("acc_any", "USER") }.andReturn().response.status, "${call.method} ${call.path}") }
    }
}

@SpringBootTest(classes = [PaymentWebTestApplication::class], properties = ["skeleton.payment.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class, DenyAllPolicyBeans::class)
class PaymentLegacyPolicyDenyHttpTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `a legacy lambda that says no closes every operator handler even for the admin role`() {
        ADMIN_CALLS.forEach { call -> mvc.send(call) { it.asUser("acc_any", "ADMIN") }.andExpect(status().isForbidden) }
    }
}

/** 권한 표에서 빠진 핸들러가 생기면 여기서 실패한다 — 새 핸들러는 권한을 말하지 않으면 거절된다 */
@SpringBootTest(classes = [PaymentWebTestApplication::class], properties = ["skeleton.payment.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentAdminPermissionTableTest {
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping

    @Test
    fun `every mapped handler of the operator controller names one declared payment permission, and the table matches the contract`() {
        val handlers = mappings.handlerMethods.values.filter { PaymentAdminController::class.java.isAssignableFrom(it.beanType) }
        assertEquals(ADMIN_CALLS.size, handlers.size)
        val declared = setOf("payment.read", "payment.refund", "payment.operate")
        val byName = handlers.associate { it.method.name to it.getMethodAnnotation(OperatorPermission::class.java)?.value }
        handlers.forEach { assertTrue(byName[it.method.name] in declared, "${it.method.name} has no declared permission: ${byName[it.method.name]}") }
        assertEquals(
            mapOf(
                "orders" to "payment.read", "order" to "payment.read", "refundList" to "payment.read", "webhookEvents" to "payment.read",
                "refund" to "payment.refund", "approve" to "payment.refund", "reject" to "payment.refund",
                "sync" to "payment.operate", "closeStuckCharge" to "payment.operate", "retry" to "payment.operate",
            ),
            byName,
        )
    }

    @Test
    fun `every public method that carries a request mapping is in the table - declared on the class, not only on the registered ones`() {
        val mapped = PaymentAdminController::class.java.declaredMethods.filter { m -> m.annotations.any { it.annotationClass.java.isAnnotationPresent(RequestMapping::class.java) } }
        assertEquals(ADMIN_CALLS.size, mapped.size)
        mapped.forEach { assertTrue(it.isAnnotationPresent(OperatorPermission::class.java), "${it.name} is mapped but has no @OperatorPermission") }
    }
}
