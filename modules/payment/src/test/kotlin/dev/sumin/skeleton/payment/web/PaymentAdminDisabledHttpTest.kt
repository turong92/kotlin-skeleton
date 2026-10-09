package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.web.PaymentHttpSupport.asOperator
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * 운영자 경로는 기본 꺼짐 — 역할이 있어도 404 이고, 그 코드는 프론트가 「꺼짐」으로 읽는 것(`ORDER_NOT_FOUND` · `REFUND_NOT_FOUND` · `WEBHOOK_EVENT_NOT_FOUND` 가 아닌 것)이다.
 */
@SpringBootTest(classes = [PaymentWebTestApplication::class])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentAdminDisabledHttpTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `with the admin switch off every operator path is 404 and the code is none of the three not-found codes of one thing`() {
        val one = listOf("PAYMENT.ORDER_NOT_FOUND", "PAYMENT.REFUND_NOT_FOUND", "PAYMENT.WEBHOOK_EVENT_NOT_FOUND")
        listOf(
            get("/api/v1/admin/payments/orders"),
            get("/api/v1/admin/payments/orders/ord_x"),
            post("/api/v1/admin/payments/orders/ord_x/refunds"),
            get("/api/v1/admin/payments/refunds"),
            post("/api/v1/admin/payments/refunds/rfd_x/approve"),
            post("/api/v1/admin/payments/orders/ord_x/sync"),
            get("/api/v1/admin/payments/webhook-events"),
            post("/api/v1/admin/payments/webhook-events/1/retry"),
        ).forEach { request ->
            val result = mvc.perform(request.asOperator()).andExpect(status().isNotFound).andReturn().response.contentAsString
            val code = PaymentHttpSupport.read(result)["code"].asString()
            kotlin.test.assertTrue(code !in one, "$code must not look like a missing single thing")
        }
    }
}
