package dev.sumin.skeleton.auth.social.testing

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeIdpTest {
    private val idp = FakeIdp()
    private val http = FakeIdp.httpClient()

    @AfterTest
    fun stop() = idp.close()

    @Test
    fun `a stubbed path answers with the given body and records the request`() {
        idp.stub("/v1/user/access_token_info", body = """{"app_id":42}""")

        val body = http.get("test-stub", "${idp.base}/v1/user/access_token_info", String::class.java) {
            header("Authorization", "Bearer at-1")
        }.block()

        assertEquals("""{"app_id":42}""", body)
        assertEquals("Bearer at-1", idp.requests.single { it.path == "/v1/user/access_token_info" }.headers["authorization"]?.single())
    }

    @Test
    fun `a stub may look at the request to choose the answer`() {
        idp.stub("/v1/nid/me") { rec -> if (rec.headers["authorization"]?.firstOrNull() == "Bearer good") 200 to """{"id":"n1"}""" else 401 to """{"resultcode":"024"}""" }

        val ok = http.get("test-stub", "${idp.base}/v1/nid/me", String::class.java) { header("Authorization", "Bearer good") }.block()

        assertEquals("""{"id":"n1"}""", ok)
        val denied = runCatching { http.get("test-stub", "${idp.base}/v1/nid/me", String::class.java) { header("Authorization", "Bearer bad") }.block() }
        assertTrue(denied.isFailure)
    }
}
