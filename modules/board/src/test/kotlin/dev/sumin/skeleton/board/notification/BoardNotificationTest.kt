package dev.sumin.skeleton.board.notification

import dev.sumin.skeleton.board.BoardAutoConfigurationTest
import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardNotifier
import dev.sumin.skeleton.board.BoardCaller
import dev.sumin.skeleton.board.CommentService
import dev.sumin.skeleton.board.CreatePost
import dev.sumin.skeleton.board.NoopBoardNotifier
import dev.sumin.skeleton.board.PostService
import dev.sumin.skeleton.board.BoardService
import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class BoardNotificationTest {
    private val author = BoardCaller("acc_author")
    private val commenter = BoardCaller("acc_commenter")
    private val mod = BoardCaller("acc_mod", moderator = true)

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java, BoardNotificationAutoConfiguration::class.java, BoardAutoConfiguration::class.java))
        .withUserConfiguration(BoardAutoConfigurationTest.FakeRepositories::class.java)

    private fun capture(ctx: org.springframework.context.ApplicationContext): MutableList<NotificationEvent> {
        val events = mutableListOf<NotificationEvent>()
        ctx.getBean(NotificationSubscriptionRegistry::class.java).subscribe(emptySet()) { events += it }
        return events
    }

    private fun scenario(ctx: org.springframework.context.ApplicationContext): Pair<Long, Long> {
        ctx.getBean(BoardService::class.java).create(mod, "general", "General", null)
        val post = ctx.getBean(PostService::class.java).create(author, "general", CreatePost("My post title", "body", null, null)).post.id
        val comments = ctx.getBean(CommentService::class.java)
        val root = comments.create(commenter, "general", post, null, "nice post").comment.id
        return post to root
    }

    @Test
    fun `a comment on someone's post notifies the post author through the notification module`() {
        runner.run { ctx ->
            assertIs<NotificationBoardNotifier>(ctx.getBean(BoardNotifier::class.java))
            val events = capture(ctx)
            val (post, root) = scenario(ctx)
            val event = events.single()
            assertEquals("board", event.topic)
            assertEquals("comment.created", event.type)
            assertEquals(setOf("acc_author"), event.recipientIds)
            assertTrue(event.title!!.contains("My post title"), event.title)
            assertTrue(event.message!!.contains("nice post"), event.message)
            assertEquals(post, (event.payload["postId"] as Number).toLong())
            assertEquals(root, (event.payload["commentId"] as Number).toLong())
            assertEquals("general", event.payload["boardCode"])
            assertEquals("acc_commenter", event.payload["authorId"])
            assertTrue(event.payload.containsKey("authorName"), "the key is always in the payload")
            assertEquals(null, event.payload["authorName"], "no directory, no name")
        }
    }

    @Test
    fun `the payload carries the commenter's name when a directory knows it, the title wording stays`() {
        runner.withBean(AuthorDirectory::class.java, { AuthorDirectory { ids, _ -> ids.associateWith { AuthorCard("Nick-$it", "0042") } } }).run { ctx ->
            val events = capture(ctx)
            scenario(ctx)
            val event = events.single()
            assertEquals("Nick-acc_commenter", event.payload["authorName"])
            assertEquals("acc_commenter", event.payload["authorId"], "the id stays for apps that link to the profile")
            assertTrue(event.title!!.startsWith("New comment on your post"))
        }
    }

    @Test
    fun `a reply notifies the author of the comment it answers`() {
        runner.run { ctx ->
            val (post, root) = scenario(ctx)
            val events = capture(ctx)
            ctx.getBean(CommentService::class.java).create(author, "general", post, root, "thanks!")
            val event = events.single()
            assertEquals("comment.replied", event.type)
            assertEquals(setOf("acc_commenter"), event.recipientIds)
            assertEquals(root, (event.payload["parentId"] as Number).toLong())
        }
    }

    @Test
    fun `an app formatter overrides the wording`() {
        runner.withBean(BoardNotificationFormatter::class.java, { BoardNotificationFormatter { BoardNotificationMessage("댓글 알림", "새 댓글: ${it.comment.body}") } })
            .run { ctx ->
                val events = capture(ctx)
                scenario(ctx)
                assertEquals("댓글 알림", events.single().title)
                assertEquals("새 댓글: nice post", events.single().message)
            }
    }

    @Test
    fun `the topic is configuration and the hook can be switched off`() {
        runner.withPropertyValues("skeleton.board.notification.topic=forum").run { ctx ->
            val events = capture(ctx)
            scenario(ctx)
            assertEquals("forum", events.single().topic)
        }
        runner.withPropertyValues("skeleton.board.notification.enabled=false").run { ctx ->
            assertEquals(NoopBoardNotifier, ctx.getBean(BoardNotifier::class.java))
        }
    }
}
