package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.payment.web.PaymentHttpSupport.read
import dev.sumin.skeleton.paymenttest.InMemoryLedgerBeans
import dev.sumin.skeleton.paymenttest.PaymentWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * 운영자 경로를 켠 앱에서도 OpenAPI 문서가 만들어진다(200 + 읽히는 JSON). 운영자 응답 DTO 의 평평하지 않은 칸(`@JsonUnwrapped`)이 스키마에서 이름 없는
 * 속성이 되어 문서 만들기가 500 이던 결함의 회귀. 운영자 경로가 문서에 나오는 것은 레포의 관례(계정 관리자 `Account admin` 도 숨기지 않는다) — 웹훅 수신 경로만 숨긴다.
 */
@SpringBootTest(classes = [PaymentWebTestApplication::class], properties = ["skeleton.payment.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(InMemoryLedgerBeans::class)
class PaymentOpenApiDocumentHttpTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the OpenAPI document builds with the operator paths on - the operator paths are listed like every other admin surface and the webhook receiver is not`() {
        val response = mvc.perform(get("/v3/api-docs")).andReturn().response
        assertEquals(200, response.status, response.contentAsString.take(300))
        val doc = read(response.contentAsString)

        val paths = doc["paths"].propertyNames().toList()
        assertTrue(paths.any { it.startsWith("/api/v1/admin/payments/orders") }, "operator paths are in the document: $paths")
        assertFalse(paths.any { "/webhooks/" in it }, "the webhook receiver stays hidden: $paths")
    }
}
