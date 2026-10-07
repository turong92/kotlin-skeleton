package dev.sumin.skeleton.app.workbench.api

import dev.sumin.skeleton.app.workbench.TestcontainersConfiguration
import dev.sumin.skeleton.authorprofile.AuthorProfileRepository
import dev.sumin.skeleton.common.author.AuthorIdentities
import com.jayway.jsonpath.JsonPath
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 작성자 프로필(author-profile + author-profile-jdbc)이 워크벤치에 **클래스패스로만** 얹혀 있음을 증명한다 — account 와 같다(자동설정은 application.yml 에서 꺼 둔다).
 * 그래서 board 는 `AuthorIdentities` 빈을 보지 못하고 워크벤치 샘플 게시판의 계약(작성자 id = 계정 id, authorId 불필요)이 바뀌지 않는다.
 * 마이그레이션은 다른 모듈의 것과 한 DB 에 함께 적용된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthorProfileCoexistenceIntegrationTest {
    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `no author identities bean exists so board keeps account ids as author ids`() {
        assertTrue(context.getBeansOfType(AuthorIdentities::class.java).isEmpty())
        assertTrue(context.getBeansOfType(AuthorProfileRepository::class.java).isEmpty())
        assertFalse(context.containsBean("authorProfileService"))
        assertFalse(context.containsBean("authorProfileController"))
    }

    @Test
    fun `the author_profiles migration is applied next to the other modules' tables`() {
        assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'author_profiles'", Int::class.java))
    }

    @Test
    fun `the sample board keeps its contract - an author id is not required`() {
        val token = login()
        mockMvc.get("/api/v1/boards/config") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() }; jsonPath("$.value.authorRequired") { value(false) } }
    }

    private fun login(): String {
        val body = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andReturn().response.contentAsString
        return JsonPath.read(body, "$.value.accessToken")
    }
}
