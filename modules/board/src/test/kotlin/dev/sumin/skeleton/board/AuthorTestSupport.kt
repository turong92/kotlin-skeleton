package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith

/** 계정 → 쓸 수 있는 공개 id 들. 부른 인자를 남긴다 */
class FakeIdentities(val allowed: Map<String, Set<String>>) : AuthorIdentities {
    val asked = CopyOnWriteArrayList<Triple<String, String, AuthorContext>>()

    override fun allows(accountId: String, authorId: String, context: AuthorContext): Boolean {
        asked += Triple(accountId, authorId, context)
        return authorId in allowed[accountId].orEmpty()
    }
}

fun standardIdentities() = FakeIdentities(mapOf("acc_author" to setOf("prf_a1", "prf_a2"), "acc_other" to setOf("prf_o1")))

/** 작성자 신원 시험용 하네스 — 기본은 [standardIdentities] 가 켜진 상태. `identities = null` 이면 꺼진 상태 */
fun authorHarness(
    identities: AuthorIdentities? = standardIdentities(),
    notices: MutableList<BoardCommentNotice> = mutableListOf(),
    directory: AuthorDirectory = AuthorDirectory.NONE,
    limiter: BoardRateLimiter = NoopBoardRateLimiter,
    wrapComments: (CommentRepository) -> CommentRepository = { it },
) = ServiceHarness(identities = identities, directory = directory, notifier = { notices += it }, limiter = limiter, wrapComments = wrapComments)

fun ServiceHarness.post(who: BoardCaller, authorId: String?, status: PostStatus? = null, attachments: List<String>? = null) =
    postService.create(who, "general", CreatePost("t", "b", attachments, status, authorId))

fun failureMessage(block: () -> Unit): String = assertFailsWith<BoardException> { block() }.message.orEmpty()

/** 호출 횟수를 세는 한도 — 거절된 쓰기가 한도를 쓰지 않았는지 본다 */
class CountingLimiter : BoardRateLimiter {
    var calls = 0
    override fun tryAcquire(accountId: String, action: String): Boolean { calls++; return true }
}
