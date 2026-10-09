package dev.sumin.skeleton.app.sample.upgrade

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.auth.sessions.SessionStore
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.board.ReactionTarget
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.NewConsentEvent
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.notification.NotificationInboxRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 업그레이드 시험 (PostgreSQL, 모든 모듈의 마이그레이션이 한 DB 에): 기준선 스키마(`migrations.lock` 의 baseline)에 모듈별 대표 행을 넣고 →
 * 앱이 뜨며(= Flyway 가 최신까지 migrate) → 행이 살아 있고 모듈의 저장소 코드가 그 행을 읽고 쓴다.
 * 기준선 뒤에 더해진 V 파일(예: board_author_ref · author_profiles)은 이 데이터 위에서 돈다 — 새 V 파일이 생기면 자동으로 검증된다 (docs/schema-management.md).
 * 앱은 작성자 프로필을 켠 채 뜨므로 기동 뒤에는 옛 글이 첫 프로필로 넘겨받혀 이름이 보인다.
 */
@SpringBootTest(properties = ["skeleton.job-queue.poll-interval=200ms", "spring.config.import=classpath:test-seeds.yml"])
@AutoConfigureMockMvc
@Import(MigrationUpgradeIntegrationTest.BaselineDatabase::class, SampleIntegrationTest.FakeStorageConfiguration::class)
class MigrationUpgradeIntegrationTest {
    /** 컨텍스트가 쓰는 DB 를 기준선까지만 올려 행을 넣어 둔다 — 컨텍스트가 뜰 때 앱의 Flyway 가 나머지를 migrate 한다 */
    @TestConfiguration(proxyBeanMethods = false)
    class BaselineDatabase {
        @Bean
        fun jdbcConnectionDetails(): JdbcConnectionDetails {
            val db = dev.sumin.skeleton.app.sample.SharedPostgres.newDatabase()
            UpgradeBaseline.install(UpgradeBaseline.dataSource(db), listOf("classpath:db/migration/postgresql"), UpgradeBaseline.SAMPLE_ROWS + legacyAccountRows())
            return db
        }
    }

