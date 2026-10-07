package dev.sumin.skeleton.board

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import org.slf4j.LoggerFactory

/**
 * 작성자 id → 화면에 보일 이름. platform 의 [AuthorDirectory] 를 **요청 하나에 한 번** 부른다(목록 · 스레드의 모든 작성자를 모아서) — 글마다 묻지 않는다.
 * 지워진 작성자(톰스톤)는 묻지 않고 이름이 없다. 고리가 느리거나 던져도 글 · 댓글 응답은 이름만 비운 채 나간다 (이름은 장식이지 계약이 아니다).
 * 고리는 부를 때마다 찾는다([directory]) — 앱이 [AuthorDirectory] 빈을 두면 그것, 없으면 [AuthorDirectory.NONE].
 */
class AuthorNames(private val directory: () -> AuthorDirectory) {
    private val log = LoggerFactory.getLogger(AuthorNames::class.java)

    fun of(boardCode: String, authorIds: Collection<String>): Map<String, AuthorCard> {
        val ids = authorIds.filterNot { BoardAuthors.isDeleted(it) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return try {
            directory().resolve(ids, AuthorContext(SOURCE, boardCode))
        } catch (e: Exception) {
            log.warn("author names unavailable for board {} ({} authors): {}", boardCode, ids.size, e.toString())
            emptyMap()
        }
    }

    fun one(boardCode: String, authorId: String): AuthorCard? = of(boardCode, listOf(authorId))[authorId]

    companion object {
        /** [AuthorContext.source] 에 실리는 이 모듈의 이름 */
        const val SOURCE = "board"
    }
}
