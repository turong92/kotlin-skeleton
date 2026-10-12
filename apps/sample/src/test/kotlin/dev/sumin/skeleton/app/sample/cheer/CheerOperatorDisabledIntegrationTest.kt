package dev.sumin.skeleton.app.sample.cheer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.test.context.TestPropertySource

/**
 * 운영자 경로를 끄면(`skeleton.payment.admin.enabled=false`) 경로 자체가 없다 — 운영자 계정이 불러도 404.
 * 운영자 열 핸들러 전부([ADMIN_SURFACE])에서: 로그인한 계정은 404, **익명은 401** — 켜져 있는지 아닌지가 로그인하지 않은 사람에게 보이지 않는다.
 */
@TestPropertySource(properties = ["skeleton.payment.admin.enabled=false"])
class CheerOperatorDisabledIntegrationTest : CheerIntegrationBase() {
    @Test
    fun `with the operator paths off every one of the ten operator handlers is 404 for a signed-in account and 401 for an anonymous caller`() {
        ADMIN_SURFACE.forEach { m ->
            fun call(token: String?) = mvc.request(m.method, m.path) {
                token?.let { header("Authorization", "Bearer $it") }
                if (m.method == HttpMethod.POST) { contentType = MediaType.APPLICATION_JSON; content = "{}"; header("Idempotency-Key", "off-key") }
            }
            call(null).andExpect { status { isUnauthorized() } }
            call(user).andExpect { status { isNotFound() } }
            call(operator).andExpect { status { isNotFound() } }
        }
    }

    @Test
    fun `with the operator paths off the public API document is served, lists the user paths, and has neither a webhook path nor an operator path`() {
        val body = mvc.get("/api/v1/docs").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(body.contains("/api/v1/payments/orders") && body.contains("/api/v1/cheers/options"), "the document is the real one")
        assertFalse(body.contains("/webhooks/"), "no webhook path in the public document")
        assertFalse(body.contains("/api/v1/admin/payments") || body.contains("Payment admin"), "no operator path when the operator paths are off")
    }
}
