package dev.sumin.skeleton.idempotency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper

/**
 * 지문은 저장소에 남는다 — 본문에 비밀번호가 있으면 무염 SHA-256 으로 오프라인 대입이 가능해진다.
 * 명령이 `ignoredBodyFields` 로 비밀 필드를 알리면 그 값은 지문에 들어가지 않는다 (같은 키 + 다른 비밀번호 = 같은 요청으로 본다 — 키는 사용자 행동 하나당 새로 만든다).
 */
class IdempotencyFingerprintTest {
    private val mapper = JsonMapper.builder().build()
    private fun request() = MockHttpServletRequest("POST", "/api/v1/account/delete").apply { contentType = "application/json" }
    private fun fp(body: String, ignored: Set<String> = emptySet()) = IdempotencyFingerprint.calculate(request(), body.toByteArray(), ignored, mapper)

    @Test
    fun `an ignored field leaves no trace - bodies that differ only in it have the same fingerprint`() {
        val ignored = setOf("currentPassword", "newPassword")
        assertEquals(fp("""{"reason":"x","currentPassword":"hunter2"}""", ignored), fp("""{"reason":"x","currentPassword":"another-password"}""", ignored))
        assertEquals(fp("""{"reason":"x"}""", ignored), fp("""{"currentPassword":"a","reason":"x"}""", ignored), "present or absent, the secret field is not part of the fingerprint")
    }

    @Test
    fun `every other field and the key order still count, and nested ignored fields are dropped too`() {
        val ignored = setOf("currentPassword")
        assertNotEquals(fp("""{"reason":"x"}""", ignored), fp("""{"reason":"y"}""", ignored))
        assertEquals(fp("""{"a":1,"b":{"currentPassword":"p","c":2}}""", ignored), fp("""{"a":1,"b":{"c":2,"currentPassword":"q"}}""", ignored))
    }

    @Test
    fun `without ignored fields the fingerprint is the old one - a body that is not JSON is hashed as it is`() {
        assertNotEquals(fp("""{"currentPassword":"a"}"""), fp("""{"currentPassword":"b"}"""))
        assertNotEquals(fp("not json at all", setOf("currentPassword")), fp("not json at all!", setOf("currentPassword")))
    }
}
