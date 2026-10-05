package dev.sumin.skeleton.board

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 서비스 · 컨트롤러 시험용 메모리 저장소 (포트의 의미를 그대로 — 카운터 · 소프트 삭제 · 반응 규칙). 진짜 DB 의미는 board-jdbc 의 dbTest 가 본다.
 * 모듈 main 에는 메모리 구현이 없다.
 */
class FakeStore {
    val boards = linkedMapOf<String, Board>()
    val posts = linkedMapOf<Long, Post>()
    val comments = linkedMapOf<Long, Comment>()
    val reactions = mutableListOf<Reaction>()
    val ids = AtomicLong(100)

    data class Reaction(val target: ReactionTarget, val targetId: Long, val accountId: String, val type: String)

    fun clear() { boards.clear(); posts.clear(); comments.clear(); reactions.clear() }
}

@Synchronized
private fun <T> FakeStore.locked(block: () -> T): T = block()

class FakeBoardRepository(private val s: FakeStore) : BoardRepository {
    override fun findAll(): List<Board> = s.locked {
        s.boards.values.map { b -> b.copy(postCount = s.posts.values.count { it.boardCode == b.code && it.status == PostStatus.PUBLISHED }.toLong()) }
    }

    override fun find(code: String): Board? = findAll().firstOrNull { it.code == code }

    override fun create(code: String, name: String, description: String?, now: Instant): Boolean = s.locked {
        if (code in s.boards) false else { s.boards[code] = Board(code, name, description, 0, now); true }
    }
}

class FakePostRepository(private val s: FakeStore) : PostRepository {
    override fun insert(post: NewPost): Post = s.locked {
        val id = s.ids.incrementAndGet()
        Post(id, post.boardCode, post.authorId, post.title, post.body, post.status, false, 0, 0, 0, post.attachments, post.now, post.now)
            .also { s.posts[id] = it }
    }

    override fun find(id: Long): Post? = s.locked { s.posts[id] }

    override fun update(id: Long, change: PostChange, now: Instant): Post? = s.locked {
        val p = s.posts[id] ?: return@locked null
        p.copy(
            title = change.title ?: p.title, body = change.body ?: p.body, attachments = change.attachments ?: p.attachments,
            status = change.status ?: p.status, pinned = change.pinned ?: p.pinned, updatedAt = now,
        ).also { s.posts[id] = it }
    }

    override fun incrementViews(id: Long) { s.locked { s.posts[id]?.let { s.posts[id] = it.copy(viewCount = it.viewCount + 1) } } }

    override fun page(query: PostQuery): PageResult<PostListItem> = s.locked {
        fun typeCount(p: Post) = s.reactions.count { it.target == ReactionTarget.POST && it.targetId == p.id && it.type == query.reactionType }.toLong()
        val filtered = s.posts.values.filter {
            it.boardCode == query.boardCode && it.status in query.statuses && (query.authorId == null || it.authorId == query.authorId) &&
                (query.search == null || it.title.contains(query.search, true) || it.body.contains(query.search, true))
        }
        val key: Comparator<Post> = when {
            query.reactionType != null -> compareByDescending(::typeCount)
            query.sort == PostSort.REACTIONS -> compareByDescending { it.reactionCount }
            query.sort == PostSort.COMMENTS -> compareByDescending { it.commentCount }
            else -> compareBy { 0 }
        }
        val sorted = filtered.sortedWith(compareByDescending<Post> { it.pinned }.then(key).thenByDescending { it.createdAt }.thenByDescending { it.id })
        val rows = sorted.drop(query.page * query.size).take(query.size).map {
            PostListItem(it.id, it.boardCode, it.authorId, it.title, it.body.take(query.excerptLength), it.status, it.pinned, it.viewCount,
                it.commentCount, it.reactionCount, it.attachments.size, it.createdAt, it.updatedAt)
        }
        PageResult(rows, filtered.size.toLong())
    }
}

class FakeCommentRepository(private val s: FakeStore) : CommentRepository {
    override fun insert(comment: NewComment): Comment? = s.locked {
        val post = s.posts[comment.postId] ?: return@locked null
        s.posts[post.id] = post.copy(commentCount = post.commentCount + 1)
        val id = s.ids.incrementAndGet()
        Comment(id, comment.postId, comment.parentId, comment.rootId ?: id, comment.depth, comment.authorId, comment.body,
            CommentStatus.PUBLISHED, 0, comment.now, comment.now).also { s.comments[id] = it }
    }

