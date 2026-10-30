package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.search.SearchEngine
import dev.sumin.skeleton.search.SearchReader
import dev.sumin.skeleton.search.SearchResultPage
import dev.sumin.skeleton.search.SearchSort
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 두 방언(postgresTest · mysqlTest)에서 같은 질의가 같은 결과를 내는지 - 한국어 2글자 부분 일치 · 영어 조각 · 질의 문법 문자 · 주인 문서 가리기 ·
 * 트랜잭션 · 덮어쓰기 규칙. 종류 이름을 시험마다 다르게 해서 같은 DB 안에서 서로 섞이지 않는다.
 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [SearchJdbcTestApplication::class])
class SearchEngineDbTest {
    @Autowired lateinit var engine: SearchEngine
    @Autowired lateinit var reader: SearchReader
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private val base = Instant.parse("2026-10-30T00:00:00Z")
    private val counter = AtomicInteger()
    private val run = java.util.UUID.randomUUID().toString().take(6)

    /** 시험마다 다른 종류 이름 */
    private fun newType() = "t${counter.incrementAndGet()}-$run"

    private fun put(type: String, id: String, title: String, body: String = "", owner: String? = null, scope: String? = null, at: Instant = base) =
        engine.upsert(SearchDocument(type, id, title, body, owner, scope, at))

    private fun find(type: String, q: String, caller: String? = null, sort: SearchSort = SearchSort.RELEVANCE, scope: String? = null): SearchResultPage =
        reader.search(q, setOf(type), scope, sort, 0, 50, caller)

    private fun ids(page: SearchResultPage) = page.results.map { it.id }

    // ---- 한국어 · 영어 · 정규화 ----

    @Test
    fun `a two-character Korean piece matches inside a longer word`() {
        val t = newType()
        put(t, "a", "가방끈 이야기")
        put(t, "b", "다른 글")
        assertEquals(listOf("a"), ids(find(t, "방끈")))
        assertEquals(listOf("a"), ids(find(t, "가방끈")))
        assertEquals(emptyList(), ids(find(t, "끈이")))   // 낱말이 다르다 (공백 너머)
        assertEquals(emptyList(), ids(find(t, "방가")))   // 순서가 다르다
    }

    @Test
    fun `short English words that are stopwords elsewhere are searchable`() {
        val t = newType()
        put(t, "a", "tonight in town", "it is on the table to be or not")
        put(t, "b", "and", "only the end")
        for (word in listOf("in", "is", "on", "to", "be", "or", "it"))
            assertTrue("a" in ids(find(t, word)), "word=$word")
        assertEquals(listOf("a"), ids(find(t, "ton")))   // tonight 의 ton
        // `end` 는 `and` 글에 맞으면 안 된다 (MySQL 불용어가 켜져 있으면 조각 `en` 이 버려져 `nd` 만 남아 맞아 버린다)
        assertEquals(listOf("b"), ids(find(t, "end")))
    }

    @Test
    fun `case and full-width forms do not matter`() {
        val t = newType()
        put(t, "a", "Hello World")
        put(t, "b", "ＡＢＣ ｶﾞ")
        assertEquals(listOf("a"), ids(find(t, "hello")))
        assertEquals(listOf("a"), ids(find(t, "HELLO wor")))
        assertEquals(listOf("b"), ids(find(t, "abc")))
        assertEquals(listOf("b"), ids(find(t, "ＡＢＣ")))
        assertEquals("ABC ガ", find(t, "abc").results.single().title)
    }

    @Test
    fun `two query words must both match`() {
        val t = newType()
        put(t, "a", "가방끈 길이")
        put(t, "b", "가방끈")
        put(t, "c", "길이")
        assertEquals(listOf("a"), ids(find(t, "가방끈 길이")))
        assertEquals(setOf("a", "b"), ids(find(t, "가방끈")).toSet())
    }

    @Test
    fun `query syntax characters are plain text and never break the query`() {
        val t = newType()
        put(t, "a", "가방끈 it's", "x+y*z drop table search documents")
        for (q in listOf("\"가방끈\"", "+가방끈", "가방*", "'가방끈'", "가방끈'; drop table search_documents; --", "-가방끈", "(가방끈)", "가방끈 @ > < ~"))
            assertEquals(listOf("a"), ids(find(t, q)), "q=$q")
        assertTrue(jdbc.sql("select count(*) from search_documents").query(Long::class.java).single() > 0)
    }

