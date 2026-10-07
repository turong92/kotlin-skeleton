package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.web.RateLimitDecision
import dev.sumin.skeleton.common.web.RateLimitStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.DefaultApplicationArguments

class BoardAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class FakeRepositories {
        private val store = FakeStore()
        @Bean fun boardRepository(): BoardRepository = FakeBoardRepository(store)
        @Bean fun postRepository(): PostRepository = FakePostRepository(store)
        @Bean fun commentRepository(): CommentRepository = FakeCommentRepository(store)
        @Bean fun reactionRepository(): ReactionRepository = FakeReactionRepository(store)
    }

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BoardAutoConfiguration::class.java))

    @Test
    fun `with repositories the services and safe defaults are registered`() {
        runner.withUserConfiguration(FakeRepositories::class.java).run { ctx ->
            assertNotNull(ctx.getBean(BoardService::class.java))
            assertNotNull(ctx.getBean(PostService::class.java))
            assertNotNull(ctx.getBean(CommentService::class.java))
            assertNotNull(ctx.getBean(ReactionService::class.java))
            assertIs<DefaultBoardPolicy>(ctx.getBean(BoardPolicy::class.java))
            assertEquals(NoopBoardRateLimiter, ctx.getBean(BoardRateLimiter::class.java))
            assertEquals(NoopBoardNotifier, ctx.getBean(BoardNotifier::class.java))
            assertEquals(listOf("LIKE", "DISLIKE"), ctx.getBean(BoardProperties::class.java).reaction.types)
        }
    }

    @Test
    fun `the author names use the platform directory bean when there is one and know nobody when there is not`() {
        runner.withUserConfiguration(FakeRepositories::class.java).run { ctx ->
            assertTrue(ctx.getBean(AuthorNames::class.java).of("general", listOf("acc_1")).isEmpty(), "no directory bean - no names, no failure")
        }
        val directory = dev.sumin.skeleton.common.author.AuthorDirectory { ids, _ -> ids.associateWith { dev.sumin.skeleton.common.author.AuthorCard("Nick") } }
        runner.withUserConfiguration(FakeRepositories::class.java).withBean(dev.sumin.skeleton.common.author.AuthorDirectory::class.java, { directory }).run { ctx ->
            assertEquals("Nick", ctx.getBean(AuthorNames::class.java).one("general", "acc_1")!!.name)
        }
    }

    @Test
    fun `an app AuthorNames bean replaces the default`() {
        val mine = AuthorNames { dev.sumin.skeleton.common.author.AuthorDirectory.NONE }
        runner.withUserConfiguration(FakeRepositories::class.java).withBean(AuthorNames::class.java, { mine }).run { ctx ->
            assertTrue(ctx.getBean(AuthorNames::class.java) === mine)
        }
    }

    @Test
    fun `without a repository the context fails and names the missing port`() {
        runner.run { ctx ->
            assertNotNull(ctx.startupFailure)
            assertTrue(generateSequence(ctx.startupFailure) { it.cause }.any { it.message.orEmpty().contains("Repository") }, ctx.startupFailure.toString())
        }
    }

    @Test
    fun `an app policy replaces the default`() {
        val policy = object : BoardPolicy by DefaultBoardPolicy() {}
        runner.withUserConfiguration(FakeRepositories::class.java).withBean(BoardPolicy::class.java, { policy }).run { ctx ->
            assertEquals(policy, ctx.getBean(BoardPolicy::class.java))
            assertEquals(1, ctx.getBeansOfType(BoardPolicy::class.java).size)
        }
    }

    @Test
    fun `rate limiting is off by default and uses the platform RateLimitStore when enabled`() {
        val denyAll = object : RateLimitStore {
            override fun consume(key: String, capacity: Int, windowMillis: Long, now: Instant) = RateLimitDecision(false, capacity, 0, now)
        }
        runner.withUserConfiguration(FakeRepositories::class.java)
            .withBean(RateLimitStore::class.java, { denyAll })
            .withPropertyValues("skeleton.board.rate-limit.enabled=true")
            .run { ctx -> assertFalse(ctx.getBean(BoardRateLimiter::class.java).tryAcquire("a", "post")) }
        runner.withUserConfiguration(FakeRepositories::class.java).withBean(RateLimitStore::class.java, { denyAll })
            .run { ctx -> assertTrue(ctx.getBean(BoardRateLimiter::class.java).tryAcquire("a", "post")) }
    }

    @Test
    fun `seed boards are created at startup`() {
        runner.withUserConfiguration(FakeRepositories::class.java)
            .withPropertyValues("skeleton.board.seed-boards[0].code=general", "skeleton.board.seed-boards[0].name=General")
            .run { ctx ->
                ctx.getBeansOfType(ApplicationRunner::class.java).values.forEach { it.run(DefaultApplicationArguments()) }
                assertEquals(listOf("general"), ctx.getBean(BoardService::class.java).list().map { it.code })
            }
    }
}
