package dev.sumin.skeleton.board.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.board.Comment
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.FakeStore
import dev.sumin.skeleton.board.Post
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.T0
import dev.sumin.skeleton.boardtest.BoardWebTestApplication
import dev.sumin.skeleton.boardtest.FakeBoardRepositories
import dev.sumin.skeleton.common.author.AuthorIdentities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class FakeAuthorIdentities {
    @Bean fun authorIdentities(): AuthorIdentities = AuthorIdentities { account, authorId, _ -> (account == "acc_user" && authorId.startsWith("prf_u")) || (account == "acc_third" && authorId == "prf_t1") }
}

/** 작성자 신원 빈이 있는 앱의 HTTP 계약 — `authorId` 본문 · 두 오류 코드 · `mine` · `authorRequired` (스펙 §8) */
@SpringBootTest(classes = [BoardWebTestApplication::class])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class, FakeAuthorIdentities::class)
class BoardAuthorIdentitiesWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeStore

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")
    private val third = TestingAuthenticationToken("acc_third", "n/a", "ROLE_USER")
    private val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_USER", "ROLE_MODERATOR")

    @BeforeEach
    fun reset() {
        store.clear()
        call(post("/api/v1/boards").json("""{"code":"general","name":"General"}"""), admin).andExpect(status().isCreated)
    }

    private fun MockHttpServletRequestBuilder.idem() = header("Idempotency-Key", java.util.UUID.randomUUID().toString())

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.principal(who) else builder)

    private fun idOf(result: MvcResult): Long = JsonPath.read<Number>(result.response.contentAsString, "$.value.id").toLong()

    private fun writePost(authorId: String?): ResultActions =
        call(post("/api/v1/boards/general/posts").idem().json("""{"title":"Hello","body":"World"${authorId?.let { ""","authorId":"$it"""" } ?: ""}}"""), user)

    @Test
    fun `config says an author is required`() {
        call(get("/api/v1/boards/config"), user).andExpect(status().isOk).andExpect(jsonPath("$.value.authorRequired").value(true))
    }

    @Test
    fun `a post without authorId is 400 BOARD_AUTHOR_REQUIRED, a refused one is 403 BOARD_AUTHOR_NOT_ALLOWED`() {
        writePost(null).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.AUTHOR_REQUIRED"))
        writePost("prf_x").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("BOARD.AUTHOR_NOT_ALLOWED"))
    }

    @Test
    fun `a post by profile shows the profile id as authorId, never the account id, and mine for the owner only`() {
        val id = idOf(writePost("prf_u1").andExpect(status().isCreated).andExpect(jsonPath("$.value.authorId").value("prf_u1")).andExpect(jsonPath("$.value.mine").value(true)).andReturn())
        call(get("/api/v1/boards/general/posts/$id"), third).andExpect(jsonPath("$.value.authorId").value("prf_u1")).andExpect(jsonPath("$.value.mine").value(false))
        call(get("/api/v1/boards/general/posts"), third).andExpect(jsonPath("$.values[0].authorId").value("prf_u1")).andExpect(jsonPath("$.values[0].mine").value(false))
        call(get("/api/v1/boards/general/posts"), user).andExpect(jsonPath("$.values[0].mine").value(true))
        val everything = call(get("/api/v1/boards/general/posts/$id"), third).andReturn().response.contentAsString
        assertFalse(everything.contains("acc_user"), everything)
    }

    @Test
    fun `comments carry authorId in the body, the same two errors, and mine`() {
        val postId = idOf(writePost("prf_u1").andReturn())
        val url = "/api/v1/boards/general/posts/$postId/comments"
        call(post(url).idem().json("""{"body":"hi"}"""), third).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.AUTHOR_REQUIRED"))
        call(post(url).idem().json("""{"body":"hi","authorId":"prf_u1"}"""), third).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("BOARD.AUTHOR_NOT_ALLOWED"))
        call(post(url).idem().json("""{"body":"hi","authorId":"prf_t1"}"""), third).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.authorId").value("prf_t1")).andExpect(jsonPath("$.value.mine").value(true))
        call(get(url), user).andExpect(jsonPath("$.values[0].authorId").value("prf_t1")).andExpect(jsonPath("$.values[0].mine").value(false))
        call(get(url), third).andExpect(jsonPath("$.values[0].mine").value(true))
    }

    @Test
    fun `the refusal wording is the same as without the SPI bean`() {
        writePost("prf_x").andExpect(status().isForbidden).andExpect(jsonPath("$.detail").value("Not allowed to write as this author"))
    }

    @Test
    fun `no response body - list, detail, comments, create, PATCH - holds an account id or a tombstone, pending rows included`() {
        val own = idOf(writePost("prf_u1").andReturn())
        val legacyPost = store.ids.incrementAndGet().also { id ->
            store.posts[id] = Post(id, "general", "acc_legacy", "old", "b", PostStatus.PUBLISHED, false, 0, 0, 0, emptyList(), T0, T0)
        }
        val goneNoRef = store.ids.incrementAndGet().also { id ->
            store.posts[id] = Post(id, "general", "deleted:aaaaaaaaaaaaaaaa", "gone", "b", PostStatus.PUBLISHED, false, 0, 0, 0, emptyList(), T0, T0)
        }
        val goneRef = store.ids.incrementAndGet().also { id ->
            store.posts[id] = Post(id, "general", "deleted:bbbbbbbbbbbbbbbb", "gone2", "b", PostStatus.PUBLISHED, false, 0, 0, 0, emptyList(), T0, T0, "prf_old2")
        }
        val legacyComment = store.ids.incrementAndGet().also { id ->
            store.comments[id] = Comment(id, own, null, id, 0, "acc_legacy", "c", CommentStatus.PUBLISHED, 0, T0, T0)
        }
        val bodies = mutableListOf<String>()
        fun grab(r: ResultActions) = bodies.add(r.andExpect(status().is2xxSuccessful).andReturn().response.contentAsString)
        grab(call(get("/api/v1/boards/general/posts").param("size", "50"), third))
        for (id in listOf(own, legacyPost, goneNoRef, goneRef)) grab(call(get("/api/v1/boards/general/posts/$id"), third))
        grab(call(get("/api/v1/boards/general/posts/$own/comments"), third))
        grab(call(post("/api/v1/boards/general/posts/$own/comments").idem().json("""{"body":"hi","authorId":"prf_t1"}"""), third))
        grab(call(post("/api/v1/boards/general/posts").idem().json("""{"title":"n","body":"b","authorId":"prf_u2"}"""), user))
        grab(call(patch("/api/v1/boards/general/posts/$own").json("""{"title":"edited"}"""), user))
        grab(call(get("/api/v1/boards/general/posts").param("mine", "true"), user))
        // 이 시험이 정직하게 보는 것: 어떤 응답에도 계정 id(acc_)나 톰스톤(deleted:)이 없고, 옛 행은 pending: 으로 나간다
        val all = bodies.joinToString("\n")
        assertFalse(all.contains("acc_"), all)
        assertFalse(all.contains("deleted:"), all)
        assertTrue(all.contains("pending:p$legacyPost") && all.contains("pending:p$goneNoRef") && all.contains("prf_old2"), all)
        assertTrue(JsonPath.read<List<Boolean>>(bodies[0], "$.values[?(@.id==$goneNoRef)].authorDeleted").single())
        assertTrue(all.contains("pending:c$legacyComment"), "the legacy comment must be in a response as pending")
    }

    @Test
    fun `a PATCH body carrying authorId neither changes the author nor breaks the edit (measured for spec 15)`() {
        val id = idOf(writePost("prf_u1").andReturn())
        call(patch("/api/v1/boards/general/posts/$id").json("""{"title":"edited","authorId":"prf_u2"}"""), user)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.title").value("edited")).andExpect(jsonPath("$.value.authorId").value("prf_u1"))
    }

    @Test
    fun `the author_ref is stored while the account stays private - author_id is the caller`() {
        val id = idOf(writePost("prf_u1").andReturn())
        val row = store.posts.getValue(id)
        assertEquals("prf_u1", row.authorRef)
        assertEquals("acc_user", row.authorId)
    }
}
