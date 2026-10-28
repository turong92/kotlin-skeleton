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
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
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
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("boardAuthorAdoptionListener") lateinit var adoption: dev.sumin.skeleton.common.author.AuthorAdoptionListener
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var erasures: List<AccountErasureListener>
    @Autowired lateinit var ledger: dev.sumin.skeleton.payment.PaymentLedgerStore
    @Autowired lateinit var plans: dev.sumin.skeleton.payment.SubscriptionPlans

    private val now = Instant.parse("2026-10-07T00:00:00Z")

    @Test
    fun `every baseline row is still there and flyway applied everything without a failure`() {
        UpgradeBaseline.TABLES.forEach { table ->
            assertTrue(jdbc.sql("select count(*) from $table").query(Long::class.java).single() >= 1, "$table lost its baseline row")
        }
        assertEquals(1L, jdbc.sql("select count(*) from notes where owner_id = 'acc_upgrade'").query(Long::class.java).single())
        assertEquals(0L, jdbc.sql("select count(*) from flyway_schema_history where not success").query(Long::class.java).single())
        // 기준선 이후의 마이그레이션도 전부 적용됐다 — 잠긴 postgresql 파일 중 **이 앱의 클래스패스에 실제로 보이는 것**은 전부. (샘플에 없는 저장 모듈의 파일 · apps/api 의 init 은 셈에 넣지 않는다 —
        // 샘플에 없는 저장 모듈이 생길 때마다 "잠금 수 - 1" 이 깨지던 것을 이름의 교집합으로 바꿨다.)
        val lockedNames = java.nio.file.Files.readAllLines(UpgradeBaseline.repoRoot.resolve("migrations.lock"))
            .filter { "/db/migration/postgresql/" in it }
            .map { it.substringBefore("  ").substringAfterLast('/') }
            .toSet()
        val onClasspath = org.springframework.core.io.support.PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/postgresql/V*.sql").mapNotNull { it.filename }.toSet()
        val expected = lockedNames.intersect(onClasspath).size
        assertTrue(expected > 0, "the sample sees locked migrations on its classpath")
        assertTrue(
            jdbc.sql("select count(*) from flyway_schema_history where success and version is not null").query(Long::class.java).single() >= expected,
            "every locked migration this app carries was applied ($expected)",
        )
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
    fun `board - the new guest, approval, revision and comment attachment columns read as everything off on baseline rows, and adoption never picks a guest row`() {
        val settings = jdbc.sql("select guest_write, post_approval, comment_approval, owner_moderates_comments from boards where code = 'upgrade'").query().singleRow()
        assertEquals(listOf("NONE", "OFF", "OFF", false), listOf(settings["guest_write"], settings["post_approval"], settings["comment_approval"], settings["owner_moderates_comments"]))
        val general = jdbc.sql("select guest_write, post_approval, comment_approval, owner_moderates_comments from boards where code = 'general'").query().singleRow()
        assertEquals(listOf("ALL", "GUESTS", "GUESTS", true), listOf(general["guest_write"], general["post_approval"], general["comment_approval"], general["owner_moderates_comments"]), "the sample's general board reads as upgraded (seed on a new DB, the sample migration on an old one)")
        val postId = jdbc.sql("select id from board_posts where board_code = 'upgrade'").query(Long::class.java).single()
        val post = assertNotNull(posts.find(postId))
        assertNull(post.guestName)
        assertNull(post.commentApproval)
        assertNull(post.moderatedBy)
        assertNull(posts.guestSecretHash(postId), "a baseline row has no guest secret")
        val commentId = jdbc.sql("select min(id) from board_comments where post_id = :p and author_id = 'acc_upgrade'").param("p", postId).query(Long::class.java).single()
        assertNull(assertNotNull(comments.find(commentId)).moderatedBy)
        // 옛 행 채우기(published_at)는 기준선이 그 V 파일보다 앞일 때만 일어난다 - 새 프로젝트로 찍으면 기준선이 최신으로 옮겨 가 열이 이미 있는 스키마에 행을 넣으므로 채울 것이 없다
        if (UpgradeBaseline.baselineVersion < "20261017100400") {
            assertTrue(assertNotNull(comments.find(commentId)).everPublished, "baseline comments were public - the published_at backfill keeps them in the thread")
        }
        assertEquals(emptyMap(), comments.attachments(listOf(commentId)))
        assertEquals(0L, jdbc.sql("select count(*) from board_revisions").query(Long::class.java).single())

        jdbc.sql("insert into boards (code, name, created_at) values ('upgrade-guests', 'Guests', now()) on conflict do nothing").update()   // 다른 시험이 'upgrade' 게시판의 글을 하나로 센다
        posts.insert(dev.sumin.skeleton.board.NewPost("upgrade-guests", "guest:01upgradeguest", "t", "b", dev.sumin.skeleton.board.PostStatus.PUBLISHED, emptyList(), now, null, "손님", "{bcrypt}x"))
        assertTrue(adoption.pendingAccounts(100_000).none { it.startsWith("guest:") }, "guest rows are not accounts")
    }

    @Test
    fun `notifications - the baseline inbox row is listed and can be marked read`() {
        val page = inbox.findByRecipient("acc_upgrade")
        assertEquals(listOf("evt_upgrade"), page.values.map { it.event.id })
        assertEquals(1, page.values.single().collapsedCount, "a row from before the link/collapse columns reads as one notification")
        assertNull(page.values.single().event.link)
        assertNull(page.values.single().event.collapseKey)
        assertNotNull(inbox.markRead("acc_upgrade", "evt_upgrade", now)?.readAt)
    }

    @Test
    fun `payment ledger and cheers - the new tables came up beside the baseline rows, and the erasure hooks clear the card and the cheer line`() {
        UpgradeBaseline.LEDGER_ROWS.forEach { jdbc.sql(it).update() }
        jdbc.sql("insert into cheers (order_id, account_id, amount, currency, status, created_at) values ('ord_upgrade', 'acc_upgrade', 3000, 'KRW', 'PAID', '2026-10-01 00:00:00')").update()
        fun count(sql: String) = jdbc.sql(sql).query(Long::class.java).single()
        assertEquals(1L, count("select count(*) from payment_orders where account_id = 'acc_upgrade' and card_issuer = 'HYUNDAI' and card_last4 = '1234'"))
        // 새 열(easy_pay_brand)은 옛 줄에서 비어 있고, 저장소가 그 줄을 그대로 읽는다
        assertEquals(1L, count("select count(*) from payment_orders where id = 'ord_upgrade' and easy_pay_brand is null"))
        assertNull(ledger.findOrder("ord_upgrade")!!.easyPayBrand)
        // the baseline rows of every other module are still there next to the new tables
        UpgradeBaseline.TABLES.forEach { table -> assertTrue(count("select count(*) from $table") >= 1, "$table lost its baseline row") }

        val request = ErasureRequest("acc_upgrade", AccountTombstone.of("acc_upgrade"))
        listOf("payment", "cheers").forEach { name -> erasures.single { it.name == name }.erase(request) }
        assertEquals(0L, count("select count(*) from payment_orders where card_issuer is not null or card_last4 is not null"), "the erasure hook cleared both card columns")
        assertEquals(1L, count("select count(*) from payment_orders where id = 'ord_upgrade' and account_erased_at is not null and status = 'PAID'"), "the order line stays, marked erased")
        assertEquals(0L, count("select count(*) from cheers where account_id = 'acc_upgrade'"), "the cheer line is deleted")
        // idempotent: a second run changes nothing and does not fail
        listOf("payment", "cheers").forEach { name -> erasures.single { it.name == name }.erase(request) }
        assertEquals(1L, count("select count(*) from payment_orders where id = 'ord_upgrade'"))
    }

    @Test
    fun `subscriptions - the new table came up beside the baseline rows and the real store keeps one live subscription per account`() {
        assertEquals(0L, jdbc.sql("select count(*) from payment_subscriptions").query(Long::class.java).single())
        UpgradeBaseline.TABLES.forEach { table -> assertTrue(jdbc.sql("select count(*) from $table").query(Long::class.java).single() >= 1, "$table lost its baseline row") }
        val now = java.time.Instant.parse("2026-10-09T00:00:00Z")
        fun sub(id: String, activeKey: String?) = dev.sumin.skeleton.payment.PaymentSubscription(
            id = id, accountId = "acc_upgrade", provider = "toss", providerMode = dev.sumin.skeleton.payment.PaymentMode.TEST, status = dev.sumin.skeleton.payment.SubscriptionStatus.PENDING,
            planCode = "basic", amount = dev.sumin.skeleton.payment.PaymentAmount(3900, "KRW"), period = "P1M", anchorAt = null, periodSeq = 0, currentPeriodStart = null, currentPeriodEnd = null,
            chargeOrderId = null, attempt = 1, customerKey = "cus_$id", billingToken = null, cardIssuer = null, cardLast4 = null, activeKey = activeKey, pendingPlanCode = null, pendingAmount = null,
            endReason = null, endedAt = null, accountErasedAt = null, createdAt = now, updatedAt = now,
        )
        assertTrue(ledger.inTransaction { it.insertSubscriptionIfAbsent(sub("sub_upgrade_1", null)) })
        assertTrue(ledger.inTransaction { it.insertSubscriptionIfAbsent(sub("sub_upgrade_2", null)) })
        ledger.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription("sub_upgrade_1")).copy(activeKey = "acc_upgrade")) }
        assertTrue(runCatching { ledger.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription("sub_upgrade_2")).copy(activeKey = "acc_upgrade")) } }.isFailure, "a second live subscription for the account is refused")
        assertEquals("sub_upgrade_1", ledger.findLiveSubscription("acc_upgrade")?.id)
    }

    @Test
    fun `catalog - the seeded table came up on the baseline schema and the plan code of a subscription row from the code-plan days still finds its price`() {
        val plan = checkNotNull(plans.find("basic")) { "the monthly price code is the old plan code" }
        assertEquals(listOf(3_900L, "KRW", "P1M", 1, true), listOf(plan.amount.amount, plan.amount.currency, plan.period, plan.level, plan.purchasable))
        assertEquals(12L, jdbc.sql("select count(*) from catalog_prices").query(Long::class.java).single(), "three products x month and year x KRW and USD")
        assertEquals(3L, jdbc.sql("select count(*) from catalog_products").query(Long::class.java).single())
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