    @Test
    fun `pieces of two different words do not match a longer query`() {
        val t = newType()
        put(t, "words", "ab bc")                 // 낱말 둘 - 'abc' 가 아니다
        put(t, "split", "ab", "bc")              // 제목 끝과 본문 처음
        put(t, "real", "abc")
        assertEquals(listOf("real"), ids(find(t, "abc")))
    }

    // ---- 순위 · 정렬 ----

    @Test
    fun `a title match ranks before a body-only match`() {
        val t = newType()
        put(t, "body", "다른 제목", "여기에 가방끈 이 있다")
        put(t, "title", "가방끈 제목", "다른 내용")
        assertEquals(listOf("title", "body"), ids(find(t, "가방끈")))
    }

    @Test
    fun `recent order puts the newest source first and paging windows the result`() {
        val t = newType()
        (1..5).forEach { put(t, "d$it", "가방끈 $it", at = base.plusSeconds(it.toLong())) }
        assertEquals(listOf("d5", "d4", "d3", "d2", "d1"), ids(find(t, "가방끈", sort = SearchSort.RECENT)))
        val second = reader.search("가방끈", setOf(t), null, SearchSort.RECENT, 1, 2, null)
        assertEquals(listOf("d3", "d2"), ids(second))
        assertEquals(5, second.total)
    }

    @Test
    fun `types and scope narrow the result`() {
        val t1 = newType()
        val t2 = newType()
        put(t1, "a", "가방끈", scope = "bag")
        put(t1, "b", "가방끈", scope = "shoe")
        put(t2, "c", "가방끈", scope = "bag")
        assertEquals(setOf("a", "b"), ids(find(t1, "가방끈")).toSet())
        assertEquals(listOf("a"), ids(find(t1, "가방끈", scope = "bag")))
        assertEquals(setOf("a", "b", "c"), reader.search("가방끈", setOf(t1, t2), null, SearchSort.RELEVANCE, 0, 50, null).results.map { it.id }.toSet())
        assertEquals(setOf("a", "c"), reader.search("가방끈", setOf(t1, t2), "bag", SearchSort.RELEVANCE, 0, 50, null).results.map { it.id }.toSet())
    }

    // ---- 주인 문서 ----

    @Test
    fun `another account's document is neither in the results nor in the count`() {
        val t = newType()
        put(t, "mine", "가방끈 내 노트", owner = "acc_1")
        put(t, "theirs", "가방끈 남의 노트", owner = "acc_2")
        put(t, "shared", "가방끈 공용")
        val mine = find(t, "가방끈", caller = "acc_1")
        assertEquals(setOf("mine", "shared"), ids(mine).toSet())
        assertEquals(2, mine.total)
        assertEquals(listOf("shared"), ids(find(t, "가방끈", caller = null)))
        assertEquals(1, find(t, "가방끈", caller = null).total)
        assertTrue(mine.results.none { "남의" in it.title || "남의" in it.snippet })
    }

    @Test
    fun `erasing an account removes only its documents`() {
        val t = newType()
        put(t, "mine", "가방끈 내 노트", owner = "acc_1")
        put(t, "theirs", "가방끈 남의 노트", owner = "acc_2")
        put(t, "shared", "가방끈 공용")
        assertEquals(1, engine.eraseOwner("acc_1"))
        assertEquals(setOf("theirs", "shared"), ids(find(t, "가방끈", caller = "acc_2")).toSet())
        assertEquals(0, engine.eraseOwner("acc_1"))
    }

    // ---- 쓰기 규칙 ----

    @Test
    fun `delete removes the document and a missing one is a no-op`() {
        val t = newType()
        put(t, "a", "가방끈")
        engine.delete(t, "a")
        engine.delete(t, "a")
        assertEquals(emptyList(), ids(find(t, "가방끈")))
    }

