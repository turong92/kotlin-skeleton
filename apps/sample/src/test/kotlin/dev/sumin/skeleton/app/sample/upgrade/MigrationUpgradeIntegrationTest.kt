package dev.sumin.skeleton.app.sample.upgrade

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.auth.sessions.SessionStore
import dev.sumin.skeleton.board.CommentRepository
import dev.sumin.skeleton.board.NewComment
import dev.sumin.skeleton.board.PostRepository
import dev.sumin.skeleton.board.ReactionRepository
import dev.sumin.skeleton.board.ReactionTarget
import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.NewConsentEvent
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.notification.NotificationInboxRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 업그레이드 시험 (PostgreSQL, 모든 모듈의 마이그레이션이 한 DB 에): 기준선 스키마(`migrations.lock` 의 baseline)에 모듈별 대표 행을 넣고 →
 * 앱이 뜨며(= Flyway 가 최신까지 migrate) → 행이 살아 있고 모듈의 저장소 코드가 그 행을 읽고 쓴다.
 * 지금은 기준선 == 최신이라 두 번째 migrate 가 0건이어도 통과한다. 새 V 파일이 더해지면 자동으로 이 데이터 위에서 검증된다 (docs/schema-management.md).
 */
@SpringBootTest(properties = ["skeleton.job-queue.poll-interval=200ms", "spring.config.import=classpath:test-seeds.yml"])
@Import(MigrationUpgradeIntegrationTest.BaselineDatabase::class, SampleIntegrationTest.FakeStorageConfiguration::class)
class MigrationUpgradeIntegrationTest {
    /** 컨텍스트가 쓰는 DB 를 기준선까지만 올려 행을 넣어 둔다 — 컨텍스트가 뜰 때 앱의 Flyway 가 나머지를 migrate 한다 */
    @TestConfiguration(proxyBeanMethods = false)
    class BaselineDatabase {
        @Bean
        fun jdbcConnectionDetails(): JdbcConnectionDetails {
            val db = dev.sumin.skeleton.app.sample.SharedPostgres.newDatabase()
            UpgradeBaseline.install(UpgradeBaseline.dataSource(db), listOf("classpath:db/migration/postgresql"), UpgradeBaseline.SAMPLE_ROWS)
            return db
        }
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var sessions: SessionStore
    @Autowired lateinit var consents: ConsentStore
    @Autowired lateinit var posts: PostRepository
    @Autowired lateinit var comments: CommentRepository
    @Autowired lateinit var reactions: ReactionRepository
    @Autowired lateinit var inbox: NotificationInboxRepository

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
}
