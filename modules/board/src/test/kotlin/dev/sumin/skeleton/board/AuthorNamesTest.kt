package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 글 · 댓글 응답이 작성자 이름을 어떻게 얻는지 — 요청 하나에 조회 한 번, 지워진 작성자는 묻지 않고, 고리가 망가져도 응답은 나간다 */
class AuthorNamesTest {
    /** 부른 기록을 남기고 `이름-<id>` / 꼬리표 `0001` 을 돌려주는 고리 */
    private class Directory(val fail: Boolean = false) : AuthorDirectory {
        val calls = CopyOnWriteArrayList<Pair<List<String>, AuthorContext>>()
        override fun resolve(ids: Collection<String>, context: AuthorContext): Map<String, AuthorCard> {
            calls += ids.toList() to context
            if (fail) error("directory is down")
            return ids.associateWith { AuthorCard("name-$it", "0001") }
        }
    }

    private fun harness(d: AuthorDirectory, notices: MutableList<BoardCommentNotice> = mutableListOf()) = ServiceHarness(directory = d, notifier = { notices += it })

    @Test
    fun `without a directory nobody has a name and nothing breaks`() {
        val h = ServiceHarness()
        val view = h.postService.create(owner, "general", CreatePost("t", "b", null, null))
        assertNull(view.author)
        assertNull(h.postService.list(null, "general", PostListRequest(0, 20)).values.single().author)
    }

    @Test
    fun `a post list asks the directory once with every distinct author and the board as the scope`() {
        val d = Directory(); val h = harness(d)
        h.newPost(owner); h.newPost(owner); h.newPost(other)
        d.calls.clear()
        val page = h.postService.list(null, "general", PostListRequest(0, 20))
        assertEquals(1, d.calls.size, "N+1 would be one call per post")
        assertEquals(setOf("acc_author", "acc_other"), d.calls.single().first.toSet())
        assertEquals(2, d.calls.single().first.size, "ids are distinct")
        assertEquals(AuthorContext("board", "general"), d.calls.single().second)
        assertEquals(listOf("name-acc_other", "name-acc_author", "name-acc_author"), page.values.map { it.author!!.name })
        assertEquals("0001", page.values.first().author!!.tag)
    }

    @Test
    fun `an empty page asks nothing`() {
        val d = Directory(); val h = harness(d)
        assertTrue(h.postService.list(null, "general", PostListRequest(0, 20)).values.isEmpty())
        assertEquals(0, d.calls.size)
    }

    @Test
    fun `a tombstoned author is not looked up and has no name, the others still do`() {
        val d = Directory(); val h = harness(d)
        h.posts.insert(NewPost("general", "deleted:abc123", "gone", "b", PostStatus.PUBLISHED, emptyList(), T0))
        h.newPost(owner)
        d.calls.clear()
        val page = h.postService.list(null, "general", PostListRequest(0, 20)).values
        assertEquals(listOf(listOf("acc_author")), d.calls.map { it.first })
        assertEquals(setOf(null, "name-acc_author"), page.map { it.author?.name }.toSet())
        val untouched = Directory()
        val onlyGone = ServiceHarness(directory = untouched)
        onlyGone.posts.insert(NewPost("general", "deleted:abc123", "gone", "b", PostStatus.PUBLISHED, emptyList(), T0))
        assertNull(onlyGone.postService.list(null, "general", PostListRequest(0, 20)).values.single().author)
        assertEquals(0, untouched.calls.size, "all authors deleted - the directory is not asked")
    }

    @Test
    fun `post detail create update and moderate all carry the author, one lookup each`() {
        val d = Directory(); val h = harness(d)
        val created = h.postService.create(owner, "general", CreatePost("t", "b", null, null))
        assertEquals("name-acc_author", created.author!!.name)
        val id = created.post.id
        d.calls.clear()
        assertEquals("name-acc_author", h.postService.get(other, "general", id).author!!.name)
        assertEquals("name-acc_author", h.postService.update(owner, "general", id, UpdatePost("t2", null, null, null)).author!!.name)
        assertEquals("name-acc_author", h.postService.moderate(moderator, "general", id, PostStatus.HIDDEN, null).author!!.name)
        assertEquals(3, d.calls.size)
        assertTrue(d.calls.all { it.first == listOf("acc_author") })
    }

    @Test
    fun `a comment thread asks once for the authors of the roots and all their replies`() {
        val d = Directory(); val h = harness(d)
        val post = h.newPost(owner).id
        val root = h.commentService.create(other, "general", post, null, "root").comment
        h.commentService.create(owner, "general", post, root.id, "reply")
        h.commentService.create(BoardCaller("acc_third"), "general", post, root.id, "reply2")
        d.calls.clear()
        val threads = h.commentService.list(null, "general", post, 0, 20, CommentSort.OLDEST).values
        assertEquals(1, d.calls.size, "N+1 would be one call per comment")
        assertEquals(setOf("acc_other", "acc_author", "acc_third"), d.calls.single().first.toSet())
        assertEquals("name-acc_other", threads.single().author!!.name)
        assertEquals(setOf("name-acc_author", "name-acc_third"), threads.single().replies.map { it.author!!.name }.toSet())
    }

    @Test
    fun `create update and moderate of a comment return its author, create also puts the name on the notice`() {
        val d = Directory(); val notices = mutableListOf<BoardCommentNotice>()
        val h = harness(d, notices)
        val post = h.newPost(owner).id
        d.calls.clear()
        val created = h.commentService.create(other, "general", post, null, "hi")
        assertEquals("name-acc_other", created.author!!.name)
        assertEquals(1, d.calls.size, "the notice reuses the lookup of the response")
        assertEquals("name-acc_other", notices.single().authorName)
        assertEquals("name-acc_other", h.commentService.update(other, "general", post, created.comment.id, "edited").author!!.name)
        assertEquals("name-acc_other", h.commentService.moderate(moderator, "general", post, created.comment.id, CommentStatus.HIDDEN).author!!.name)
    }

    @Test
    fun `a failing directory costs the names, not the response or the comment`() {
        val h = harness(Directory(fail = true))
        val post = h.newPost(owner).id
        val c = h.commentService.create(other, "general", post, null, "hi")
        assertNull(c.author)
        assertEquals("hi", h.commentService.list(null, "general", post, 0, 20, CommentSort.OLDEST).values.single().body)
        assertNull(h.postService.list(null, "general", PostListRequest(0, 20)).values.single().author)
    }

    @Test
    fun `a directory that omits an id leaves that author unnamed`() {
        val h = harness(AuthorDirectory { ids, _ -> ids.filter { it == "acc_author" }.associateWith { AuthorCard("Known", null) } })
        h.newPost(owner); h.newPost(other)
        val authors = h.postService.list(null, "general", PostListRequest(0, 20)).values.map { it.author }
        assertEquals(listOf(null, "Known"), authors.map { it?.name })
        assertNull(authors.last()!!.tag)
    }
}