    override fun find(id: Long): Comment? = s.locked { s.comments[id] }

    override fun updateBody(id: Long, body: String, now: Instant): Comment? = s.locked {
        s.comments[id]?.copy(body = body, updatedAt = now)?.also { s.comments[id] = it }
    }

    override fun setStatus(id: Long, status: CommentStatus, now: Instant): Comment? = s.locked {
        val c = s.comments[id] ?: return@locked null
        val delta = (if (status == CommentStatus.PUBLISHED) 1 else 0) - (if (c.status == CommentStatus.PUBLISHED) 1 else 0)
        s.posts[c.postId]?.let { s.posts[c.postId] = it.copy(commentCount = it.commentCount + delta) }
        c.copy(status = status, updatedAt = now).also { s.comments[id] = it }
    }

    override fun rootPage(query: CommentRootQuery): PageResult<Comment> = s.locked {
        val roots = s.comments.values.filter { it.postId == query.postId && it.parentId == null }
        val sorted = when (query.sort) {
            CommentSort.OLDEST -> roots.sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id })
            CommentSort.LATEST -> roots.sortedWith(compareByDescending<Comment> { it.createdAt }.thenByDescending { it.id })
            CommentSort.REACTIONS -> roots.sortedWith(compareByDescending<Comment> { it.reactionCount }.thenBy { it.createdAt }.thenBy { it.id })
        }
        PageResult(sorted.drop(query.page * query.size).take(query.size), roots.size.toLong())
    }

    override fun descendants(rootIds: Collection<Long>): List<Comment> = s.locked {
        s.comments.values.filter { it.parentId != null && it.rootId in rootIds }.sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id })
    }
}

class FakeReactionRepository(private val s: FakeStore) : ReactionRepository {
    private fun exists(t: ReactionTarget, id: Long) = if (t == ReactionTarget.POST) id in s.posts else id in s.comments

    private fun bump(t: ReactionTarget, id: Long, delta: Int) {
        if (t == ReactionTarget.POST) s.posts[id]?.let { s.posts[id] = it.copy(reactionCount = it.reactionCount + delta) }
        else s.comments[id]?.let { s.comments[id] = it.copy(reactionCount = it.reactionCount + delta) }
    }

    override fun react(target: ReactionTarget, targetId: Long, accountId: String, type: String, mode: ReactionMode, now: Instant): Boolean = s.locked {
        if (!exists(target, targetId)) return@locked false
        if (mode == ReactionMode.SINGLE) {
            val others = s.reactions.filter { it.target == target && it.targetId == targetId && it.accountId == accountId && it.type != type }
            s.reactions.removeAll(others.toSet()); bump(target, targetId, -others.size)
        }
        val r = FakeStore.Reaction(target, targetId, accountId, type)
        if (r !in s.reactions) { s.reactions += r; bump(target, targetId, 1) }
        true
    }

    override fun remove(target: ReactionTarget, targetId: Long, accountId: String, type: String?): Boolean = s.locked {
        if (!exists(target, targetId)) return@locked false
        val gone = s.reactions.filter { it.target == target && it.targetId == targetId && it.accountId == accountId && (type == null || it.type == type) }
        s.reactions.removeAll(gone.toSet()); bump(target, targetId, -gone.size)
        true
    }

    override fun counts(target: ReactionTarget, targetIds: Collection<Long>): Map<Long, Map<String, Long>> = s.locked {
        s.reactions.filter { it.target == target && it.targetId in targetIds }.groupBy { it.targetId }
            .mapValues { (_, rs) -> rs.groupingBy { it.type }.eachCount().mapValues { it.value.toLong() } }
    }

    override fun mine(target: ReactionTarget, targetIds: Collection<Long>, accountId: String): Map<Long, Set<String>> = s.locked {
        s.reactions.filter { it.target == target && it.targetId in targetIds && it.accountId == accountId }
            .groupBy { it.targetId }.mapValues { (_, rs) -> rs.map { it.type }.toSet() }
    }
}
