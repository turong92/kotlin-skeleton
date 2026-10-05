package dev.sumin.skeleton.board.nooptional

import dev.sumin.skeleton.board.Board
import dev.sumin.skeleton.board.BoardNotifier
import dev.sumin.skeleton.board.BoardRepository
import dev.sumin.skeleton.board.Comment
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.CommentRootQuery
import dev.sumin.skeleton.board.CommentStatus
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.NewPost
import dev.sumin.skeleton.board.NoopBoardNotifier
import dev.sumin.skeleton.board.PageResult
import dev.sumin.skeleton.board.Post
import dev.sumin.skeleton.board.PostChange
import dev.sumin.skeleton.board.PostListItem
import dev.sumin.skeleton.board.PostQuery
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.ReactionMode
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.board.ReactionTarget
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootApplication
class NoOptionalApp

private val T = Instant.parse("2026-01-01T00:00:00Z")

@TestConfiguration(proxyBeanMethods = false)
class MinimalRepositories {
    @Bean fun boards(): BoardRepository = object : BoardRepository {
        private val general = Board("general", "General", null, 0, T)
        override fun findAll() = listOf(general)
        override fun find(code: String) = general.takeIf { it.code == code }
        override fun create(code: String, name: String, description: String?, now: Instant) = false
    }

    @Bean fun posts(): PostRepository = object : PostRepository {
        override fun insert(post: NewPost) =
            Post(1, post.boardCode, post.authorId, post.title, post.body, post.status, false, 0, 0, 0, post.attachments, post.now, post.now)
        override fun find(id: Long): Post? = null
        override fun update(id: Long, change: PostChange, now: Instant): Post? = null
        override fun incrementViews(id: Long) = Unit
        override fun page(query: PostQuery) = PageResult<PostListItem>(emptyList(), 0)
    }

    @Bean fun comments(): CommentRepository = object : CommentRepository {
        override fun insert(comment: NewComment): Comment? = null
        override fun find(id: Long): Comment? = null
        override fun updateBody(id: Long, body: String, now: Instant): Comment? = null
        override fun setStatus(id: Long, status: CommentStatus, now: Instant): Comment? = null
        override fun rootPage(query: CommentRootQuery) = PageResult<Comment>(emptyList(), 0)
        override fun descendants(rootIds: Collection<Long>) = emptyList<Comment>()
    }

    @Bean fun reactions(): ReactionRepository = object : ReactionRepository {
        override fun react(target: ReactionTarget, targetId: Long, accountId: String, type: String, mode: ReactionMode, now: Instant) = true
        override fun remove(target: ReactionTarget, targetId: Long, accountId: String, type: String?) = true
        override fun counts(target: ReactionTarget, targetIds: Collection<Long>) = emptyMap<Long, Map<String, Long>>()
        override fun mine(target: ReactionTarget, targetIds: Collection<Long>, accountId: String) = emptyMap<Long, Set<String>>()
    }
}

/**
 * notification · idempotency 가 클래스패스에 정말 없을 때 (앱이 그 모듈을 더하지 않았을 때) 게시판이 그대로 뜨고 동작한다.
 * 컨트롤러의 `@IdempotentOperation` 은 JVM 이 없는 애너테이션 클래스를 건너뛰고, 알림 구현은 등록되지 않는다.
 */
@SpringBootTest(classes = [NoOptionalApp::class])
@AutoConfigureMockMvc
@Import(MinimalRepositories::class)
class NoOptionalModulesTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var notifier: BoardNotifier

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")

    @Test
    fun `the optional modules are really absent from the classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.notification.NotificationPublisher") }
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.idempotency.IdempotentOperation") }
    }

    @Test
    fun `the notifier is the no-op default`() {
        assertEquals(NoopBoardNotifier, notifier)
    }

    @Test
    fun `reads work`() {
        mvc.perform(get("/api/v1/boards").principal(user)).andExpect(status().isOk).andExpect(jsonPath("$.values[0].code").value("general"))
        mvc.perform(get("/api/v1/boards/config").principal(user)).andExpect(status().isOk).andExpect(jsonPath("$.value.reactionTypes[0]").value("LIKE"))
    }

    @Test
    fun `creating a post needs no Idempotency-Key when that module is absent`() {
        mvc.perform(post("/api/v1/boards/general/posts").principal(user).contentType(MediaType.APPLICATION_JSON).content("""{"title":"t","body":"b"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.title").value("t"))
    }
}
