package dev.sumin.skeleton.board.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.board.FakePostRepository
import dev.sumin.skeleton.board.FakeStore
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.T0
import dev.sumin.skeleton.boardtest.BoardWebTestApplication
import dev.sumin.skeleton.boardtest.FakeBoardRepositories
import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorDirectory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class NamedAuthors {
    val asked = CopyOnWriteArrayList<List<String>>()

    @Bean fun authorDirectory(): AuthorDirectory = AuthorDirectory { ids, context ->
        asked += ids.toList()
        ids.filter { it != "acc_nameless" }.associateWith { AuthorCard("Nick-${it.removePrefix("acc_")}-${context.scope}", if (it == "acc_user") "0417" else null) }
    }
}

/** 응답 JSON 에 authorName · authorTag 가 나가는지 — 이름이 없어도 키는 늘 있다 (프론트가 string | null 로 읽는다) */
@SpringBootTest(classes = [BoardWebTestApplication::class])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class, NamedAuthors::class)
class BoardAuthorNamesWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeStore
    @Autowired lateinit var names: NamedAuthors

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")
    private val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_USER", "ROLE_MODERATOR")

    @BeforeEach
    fun reset() {
        store.clear(); names.asked.clear()
        mvc.perform(post("/api/v1/boards").principal(admin).json("""{"code":"general","name":"General"}""")).andExpect(status().isCreated)
    }

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)
    private fun MockHttpServletRequestBuilder.idem() = header("Idempotency-Key", java.util.UUID.randomUUID().toString())

    @Test
    fun `post list detail and comments carry authorName and authorTag, the board code is the scope the app can see`() {
        val id = JsonPath.read<Number>(
            mvc.perform(post("/api/v1/boards/general/posts").principal(user).idem().json("""{"title":"Hello","body":"World"}""")).andExpect(status().isCreated)
                .andExpect(jsonPath("$.value.authorName").value("Nick-user-general")).andExpect(jsonPath("$.value.authorTag").value("0417"))
                .andReturn().response.contentAsString, "$.value.id",
        ).toLong()
        mvc.perform(post("/api/v1/boards/general/posts/$id/comments").principal(admin).idem().json("""{"body":"hi"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.authorName").value("Nick-admin-general")).andExpect(jsonPath("$.value.authorTag").isEmpty)
        names.asked.clear()

        mvc.perform(get("/api/v1/boards/general/posts").principal(user)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].authorId").value("acc_user")).andExpect(jsonPath("$.values[0].authorName").value("Nick-user-general")).andExpect(jsonPath("$.values[0].authorTag").value("0417"))
        mvc.perform(get("/api/v1/boards/general/posts/$id").principal(user)).andExpect(status().isOk).andExpect(jsonPath("$.value.authorName").value("Nick-user-general"))
        mvc.perform(get("/api/v1/boards/general/posts/$id/comments").principal(user)).andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].authorName").value("Nick-admin-general")).andExpect(jsonPath("$.values[0].authorTag").isEmpty)
        assertEquals(3, names.asked.size, "one lookup per request")
    }

    @Test
    fun `an author the directory does not know and a deleted author have the keys with null, authorDeleted tells the second`() {
        val posts = FakePostRepository(store)
        posts.insert(NewPost("general", "acc_nameless", "A", "b", PostStatus.PUBLISHED, emptyList(), T0))
        posts.insert(NewPost("general", "deleted:abc", "B", "b", PostStatus.PUBLISHED, emptyList(), T0))
        val body = mvc.perform(get("/api/v1/boards/general/posts").principal(user)).andExpect(status().isOk).andReturn().response.contentAsString
        val rows = JsonPath.read<List<Map<String, Any?>>>(body, "$.values")
        assertEquals(2, rows.size)
        rows.forEach { r ->
            assertEquals(true, r.containsKey("authorName") && r.containsKey("authorTag"), "keys are always there: $r")
            assertEquals(null, r["authorName"]); assertEquals(null, r["authorTag"])
        }
        assertEquals(setOf(true, false), rows.map { it["authorDeleted"] as Boolean }.toSet())
        assertEquals(listOf(listOf("acc_nameless")), names.asked.toList(), "the tombstone was not looked up")
    }
}
