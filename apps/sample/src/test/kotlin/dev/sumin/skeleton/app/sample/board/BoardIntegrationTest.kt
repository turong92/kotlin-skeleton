package dev.sumin.skeleton.app.sample.board

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** 샘플 앱의 게시판 한 바퀴 — 진짜 PostgreSQL · 보안 체인 · 모듈(board + board-jdbc + notification-jdbc)이 앱 설정(application.yml)과 함께 돈다. */
class BoardIntegrationTest : SampleIntegrationTest() {
    private lateinit var moderator: String



    private fun createPost(token: String, title: String = "첫 글", body: String = "안녕하세요"): Long =
        mockMvc.post("/api/v1/boards/general/posts") {
            header("Authorization", "Bearer $token"); header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON; content = """{"title":${json(title)},"body":${json(body)}}"""
        }.andExpect { status { isCreated() } }.andReturn().let { JsonPath.read<Number>(it.response.contentAsString, "$.value.id").toLong() }

    private fun comment(token: String, post: Long, text: String, parent: Long? = null): ResultActionsDsl =
        mockMvc.post("/api/v1/boards/general/posts/$post/comments") {
            header("Authorization", "Bearer $token"); header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON; content = """{"body":${json(text)}${parent?.let { ""","parentId":$it""" } ?: ""}}"""
        }

    private fun commentId(token: String, post: Long, text: String, parent: Long? = null): Long =
        comment(token, post, text, parent).andExpect { status { isCreated() } }.andReturn().let { JsonPath.read<Number>(it.response.contentAsString, "$.value.id").toLong() }

    private fun react(token: String, path: String, type: String): ResultActionsDsl =
        mockMvc.put("$path/reactions") { header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = """{"type":"$type"}""" }

    @org.junit.jupiter.api.BeforeEach
    fun loginModerator() { moderator = login("moderator@example.com") }

    @Test
    fun `the seeded board and the app's extra reaction types are reported by the config`() {
        mockMvc.get("/api/v1/boards") { header("Authorization", "Bearer $user") }
            .andExpect { status { isOk() }; jsonPath("$.values[?(@.code == 'general')].name") { value(hasItem("General")) } }
        mockMvc.get("/api/v1/boards/config") { header("Authorization", "Bearer $user") }.andExpect {
            jsonPath("$.value.reactionTypes") { value(org.hamcrest.Matchers.contains("LIKE", "DISLIKE", "EMPATHY")) }
            jsonPath("$.value.reactionMode") { value("SINGLE") }
            jsonPath("$.value.canModerate") { value(false) }
        }
        mockMvc.get("/api/v1/boards/config") { header("Authorization", "Bearer $moderator") }.andExpect { jsonPath("$.value.canModerate") { value(true) } }
    }

    @Test
    fun `a post, a comment thread with a reply, and a reaction type that exists only in this app's yml`() {
        val post = createPost(user)
        val root = commentId(other, post, "좋은 글이네요")
        val reply = commentId(user, post, "감사합니다", root)
        commentId(other, post, "별말씀을", reply)
        comment(user, post, "너무 깊다", parent = commentId(other, post, "깊은 답글", reply)).andExpect { status { isUnprocessableEntity() }; jsonPath("$.code") { value("BOARD.COMMENT_TOO_DEEP") } }.let { }

        react(other, "/api/v1/boards/general/posts/$post", "EMPATHY").andExpect { status { isOk() }; jsonPath("$.value.counts.EMPATHY") { value(1) } }
        react(other, "/api/v1/boards/general/posts/$post", "LIKE").andExpect { jsonPath("$.value.counts.EMPATHY") { value(0) }; jsonPath("$.value.counts.LIKE") { value(1) } }
        react(other, "/api/v1/boards/general/posts/$post", "SAD").andExpect { status { isBadRequest() }; jsonPath("$.code") { value("BOARD.REACTION_TYPE_INVALID") } }

        mockMvc.get("/api/v1/boards/general/posts/$post/comments") { header("Authorization", "Bearer $user") }.andExpect {
            jsonPath("$.values", hasSize<Any>(1)); jsonPath("$.values[0].replyCount") { value(3) }; jsonPath("$.values[0].replies", hasSize<Any>(3))
        }
        mockMvc.get("/api/v1/boards/general/posts") { header("Authorization", "Bearer $user") }.andExpect {
            jsonPath("$.values[0].id") { value(post) }; jsonPath("$.values[0].commentCount") { value(4) }; jsonPath("$.values[0].reactionCounts.LIKE") { value(1) }
        }
    }

    @Test
    fun `a moderator hides a post, a normal user cannot, and only the author and moderators still see it`() {
        val post = createPost(user)
        mockMvc.put("/api/v1/boards/general/posts/$post/moderation") { header("Authorization", "Bearer $other"); contentType = MediaType.APPLICATION_JSON; content = """{"status":"HIDDEN"}""" }
            .andExpect { status { isForbidden() }; jsonPath("$.code") { value("BOARD.FORBIDDEN") } }
        mockMvc.put("/api/v1/boards/general/posts/$post/moderation") { header("Authorization", "Bearer $moderator"); contentType = MediaType.APPLICATION_JSON; content = """{"status":"HIDDEN","pinned":true}""" }
            .andExpect { status { isOk() }; jsonPath("$.value.status") { value("HIDDEN") } }
        mockMvc.get("/api/v1/boards/general/posts/$post") { header("Authorization", "Bearer $other") }.andExpect { status { isNotFound() } }
        mockMvc.get("/api/v1/boards/general/posts/$post") { header("Authorization", "Bearer $user") }.andExpect { status { isOk() } }
    }

    @Test
    fun `a comment on your post lands in your inbox through the notification module, and your own comment does not`() {
        val post = createPost(user, title = "알림 받을 글")
        commentId(user, post, "내 글에 내가")
        commentId(other, post, "남이 단 댓글")
        val inbox = await {
            mockMvc.get("/api/v1/notifications?size=100&topic=board") { header("Authorization", "Bearer $user") }.andReturn().response.contentAsString
                .takeIf { it.contains("남이 단 댓글") }
        }
        assertEquals(1, Regex("comment.created").findAll(inbox).count(), inbox)
        mockMvc.get("/api/v1/notifications?size=100&topic=board") { header("Authorization", "Bearer $user") }
            .andExpect { jsonPath("$.values[0].title") { value(org.hamcrest.Matchers.containsString("알림 받을 글")) } }
    }

    @Test
    fun `creating needs an Idempotency-Key and the same key does not create twice`() {
        mockMvc.post("/api/v1/boards/general/posts") { header("Authorization", "Bearer $user"); contentType = MediaType.APPLICATION_JSON; content = """{"title":"a","body":"b"}""" }
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("COMMON.IDEMPOTENCY_ERROR") } }
        val key = UUID.randomUUID().toString()
        repeat(2) {
            mockMvc.post("/api/v1/boards/general/posts") { header("Authorization", "Bearer $user"); header("Idempotency-Key", key); contentType = MediaType.APPLICATION_JSON; content = """{"title":"한 번만","body":"b"}""" }
                .andExpect { status { isCreated() } }
        }
        mockMvc.get("/api/v1/boards/general/posts?q=한 번만") { header("Authorization", "Bearer $user") }.andExpect { jsonPath("$.pagination.totalElements") { value(1) } }
    }

    @Test
    fun `without a token the board is closed`() {
        mockMvc.get("/api/v1/boards").andExpect { status { isUnauthorized() } }
    }
}
