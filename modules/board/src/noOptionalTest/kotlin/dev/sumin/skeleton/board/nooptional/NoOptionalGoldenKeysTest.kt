package dev.sumin.skeleton.board.nooptional

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.board.Board
import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.Comment
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.CommentRootQuery
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.PageResult
import dev.sumin.skeleton.board.Post
import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostListItem
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.PostStatus
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.board.ReactionTarget
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

private val T0 = Instant.parse("2026-01-01T00:00:00Z")

/** 한 줄씩 데이터가 있는 저장소 — 글 하나(1) · 최상위 댓글(10) 하나와 그 답글(11). 응답의 **키**를 보려는 것이라 값은 아무래도 좋다 */
@TestConfiguration(proxyBeanMethods = false)
class KeyedRepositories {
    private val post = Post(1, "general", "acc_user", "title", "body", PostStatus.PUBLISHED, false, 0, 1, 0, listOf("uploads/acc_user/k/a.png"), T0, T0)
    private val root = Comment(10, 1, null, 10, 0, "acc_user", "root", CommentStatus.PUBLISHED, 0, T0, T0)
    private val reply = Comment(11, 1, 10, 10, 1, "acc_user", "reply", CommentStatus.PUBLISHED, 0, T0, T0)

    @Bean fun boards(): BoardRepository = object : BoardRepository {
        private val general = Board("general", "General", null, 1, T0)
        override fun findAll() = listOf(general)
        override fun find(code: String) = general.takeIf { it.code == code }
        override fun create(code: String, name: String, description: String?, now: Instant) = false
    }

    @Bean fun posts(): PostRepository = object : PostRepository {
        override fun insert(post: NewPost) = this@KeyedRepositories.post
        override fun find(id: Long): Post? = post.takeIf { it.id == id }
        override fun update(id: Long, change: PostChange, now: Instant): Post? = null
        override fun incrementViews(id: Long) = Unit
        override fun page(query: PostQuery) = PageResult(listOf(PostListItem(1, "general", "acc_user", "title", "body", PostStatus.PUBLISHED, false, 0, 1, 0, 1, T0, T0)), 1)
    }

    @Bean fun comments(): CommentRepository = object : CommentRepository {
        override fun insert(comment: NewComment): Comment = root
        override fun find(id: Long): Comment? = listOf(root, reply).firstOrNull { it.id == id }
        override fun updateBody(id: Long, body: String, now: Instant): Comment? = null
        override fun setStatus(id: Long, status: CommentStatus, now: Instant): Comment? = null
        override fun rootPage(query: CommentRootQuery) = PageResult(listOf(root), 1)
        override fun descendants(rootIds: Collection<Long>) = listOf(reply)
    }

    @Bean fun reactions(): ReactionRepository = object : ReactionRepository {
        override fun react(target: ReactionTarget, targetId: Long, accountId: String, type: String, mode: ReactionMode, now: Instant) = true
        override fun remove(target: ReactionTarget, targetId: Long, accountId: String, type: String?) = true
        override fun counts(target: ReactionTarget, targetIds: Collection<Long>) = emptyMap<Long, Map<String, Long>>()
        override fun mine(target: ReactionTarget, targetIds: Collection<Long>, accountId: String) = emptyMap<Long, Set<String>>()
    }
}

/**
 * 스펙 §12 조립 2(b) — **작성자 모듈을 끼우지 않은** 앱(신원 · 디렉터리 빈 없음, 알림 · 멱등 모듈도 없음)의 응답 키가 작성자 프로필 이전(`e76e8e7`)과 같고,
 * 덧붙은 것은 `mine`(글 요약 · 상세 · 댓글)과 `GET /boards/config` 의 `authorRequired` 뿐이다.
 *
 * 기준 키는 `git show e76e8e7:modules/board/src/main/kotlin/dev/sumin/skeleton/board/web/BoardDtos.kt` 의 DTO 칸에서 뽑아 상수로 고정했다.
 * 새 칸을 DTO 에 더하면 이 시험이 실패해, 응답 계약이 조용히 늘지 않는다 (늘리려면 아래 PLUS 에 이유와 함께 적는다).
 */
@SpringBootTest(classes = [NoOptionalApp::class])
@AutoConfigureMockMvc
@Import(KeyedRepositories::class)
class NoOptionalGoldenKeysTest {
    @Autowired lateinit var mvc: MockMvc

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")

