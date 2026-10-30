package dev.sumin.skeleton.search.jdbc

import dev.sumin.skeleton.common.search.SearchDocument
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.search.SearchSourcePage
import dev.sumin.skeleton.search.SearchEngine
import dev.sumin.skeleton.search.SearchRebuilds
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 시험이 문서를 마음대로 바꾸는 원본 - id 순 커서 */
class MutableSource(override val type: String) : SearchSource {
    val documents = CopyOnWriteArrayList<SearchDocument>()

    override fun page(after: String?, limit: Int): SearchSourcePage {
        val sorted = documents.sortedBy { it.id }.filter { after == null || it.id > after }
        val page = sorted.take(limit)
        return SearchSourcePage(page, if (sorted.size > limit) page.last().id else null)
    }
}

/** 쪽을 읽는 도중(원본을 읽은 뒤 색인에 쓰기 전)에 다른 트랜잭션이 문서를 지우는 경주를 일으키는 원본 */
class RacingSource(override val type: String, private val engine: SearchEngine) : SearchSource {
    val documents = CopyOnWriteArrayList<SearchDocument>()
    @Volatile var deleter: Thread? = null

    override fun page(after: String?, limit: Int): SearchSourcePage {
        val snapshot = documents.toList()   // 지우기 전의 원본을 읽었다
        val thread = Thread { engine.delete(type, "gone") }.also { it.start() }
        deleter = thread
        thread.join(1_500)                  // 재색인이 쓰는 동안 지우기는 기다려야 한다(기다리지 않으면 여기서 이미 끝난다)
        return SearchSourcePage(snapshot, null)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RebuildTestBeans {
    @Bean fun sourceRace(engine: SearchEngine) = RacingSource("rb-race", engine)
    @Bean fun sourceOne() = MutableSource("rb-one")
    @Bean fun sourceTwo() = MutableSource("rb-two")
    @Bean fun sourceThree() = MutableSource("rb-three")
}

/** 재색인 작업: 쪽마다 이어 달리기 · 사라진 원본 정리 · 같은 종류 동시 시작 거절 · 멈춘 진행 되살리기 */
@Import(DbTestcontainers::class, RebuildTestBeans::class)
@SpringBootTest(classes = [SearchJdbcTestApplication::class], properties = ["skeleton.job-queue.poll-interval=200ms"])
class SearchRebuildDbTest {
    @Autowired lateinit var rebuilds: SearchRebuilds
    @Autowired lateinit var engine: SearchEngine
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dialect: SqlDialect
    @Autowired lateinit var sourceOne: MutableSource
    @Autowired lateinit var sourceTwo: MutableSource
    @Autowired lateinit var sourceThree: MutableSource
    @Autowired lateinit var sourceRace: RacingSource

    private val at = Instant.parse("2026-10-30T00:00:00Z")

    private fun doc(type: String, id: String, title: String) = SearchDocument(type, id, title, "", null, null, at)

    private fun awaitIdle(type: String) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (rebuilds.states()[type]?.running == false) return
            Thread.sleep(100)
        }
        error("rebuild of $type did not finish")
    }

    private fun count(type: String) = jdbc.sql("select count(*) from search_documents where doc_type = ?").param(type).query(Long::class.java).single()

    @Test
    fun `a rebuild pages through the source, refreshes changed documents and removes vanished ones`() {
        sourceOne.documents += (1..1_200).map { doc("rb-one", "d%05d".format(it), "가방끈 $it") }
        engine.upsert(doc("rb-one", "ghost", "가방끈 유령"))                       // 원본에는 없다 - 사라진 문서
        engine.upsert(doc("rb-one", "d00001", "옛 제목"))                         // 원본이 진실 - 제목이 되돌아온다
        assertEquals(2, count("rb-one"))

        assertTrue(rebuilds.start("rb-one"))
        awaitIdle("rb-one")

        assertEquals(1_200, count("rb-one"))
        assertEquals(0, jdbc.sql("select count(*) from search_documents where doc_type = 'rb-one' and doc_id = 'ghost'").query(Long::class.java).single())
        assertEquals("가방끈 1", jdbc.sql("select title from search_documents where doc_type = 'rb-one' and doc_id = 'd00001'").query(String::class.java).single())
        assertNotNull(rebuilds.states()["rb-one"]?.lastRebuiltAt)
    }

    @Test
    fun `a second start while one is running is refused and a finished one can start again`() {
        sourceTwo.documents += (1..1_100).map { doc("rb-two", "d%05d".format(it), "신발끈 $it") }
        assertTrue(rebuilds.start("rb-two"))
        assertFalse(rebuilds.start("rb-two"))
        assertTrue(rebuilds.states().getValue("rb-two").running)
        awaitIdle("rb-two")
        assertTrue(rebuilds.start("rb-two"))
        awaitIdle("rb-two")
        assertEquals(1_100, count("rb-two"))
    }

    @Test
    fun `a run that made no progress for the stale time is taken over`() {
        sourceThree.documents += doc("rb-three", "a", "지갑끈")
        // 일꾼이 죽어 진행 표시만 남은 줄을 만든다 (한 시간 전에 갱신한 채 running)
        assertTrue(rebuilds.start("rb-three"))
        awaitIdle("rb-three")
        jdbc.sql("update search_rebuilds set running = true, updated_at = ? where doc_type = 'rb-three'")
            .param(dialect.instantParam(Instant.now().minusSeconds(3600))).update()
        assertTrue(rebuilds.start("rb-three"))
        awaitIdle("rb-three")
        assertEquals(1, count("rb-three"))
    }

    @Test
    fun `a document deleted while its page is being rebuilt does not come back`() {
        sourceRace.documents += listOf(doc("rb-race", "gone", "가방끈 판매중지"), doc("rb-race", "kept", "가방끈 판매중"))
        engine.upsert(doc("rb-race", "gone", "가방끈 판매중지"))
        assertTrue(rebuilds.start("rb-race"))
        awaitIdle("rb-race")
        sourceRace.deleter!!.join(30_000)
        assertEquals(0, jdbc.sql("select count(*) from search_documents where doc_type = 'rb-race' and doc_id = 'gone'").query(Long::class.java).single())
        assertEquals(1, jdbc.sql("select count(*) from search_documents where doc_type = 'rb-race' and doc_id = 'kept'").query(Long::class.java).single())
    }
}
