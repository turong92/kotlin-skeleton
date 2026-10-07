package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 게시판 서비스와 교체 가능한 기본값(정책 · 한도 · 알림). 저장소 포트(`BoardRepository` 등)는 `board-jdbc` 가 내놓는다 — 없으면 시작이 실패하고
 * 빠진 빈 이름이 메시지에 나온다. 알림 · 멱등은 선택 통합이라 이 클래스는 그 모듈의 타입을 참조하지 않는다
 * (알림 구현은 [dev.sumin.skeleton.board.notification.BoardNotificationAutoConfiguration] 이 이 설정보다 먼저 등록한다).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.board.jdbc.BoardJdbcAutoConfiguration"])
@EnableConfigurationProperties(BoardProperties::class)
class BoardAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun boardPolicy(): BoardPolicy = DefaultBoardPolicy()

    /** 꺼져 있으면 아무것도 막지 않는다. 켜면 platform 의 [RateLimitStore] 빈(redis-rate-limit 이 있으면 Redis, 없으면 인메모리)에 계정별 한도를 건다. */
    @Bean
    @ConditionalOnMissingBean
    fun boardRateLimiter(properties: BoardProperties, stores: ObjectProvider<RateLimitStore>): BoardRateLimiter =
        if (!properties.rateLimit.enabled) NoopBoardRateLimiter
        else {
            val fallback by lazy { InMemoryFixedWindowRateLimitStore() }
            StoreBoardRateLimiter({ stores.getIfAvailable { fallback } }, properties.rateLimit)
        }

    @Bean
    @ConditionalOnMissingBean
    fun boardNotifier(): BoardNotifier = NoopBoardNotifier

    @Bean
    @ConditionalOnMissingBean
    fun boardContentRules(properties: BoardProperties): BoardContentRules = BoardContentRules(properties)

    @Bean
    @ConditionalOnMissingBean
    fun boardAccess(boards: BoardRepository, posts: PostRepository, comments: CommentRepository, policy: BoardPolicy): BoardAccess =
        BoardAccess(boards, posts, comments, policy)

    /** 작성자 이름 조회 — platform 의 [AuthorDirectory] 빈이 있으면 그것(예: `account` 의 닉네임 · 앱이 만든 것), 없으면 이름 없음. 부를 때마다 찾는다 */
    @Bean
    @ConditionalOnMissingBean
    fun authorNames(directories: ObjectProvider<AuthorDirectory>): AuthorNames = AuthorNames { directories.getIfAvailable { AuthorDirectory.NONE } }

    @Bean
    @ConditionalOnMissingBean
    fun reactionSupport(reactions: ReactionRepository, properties: BoardProperties): ReactionSupport =
        ReactionSupport(reactions, properties)

    @Bean
    @ConditionalOnMissingBean
    fun boardService(boards: BoardRepository, policy: BoardPolicy, properties: BoardProperties, time: ObjectProvider<TimeProvider>): BoardService =
        BoardService(boards, policy, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    fun postService(
        access: BoardAccess,
        posts: PostRepository,
        reactions: ReactionSupport,
        authors: AuthorNames,
        policy: BoardPolicy,
        rules: BoardContentRules,
        properties: BoardProperties,
        limiter: BoardRateLimiter,
        time: ObjectProvider<TimeProvider>,
    ): PostService = PostService(access, posts, reactions, authors, policy, rules, properties, limiter, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    fun commentService(
        access: BoardAccess,
        comments: CommentRepository,
        reactions: ReactionSupport,
        authors: AuthorNames,
        policy: BoardPolicy,
        rules: BoardContentRules,
        properties: BoardProperties,
        limiter: BoardRateLimiter,
        notifier: BoardNotifier,
        time: ObjectProvider<TimeProvider>,
    ): CommentService =
        CommentService(access, comments, reactions, authors, policy, rules, properties, limiter, notifier, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    fun reactionService(
        access: BoardAccess,
        comments: CommentRepository,
        repository: ReactionRepository,
        support: ReactionSupport,
        policy: BoardPolicy,
        properties: BoardProperties,
        limiter: BoardRateLimiter,
        time: ObjectProvider<TimeProvider>,
    ): ReactionService = ReactionService(access, comments, repository, support, policy, properties, limiter, time.getIfAvailable { TimeProvider.systemUtc() })

    /** `skeleton.board.seed-boards` 를 기동할 때 한 번 — 이미 있는 게시판은 건드리지 않는다 */
    @Bean
    @ConditionalOnMissingBean(name = ["boardSeedRunner"])
    fun boardSeedRunner(service: BoardService): ApplicationRunner = ApplicationRunner { service.seed() }
}
