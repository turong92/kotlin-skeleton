package dev.sumin.skeleton.inquiry.web

import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.inquiry.InMemoryInquiryRepository
import dev.sumin.skeleton.inquiry.InquiryHumanCheck
import dev.sumin.skeleton.inquiry.InquiryProperties
import dev.sumin.skeleton.inquiry.InquiryService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 공개 POST 의 HTTP 모양 — 202 · no-store · 위반 목록 · 사람 확인 · 한도 · 로그인 호출은 계정이 붙는다 */
class InquiryWebTest {
    private val repository = InMemoryInquiryRepository()
    private var humanOk = true
    private val service = InquiryService(
        repository, InquiryHumanCheck { _, _ -> humanOk }, emptyList(), InquiryProperties(perClient = 2, perAccount = 3), TimeProvider.systemUtc(),
    )
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(InquiryController(service, ClientIps()))
        .setControllerAdvice(GlobalExceptionHandler()).build()

    private fun json(body: String = "도움말에서 못 찾은 질문이 있어요") =
        """{"kind":"QUESTION","body":"$body","lang":"ko","captchaToken":"t"}"""

    private fun send(content: String, ip: String = "198.51.100.1", auth: TestingAuthenticationToken? = null) =
        mvc.perform(
            post("/api/v1/inquiries").contentType(MediaType.APPLICATION_JSON).content(content)
                .with { it.remoteAddr = ip; it }.apply { auth?.let { principal(it) } },
        )

    @Test
    fun `an anonymous question is accepted with 202 and never cached`() {
        send(json()).andExpect(status().isAccepted).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.value.status").value("RECEIVED"))
        assertEquals(1, repository.list(null, 10).size)
        assertEquals(null, repository.list(null, 10).single().accountId)
    }

    @Test
    fun `a signed-in call is linked to the account and skips the human check`() {
        humanOk = false
        send(json(), auth = TestingAuthenticationToken("acc_1", "n", "ROLE_USER").apply { isAuthenticated = true }).andExpect(status().isAccepted)
        assertEquals("acc_1", repository.list(null, 10).single().accountId)
    }

    @Test
    fun `invalid values answer 400 INQUIRY_INVALID with the violated fields`() {
        send("""{"kind":"NOPE","body":"짧아요"}""").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INQUIRY.INVALID"))
            .andExpect(jsonPath("$.data.violations[?(@.field=='kind')]").exists())
            .andExpect(jsonPath("$.data.violations[?(@.field=='body')]").exists())
    }

    @Test
    fun `a failed human check answers 400 and a flood answers 429 with Retry-After seconds`() {
        humanOk = false
        send(json()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INQUIRY.CAPTCHA_FAILED"))
        humanOk = true
        send(json(), ip = "198.51.100.9").andExpect(status().isAccepted)
        send(json(), ip = "198.51.100.9").andExpect(status().isAccepted)
        send(json(), ip = "198.51.100.9").andExpect(status().isTooManyRequests).andExpect(jsonPath("$.data.retryAfterSeconds").exists())
    }

    @Test
    fun `the request body is redacted in toString so debug logging never prints it`() {
        val text = dev.sumin.skeleton.inquiry.InquiryRequest(kind = "BUG", body = "비밀 본문", replyEmail = "me@example.com", captchaToken = "tok").toString()
        assertEquals(false, text.contains("비밀 본문") || text.contains("me@example.com") || text.contains("tok"))
    }
}
