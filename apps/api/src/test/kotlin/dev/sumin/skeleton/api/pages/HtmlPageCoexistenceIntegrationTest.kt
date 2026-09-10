package dev.sumin.skeleton.api.pages

import dev.sumin.skeleton.TestcontainersConfiguration
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `/pages/` 하위가 JWT 없이 열리고, 에러가 JSON ApiError 가 아니라 HTML 로 나가고, `/api/` 하위는 여전히 보호되는지. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class HtmlPageCoexistenceIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `공개 페이지는 토큰 없이 text-html 로 열린다`() {
        mockMvc.perform(get("/pages/hello"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("hello page")))
    }

    @Test
    fun `페이지 컨트롤러의 예외는 HTML 로 나간다 (JSON envelope 아님)`() {
        val body = mockMvc.perform(get("/pages/boom/x"))
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andReturn().response.contentAsString
        assertTrue(body.contains("<h1>"), body)
        assertTrue(!body.contains("\"traceId\""), body)
    }

    @Test
    fun `API 는 여전히 인증 필요`() {
        mockMvc.perform(get("/api/v1/skeleton/modules")).andExpect(status().isUnauthorized)
    }
}
