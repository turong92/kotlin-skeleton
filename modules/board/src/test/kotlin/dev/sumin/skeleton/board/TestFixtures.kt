package dev.sumin.skeleton.board

import java.time.Instant

val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")

fun aPost(
    id: Long = 1,
    author: String = "acc_author",
    status: PostStatus = PostStatus.PUBLISHED,
    board: String = "general",
    pinned: Boolean = false,
) = Post(id, board, author, "title", "body", status, pinned, 0, 0, 0, emptyList(), T0, T0)

fun aComment(
    id: Long = 10,
    postId: Long = 1,
    author: String = "acc_author",
    status: CommentStatus = CommentStatus.PUBLISHED,
    parentId: Long? = null,
    rootId: Long = id,
    depth: Int = 0,
) = Comment(id, postId, parentId, rootId, depth, author, "c", status, 0, T0, T0)

val aBoard = Board("general", "General", null, 0, T0)
val owner = BoardCaller("acc_author")
val other = BoardCaller("acc_other")
val moderator = BoardCaller("acc_mod", moderator = true)
