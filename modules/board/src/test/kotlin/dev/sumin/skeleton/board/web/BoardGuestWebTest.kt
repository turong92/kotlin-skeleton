package dev.sumin.skeleton.board.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.BoardSettings
import dev.sumin.skeleton.board.FakeStore
import dev.sumin.skeleton.board.GuestWrite
import dev.sumin.skeleton.board.T0
import dev.sumin.skeleton.boardtest.BoardWebTestApplication
import dev.sumin.skeleton.boardtest.FakeBoardRepositories
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 로그인하지 않은 쓰기가 HTTP 에서 어떻게 보이는가 — 비밀은 헤더로만 받고 응답 어디에도 없다 */
@SpringBootTest(classes = [BoardWebTestApplication::class], properties = ["skeleton.board.guest.enabled=true", "skeleton.board.guest.rate-limit.capacity=100"])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class)
class BoardGuestWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeStore
    @Autowired lateinit var boards: BoardRepository

    @BeforeEach
    fun reset() {
        store.clear()
        boards.create("general", "General", null, T0, BoardSettings(guestWrite = GuestWrite.ALL))
        boards.create("closed", "Closed", null, T0)
    }

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun MockHttpServletRequestBuilder.idem() = header("Idempotency-Key", java.util.UUID.randomUUID().toString())

    private fun MockHttpServletRequestBuilder.secret(value: String?) = if (value == null) this else header("X-Board-Guest-Secret", value)

    private fun guestPost(secret: String? = "pass1234", board: String = "general") =
        mvc.perform(post("/api/v1/boards/$board/posts").idem().secret(secret).json("""{"title":"Hi","body":"There","guestName":"손님"}"""))

    @Test
    fun `an anonymous request with a name and the secret header creates a guest post and no response carries the secret`() {
        val result = guestPost().andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.authorGuest").value(true))
            .andExpect(jsonPath("$.value.authorName").value("손님"))
            .andExpect(jsonPath("$.value.mine").value(false))
            .andReturn()
        val body = result.response.contentAsString
        assertFalse(body.contains("pass1234"))
        assertFalse(body.contains("bcrypt"))
        val id = JsonPath.read<Number>(body, "$.value.id")
        mvc.perform(get("/api/v1/boards/general/posts/$id")).andExpect(status().isUnauthorized)   // 읽기는 allow-anonymous-read 가 정한다 — 기본은 닫힘
    }

    @Test
    fun `no secret header is a 400, a closed board is the usual 401`() {
        guestPost(secret = null).andExpect(status().isBadRequest)
        guestPost(board = "closed").andExpect(status().isUnauthorized)
    }

    @Test
    fun `edit and delete need the same header and a wrong one is a 403`() {
        val id = JsonPath.read<Number>(guestPost("right-secret").andReturn().response.contentAsString, "$.value.id")
        mvc.perform(patch("/api/v1/boards/general/posts/$id").secret("wrong-secret").json("""{"title":"X"}""")).andExpect(status().isForbidden)
        mvc.perform(patch("/api/v1/boards/general/posts/$id").json("""{"title":"X"}""")).andExpect(status().isForbidden)
        mvc.perform(patch("/api/v1/boards/general/posts/$id").secret("right-secret").json("""{"title":"New"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.title").value("New"))
        mvc.perform(delete("/api/v1/boards/general/posts/$id").secret("wrong-secret")).andExpect(status().isForbidden)
        mvc.perform(delete("/api/v1/boards/general/posts/$id").secret("right-secret")).andExpect(status().isNoContent)
    }

    @Test
    fun `config announces the guest keys`() {
        val principal = org.springframework.security.authentication.TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")
        mvc.perform(get("/api/v1/boards/config").principal(principal)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.guestEnabled").value(true))
            .andExpect(jsonPath("$.value.guestSecret").value("PASSWORD"))
            .andExpect(jsonPath("$.value.guestNameMaxLength").value(20))
            .andExpect(jsonPath("$.value.captchaRequired").value(false))
    }

    @Test
    fun `only a moderator edits board settings over HTTP and a waiting-comment list needs a manager`() {
        val admin = org.springframework.security.authentication.TestingAuthenticationToken("acc_admin", "n/a", "ROLE_USER", "ROLE_MODERATOR")
        val user = org.springframework.security.authentication.TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")
        val body = """{"settings":{"commentApproval":"GUESTS","ownerModeratesComments":true}}"""
        mvc.perform(patch("/api/v1/boards/general").principal(user).json(body)).andExpect(status().isForbidden)
        mvc.perform(patch("/api/v1/boards/general").principal(admin).json(body)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.settings.guestWrite").value("ALL"))
            .andExpect(jsonPath("$.value.settings.commentApproval").value("GUESTS"))
            .andExpect(jsonPath("$.value.settings.ownerModeratesComments").value(true))

        val postId = JsonPath.read<Number>(guestPost("right-secret").andReturn().response.contentAsString, "$.value.id")
        mvc.perform(
            post("/api/v1/boards/general/posts/$postId/comments").idem().json("""{"body":"대기","guestName":"손님"}""").header("X-Board-Guest-Secret", "comment-secret"),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.value.status").value("PENDING")).andExpect(jsonPath("$.value.authorGuest").value(true))
        mvc.perform(get("/api/v1/boards/general/posts/$postId/comments").param("status", "PENDING").principal(user)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/boards/general/posts/$postId/comments").param("status", "PENDING").principal(admin))
            .andExpect(status().isOk).andExpect(jsonPath("$.values[0].body").value("대기"))
        mvc.perform(get("/api/v1/boards/general/posts/$postId/comments").param("status", "PENDING").header("X-Board-Guest-Secret", "right-secret"))
            .andExpect(status().isOk).andExpect(jsonPath("$.values[0].status").value("PENDING"))
    }
}
