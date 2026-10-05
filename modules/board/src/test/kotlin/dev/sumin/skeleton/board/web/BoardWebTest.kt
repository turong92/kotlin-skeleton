package dev.sumin.skeleton.board.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.board.FakeStore
import dev.sumin.skeleton.boardtest.BoardWebTestApplication
import dev.sumin.skeleton.boardtest.FakeBoardRepositories
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [BoardWebTestApplication::class])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class)
class BoardWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeStore

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")
    private val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_USER", "ROLE_MODERATOR")
    private val third = TestingAuthenticationToken("acc_third", "n/a", "ROLE_USER")

    @BeforeEach
    fun reset() {
        store.clear()
        call(post("/api/v1/boards").json("""{"code":"general","name":"General"}"""), admin).andExpect(status().isCreated)
    }

    /** 글 · 댓글 만들기는 @IdempotentOperation 이라 idempotency 모듈이 클래스패스에 있으면 Idempotency-Key 가 필수다 */
    private fun MockHttpServletRequestBuilder.idem(key: String = java.util.UUID.randomUUID().toString()) = header("Idempotency-Key", key)

    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)

    private fun call(builder: MockHttpServletRequestBuilder, who: TestingAuthenticationToken? = null): ResultActions =
        mvc.perform(if (who != null) builder.principal(who) else builder)

    private fun idOf(result: MvcResult): Long = JsonPath.read<Number>(result.response.contentAsString, "$.value.id").toLong()

    private fun newPost(who: TestingAuthenticationToken = user, body: String = """{"title":"Hello","body":"World"}"""): Long =
        idOf(call(post("/api/v1/boards/general/posts").idem().json(body), who).andExpect(status().isCreated).andReturn())

    private fun newComment(postId: Long, who: TestingAuthenticationToken = third, parent: Long? = null, text: String = "hi"): Long =
        idOf(call(post("/api/v1/boards/general/posts/$postId/comments").idem().json("""{"body":"$text"${parent?.let { ""","parentId":$it""" } ?: ""}}"""), who)
            .andExpect(status().isCreated).andReturn())

    // ---- config & boards

    @Test
    fun `config reports reaction types, mode, limits and whether the caller moderates`() {
        call(get("/api/v1/boards/config"), user).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.reactionTypes[0]").value("LIKE"))
            .andExpect(jsonPath("$.value.reactionTypes[1]").value("DISLIKE"))
            .andExpect(jsonPath("$.value.reactionMode").value("SINGLE"))
            .andExpect(jsonPath("$.value.maxCommentDepth").value(2))
            .andExpect(jsonPath("$.value.canModerate").value(false))
        call(get("/api/v1/boards/config"), admin).andExpect(jsonPath("$.value.canModerate").value(true))
    }

    @Test
    fun `boards are listed with their published post count and fetched by code`() {
        newPost()
        newPost(body = """{"title":"d","body":"d","status":"DRAFT"}""")
        call(get("/api/v1/boards"), user).andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(1)))
            .andExpect(jsonPath("$.values[0].code").value("general"))
            .andExpect(jsonPath("$.values[0].postCount").value(1))
        call(get("/api/v1/boards/general"), user).andExpect(jsonPath("$.value.name").value("General"))
        call(get("/api/v1/boards/nope"), user).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("BOARD.NOT_FOUND"))
    }

    @Test
    fun `only moderators create boards, codes are unique and validated`() {
        call(post("/api/v1/boards").json("""{"code":"news","name":"News"}"""), user)
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("BOARD.FORBIDDEN"))
        call(post("/api/v1/boards").json("""{"code":"general","name":"Again"}"""), admin)
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BOARD.CODE_TAKEN"))
        call(post("/api/v1/boards").json("""{"code":"Bad Code","name":"x"}"""), admin)
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.CONTENT_INVALID"))
        call(post("/api/v1/boards").json("""{"code":"config","name":"x"}"""), admin).andExpect(status().isBadRequest)
    }

    // ---- posts

    @Test
    fun `creating a post answers 201 with Location and the detail`() {
        call(post("/api/v1/boards/general/posts").idem().json("""{"title":"Hello","body":"World","attachments":["u/1.png"]}"""), user)
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/boards/general/posts/")))
            .andExpect(jsonPath("$.value.boardCode").value("general"))
            .andExpect(jsonPath("$.value.authorId").value("acc_user"))
            .andExpect(jsonPath("$.value.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.value.body").value("World"))
            .andExpect(jsonPath("$.value.attachments[0]").value("u/1.png"))
            .andExpect(jsonPath("$.value.attachmentCount").value(1))
            .andExpect(jsonPath("$.value.reactionCounts.LIKE").value(0))
            .andExpect(jsonPath("$.value.myReactions", hasSize<Any>(0)))
            .andExpect(jsonPath("$.meta.timestamp").exists())
    }

    @Test
    fun `invalid content is BOARD CONTENT_INVALID`() {
        call(post("/api/v1/boards/general/posts").idem().json("""{"title":" ","body":"x"}"""), user)
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.CONTENT_INVALID"))
    }

    @Test
    fun `the list is a page envelope, filtered by search and sorted`() {
        val a = newPost(body = """{"title":"kotlin","body":"x"}""")
        newPost(body = """{"title":"java","body":"y"}""")
        call(put("/api/v1/boards/general/posts/$a/reactions").json("""{"type":"LIKE"}"""), third)

        call(get("/api/v1/boards/general/posts").param("page", "0").param("size", "1").param("sort", "latest"), user)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(1)))
            .andExpect(jsonPath("$.pagination.totalElements").value(2))
            .andExpect(jsonPath("$.pagination.hasNext").value(true))
        call(get("/api/v1/boards/general/posts").param("q", "kotlin"), user).andExpect(jsonPath("$.pagination.totalElements").value(1))
        call(get("/api/v1/boards/general/posts").param("sort", "reactions"), user).andExpect(jsonPath("$.values[0].id").value(a))
        call(get("/api/v1/boards/general/posts").param("sort", "REACTIONS").param("reaction", "LIKE"), third)
            .andExpect(jsonPath("$.values[0].myReactions[0]").value("LIKE"))
        call(get("/api/v1/boards/general/posts").param("sort", "nonsense"), user).andExpect(status().isBadRequest)
        call(get("/api/v1/boards/general/posts").param("reaction", "NOPE"), user)
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.REACTION_TYPE_INVALID"))
    }

    @Test
    fun `the detail counts views, and drafts are 404 for others`() {
        val id = newPost()
        call(get("/api/v1/boards/general/posts/$id"), third).andExpect(jsonPath("$.value.viewCount").value(1))
        call(get("/api/v1/boards/general/posts/$id"), third).andExpect(jsonPath("$.value.viewCount").value(2))
        val draft = newPost(body = """{"title":"d","body":"d","status":"DRAFT"}""")
        call(get("/api/v1/boards/general/posts/$draft"), third).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("BOARD.POST_NOT_FOUND"))
        call(get("/api/v1/boards/general/posts/$draft"), user).andExpect(status().isOk)
    }

    @Test
    fun `list mine and status are for the owner and moderators`() {
        newPost(body = """{"title":"d","body":"d","status":"DRAFT"}""")
        call(get("/api/v1/boards/general/posts").param("mine", "true"), user).andExpect(jsonPath("$.pagination.totalElements").value(1))
        call(get("/api/v1/boards/general/posts").param("status", "DRAFT"), user).andExpect(status().isForbidden)
        call(get("/api/v1/boards/general/posts").param("status", "DRAFT"), admin).andExpect(jsonPath("$.pagination.totalElements").value(1))
    }

    @Test
    fun `patch is for the author or a moderator, delete is soft and answers 204`() {
        val id = newPost()
        call(patch("/api/v1/boards/general/posts/$id").json("""{"title":"Changed"}"""), third)
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("BOARD.FORBIDDEN"))
        call(patch("/api/v1/boards/general/posts/$id").json("""{"title":"Changed"}"""), user)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.title").value("Changed")).andExpect(jsonPath("$.value.body").value("World"))
        call(delete("/api/v1/boards/general/posts/$id"), third).andExpect(status().isForbidden)
        call(delete("/api/v1/boards/general/posts/$id"), user).andExpect(status().isNoContent)
        call(get("/api/v1/boards/general/posts/$id"), third).andExpect(status().isNotFound)
    }

    @Test
    fun `moderation hides and pins`() {
        val id = newPost()
        call(put("/api/v1/boards/general/posts/$id/moderation").json("""{"status":"HIDDEN","pinned":true}"""), user).andExpect(status().isForbidden)
        call(put("/api/v1/boards/general/posts/$id/moderation").json("""{"status":"HIDDEN","pinned":true}"""), admin)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("HIDDEN")).andExpect(jsonPath("$.value.pinned").value(true))
        call(get("/api/v1/boards/general/posts/$id"), third).andExpect(status().isNotFound)
    }

    // ---- comments

    @Test
    fun `comments nest, replies come flattened under their top-level comment, and depth is capped`() {
        val p = newPost()
        val root = newComment(p)
        val reply = newComment(p, user, root, "re")
        val deep = newComment(p, third, reply, "deep")
        call(post("/api/v1/boards/general/posts/$p/comments").idem().json("""{"body":"too deep","parentId":$deep}"""), user)
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("BOARD.COMMENT_TOO_DEEP"))

        call(get("/api/v1/boards/general/posts/$p/comments").param("sort", "oldest"), user).andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(1)))
            .andExpect(jsonPath("$.pagination.totalElements").value(1))
            .andExpect(jsonPath("$.values[0].id").value(root))
            .andExpect(jsonPath("$.values[0].rootId").value(root))
            .andExpect(jsonPath("$.values[0].depth").value(0))
            .andExpect(jsonPath("$.values[0].replyCount").value(2))
            .andExpect(jsonPath("$.values[0].replies", hasSize<Any>(2)))
            .andExpect(jsonPath("$.values[0].replies[0].parentId").value(root))
            .andExpect(jsonPath("$.values[0].replies[1].depth").value(2))
            .andExpect(jsonPath("$.values[0].replies[1].replies", hasSize<Any>(0)))
            .andExpect(jsonPath("$.values[0].reactionCounts.LIKE").value(0))
    }

    @Test
    fun `a created comment answers 201 with the comment`() {
        val p = newPost()
        call(post("/api/v1/boards/general/posts/$p/comments").idem().json("""{"body":" hello "}"""), third).andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.body").value("hello")).andExpect(jsonPath("$.value.authorId").value("acc_third"))
            .andExpect(jsonPath("$.value.parentId").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.value.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.value.replies").doesNotExist())
    }

    @Test
    fun `a deleted comment keeps its place with a null body and its status`() {
        val p = newPost()
        val c = newComment(p)
        call(delete("/api/v1/boards/general/posts/$p/comments/$c"), user).andExpect(status().isForbidden)
        call(delete("/api/v1/boards/general/posts/$p/comments/$c"), third).andExpect(status().isNoContent)
        call(get("/api/v1/boards/general/posts/$p/comments"), user)
            .andExpect(jsonPath("$.values[0].body").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.values[0].status").value("DELETED"))
    }

    @Test
    fun `comment edit is the author's, moderation is the moderator's`() {
        val p = newPost()
        val c = newComment(p)
        call(patch("/api/v1/boards/general/posts/$p/comments/$c").json("""{"body":"edited"}"""), user).andExpect(status().isForbidden)
        call(patch("/api/v1/boards/general/posts/$p/comments/$c").json("""{"body":"edited"}"""), third)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.body").value("edited"))
        call(put("/api/v1/boards/general/posts/$p/comments/$c/moderation").json("""{"status":"HIDDEN"}"""), third).andExpect(status().isForbidden)
        call(put("/api/v1/boards/general/posts/$p/comments/$c/moderation").json("""{"status":"HIDDEN"}"""), admin)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("HIDDEN")).andExpect(jsonPath("$.value.body").value(org.hamcrest.Matchers.nullValue()))
    }

    // ---- reactions

    @Test
    fun `reactions switch in SINGLE mode and return the state`() {
        val p = newPost()
        call(put("/api/v1/boards/general/posts/$p/reactions").json("""{"type":"LIKE"}"""), third)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.counts.LIKE").value(1)).andExpect(jsonPath("$.value.myReactions[0]").value("LIKE"))
        call(put("/api/v1/boards/general/posts/$p/reactions").json("""{"type":"DISLIKE"}"""), third)
            .andExpect(jsonPath("$.value.counts.LIKE").value(0)).andExpect(jsonPath("$.value.counts.DISLIKE").value(1))
            .andExpect(jsonPath("$.value.myReactions", hasSize<Any>(1)))
        call(delete("/api/v1/boards/general/posts/$p/reactions"), third)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.counts.DISLIKE").value(0)).andExpect(jsonPath("$.value.myReactions", hasSize<Any>(0)))
        call(delete("/api/v1/boards/general/posts/$p/reactions").param("type", "LIKE"), third).andExpect(status().isOk)
    }

    @Test
    fun `an unknown reaction type is 400 BOARD REACTION_TYPE_INVALID`() {
        val p = newPost()
        call(put("/api/v1/boards/general/posts/$p/reactions").json("""{"type":"EMPATHY"}"""), third)
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.REACTION_TYPE_INVALID"))
        call(put("/api/v1/boards/general/posts/$p/reactions").json("""{}"""), third).andExpect(status().isBadRequest)
    }

    @Test
    fun `comments take reactions too`() {
        val p = newPost()
        val c = newComment(p)
        call(put("/api/v1/boards/general/posts/$p/comments/$c/reactions").json("""{"type":"LIKE"}"""), user)
            .andExpect(status().isOk).andExpect(jsonPath("$.value.counts.LIKE").value(1))
        call(get("/api/v1/boards/general/posts/$p/comments"), user).andExpect(jsonPath("$.values[0].myReactions[0]").value("LIKE"))
        call(delete("/api/v1/boards/general/posts/$p/comments/$c/reactions"), user).andExpect(jsonPath("$.value.counts.LIKE").value(0))
    }

    @Test
    fun `creating needs an Idempotency-Key and the same key replays instead of creating twice`() {
        call(post("/api/v1/boards/general/posts").json("""{"title":"a","body":"b"}"""), user)
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.IDEMPOTENCY_ERROR"))
        val body = """{"title":"once","body":"b"}"""
        val first = idOf(call(post("/api/v1/boards/general/posts").idem("k1").json(body), user).andExpect(status().isCreated).andReturn())
        val second = idOf(call(post("/api/v1/boards/general/posts").idem("k1").json(body), user).andExpect(status().isCreated).andReturn())
        kotlin.test.assertEquals(first, second)
        kotlin.test.assertEquals(1, store.posts.size)
    }

    // ---- who is calling

    @Test
    fun `anonymous callers are 401 by default, for reads and writes`() {
        call(get("/api/v1/boards")).andExpect(status().isUnauthorized)
        call(get("/api/v1/boards/config")).andExpect(status().isUnauthorized)
        call(post("/api/v1/boards/general/posts").idem().json("""{"title":"a","body":"b"}""")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an author whose account was erased shows as a deleted user, others do not`() {
        val gone = TestingAuthenticationToken("deleted:0a1b2c3d4e5f6071", "n/a", "ROLE_USER")
        val goneId = newPost(gone)
        val liveId = newPost(user)
        call(get("/api/v1/boards/general/posts/$goneId"), user).andExpect(jsonPath("$.value.authorDeleted").value(true))
        call(get("/api/v1/boards/general/posts/$liveId"), user).andExpect(jsonPath("$.value.authorDeleted").value(false))
        call(get("/api/v1/boards/general/posts"), user).andExpect(jsonPath("$.values[?(@.id==$goneId)].authorDeleted").value(true))
        val commentId = newComment(liveId, gone)
        call(get("/api/v1/boards/general/posts/$liveId/comments"), user).andExpect(jsonPath("$.values[0].authorDeleted").value(true))
        assertEquals(true, commentId > 0)
    }
}

@SpringBootTest(classes = [BoardWebTestApplication::class], properties = ["skeleton.board.http.allow-anonymous-read=true"])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class)
class BoardWebAnonymousReadTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeStore

    @Test
    fun `anonymous may read but not write`() {
        store.clear()
        mvc.perform(post("/api/v1/boards").contentType(MediaType.APPLICATION_JSON).content("""{"code":"open","name":"Open"}""")
            .principal(TestingAuthenticationToken("m", "n/a", "ROLE_MODERATOR"))).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/boards")).andExpect(status().isOk).andExpect(jsonPath("$.values[0].code").value("open"))
        mvc.perform(get("/api/v1/boards/config")).andExpect(jsonPath("$.value.canModerate").value(false))
        mvc.perform(get("/api/v1/boards/open/posts")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/boards/open/posts").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("""{"title":"a","body":"b"}""")).andExpect(status().isUnauthorized)
        mvc.perform(put("/api/v1/boards/open/posts/1/reactions").contentType(MediaType.APPLICATION_JSON).content("""{"type":"LIKE"}""")).andExpect(status().isUnauthorized)
    }
}

@SpringBootTest(classes = [BoardWebTestApplication::class], properties = ["skeleton.board.http.enabled=false"])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class)
class BoardWebDisabledTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the endpoints are gone when http is disabled`() {
        mvc.perform(get("/api/v1/boards").principal(TestingAuthenticationToken("a", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}

@SpringBootTest(classes = [BoardWebTestApplication::class], properties = ["skeleton.board.http.base-path=/api/v1/forum", "skeleton.board.moderator-role=ADMIN"])
@AutoConfigureMockMvc
@Import(FakeBoardRepositories::class)
class BoardWebConfiguredPathTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the base path and the moderator role are configuration`() {
        mvc.perform(get("/api/v1/forum/config").principal(TestingAuthenticationToken("a", "n/a", "ROLE_ADMIN")))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.canModerate").value(true))
        mvc.perform(get("/api/v1/forum/config").principal(TestingAuthenticationToken("b", "n/a", "ROLE_MODERATOR")))
            .andExpect(jsonPath("$.value.canModerate").value(false))
        mvc.perform(get("/api/v1/boards").principal(TestingAuthenticationToken("a", "n/a", "ROLE_ADMIN"))).andExpect(status().isNotFound)
    }
}
