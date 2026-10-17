package dev.sumin.skeleton.board

import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 커밋 뒤에 불리는 앱의 확장 자리 — 앱이 이 타입의 빈을 만들면(여러 개 가능) 글 · 댓글 쓰기마다 [BoardEvent] 를 받는다. 던져도 요청은 성공한다 (경고 로그). */
fun interface BoardEventListener {
    fun onEvent(event: BoardEvent)
}

/** 쓴 쪽의 종류. [accountId] 는 MEMBER · OWNER · MODERATOR 일 때 계정 id (GUEST 는 null). 공개 자리에 옮길 때는 계정 id 가 아니라 공개 작성자 id 를 쓴다 */
enum class BoardActorKind { MEMBER, GUEST, OWNER, MODERATOR }

data class BoardActor(val kind: BoardActorKind, val accountId: String? = null)

/**
 * 게시판 쓰기 이벤트 — 모델에 비밀 해시는 없다. `Post` · `Comment` 의 `authorId` 는 계정 id 일 수 있으니 공개 자리에는 [BoardAuthors.publicId] 를 쓴다.
 */
sealed interface BoardEvent {
    val boardCode: String
    val postId: Long
    val actor: BoardActor
}

data class PostCreated(val post: Post, override val actor: BoardActor) : BoardEvent {
    override val boardCode get() = post.boardCode
    override val postId get() = post.id
}

data class PostUpdated(val post: Post, override val actor: BoardActor) : BoardEvent {
    override val boardCode get() = post.boardCode
    override val postId get() = post.id
}

data class PostStatusChanged(val post: Post, val from: PostStatus, val to: PostStatus, override val actor: BoardActor) : BoardEvent {
    override val boardCode get() = post.boardCode
    override val postId get() = post.id
}

data class CommentCreated(val comment: Comment, override val boardCode: String, override val actor: BoardActor) : BoardEvent {
    override val postId get() = comment.postId
}

data class CommentUpdated(val comment: Comment, override val boardCode: String, override val actor: BoardActor) : BoardEvent {
    override val postId get() = comment.postId
}

data class CommentStatusChanged(
    val comment: Comment,
    override val boardCode: String,
    val from: CommentStatus,
    val to: CommentStatus,
    override val actor: BoardActor,
) : BoardEvent {
    override val postId get() = comment.postId
}

/**
 * 이벤트를 커밋 뒤에 내보낸다. 트랜잭션 동기화가 열려 있으면(앱이 자기 `@Transactional` 안에서 서비스를 불렀고, 저장소의 `TransactionTemplate` 이 거기에 합류한 경우)
 * `afterCommit` 에 걸고, 없으면 저장소 호출이 커밋하고 돌아온 바로 뒤에 부른다. 롤백되면 부르지 않는다. 같은 스레드에서 돈다 —
 * `afterCommit` 안에서 DB 를 쓰려면 리스너가 `REQUIRES_NEW` 를 써야 한다 (끝난 트랜잭션이 아직 묶여 있다는 Spring 규칙). 내구성은 없다 — 커밋과 리스너 사이에 죽으면 잃는다.
 */
class BoardEvents(private val listeners: () -> List<BoardEventListener>) {
    private val log = LoggerFactory.getLogger(BoardEvents::class.java)

    fun publish(event: BoardEvent) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = dispatch(event)
            })
        } else {
            dispatch(event)
        }
    }

    private fun dispatch(event: BoardEvent) {
        for (listener in listeners()) {
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                log.warn("board event listener failed ({}): {}", event::class.simpleName, e.toString())
            }
        }
    }

    companion object {
        val NONE = BoardEvents { emptyList() }
    }
}

/** 승인 대상 작성자가 고쳐 판이 생겼다 (대기 중에 또 고쳐 덮어썼으면 [replaced]) */
data class RevisionSubmitted(
    val target: RevisionTarget,
    val targetId: Long,
    override val boardCode: String,
    override val postId: Long,
    val replaced: Boolean,
    override val actor: BoardActor,
) : BoardEvent

/** 판이 승인 · 거절 · 버려졌다. 승인이면 공개본이 바뀌었으므로 `…Updated` 도 함께 나간다 — 내용을 옮겨 두는 리스너는 `…Updated` 만 들으면 된다 */
data class RevisionDecided(
    val target: RevisionTarget,
    val targetId: Long,
    override val boardCode: String,
    override val postId: Long,
    val outcome: RevisionOutcome,
    override val actor: BoardActor,
) : BoardEvent
