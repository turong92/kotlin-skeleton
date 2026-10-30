package dev.sumin.skeleton.search.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.search.SearchSource
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import dev.sumin.skeleton.search.FakeRebuilds
import dev.sumin.skeleton.search.FixedSource
import dev.sumin.skeleton.search.RecordingSearchEngine
import dev.sumin.skeleton.search.SearchEngine
import dev.sumin.skeleton.search.SearchHit
import dev.sumin.skeleton.search.SearchHits
import dev.sumin.skeleton.search.SearchRebuildState
import dev.sumin.skeleton.search.SearchRebuilds
import dev.sumin.skeleton.search.SearchSort
import dev.sumin.skeleton.searchtest.SearchWebTestApplication
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class SearchTestBeans {
    @Bean fun recording() = RecordingSearchEngine()
    @Bean fun engine(e: RecordingSearchEngine): SearchEngine = e
    @Bean fun rebuilds(): FakeRebuilds = FakeRebuilds()
    @Bean fun rebuildsPort(r: FakeRebuilds): SearchRebuilds = r
    @Bean fun noteSource(): SearchSource = FixedSource("note")
    @Bean fun access(): OperatorAccess = object : OperatorAccess {
        override fun permissionsOf(ref: OperatorRef): Set<String> = GRANTS[ref.accountId] ?: emptySet()
    }
    @Bean fun audit(): OperatorAuditLog = OperatorAuditLog { AUDIT += it }

    companion object {
        val GRANTS = ConcurrentHashMap<String, Set<String>>()
        val AUDIT = CopyOnWriteArrayList<OperatorAction>()
    }
}