    private companion object {
        val E76E8E7_BOARD = setOf("code", "name", "description", "postCount", "createdAt")
        val E76E8E7_CONFIG = setOf("reactionTypes", "reactionMode", "maxCommentDepth", "titleMaxLength", "bodyMaxLength", "commentMaxLength", "maxPageSize", "canModerate")
        val E76E8E7_SUMMARY = setOf(
            "id", "boardCode", "authorId", "title", "excerpt", "status", "pinned", "viewCount", "commentCount", "reactionCounts", "myReactions",
            "attachmentCount", "createdAt", "updatedAt", "authorDeleted", "authorName", "authorTag",
        )
        val E76E8E7_DETAIL = E76E8E7_SUMMARY + setOf("body", "attachments")
        val E76E8E7_COMMENT = setOf(
            "id", "postId", "parentId", "rootId", "depth", "authorId", "body", "status", "reactionCounts", "myReactions", "replyCount", "createdAt", "updatedAt",
            "authorDeleted", "authorName", "authorTag",
        )
        /** 목록의 댓글 한 줄(스레드)에만 있는 칸 — 한 댓글만 돌려주는 응답에는 없다 */
        val THREAD_ONLY = setOf("replies")
        /** 작성자 프로필 기능이 덧붙인 칸 */
        const val MINE = "mine"
        const val AUTHOR_REQUIRED = "authorRequired"
        /** 비회원 · 승인 대기 · 글 주인 관리가 덧붙인 칸 (스펙 docs/superpowers/specs/2026-10-17-board-guest-moderation-design.md §8) — 기본 설정에서는 모두 꺼진 값이다 */
        val BOARD_PLUS = setOf("settings")
        val CONFIG_PLUS = setOf("guestEnabled", "guestSecret", "guestNameMaxLength", "captchaRequired")
        val SUMMARY_PLUS = setOf("authorGuest")
        val DETAIL_PLUS = setOf("authorGuest", "canManageComments", "commentApproval")
        val COMMENT_PLUS = setOf("authorGuest")
    }

    private fun keys(json: String, path: String): Set<String> = JsonPath.read<Map<String, Any?>>(json, path).keys

    private fun body(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) = mvc.perform(builder.principal(user)).andReturn().response.also { assertEquals(true, it.status in 200..299, "${it.status} ${it.contentAsString}") }.contentAsString

    @Test
    fun `without the author beans every response has the keys of e76e8e7 plus mine and authorRequired and nothing else`() {
        assertEquals(E76E8E7_BOARD + BOARD_PLUS, keys(body(get("/api/v1/boards")), "$.values[0]"), "boards list")
        assertEquals(E76E8E7_CONFIG + AUTHOR_REQUIRED + CONFIG_PLUS, keys(body(get("/api/v1/boards/config")), "$.value"), "config")
        assertEquals(E76E8E7_SUMMARY + MINE + SUMMARY_PLUS, keys(body(get("/api/v1/boards/general/posts")), "$.values[0]"), "post summary")
        assertEquals(E76E8E7_DETAIL + MINE + DETAIL_PLUS, keys(body(get("/api/v1/boards/general/posts/1")), "$.value"), "post detail")
        val thread = body(get("/api/v1/boards/general/posts/1/comments"))
        assertEquals(E76E8E7_COMMENT + THREAD_ONLY + MINE + COMMENT_PLUS, keys(thread, "$.values[0]"), "comment thread line")
        assertEquals(E76E8E7_COMMENT + THREAD_ONLY + MINE + COMMENT_PLUS, keys(thread, "$.values[0].replies[0]"), "reply inside a thread")
    }

    @Test
    fun `the responses to a create carry the same keys as the reads`() {
        val json = MediaType.APPLICATION_JSON
        assertEquals(E76E8E7_DETAIL + MINE + DETAIL_PLUS, keys(body(post("/api/v1/boards/general/posts").contentType(json).content("""{"title":"t","body":"b"}""")), "$.value"), "created post")
        assertEquals(E76E8E7_COMMENT + MINE + COMMENT_PLUS, keys(body(post("/api/v1/boards/general/posts/1/comments").contentType(json).content("""{"body":"b"}""")), "$.value"), "created comment (no replies key)")
    }
}
