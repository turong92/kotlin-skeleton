package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import kotlin.test.assertFailsWith

/** 서비스 시험 바탕 — 같은 메모리 저장소 위에 서비스들을 조립한다. */
class ServiceHarness(
    val props: BoardProperties = BoardProperties(),
    val policy: BoardPolicy = DefaultBoardPolicy(),
    val limiter: BoardRateLimiter = NoopBoardRateLimiter,
    val notifier: BoardNotifier = NoopBoardNotifier,
    /** 작성자 이름을 푸는 고리 — 기본은 이름을 모른다 */
    val directory: AuthorDirectory = AuthorDirectory.NONE,
    /** 작성자 신원 고리 — 기본은 없음(이 기능 이전의 동작) */
    val identities: AuthorIdentities? = null,
    /** 커밋 뒤 이벤트를 받는 리스너 — 기본은 없음 */
    listeners: List<BoardEventListener> = emptyList(),
    /** `general` 게시판의 설정 — 기본은 새 기능이 모두 꺼진 채 */
    val settings: BoardSettings = BoardSettings(),
    /** 비회원 캡차 검증기 — 기본은 없음 */
    val captcha: BoardCaptcha? = null,
    /** 비밀 해시 방식 — 기본은 설정(`props.guest.secret`)을 따르는 구현 */
    val secrets: BoardGuestSecrets = DefaultBoardGuestSecrets(props.guest.secret),
    /** 댓글 저장소를 감싼다 — 저장소가 author_ref 를 남기지 않는 경우 같은 것을 흉내 낸다 */
    wrapComments: (CommentRepository) -> CommentRepository = { it },
) {
    val store = FakeStore()
    private val boards = FakeBoardRepository(store)
    val posts = FakePostRepository(store)
    val comments = FakeCommentRepository(store)
    val reactionRepo = FakeReactionRepository(store)
    private val time = TimeProvider.fixed(T0)
    private val rules = BoardContentRules(props)
    private val access = BoardAccess(boards, posts, comments, policy)
    private val support = ReactionSupport(reactionRepo, props)
    private val authors = AuthorNames { directory }
    private val authorRules = BoardAuthorRules { identities }
    private val events = BoardEvents { listeners }
    /** 하네스마다 새 저장소 — 시험끼리 한도를 나눠 쓰지 않는다 */
    private val guestStore = InMemoryFixedWindowRateLimitStore()
    val guestLimiter: BoardGuestLimiter = StoreBoardGuestLimiter({ guestStore }, props.guest)
    private val guests = BoardGuests(props, secrets, guestLimiter, { captcha })
    val boardService = BoardService(boards, policy, props, time, authorRules)
    val postService = PostService(access, posts, support, authors, policy, rules, props, limiter, time, authorRules, events, guests)
    val commentService = CommentService(access, wrapComments(comments), support, authors, policy, rules, props, limiter, notifier, time, authorRules, events, guests)
    val reactionService = ReactionService(access, comments, reactionRepo, support, policy, props, limiter, time)

    init { boards.create("general", "General", null, T0, settings) }

    fun failure(block: () -> Unit): BoardErrorCode = assertFailsWith<BoardException> { block() }.errorCode as BoardErrorCode

    /** 한도 시험용 — 한도가 걸린 하네스에서도 글은 만들 수 있게 저장소에 바로 넣는다 */
    fun newPostForced(): Long = posts.insert(NewPost("general", "acc_author", "t", "b", PostStatus.PUBLISHED, emptyList(), T0)).id

    fun newPost(caller: BoardCaller = owner, title: String = "Hello", body: String = "World", status: PostStatus? = null): Post =
        postService.create(caller, "general", CreatePost(title, body, null, status)).post
}