/** 사용자 경로 - 로그인 필요(기본) · 쪽 envelope · 질의 인자 · 오류 코드 */
@SpringBootTest(classes = [SearchWebTestApplication::class])
@AutoConfigureMockMvc
@Import(SearchTestBeans::class)
class SearchUserWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var engine: RecordingSearchEngine
    @Autowired lateinit var contributors: ObjectProvider<PublicEndpointContributor>

    private val user = TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")
    private val at = Instant.parse("2026-10-30T00:00:00Z")

    @BeforeEach
    fun reset() {
        engine.lastQuery = null
        engine.hits = SearchHits(listOf(SearchHit("note", "n1", "가방끈 이야기", "오늘은 가방끈이 짧다", at)), total = 1)
    }

    @Test
    fun `anonymous callers are refused by default and no public path is declared`() {
        mvc.perform(get("/api/v1/search").param("q", "가방끈")).andExpect(status().isUnauthorized)
        assertTrue(PublicEndpointRegistry.from(WebProperties(), contributors.orderedStream().toList()).endpoints.none { it.pattern.startsWith("/api/v1/search") })
    }

    @Test
    fun `a search returns the page envelope with snippet and UTF-16 ranges`() {
        mvc.perform(get("/api/v1/search").principal(user).param("q", "방끈"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].type").value("note"))
            .andExpect(jsonPath("$.values[0].id").value("n1"))
            .andExpect(jsonPath("$.values[0].title").value("가방끈 이야기"))
            .andExpect(jsonPath("$.values[0].snippet").value("오늘은 가방끈이 짧다"))
            .andExpect(jsonPath("$.values[0].titleHighlights[0][0]").value(1))
            .andExpect(jsonPath("$.values[0].titleHighlights[0][1]").value(3))
            .andExpect(jsonPath("$.values[0].snippetHighlights[0][0]").value(5))
            .andExpect(jsonPath("$.values[0].updatedAt").exists())
            .andExpect(jsonPath("$.pagination.totalElements").value(1))
            .andExpect(jsonPath("$.pagination.page").value(0))
            .andExpect(jsonPath("$.values[0].score").doesNotExist())
        val query = engine.lastQuery!!
        assertEquals("acc_1", query.callerId)
        assertEquals(SearchSort.RELEVANCE, query.sort)
    }

    @Test
    fun `types scope sort page and size reach the engine`() {
        mvc.perform(get("/api/v1/search").principal(user).param("q", "가방끈").param("types", "note,product").param("scope", "bag").param("sort", "recent").param("page", "1").param("size", "10"))
            .andExpect(status().isOk)
        val query = engine.lastQuery!!
        assertEquals(setOf("note", "product"), query.types)
        assertEquals("bag", query.scope)
        assertEquals(SearchSort.RECENT, query.sort)
        assertEquals(10, query.offset)
        assertEquals(10, query.limit)
    }

    @Test
    fun `bad input is a 400 with a stable code`() {
        mvc.perform(get("/api/v1/search").principal(user).param("q", "a")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SEARCH.QUERY_TOO_SHORT"))
        mvc.perform(get("/api/v1/search").principal(user)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SEARCH.QUERY_TOO_SHORT"))
        mvc.perform(get("/api/v1/search").principal(user).param("q", "가방끈").param("page", "20").param("size", "50")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SEARCH.PAGE_TOO_DEEP"))
        mvc.perform(get("/api/v1/search").principal(user).param("q", "가방끈").param("size", "51")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/search").principal(user).param("q", "가방끈").param("sort", "newest")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/search").principal(user).param("q", "가방끈").param("types", "No Te")).andExpect(status().isBadRequest)
    }

    @Test
    fun `the module registers an erasure listener that removes the account's documents`() {
        val listener = erasure.single { it.name == "search" }
        listener.erase(ErasureRequest("acc_7", "deleted:abc"))
        assertEquals("acc_7", engine.erasedOwner)
    }

    @Autowired lateinit var erasure: List<AccountErasureListener>
}

@SpringBootTest(classes = [SearchWebTestApplication::class], properties = ["skeleton.search.http.allow-anonymous-read=true"])
@AutoConfigureMockMvc
@Import(SearchTestBeans::class)
class SearchAnonymousWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var engine: RecordingSearchEngine
    @Autowired lateinit var contributors: ObjectProvider<PublicEndpointContributor>

    @Test
    fun `turning anonymous read on opens only the search path and sees only ownerless documents`() {
        mvc.perform(get("/api/v1/search").param("q", "가방끈")).andExpect(status().isOk)
        assertEquals(null, engine.lastQuery!!.callerId)
        val endpoints = PublicEndpointRegistry.from(WebProperties(), contributors.orderedStream().toList()).endpoints.filter { it.pattern.startsWith("/api/v1/search") }
        assertEquals(listOf("/api/v1/search"), endpoints.map { it.pattern })
    }
}

@SpringBootTest(classes = [SearchWebTestApplication::class], properties = ["skeleton.search.http.enabled=false"])
@AutoConfigureMockMvc
@Import(SearchTestBeans::class)
class SearchHttpOffWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `switching http off removes the user path`() {
        mvc.perform(get("/api/v1/search").principal(TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")).param("q", "가방끈")).andExpect(status().isNotFound)
    }
}

/** 운영 경로: 꺼짐 404 · 익명 401 · 권한 없음 403 · 종류 목록 · 재색인 202/404/409 · 운영 기록 */
@SpringBootTest(classes = [SearchWebTestApplication::class], properties = ["skeleton.search.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(SearchTestBeans::class)
class SearchAdminWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var rebuilds: FakeRebuilds
    @Autowired lateinit var engine: RecordingSearchEngine

    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")
    private val json = MediaType.APPLICATION_JSON

    @BeforeEach
    fun reset() {
        SearchTestBeans.GRANTS.clear()
        SearchTestBeans.AUDIT.clear()
        SearchTestBeans.GRANTS["acc_op"] = setOf("search.manage")
        rebuilds.accept = true
        rebuilds.started.clear()
        rebuilds.states = mapOf("note" to SearchRebuildState(true, Instant.parse("2026-10-30T00:00:00Z")))
        engine.documents = mapOf("note" to 4)
    }

    @Test
    fun `anonymous is 401 and a caller without search manage is 403`() {
        mvc.perform(get("/api/v1/admin/search/types")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/admin/search/types").principal(other)).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/admin/search/rebuild").principal(other).contentType(json).content("""{"type":"note"}""")).andExpect(status().isForbidden)
        assertTrue(rebuilds.started.isEmpty())
    }

    @Test
    fun `types lists documents and rebuild state`() {
        mvc.perform(get("/api/v1/admin/search/types").principal(operator))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values[0].type").value("note"))
            .andExpect(jsonPath("$.values[0].documents").value(4))
            .andExpect(jsonPath("$.values[0].rebuilding").value(true))
            .andExpect(jsonPath("$.values[0].lastRebuiltAt").value("2026-10-30T00:00:00Z"))
    }

    @Test
    fun `rebuild is 202 and audited, 404 without a source, 409 while running`() {
        mvc.perform(post("/api/v1/admin/search/rebuild").principal(operator).contentType(json).content("""{"type":"note"}""")).andExpect(status().isAccepted)
        assertEquals(listOf("note"), rebuilds.started)
        assertEquals(listOf("search.rebuilt"), SearchTestBeans.AUDIT.map { it.action })
        mvc.perform(post("/api/v1/admin/search/rebuild").principal(operator).contentType(json).content("""{"type":"nope"}""")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("SEARCH.SOURCE_NOT_FOUND"))
        rebuilds.accept = false
        mvc.perform(post("/api/v1/admin/search/rebuild").principal(operator).contentType(json).content("""{"type":"note"}""")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("SEARCH.REBUILD_RUNNING"))
        mvc.perform(post("/api/v1/admin/search/rebuild").principal(operator).contentType(json).content("""{}""")).andExpect(status().isBadRequest)
    }
}

@SpringBootTest(classes = [SearchWebTestApplication::class])
@AutoConfigureMockMvc
@Import(SearchTestBeans::class)
class SearchAdminDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `the operator paths do not exist unless enabled`() {
        SearchTestBeans.GRANTS["acc_op"] = setOf("*")
        mvc.perform(get("/api/v1/admin/search/types").principal(TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER"))).andExpect(status().isNotFound)
    }
}
