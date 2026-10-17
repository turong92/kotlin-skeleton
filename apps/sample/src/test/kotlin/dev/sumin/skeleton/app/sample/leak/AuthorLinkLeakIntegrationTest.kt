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
import org.junit.jupiter.api.BeforeAll
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
 * 계정 X 가 프로필 A · B · C 로 쓴 뒤 Y(로그인한 제3자) · Z(비로그인, `allow-anonymous-read=true`) · 운영자(MODERATOR)가 **각자** 읽을 수 있는 모든 경로를 훑어,
 * X 의 계정 id · 이메일 · 계정 톰스톤 · 계정 이름이 어디에도 없고 프로필 조각이 **값으로** 겹치지 않음을 — 상태를 바꿔 가며 — 확인한다.
 * 스캔이 값을 찾을 수 있다는 증명이 먼저다(1번 · 2번의 빈 허용 목록 대조, 그리고 `LeakScanTest`). 단계는 순서대로 상태를 바꾼다:
 * 기준선 → B 삭제 → X 정지 → (정지 해제 · 탈퇴 요청) 유예 중 → 지우기 → 둘째 계정 V 의 관리자 지우기 경로.
 *
 * 이 시험을 쓰는 앱: 이 클래스 · `leak-seeds.yml` 을 **자기 앱 시험으로 옮기고**, 앱이 더한 표 · 경로(`AuthorLinkColumns` 의 표, `LeakSweep` 의 읽기 경로)를 추가한다 —
 * 스켈레톤의 스캔은 샘플 앱의 경로만 안다.
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

    /** X 의 **옛 행**(공개 작성자 id 가 없던 때의 글) — 켜기 전부터 있던 행. 어디에서도 `pending:p<번호>` 로만 나가야 한다 */
    private var plantedPost = 0L

    @BeforeAll
    fun plantTheOldRow() {
        plantedPost = jdbc.sql(
            "insert into board_posts (board_code, author_id, title, body, status, created_at, updated_at) values ('general', :x, :t, 'written before the module', 'PUBLISHED', now(), now()) returning id",
        ).param("x", w.xAcc).param("t", "Old row ${w.nonce}").query(Long::class.java).single()
    }

    private val plantedOwner get() = "pending:p$plantedPost"

    private fun labels(vararg c: Captured, forbidden: Map<String, String> = w.forbidden) = LeakScan.forbiddenHits(c.toList(), forbidden).map { it.label }.toSet()

    private fun shown(post: Long, token: String? = w.yToken): Triple<String, Boolean, String?> =
        http.get("/api/v1/boards/general/posts/$post", token, record = false).ok().let { Triple(it.read("$.value.authorId"), it.read("$.value.authorDeleted"), it.read("$.value.authorName")) }

    private fun expectShown(phase: String, aDeleted: Boolean, bDeleted: Boolean, accountGone: Boolean) {
        for ((who, post, deleted) in listOf(Triple(w.a, w.postA, aDeleted), Triple(w.b, w.postB, bDeleted))) {
            for (token in listOf(w.yToken, null, w.modToken)) {
                val (id, gone, name) = shown(post, token)
                assertEquals(who.id, id, "$phase: the public author id is the profile id")
                assertEquals(deleted, gone, "$phase: authorDeleted of ${who.id}")
                assertEquals(if (deleted) null else who.name, name, "$phase: authorName of ${who.id}")
            }
        }
        assertNotEquals(shown(w.postA).first, shown(w.postB).first, "$phase: the two profiles never share an author id")
        // 옛 행: 계정 id 도 톰스톤도 아닌 pending 으로만 나간다 (탈퇴 뒤에도 — authorDeleted 만 true)
        assertEquals(Triple(plantedOwner, accountGone, null), shown(plantedPost), "$phase: the pre-module row reads as pending, never as the account")
    }

    private fun pairs(): List<Pair<String, String>> {
        val owners = listOf(w.a.id, w.b.id, w.c.id, plantedOwner)
        return owners.flatMapIndexed { i, p -> owners.drop(i + 1).map { p to it } }
    }

    private fun assertPairsClean(phase: String, pieces: LeakPieces, allow: LeakAllow) {
        pairs().forEach { (p, q) -> assertEquals(emptyList(), LeakScan.commonValues(pieces.of(p), pieces.of(q), allow), "$phase: values shared by $p and $q") }
    }

    /** 한 독자([reader])의 응답 전부에 금지 값이 없고, 작성자 조각끼리 허용 목록 밖의 공통 값이 없다 — 스캔이 비어 있지 않음도 함께 단정한다 */
    private fun assertNoLink(phase: String, reader: String, seen: List<Captured>, notices: Boolean) {
        val all = seen + if (notices) sweep.sseEventsForY() else emptyList()
        assertEquals(emptyList(), LeakScan.forbiddenHits(all, w.forbidden), "$phase / $reader: forbidden values in responses")
        val board = sweep.boardPieces(seen)
        listOf(w.a, w.b).forEach { assertEquals(setOf("post", "comment"), board.of(it.id).map { l -> l.kind }.toSet(), "$phase / $reader: ${it.id} must have a post and a comment piece, or the pair scan proves nothing") }
        assertTrue(board.of(w.c.id).isNotEmpty() && board.of(plantedOwner).isNotEmpty(), "$phase / $reader: C's comment and the pre-module row were scanned")
        assertEquals(2, w.keys.size)
        assertTrue(w.keys.all { k -> (board.of(w.a.id) + board.of(w.b.id)).any { it.value == k } }, "$phase / $reader: the attachment keys of A's and B's posts were scanned (opaque, so they share nothing)")
        assertPairsClean("$phase / $reader", board, sweep.allow)
        // 비회원 행의 공개 id 는 `guest:<ULID>` - 행마다 새 값이라 계정 · 프로필 어느 쪽과도 이어지지 않는다 (같은 비회원의 두 글도 이어지지 않는다)
        (board.owners()).forEach { assertTrue(it.startsWith("prf_") || it.startsWith("guest:") || it == plantedOwner, "$phase / $reader: author id $it is a profile id (or the planted pending row, or a guest id)") }
        assertTrue(all.none { "deleted:" in it.body }, "$phase / $reader: no tombstone value in any response")
        if (notices) {
            val notice = sweep.noticePieces(all)
            listOf(w.a, w.b, w.c).forEach { assertTrue(notice.of(it.id).isNotEmpty(), "$phase / $reader: the notice of ${it.id} was scanned") }
            assertTrue(sweep.sseEventsForY().size >= 3, "$phase / $reader: Y's SSE stream carried the notices of A, B and C")
            assertTrue(sweep.noticePieces(seen).let { n -> listOf(w.a, w.b, w.c).all { n.of(it.id).isNotEmpty() } }, "$phase / $reader: Y's inbox responses (not only SSE) carry them")
            assertPairsClean("$phase / $reader (inbox + SSE)", notice, sweep.allow)
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

    /** 한 단계: Y · Z · 운영자가 각자 읽고 (따로 스캔) 단정한다 */
    private fun scanPhase(phase: String, aDeleted: Boolean, bDeleted: Boolean, accountGone: Boolean = false, profileRows: Boolean = true) {
        val y = sweep.boardReads(w.yToken) + sweep.observerOnly() + sweep.reactionWrites() + sweep.valueSearches(w.yToken)
        val z = sweep.boardReads(null) + sweep.valueSearches(null)
        val mod = sweep.moderatorReads()
        // 운영자로 정말 읽었다: 임시저장 목록 · 숨김 · 복구가 200 이고 A 의 임시저장 글(제목 `A draft kiwi …`)이 A 의 조각에 들어 있다
        mod.filter { it.label.contains("status=DRAFT") || it.label.contains("/moderation") || it.label.endsWith("/posts/${w.postDraft}") }
            .also { assertTrue(it.size >= 6, "$phase: the moderator calls were made") }.forEach { assertEquals(200, it.status, "$phase: moderator ${it.label}") }
        assertTrue(sweep.boardPieces(mod).of(w.a.id).any { it.value == "A draft kiwi ${w.nonce}" }, "$phase: the moderator's reads carry A's draft in A's piece")
        z.filter { it.label.contains("/posts") && !it.label.contains("999999") && !it.label.contains("zzz") && !it.label.contains("mine=true") && !it.label.contains("q=${w.xAcc}") && !it.label.contains("q=${w.xEmail}") }
            .forEach { assertEquals(200, it.status, "$phase: Z reads ${it.label}") }
        assertEquals(401, z.first { it.label.endsWith("/api/v1/author-profiles") }.status, "$phase: Z has no profile list")
        for ((q, c) in listOf(y, z).flatMap { l -> l.filter { it.label.contains("q=${w.xAcc}") || it.label.contains("q=${w.xEmail}") } }.map { it.label to it }) {
            assertEquals(0, c.read<Number>("$.pagination.totalElements").toInt(), "$phase: searching the account id or email finds nothing ($q)")
        }
        assertNoLink(phase, "Y", y, notices = true)
        assertNoLink(phase, "Z", z, notices = false)
        assertNoLink(phase, "moderator", mod, notices = false)
        assertProbesIdentical()
        expectShown(phase, aDeleted, bDeleted, accountGone)
        assertPublicColumnsClean(phase, profileRows)
    }

    private class PublicColumns(val seen: List<Captured>, val board: LeakPieces, val notice: LeakPieces, val profiles: LeakPieces)

    /** 공개 열 전부의 값 (열마다 하나의 [Captured]) 과 작성자별 조각 — `author_ref` · 남의 편지함 payload · `author_profiles` 행 */
    private fun readPublicColumns(): PublicColumns {
        val seen = mutableListOf<Captured>()
        val board = LeakPieces()
        val notice = LeakPieces()
        val profiles = LeakPieces()
        AuthorLinkColumns.PUBLIC.forEach { (table, columns) ->
            val rows = jdbc.sql("select ${columns.joinToString()} from $table").query { rs, _ -> columns.associateWith { c -> rs.getObject(c).let { v -> if (v is Number || v is Boolean) v else v?.toString() } } }.list()
            columns.forEach { c -> seen += Captured("db $table.$c", 200, rows.joinToString("\n") { it[c]?.toString() ?: "" }, emptyMap()) }
            rows.forEach { r ->
                when (table) {
                    "board_posts", "board_comments" -> (r["author_ref"] as String?)?.let { board.addRow(it, if (table == "board_posts") "post" else "comment", r) }
                    "author_profiles" -> profiles.addRow(r["id"] as String, "profile", r)
                    "notification_inbox" -> JsonPath.parse(r["payload_json"] as String).json<Map<String, Any?>>().let { payload ->
                        notice.addNode(mapOf("authorId" to payload["authorId"]) + r.filterKeys { it != "payload_json" } + payload.filterKeys { it != "authorId" })
                    }
                }
            }
        }
        return PublicColumns(seen, board, notice, profiles)
    }

    /** 스펙 §12 7: 공개 열 전체에서 금지 값 · 짝 스캔 — author_ref · 남의 편지함 payload · 프로필 표(탈퇴 전)를 포함한다 */
    private fun assertPublicColumnsClean(phase: String, profileRows: Boolean) {
        val cols = readPublicColumns()
        val seen = cols.seen; val board = cols.board; val notice = cols.notice; val profiles = cols.profiles
        assertEquals(emptyList(), LeakScan.forbiddenHits(seen, w.forbidden), "$phase: forbidden values in public columns")
        listOf(w.a, w.b).forEach { assertEquals(setOf("post", "comment"), board.of(it.id).map { l -> l.kind }.toSet(), "$phase: ${it.id} has rows in the public columns") }
        assertTrue(board.of(w.c.id).isNotEmpty(), "$phase: C's comment row is in the public columns")
        listOf(w.a, w.b, w.c).forEach { assertTrue(notice.of(it.id).isNotEmpty(), "$phase: the notice of ${it.id} is in the public columns") }
        assertPairsClean("$phase (public columns)", board, sweep.allow)
        assertPairsClean("$phase (public columns, inbox)", notice, sweep.allow)
        if (profileRows) {
            listOf(w.a, w.b, w.c).forEach { assertTrue(profiles.of(it.id).isNotEmpty(), "$phase: the author_profiles row of ${it.id}") }
            assertPairsClean("$phase (author_profiles public columns)", profiles, sweep.allow.copy(strings = sweep.allow.strings + setOf("ACTIVE", "DELETED")))
        }
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
        assertTrue(plantedPost > 0, "an old row of X (no author_ref) exists")
    }

    @Test
    @Order(2)
    fun `baseline - nothing Y, Z or a moderator can read names X and no value ties the profiles together`() {
        scanPhase("baseline", aDeleted = false, bDeleted = false)
        // 양성 대조: 허용 목록을 비우면 같은 진짜 응답에서 스캔이 공통 값을 찾는다 (스캔이 눈이 멀지 않았다)
        val pieces = sweep.boardPieces(sweep.boardReads(w.yToken))
        val found = LeakScan.commonValues(pieces.of(w.a.id), pieces.of(w.b.id), LeakAllow(emptySet()))
        assertTrue("string general" in found && "string PUBLISHED" in found, "with an empty allow list the pair scan reports the board code and the status of the real responses: $found")
        assertTrue(found.any { it.startsWith("number comment ${w.yPost}") }, "... and the thread's post id: $found")
    }

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
    fun `during the deletion grace period the profiles still read as live ones`() {
        http.post("/api/v1/admin/accounts/${w.xAcc}/unsuspend", w.adminToken, "{}").also { assertEquals(204, it.status) }
        val token = w.relogin(w.xEmail)
        http.post("/api/v1/account/delete", token, """{"currentPassword":"password"}""").also { assertEquals(202, it.status) }
        scanPhase("X in grace", aDeleted = false, bDeleted = true)
    }

    @Test
    @Order(7)
    fun `after X is erased both profiles read as a deleted user's with different ids and no shared tombstone`() {
        jdbc.sql("update accounts set purge_after = now() - interval '2 days' where id = :id").param("id", w.xAcc).update()
        assertEquals(1, purge.purgeDue())
        val authorIds = Captured("db board.author_id", 200, jdbc.sql("select author_id from board_posts union all select author_id from board_comments").query(String::class.java).list().joinToString("\n"), emptyMap())
        assertEquals(setOf("tombstone"), labels(authorIds), "the private columns hold the account tombstone (the scan can see it) - and only they")
        assertEquals(0L, jdbc.sql("select count(*) from author_profiles where account_id = :id").param("id", w.xAcc).query(Long::class.java).single(), "the erasure removed the profile rows")
        assertEquals(setOf(AccountTombstone.of(w.xAcc)), jdbc.sql("select distinct author_id from board_posts where id in (:a, :b, :p)").param("a", w.postA).param("b", w.postB).param("p", plantedPost).query(String::class.java).list().toSet(), "A's, B's and the old row share the tombstone in the private column")
        scanPhase("X erased", aDeleted = true, bDeleted = true, accountGone = true, profileRows = false)
    }

    @Test
    @Order(8)
    fun `the other erase path - an administrator erases a suspended account - leaves no tie either`() {
        val (vToken, vAcc) = w.login("vera.leak@example.com")
        val v1 = w.profiles(vToken).first()   // 첫 프로필이 계정 이름(Vera)을 복사해 간 뒤에 계정 이름을 바꾼다 — 바뀐 이름이 금지 값이다
        val vName = "Vera${w.nonce}"
        http.patch("/api/v1/account/me", vToken, """{"displayName":"$vName"}""").ok()
        val v2 = w.newProfile(vToken, "Vee${w.nonce}")
        val p1 = w.createPost(vToken, v1, "V one ${w.nonce}", "as V1")
        val p2 = w.createPost(vToken, v2, "V two ${w.nonce}", "as V2")
        w.createComment(vToken, v1, w.yPost, "V1 comment ${w.nonce}")
        http.post("/api/v1/admin/accounts/$vAcc/suspend", w.adminToken, "{}").also { assertEquals(204, it.status) }
        http.post("/api/v1/admin/accounts/$vAcc/erase", w.adminToken, "{}").also { assertEquals(204, it.status) }
        val forbidden = mapOf("account id" to vAcc, "email" to "vera.leak@example.com", "tombstone" to AccountTombstone.of(vAcc), "account name" to vName)
        val reads = sweep.boardReads(w.yToken) + sweep.observerOnly() + sweep.boardReads(null) + sweep.moderatorReads()
        val all = reads + sweep.sseEventsForY()
        assertEquals(emptyList(), LeakScan.forbiddenHits(all, forbidden), "nothing of V in what readers get (board, inbox, SSE, probes)")
        val board = sweep.boardPieces(reads)
        assertTrue(board.of(v1.id).map { it.kind }.toSet() == setOf("post", "comment") && board.of(v2.id).isNotEmpty(), "V1's and V2's pieces were scanned")
        assertEquals(emptyList(), LeakScan.commonValues(board.of(v1.id), board.of(v2.id), sweep.allow), "no value ties V1 and V2")
        val cols = readPublicColumns()
        assertEquals(emptyList(), LeakScan.forbiddenHits(cols.seen, forbidden), "nothing of V in the public columns")
        assertEquals(emptyList(), LeakScan.commonValues(cols.board.of(v1.id), cols.board.of(v2.id), sweep.allow), "no value ties V1 and V2 in author_ref rows")
        assertTrue(cols.notice.of(v1.id).isNotEmpty(), "V1's notice is in Y's inbox rows")
        for (token in listOf(w.yToken, null)) {
            val s1 = http.get("/api/v1/boards/general/posts/$p1", token, record = false).ok()
            val s2 = http.get("/api/v1/boards/general/posts/$p2", token, record = false).ok()
            assertEquals(listOf(v1.id, v2.id), listOf(s1.read<String>("$.value.authorId"), s2.read<String>("$.value.authorId")))
            listOf(s1, s2).forEach { assertEquals(true, it.read<Boolean>("$.value.authorDeleted")); assertEquals(null, it.read<String?>("$.value.authorName")) }
        }
        assertEquals(0L, jdbc.sql("select count(*) from author_profiles where account_id = :id").param("id", vAcc).query(Long::class.java).single())
    }
}
