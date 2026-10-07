package dev.sumin.skeleton.app.sample.leak

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.AccountPurgeService
import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper

/**
 * 연결 누출 스캔 (스펙 §12 1~7) — 진짜 PostgreSQL · 보안 체인 · board + account + author-profile + notification-jdbc + storage 가 다 끼워진 샘플 앱.
 * 계정 X 가 프로필 A · B · C 로 쓴 뒤 Y(로그인한 제3자)와 Z(비로그인, `allow-anonymous-read=true`)가 읽을 수 있는 모든 경로를 훑어,
 * X 의 계정 id · 이메일 · 계정 톰스톤 · 계정 이름이 어디에도 없고 A · B 의 조각이 값으로 겹치지 않음을 — 상태를 바꿔 가며 — 확인한다.
 * 스캔이 값을 찾을 수 있다는 증명이 먼저다(1번, 그리고 `LeakScanTest`). 단계는 순서대로 상태를 바꾼다: 기준선 → B 삭제 → X 정지 → X 탈퇴.
 */
@SpringBootTest(
    properties = [
        "skeleton.job-queue.poll-interval=200ms", "spring.config.import=classpath:leak-seeds.yml",
        "skeleton.board.http.allow-anonymous-read=true",   // Z 가 읽을 수 있게 — 스펙 §3 의 비로그인 · 검색엔진
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SampleIntegrationTest.FakeStorageConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthorLinkLeakIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var registry: NotificationSubscriptionRegistry
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var purge: AccountPurgeService

    private val http by lazy { LeakHttp(mvc) }
    private val w by lazy { LeakWorld(http, registry) }
    private val sweep by lazy { LeakSweep(http, w, mapper) }

    private fun labels(vararg c: Captured, forbidden: Map<String, String> = w.forbidden) = LeakScan.forbiddenHits(c.toList(), forbidden).map { it.label }.toSet()

    /** 이 응답에서 작성자 칸 셋 — Y 의 눈에 보이는 대로 */
    private fun shown(post: Long, token: String? = w.yToken): Triple<String, Boolean, String?> =
        http.get("/api/v1/boards/general/posts/$post", token, record = false).ok().let { Triple(it.read("$.value.authorId"), it.read("$.value.authorDeleted"), it.read("$.value.authorName")) }

    private fun expectShown(phase: String, aDeleted: Boolean, bDeleted: Boolean) {
        for ((who, post, deleted) in listOf(Triple(w.a, w.postA, aDeleted), Triple(w.b, w.postB, bDeleted))) {
            for (token in listOf(w.yToken, null)) {
                val (id, gone, name) = shown(post, token)
                assertEquals(who.id, id, "$phase: the public author id is the profile id")
                assertEquals(deleted, gone, "$phase: authorDeleted of ${who.id}")
                assertEquals(if (deleted) null else who.name, name, "$phase: authorName of ${who.id}")
            }
        }
        assertNotEquals(shown(w.postA).first, shown(w.postB).first, "$phase: the two profiles never share an author id")
    }

    /** 모은 응답 전부에 금지 값이 없고, 작성자 조각끼리 허용 목록 밖의 공통 값이 없다 — 스캔이 비어 있지 않음도 함께 단정한다 */
    private fun assertNoLink(phase: String, seen: List<Captured>) {
        val all = seen + sweep.sseEventsForY()
        assertEquals(emptyList(), LeakScan.forbiddenHits(all, w.forbidden), "$phase: forbidden values in responses")
        val board = sweep.boardPieces(seen)
        val notice = sweep.noticePieces(all)
        listOf(w.a, w.b).forEach { assertEquals(setOf("post", "comment"), board.of(it.id).map { l -> l.kind }.toSet(), "$phase: ${it.id} must have a post and a comment piece, or the pair scan proves nothing") }
        assertTrue(board.of(w.c.id).isNotEmpty() && notice.of(w.a.id).isNotEmpty() && notice.of(w.b.id).isNotEmpty(), "$phase: C's comment and the notices of A and B were scanned")
        assertEquals(2, w.keys.size)
        assertTrue(w.keys.all { k -> (board.of(w.a.id) + board.of(w.b.id)).any { it.value == k } }, "$phase: the attachment keys of A's and B's posts were scanned (opaque, so they share nothing)")
        assertTrue(sweep.sseEventsForY().size >= 2, "$phase: Y's SSE stream carried the notices of A and B")
        assertTrue(sweep.noticePieces(seen).let { it.of(w.a.id).isNotEmpty() && it.of(w.b.id).isNotEmpty() }, "$phase: Y's inbox responses (not only SSE) carry the notices of A and B")
        assertPairsClean(phase, board, sweep.allow)
        assertPairsClean("$phase (inbox + SSE)", notice, sweep.allow)
        (board.owners() + notice.owners()).forEach { assertTrue(it.startsWith("prf_"), "$phase: author id $it is a profile id") }
        assertTrue(all.none { "deleted:" in it.body }, "$phase: no tombstone value in any response")
    }

    private fun assertPairsClean(phase: String, pieces: LeakPieces, allow: LeakAllow) {
        listOf(w.a to w.b, w.a to w.c, w.b to w.c).forEach { (p, q) ->
            assertEquals(emptyList(), LeakScan.commonValues(pieces.of(p.id), pieces.of(q.id), allow), "$phase: values shared by ${p.id} and ${q.id}")
        }
    }

    private fun assertProbesIdentical() {
        // 요청마다 달라지는 추적 칸(traceId · spanId · timestamp, 그리고 그것들 때문에 달라지는 Content-Length)만 뺀다 — 상태 · 문구 · 코드 · 나머지 헤더는 그대로 같아야 한다
        fun normal(c: Captured): Triple<Int, Any?, Map<String, String>> {
            val body = runCatching { JsonPath.parse(c.body).json<Map<String, Any?>>() }.getOrNull()
            val trace = body?.get("traceId") as String?
            return Triple(c.status, body?.minus(setOf("traceId", "spanId", "timestamp")) ?: c.body, c.headers.filter { (k, v) -> k !in setOf("Date", "X-Span-Id", "X-Trace-Id", "Idempotency-Key", "Content-Length") && (trace == null || trace !in v) })
        }
        sweep.probes.forEach { (kind, answers) ->
            assertEquals(1, answers.map(::normal).toSet().size, "$kind: an own, a foreign, a missing and a malformed id are answered the same way: ${answers.map(::normal).distinct()}")
        }
        assertEquals(404, sweep.probes.getValue("PATCH profile").first().status)
        assertEquals("AUTHOR_PROFILE.NOT_FOUND", JsonPath.read<String>(sweep.probes.getValue("DELETE profile").first().body, "$.code"))
        assertEquals(403, sweep.probes.getValue("POST post").first().status)
        assertEquals("BOARD.AUTHOR_NOT_ALLOWED", JsonPath.read<String>(sweep.probes.getValue("POST comment").first().body, "$.code"))
    }

    /** 한 단계의 읽기 전부 (Y · Z) + 단정 */
    private fun scanPhase(phase: String, aDeleted: Boolean, bDeleted: Boolean) {
        val y = sweep.boardReads(w.yToken) + sweep.observerOnly()
        val z = sweep.boardReads(null)
        z.filter { it.label.contains("/posts") && !it.label.contains("999999") && !it.label.contains("zzz") && !it.label.contains("mine=true") }
            .forEach { assertEquals(200, it.status, "$phase: Z reads ${it.label}") }
        assertEquals(401, z.last().status, "$phase: Z has no profile list")
        assertNoLink("$phase / Y+Z", y + z)
        assertProbesIdentical()
        expectShown(phase, aDeleted, bDeleted)
        assertPublicColumnsClean(phase)
    }

    /** 스펙 §12 7: 공개 열 전체에서 금지 값 · 짝 스캔 — author_ref 와 남의 편지함 payload 를 포함한다 */
    private fun assertPublicColumnsClean(phase: String) {
        val seen = mutableListOf<Captured>()
        val board = LeakPieces()
        val notice = LeakPieces()
        AuthorLinkColumns.PUBLIC.forEach { (table, columns) ->
            val rows = jdbc.sql("select ${columns.joinToString()} from $table").query { rs, _ -> columns.associateWith { c -> rs.getObject(c).let { v -> if (v is Number || v is Boolean) v else v?.toString() } } }.list()
            columns.forEach { c -> seen += Captured("db $table.$c", 200, rows.joinToString("\n") { it[c]?.toString() ?: "" }, emptyMap()) }
            rows.forEach { r ->
                when (table) {
                    "board_posts", "board_comments" -> (r["author_ref"] as String?)?.let { board.addRow(it, if (table == "board_posts") "post" else "comment", r) }
                    "notification_inbox" -> JsonPath.parse(r["payload_json"] as String).json<Map<String, Any?>>().let { payload ->
                        notice.addNode(mapOf("authorId" to payload["authorId"]) + r.filterKeys { it != "payload_json" } + payload.filterKeys { it != "authorId" })
                    }
                }
            }
        }
        assertEquals(emptyList(), LeakScan.forbiddenHits(seen, w.forbidden), "$phase: forbidden values in public columns")
        listOf(w.a, w.b).forEach { assertEquals(setOf("post", "comment"), board.of(it.id).map { l -> l.kind }.toSet(), "$phase: ${it.id} has rows in the public columns") }
        assertTrue(notice.of(w.a.id).isNotEmpty() && notice.of(w.b.id).isNotEmpty(), "$phase: notices of A and B are in the public columns")
        assertPairsClean("$phase (public columns)", board, sweep.allow)
        assertPairsClean("$phase (public columns, inbox)", notice, sweep.allow)
    }

    @Test
    @Order(1)
    fun `the scan finds X's account id, email and account name when they are there, and the profile name is not the account name`() {
        val own = http.get("/api/v1/account/me", w.xToken, record = false).ok()
        assertEquals(setOf("account id", "email", "account name"), labels(own), "X reading /account/me: the same scan function sees the planted values")
        val authorIds = Captured("db board_posts.author_id", 200, jdbc.sql("select author_id from board_posts").query(String::class.java).list().joinToString("\n"), emptyMap())
        assertEquals(setOf("account id"), labels(authorIds), "the private author_id column does hold the account id")
        assertEquals("Xavier", w.a.name, "the first profile copied the account name once")
        assertNotEquals(w.xAccountName, w.a.name)
        assertEquals(emptyList(), jdbc.sql("select id from author_profiles where name = :n").param("n", w.xAccountName).query(String::class.java).list(), "no profile carries the current account name")
    }

    @Test
    @Order(2)
    fun `baseline - nothing Y or Z can read names X and no value ties the profiles together`() = scanPhase("baseline", aDeleted = false, bDeleted = false)

    @Test
    @Order(3)
    fun `every board, notification and author profile column is classified public or private`() {
        assertEquals(emptyList(), AuthorLinkColumns.problems(AuthorLinkColumns.columnsOf(jdbc, "current_schema()")))
    }

    @Test
    @Order(4)
    fun `after B is deleted the scan still passes, B's posts read as a deleted user's and A's are untouched`() {
        http.delete("/api/v1/author-profiles/${w.b.id}", w.xToken).also { assertEquals(204, it.status) }
        scanPhase("B deleted", aDeleted = false, bDeleted = true)
    }

    @Test
    @Order(5)
    fun `after X is suspended nothing changes for readers - no marker appears on any profile`() {
        http.post("/api/v1/admin/accounts/${w.xAcc}/suspend", w.adminToken, """{"reason":"leak scan"}""").also { assertEquals(204, it.status) }
        scanPhase("X suspended", aDeleted = false, bDeleted = true)
    }

    @Test
    @Order(6)
    fun `after X is erased both profiles read as a deleted user's with different ids and no shared tombstone`() {
        http.post("/api/v1/admin/accounts/${w.xAcc}/unsuspend", w.adminToken, "{}").also { assertEquals(204, it.status) }
        val token = w.relogin(w.xEmail)
        http.post("/api/v1/account/delete", token, """{"currentPassword":"password"}""").also { assertEquals(202, it.status) }
        jdbc.sql("update accounts set purge_after = now() - interval '1 minute' where id = :id").param("id", w.xAcc).update()
        assertEquals(1, purge.purgeDue())
        val authorIds = Captured("db board.author_id", 200, jdbc.sql("select author_id from board_posts union all select author_id from board_comments").query(String::class.java).list().joinToString("\n"), emptyMap())
        assertEquals(setOf("tombstone"), labels(authorIds), "the private columns hold the account tombstone (the scan can see it) - and only they")
        assertEquals(0L, jdbc.sql("select count(*) from author_profiles where account_id = :id").param("id", w.xAcc).query(Long::class.java).single(), "the erasure removed the profile rows")
        assertEquals(setOf(AccountTombstone.of(w.xAcc)), jdbc.sql("select distinct author_id from board_posts where id in (:a, :b)").param("a", w.postA).param("b", w.postB).query(String::class.java).list().toSet(), "both profiles' rows share the tombstone in the private column")
        scanPhase("X erased", aDeleted = true, bDeleted = true)
    }
}
