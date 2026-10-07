package dev.sumin.skeleton.app.sample.leak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 열 분류 표를 검사하는 함수의 양성 대조 — 새 열 · 사라진 열 · 이중 분류를 실제로 잡는다 */
class AuthorLinkColumnsTest {
    private val everything = listOf(AuthorLinkColumns.PRIVATE, AuthorLinkColumns.PUBLIC).flatMap { m -> m.flatMap { (t, cs) -> cs.map { "$t.$it" } } }.toSet()

    @Test
    fun `the table as written is consistent with itself`() {
        assertEquals(emptyList(), AuthorLinkColumns.problems(everything))
    }

    @Test
    fun `a new column that nobody classified fails, so does a column that disappeared`() {
        assertTrue(AuthorLinkColumns.problems(everything + "board_posts.owner_account").single().startsWith("분류되지 않은 열: board_posts.owner_account"))
        assertTrue(AuthorLinkColumns.problems(everything - "board_posts.author_ref").single().contains("DB 에 없는 열: board_posts.author_ref"))
    }
}