    companion object {
        const val LEGACY_PASSWORD = "legacy-pass-7-moon"

        /**
         * 로그인 아이디가 생기기 전부터 있던 계정 — 이메일이 확인된 평범한 가입자(비밀번호를 안다). 기준선 스키마(`login_id` 열이 없다)에 넣고, 앱이 뜨며 Flyway 가 새 V 파일을 이 위에서 돌린다.
         * (`acc_upgrade` 는 다른 시험이 이메일 확인을 바꾸므로 따로 둔다.)
         */
        fun legacyAccountRows(): List<String> = listOf(
            "insert into accounts (id, email, email_verified, status, display_name, display_name_key, display_tag, locale, time_zone, created_at, updated_at) " +
                "values ('acc_legacy', 'legacy@example.com', true, 'ACTIVE', 'Legacy', 'legacy', '0000', 'ko', 'Asia/Seoul', '2026-10-01 00:00:00', '2026-10-01 00:00:00')",
            "insert into account_identities (id, account_id, method, subject, verified, secret, created_at) " +
                "values ('idn_legacy', 'acc_legacy', 'password', 'legacy@example.com', true, '{bcrypt}" + org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(LEGACY_PASSWORD) + "', '2026-10-01 00:00:00')",
        )
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var sessions: SessionStore
    @Autowired lateinit var consents: ConsentStore
    @Autowired lateinit var posts: PostRepository
    @Autowired lateinit var comments: CommentRepository
    @Autowired lateinit var reactions: ReactionRepository
    @Autowired lateinit var inbox: NotificationInboxRepository
    @Autowired lateinit var authors: AuthorDirectory
    @Autowired lateinit var mockMvc: MockMvc

    private val now = Instant.parse("2026-10-07T00:00:00Z")

    @Test
    fun `every baseline row is still there and flyway applied everything without a failure`() {
        UpgradeBaseline.TABLES.forEach { table ->
            assertTrue(jdbc.sql("select count(*) from $table").query(Long::class.java).single() >= 1, "$table lost its baseline row")
        }
        assertEquals(1L, jdbc.sql("select count(*) from notes where owner_id = 'acc_upgrade'").query(Long::class.java).single())
        assertEquals(0L, jdbc.sql("select count(*) from flyway_schema_history where not success").query(Long::class.java).single())
        // 기준선 이후의 마이그레이션도 전부 적용됐다 (잠금에 있는 postgresql 파일 수 이상)
        val locked = java.nio.file.Files.readAllLines(UpgradeBaseline.repoRoot.resolve("migrations.lock")).count { "/db/migration/postgresql/" in it }
        assertTrue(jdbc.sql("select count(*) from flyway_schema_history where success and version is not null").query(Long::class.java).single() >= locked - 1 /* apps/api 의 init 은 샘플 클래스패스에 없다 */)
    }

    @Test
    fun `accounts - the repository reads the baseline account and writes it`() {
        val byEmail = assertNotNull(accounts.findByEmail("upgrade@example.com"))
        assertEquals("acc_upgrade", byEmail.id)
        assertEquals("Upgrader", byEmail.displayName)
        assertTrue("ADMIN" in byEmail.roles)
        assertEquals("acc_upgrade", accounts.findByIdentity("password", "upgrade@example.com")?.id)
        assertTrue(!byEmail.emailVerified)
        assertTrue(accounts.markEmailVerified("acc_upgrade", now))
        assertTrue(assertNotNull(accounts.findById("acc_upgrade")).emailVerified)
        // 연락 이메일 열(V20261008053037): 기준선 행은 NULL 로 읽히고, 올린 뒤 쓰고 · 지울 수 있다
        assertNull(byEmail.contactEmail)
        accounts.update("acc_upgrade", dev.sumin.skeleton.account.AccountPatch(contactEmail = "reach@example.com"), now)
        assertEquals("reach@example.com", assertNotNull(accounts.findById("acc_upgrade")).contactEmail)
        accounts.update("acc_upgrade", dev.sumin.skeleton.account.AccountPatch(clearContactEmail = true), now)
        assertNull(assertNotNull(accounts.findById("acc_upgrade")).contactEmail)
    }

    @Test
    fun `sessions - the baseline session and its refresh token are readable and usable`() {
        val session = assertNotNull(sessions.find("ses_upgrade"))
        assertEquals("acc_upgrade", session.accountId)
        val hash = "d".repeat(64)
        assertEquals(null, assertNotNull(sessions.findToken(hash)).usedAt)
        assertTrue(sessions.markTokenUsed(hash, now))
        sessions.addToken("ses_upgrade", "f".repeat(64), now)
        assertNotNull(sessions.findToken("f".repeat(64)))
    }

    @Test
    fun `legal - the baseline consent is the latest and a later event appends`() {
        val subject = Subject.account("acc_upgrade")
        assertEquals("v1", consents.latest(subject, listOf("terms"), null)["terms"]?.version)
        assertTrue(consents.append(NewConsentEvent(subject, "terms", "v1", "e".repeat(64), "ko", ConsentAction.WITHDRAWN, "withdraw", null, 2, null, null, now)))
        assertEquals(ConsentAction.WITHDRAWN, consents.latest(subject, listOf("terms"), null)["terms"]?.action)
        assertEquals(2L, consents.history(subject, 0, 10).total)
    }

    @Test
    fun `board - the baseline post is readable, takes a new comment and keeps its reaction`() {
        val postId = jdbc.sql("select id from board_posts where board_code = 'upgrade'").query(Long::class.java).single()
        assertEquals("Before the upgrade", assertNotNull(posts.find(postId)).title)
        // 마이그레이션이 만든 author_ref 는 비어 있었지만(MySqlMigrationUpgradeTest 가 그 순간을 본다) 이 앱은 author-profile 을 켜고 떴다 —
        // 기동 실행기(adopt-on-startup)가 옛 글 · 댓글을 계정의 첫 프로필로 넘겨받았고, 그 프로필이 계정 이름을 한 번 복사해 갖는다 (스펙 §7.4 · §12 조립 줄)
        val ref = assertNotNull(assertNotNull(posts.find(postId)).authorRef, "adopt-on-startup gave the baseline post a first profile")
        assertTrue(ref.startsWith("prf_"), ref)
        val card = assertNotNull(authors.resolve(listOf(ref), AuthorContext("board", "upgrade"))[ref], "the old post shows under the first profile")
        assertEquals("Upgrader", card.name, "the first profile copied the account name")
        assertTrue(Regex("[0-9]{4}").matches(card.tag.orEmpty()) && !card.deleted, card.toString())   // 기준선 꼬리표 0000 은 자리표시라 프로필이 새로 뽑는다 — 값은 정하지 않는다
        // 읽기 경로(HTTP)에서도 같다: 옛 글이 첫 프로필 이름으로 보이고 authorId 는 프로필 id 이며 계정 id 가 아니다
        val token = com.jayway.jsonpath.JsonPath.read<String>(
            mockMvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = """{"email":"user@example.com","password":"password"}""" }
                .andReturn().response.contentAsString, "$.value.accessToken",
        )
        mockMvc.get("/api/v1/boards/upgrade/posts/$postId") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.value.authorName") { value("Upgrader") }
            jsonPath("$.value.authorId") { value(ref) }
            jsonPath("$.value.mine") { value(false) }
        }
        val baselineComment = jdbc.sql("select id from board_comments where post_id = :p and author_id = 'acc_upgrade'").param("p", postId).query(Long::class.java).single()
        assertEquals(ref, assertNotNull(comments.find(baselineComment)).authorRef, "the baseline comment went to the same first profile")
        val added = assertNotNull(comments.insert(NewComment(postId, null, null, 0, "acc_upgrade", "after the upgrade", now)))
        assertEquals("after the upgrade", assertNotNull(comments.find(added.id)).body)
        assertEquals(mapOf("LIKE" to 1L), reactions.counts(ReactionTarget.POST, listOf(postId))[postId])
    }

    @Test
    fun `notifications - the baseline inbox row is listed and can be marked read`() {
        val page = inbox.findByRecipient("acc_upgrade")
        assertEquals(listOf("evt_upgrade"), page.values.map { it.event.id })
        assertNotNull(inbox.markRead("acc_upgrade", "evt_upgrade", now)?.readAt)
    }

    @Test
    fun `login ID - an account from before the feature has none, still signs in by email, meets the gate on its next call and is released by choosing an ID`() {
        // 새 V 파일(account_login_id)이 데이터가 있는 DB 에 적용됐다: 기존 계정의 열은 비어 있다
        assertNull(jdbc.sql("select login_id from accounts where id = 'acc_legacy'").query(String::class.java).optional().orElse(null), "the existing account has no login ID")
        assertNull(jdbc.sql("select login_id from accounts where id = 'acc_upgrade'").query(String::class.java).optional().orElse(null))
        assertNull(assertNotNull(accounts.findById("acc_legacy")).loginId)

        // 이메일로 로그인된다 (아이디가 없어도 로그인은 막히지 않는다)
        val login = mockMvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = """{"email":"legacy@example.com","password":"$LEGACY_PASSWORD"}""" }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        val token = com.jayway.jsonpath.JsonPath.read<String>(login, "$.value.accessToken")
        assertEquals("acc_legacy", com.jayway.jsonpath.JsonPath.read<String>(login, "$.value.principal.accountId"))

        // 다음 호출에서 아이디 게이트를 만난다 — 읽기도. 허용 목록(me)은 열려 있고 아이디가 없다고 알려 준다
        mockMvc.get("/api/v1/notes") { header("Authorization", "Bearer $token") }.andExpect { status { isForbidden() }; jsonPath("$.code") { value("ACCOUNT.LOGIN_ID_REQUIRED") } }
        mockMvc.get("/api/v1/boards/upgrade/posts") { header("Authorization", "Bearer $token") }.andExpect { status { isForbidden() }; jsonPath("$.code") { value("ACCOUNT.LOGIN_ID_REQUIRED") } }
        mockMvc.get("/api/v1/account/me") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() }; jsonPath("$.value.loginId") { value(null as String?) }; jsonPath("$.value.email") { value("legacy@example.com") } }

        // 아이디를 정하면 풀린다 — 이메일은 이미 있고(쓰기 때 방식도 통과), 이 계정은 이제 아이디로도 로그인된다
        mockMvc.post("/api/v1/account/login-id") { with { it.remoteAddr = dev.sumin.skeleton.app.sample.TestClientIps.fresh(); it }; contentType = MediaType.APPLICATION_JSON; content = """{"loginId":"legacy.user"}"""; header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() }; jsonPath("$.value.loginId") { value("legacy.user") } }
        mockMvc.get("/api/v1/notes") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/boards/upgrade/posts") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        val byId = mockMvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = """{"username":"Legacy.User","password":"$LEGACY_PASSWORD"}""" }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals("acc_legacy", com.jayway.jsonpath.JsonPath.read<String>(byId, "$.value.principal.accountId"))
        assertEquals("legacy.user", jdbc.sql("select login_id from accounts where id = 'acc_legacy'").query(String::class.java).single())
        // 정한 아이디는 해시로도 남는다 (탈퇴해도 다시 쓸 수 없게) — 표는 마이그레이션이 만들었다
        assertTrue(jdbc.sql("select count(*) from account_used_login_ids").query(Long::class.java).single() >= 1)
    }
}
