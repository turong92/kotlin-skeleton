package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.upload.GuestUploadKeys
import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploads
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.TaskScheduler
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
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
    /** 이 모듈이 가진 권한 — 모듈을 빼면 권한 카탈로그에서도 사라진다 */
    @Bean
    @ConditionalOnMissingBean(name = ["boardPermissionDeclaration"])
    fun boardPermissionDeclaration(): PermissionDeclaration = BoardPermissions.declaration()

    @Bean
    @ConditionalOnMissingBean
    fun boardPolicy(guestKeys: ObjectProvider<GuestUploadKeys>): BoardPolicy = DefaultBoardPolicy { guestKeys.getIfAvailable() }

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
    fun boardGuestSecrets(properties: BoardProperties): BoardGuestSecrets = DefaultBoardGuestSecrets(properties.guest.secret)

    /** 비회원 한도 — `rate-limit.enabled` 와 무관하게 늘 걸린다 (`guest.enabled` 일 때만 쓰인다). platform 의 [RateLimitStore] 빈이 있으면 그것(Redis), 없으면 인메모리 */
    @Bean
    @ConditionalOnMissingBean
    fun boardGuestLimiter(properties: BoardProperties, stores: ObjectProvider<RateLimitStore>): BoardGuestLimiter {
        val fallback by lazy { InMemoryFixedWindowRateLimitStore() }
        return StoreBoardGuestLimiter({ stores.getIfAvailable { fallback } }, properties.guest)
    }

    /** `guest.captcha=REQUIRED` 인데 [BoardCaptcha] 가 없으면 기동이 실패한다 — 켠 줄 알고 검증 없이 도는 사고를 막는다 */
    @Bean
    @ConditionalOnMissingBean
    fun boardGuests(
        properties: BoardProperties,
        secrets: BoardGuestSecrets,
        limiter: BoardGuestLimiter,
        captchas: ObjectProvider<BoardCaptcha>,
    ): BoardGuests {
        check(!(properties.guest.enabled && properties.guest.captcha == GuestCaptcha.REQUIRED) || captchas.getIfAvailable() != null) {
            "skeleton.board.guest.captcha=REQUIRED needs a BoardCaptcha bean (add captcha-turnstile and enable it, or define your own)"
        }
        return BoardGuests(properties, secrets, limiter, { captchas.getIfAvailable() })
    }

    @Bean
    @ConditionalOnMissingBean
    fun boardNotifier(): BoardNotifier = NoopBoardNotifier

    /** 앱의 [BoardEventListener] 빈들(여러 개 가능, 순서는 `@Order`)에 커밋 뒤 이벤트를 내보낸다. 부를 때마다 찾는다 */
    @Bean
    @ConditionalOnMissingBean
    fun boardEvents(listeners: ObjectProvider<BoardEventListener>): BoardEvents = BoardEvents { listeners.orderedStream().toList() }

    /** 고친 판 저장소 — `board-jdbc` 가 있으면 그것이 먼저 등록되고, 아니면 판이 없는 기본값 (판이 필요한 첫 수정에서 닫힌 쪽으로 실패) */
    @Bean
    @ConditionalOnMissingBean
    fun boardRevisionRepository(): BoardRevisionRepository = NoBoardRevisions

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

    /** 작성자 신원 고리 — platform 의 [AuthorIdentities] 빈이 있으면 쓰기 규칙 · 공개 작성자 id 가 켜진다 (스펙 §7). 없으면 이 기능 이전과 같다. 부를 때마다 찾는다 */
    @Bean
    @ConditionalOnMissingBean
    fun boardAuthorRules(identities: ObjectProvider<AuthorIdentities>): BoardAuthorRules = BoardAuthorRules { identities.getIfAvailable() }

    @Bean
    @ConditionalOnMissingBean
    fun reactionSupport(reactions: ReactionRepository, properties: BoardProperties): ReactionSupport =
        ReactionSupport(reactions, properties)

    @Bean
    @ConditionalOnMissingBean
    fun boardService(
        boards: BoardRepository,
        policy: BoardPolicy,
        properties: BoardProperties,
        time: ObjectProvider<TimeProvider>,
        authorRules: BoardAuthorRules,
    ): BoardService = BoardService(boards, policy, properties, time.getIfAvailable { TimeProvider.systemUtc() }, authorRules)

    /** storage 의 공개 영역 · 서명 읽기 - 둘 다 없으면(기본) board 는 키만 낸다. 부를 때마다 찾는다 */
    @Bean
    @ConditionalOnMissingBean
    fun boardUploads(
        publicUploads: ObjectProvider<PublicUploads>,
        access: ObjectProvider<PrivateUploadAccess>,
        reconciler: ObjectProvider<BoardPublicAttachments>,
    ): BoardUploads =
        // 공개 주소는 조정자(저장소 포트가 있어야 생긴다)가 있을 때만 낸다 - 앱이 자기 저장소를 두고 포트를 구현하지 않았다면 복사하는 이가 없어 주소가 404 가 된다
        BoardUploads({ if (reconciler.getIfAvailable() != null) publicUploads.getIfAvailable() else null }, { access.getIfAvailable() })

    /** 공개 영역을 board 상태에 맞추는 조정자 - 저장소 포트(board-jdbc)가 있을 때만. `skeleton.storage.web.public` 이 꺼져 있으면 아무것도 하지 않는다 */
    @Bean
    @ConditionalOnBean(BoardPublicAttachmentRepository::class)
    @ConditionalOnMissingBean
    fun boardPublicAttachments(
        repository: BoardPublicAttachmentRepository,
        properties: BoardProperties,
        publicUploads: ObjectProvider<PublicUploads>,
        alerts: ObjectProvider<BoardPublicSyncAlerts>,
        time: ObjectProvider<TimeProvider>,
        projects: ObjectProvider<ProjectRegistry>,
    ): BoardPublicAttachments =
        BoardPublicAttachments(
            repository, { publicUploads.getIfAvailable()?.takeIf { it.enabled() } }, time.getIfAvailable { TimeProvider.systemUtc() }, projects.getIfAvailable(),
            properties.publicAttachments.fullPassInterval, alerts.getIfAvailable { BoardPublicSyncAlerts.NONE }, properties.publicAttachments.alertAfterAttempts,
        )

    @Bean
    @ConditionalOnBean(BoardPublicAttachments::class)
    @ConditionalOnMissingBean(name = ["boardPublicAttachmentListener"])
    fun boardPublicAttachmentListener(attachments: BoardPublicAttachments): BoardEventListener = BoardPublicAttachmentListener(attachments)

    /**
     * 1분마다 실패한 항목만 다시 맞추고(표에 적힌 것 - 전체를 훑지 않는다), 한 시간마다 전체 대조를 시도한다(마지막 실행이 `full-pass-interval`(기본 하루)보다 오래됐을 때 DB 의 원자적
     * 비교-교체로 **한 인스턴스만** 돈다). `scheduler` 모듈의 `skeletonTaskScheduler` 가 있어야 돈다(없으면 이벤트로만 맞춘다). 단일 실행 장치는 scheduler 모듈의 락 매니저가 아니라 DB 한 줄이다.
     */
    @Bean
    @ConditionalOnBean(BoardPublicAttachments::class)
    @ConditionalOnMissingBean(name = ["boardPublicAttachmentSweep"])
    fun boardPublicAttachmentSweep(
        attachments: BoardPublicAttachments,
        publicUploads: ObjectProvider<PublicUploads>,
        @Qualifier("skeletonTaskScheduler") schedulers: ObjectProvider<TaskScheduler>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val log = LoggerFactory.getLogger(BoardPublicAttachments::class.java)
        val scheduler = schedulers.getIfAvailable()
        if (publicUploads.getIfAvailable() != null) {
            if (scheduler == null) {
                log.warn("storage public uploads are on but there is no skeletonTaskScheduler bean (add the scheduler module) - public copies are only kept in step by board events")
            } else {
                scheduler.scheduleWithFixedDelay(
                    { try { attachments.processQueue() } catch (e: Exception) { log.warn("board public attachment queue failed: {}", e.toString()) } },
                    Instant.now().plus(BoardPublicAttachments.TICK),
                    BoardPublicAttachments.TICK,
                )
                scheduler.scheduleWithFixedDelay(
                    { try { attachments.fullPass() } catch (e: Exception) { log.warn("board public attachment full pass failed: {}", e.toString()) } },
                    Instant.now().plus(Duration.ofMinutes(5)),
                    Duration.ofHours(1),
                )
            }
        }
    }

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
        authorRules: BoardAuthorRules,
        events: BoardEvents,
        guests: BoardGuests,
        revisions: BoardRevisionRepository,
        uploads: BoardUploads,
    ): PostService =
        PostService(access, posts, reactions, authors, policy, rules, properties, limiter, time.getIfAvailable { TimeProvider.systemUtc() }, authorRules, events, guests, revisions, uploads)

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
        authorRules: BoardAuthorRules,
        events: BoardEvents,
        guests: BoardGuests,
        revisions: BoardRevisionRepository,
        uploads: BoardUploads,
    ): CommentService =
        CommentService(access, comments, reactions, authors, policy, rules, properties, limiter, notifier, time.getIfAvailable { TimeProvider.systemUtc() }, authorRules, events, guests, revisions, uploads)

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