    @Test
    fun `an older source time does not overwrite, a newer one does, and reindex always does`() {
        val t = newType()
        put(t, "a", "가방끈 새것", at = base.plusSeconds(10))
        put(t, "a", "가방끈 옛것", at = base)
        assertEquals("가방끈 새것", find(t, "가방끈").results.single().title)
        put(t, "a", "가방끈 더새것", at = base.plusSeconds(20))
        assertEquals("가방끈 더새것", find(t, "가방끈").results.single().title)
        engine.reindex(SearchDocument(t, "a", "가방끈 원본", "", null, null, base))
        assertEquals("가방끈 원본", find(t, "가방끈").results.single().title)
        assertEquals(1, jdbc.sql("select count(*) from search_documents where doc_type = ?").param(t).query(Long::class.java).single())
    }

    @Test
    fun `overwriting replaces the searchable text, owner and scope`() {
        val t = newType()
        put(t, "a", "가방끈", "옛날이야기", owner = "acc_1", scope = "bag", at = base)
        put(t, "a", "신발끈", "새이야기", owner = null, scope = "shoe", at = base.plusSeconds(1))
        assertEquals(emptyList(), ids(find(t, "가방끈")))
        assertEquals(listOf("a"), ids(find(t, "신발끈", caller = null, scope = "shoe")))
        assertEquals(emptyList(), ids(find(t, "옛날")))
        assertEquals(listOf("a"), ids(find(t, "새이")))
    }

    @Test
    fun `an upsert in a rolled back transaction leaves nothing and a committed one is visible`() {
        val t = newType()
        val tx = TransactionTemplate(transactionManager)
        assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                put(t, "gone", "가방끈 롤백")
                throw IllegalStateException("rollback")
            }
        }
        assertEquals(emptyList(), ids(find(t, "가방끈")))
        tx.executeWithoutResult { put(t, "kept", "가방끈 커밋") }
        assertEquals(listOf("kept"), ids(find(t, "가방끈")))
    }

    @Test
    fun `a body longer than 16000 characters is indexed only up to the limit`() {
        val t = newType()
        val body = "가".repeat(15_000) + " 앞부분끝 " + "나".repeat(1_100) + " 뒷부분 " + "다".repeat(3_000)
        put(t, "long", "긴 글", body)
        assertEquals(listOf("long"), ids(find(t, "앞부분끝")))
        assertEquals(emptyList(), ids(find(t, "뒷부분")))
        assertTrue(jdbc.sql("select char_length(body) from search_documents where doc_type = ?").param(t).query(Int::class.java).single() <= 16_000)
    }

    @Test
    fun `a title and a body with unusual characters are stored and searchable`() {
        val t = newType()
        put(t, "a", "이모지 😀 가방끈", "널\u0000문자 와 \u0007벨")
        assertEquals(listOf("a"), ids(find(t, "가방끈")))
        assertEquals(listOf("a"), ids(find(t, "문자")))
    }

    @Test
    fun `parallel upserts of one document leave one row holding the newest source`() {
        val t = newType()
        val n = 12
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        val futures = (1..n).map { i -> pool.submit { start.await(); put(t, "same", "가방끈 $i", at = base.plusSeconds(i.toLong())) } }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, jdbc.sql("select count(*) from search_documents where doc_type = ?").param(t).query(Long::class.java).single())
        assertEquals("가방끈 $n", find(t, "가방끈").results.single().title)
    }

    @Test
    fun `counts by type`() {
        val t = newType()
        put(t, "a", "가방끈")
        put(t, "b", "신발끈")
        assertEquals(2L, engine.countByType()[t])
    }

    @Test
    fun `deleting what was indexed before a time removes only that type's older rows`() {
        val t = newType()
        val other = newType()
        put(t, "a", "가방끈")
        put(other, "a", "가방끈")
        assertEquals(0, engine.deleteIndexedBefore(t, Instant.parse("2000-01-01T00:00:00Z")))
        assertEquals(1, engine.deleteIndexedBefore(t, Instant.now().plusSeconds(3600)))
        assertEquals(emptyList(), ids(find(t, "가방끈")))
        assertEquals(listOf("a"), ids(find(other, "가방끈")))
    }
}
