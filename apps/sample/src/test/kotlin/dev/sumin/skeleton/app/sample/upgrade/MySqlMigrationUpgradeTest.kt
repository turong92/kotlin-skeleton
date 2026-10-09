package dev.sumin.skeleton.app.sample.upgrade

import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.PaymentAccountErasure
import dev.sumin.skeleton.payment.PaymentLedgerWriter
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.jdbc.JdbcPaymentLedgerStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager

/**
 * MySQL 쪽 업그레이드 시험: 기준선(`migrations.lock` 의 baseline)까지 migrate → 모든 모듈의 대표 행 → 최신까지 migrate → 행이 그대로.
 * 이 앱은 PostgreSQL 이라 MySQL 마이그레이션은 클래스패스가 아니라 레포의 `db/migration/mysql` 폴더들을 그대로 읽는다 (저장소 코드 단정은 PostgreSQL 쪽 시험이 한다).
 */
class MySqlMigrationUpgradeTest {
    private fun mysqlLocations(): List<String> =
        Files.walk(UpgradeBaseline.repoRoot).use { paths ->
            paths.filter { Files.isDirectory(it) && it.fileName.toString() == "mysql" && it.parent?.invariantSeparatorsPathString?.endsWith("src/main/resources/db/migration") == true }
                .filter { dir -> UpgradeBaseline.repoRoot.relativize(dir).none { it.toString() in setOf("build", "node_modules", ".claude", ".git") } }
                .map { "filesystem:" + it.toAbsolutePath() }.toList().sorted()
        }

    @Test
    fun `rows written on the baseline schema survive the migration to the latest`() {
        val db = SharedMySql.newDatabase()
        val dataSource = UpgradeBaseline.dataSource(db)
        val locations = mysqlLocations()
        assertTrue(locations.isNotEmpty(), "no db/migration/mysql folders found under ${UpgradeBaseline.repoRoot}")

        UpgradeBaseline.install(dataSource, locations)
        val jdbc = JdbcClient.create(dataSource)
        val atBaseline = jdbc.sql("select count(*) from flyway_schema_history where success = 1").query(Long::class.java).single()

        val migrated = Flyway.configure().dataSource(dataSource).locations(*locations.toTypedArray()).load().migrate()
        // 기준선 뒤의 새 V 파일(board_author_ref 등)이 기존 데이터 위에서 돈다 — 있으면 적어도 하나는 실행돼야 한다.
        // (찍은 프로젝트는 기준선 == 최신이라 기준선 뒤 파일이 없다 — 그때는 0 건이 맞다)
        val afterBaseline = locations.sumOf { loc ->
            Files.list(Path.of(loc.removePrefix("filesystem:"))).use { files ->
                files.filter { f -> Regex("""V(\d{14})__.*\.sql""").matchEntire(f.fileName.toString())?.groupValues?.get(1)?.let { it > UpgradeBaseline.baselineVersion } == true }.count()
            }
        }
        if (afterBaseline > 0) assertTrue(migrated.migrationsExecuted >= 1, "$afterBaseline migration file(s) come after the baseline but none ran")
        val failed = jdbc.sql("select count(*) from flyway_schema_history where success = 0").query(Long::class.java).single()
        assertEquals(0L, failed)
        assertTrue(jdbc.sql("select count(*) from flyway_schema_history where success = 1").query(Long::class.java).single() >= atBaseline)

        UpgradeBaseline.TABLES.forEach { table ->
            assertTrue(jdbc.sql("select count(*) from $table").query(Long::class.java).single() >= 1, "$table lost its baseline row")
        }
        assertEquals("upgrade@example.com", jdbc.sql("select email from accounts where id = 'acc_upgrade'").query(String::class.java).single())
        // 연락 이메일 열(V20261008053037): 기준선 행은 NULL, 올린 뒤 쓸 수 있다
        assertEquals(null, jdbc.sql("select contact_email from accounts where id = 'acc_upgrade'").query(String::class.java).optional().orElse(null))
        assertEquals(1, jdbc.sql("update accounts set contact_email = 'reach@example.com', contact_email_verified_at = now(6) where id = 'acc_upgrade'").update())
        // 로그인 아이디 열 · 표(V20261008173435): 기준선 계정은 아이디가 없다(NULL). 올린 뒤 정할 수 있고 같은 값은 둘이 못 가지며, 한 번 쓰인 아이디의 해시 표가 있다
        assertEquals(null, jdbc.sql("select login_id from accounts where id = 'acc_upgrade'").query(String::class.java).optional().orElse(null), "the baseline account has no login ID")
        assertEquals(1, jdbc.sql("update accounts set login_id = 'upgrade.user' where id = 'acc_upgrade'").update())
        jdbc.sql("insert into accounts (id, email, email_verified, status, created_at, updated_at) values ('acc_twin', 'twin@example.com', false, 'ACTIVE', '2026-10-01 00:00:00', '2026-10-01 00:00:00')").update()
        assertTrue(runCatching { jdbc.sql("update accounts set login_id = 'upgrade.user' where id = 'acc_twin'").update() }.isFailure, "two accounts cannot hold the same login ID")
        assertEquals(1, jdbc.sql("update accounts set login_id = 'Upgrade.User' where id = 'acc_twin'").update(), "the column compares binary (utf8mb4_bin): a different case is a different string here - the service lower-cases before it gets here")
        assertEquals(1, jdbc.sql("insert into account_used_login_ids (hash, used_at) values (:h, now(6))").param("h", "f".repeat(64)).update())
        assertEquals("Before the upgrade", jdbc.sql("select title from board_posts where board_code = 'upgrade'").query(String::class.java).single())
        assertEquals("a comment on the baseline schema", jdbc.sql("select body from board_comments where author_id = 'acc_upgrade'").query(String::class.java).single())
        assertEquals(0L, jdbc.sql("select count(*) from board_posts where author_id = 'acc_upgrade' and author_ref is not null").query(Long::class.java).single(), "baseline posts have no author_ref")
        assertEquals(0L, jdbc.sql("select count(*) from board_comments where author_id = 'acc_upgrade' and author_ref is not null").query(Long::class.java).single(), "baseline comments have no author_ref")
        ledgerErasureClearsTheCard(dataSource, jdbc)
        assertEquals("v1", jdbc.sql("select version from legal_consents where subject_id = 'acc_upgrade'").query(String::class.java).single())
        assertEquals("{\"k\": \"v\"}".replace(" ", ""), jdbc.sql("select payload_json from notification_inbox where event_id = 'evt_upgrade'").query(String::class.java).single().replace(" ", ""))
    }

    /**
     * 결제 장부 표(기준선 뒤의 `V20261009124513__payment_ledger`)가 기준선 데이터 위에서 올라왔다 — 기준선 계정이 산 결제 한 줄을 넣고, **진짜 저장소(`JdbcPaymentLedgerStore`)가 MySQL 위에서**
     * 탈퇴 고리(`PaymentAccountErasure`)를 돌려 카드 두 칸이 비는지 본다. 이 앱은 PostgreSQL 이라 MySQL 방언 모듈이 클래스패스에 없다 — 시각 바인딩만 같은 규칙(UTC `LocalDateTime`)으로 다시 쓴 시험용 방언을 건넨다.
     */
    private fun ledgerErasureClearsTheCard(dataSource: javax.sql.DataSource, jdbc: JdbcClient) {
        UpgradeBaseline.LEDGER_ROWS.forEach { jdbc.sql(it).update() }
        assertEquals(1L, jdbc.sql("select count(*) from payment_orders where account_id = 'acc_upgrade' and card_issuer = 'HYUNDAI' and card_last4 = '1234'").query(Long::class.java).single())
        val store = JdbcPaymentLedgerStore(NamedParameterJdbcTemplate(dataSource), DataSourceTransactionManager(dataSource), UtcMySqlDialect)
        val erasure = PaymentAccountErasure(store, PaymentLedgerWriter(TimeProvider.systemUtc()), PaymentProperties.Erasure.AccountIdMode.KEEP)
        repeat(2) { erasure.erase(ErasureRequest("acc_upgrade", AccountTombstone.of("acc_upgrade"))) }   // 두 번째는 아무것도 바꾸지 않는다
        assertEquals(0L, jdbc.sql("select count(*) from payment_orders where card_issuer is not null or card_last4 is not null").query(Long::class.java).single(), "the erasure hook cleared both card columns on MySQL")
        assertEquals(1L, jdbc.sql("select count(*) from payment_orders where id = 'ord_upgrade' and account_erased_at is not null and status = 'PAID'").query(Long::class.java).single(), "the order line stays, marked erased")
    }

    private object UtcMySqlDialect : SqlDialect {
        override val vendor: String = "mysql"
        override fun instantParam(value: java.time.Instant?): Any? = value?.let { java.time.LocalDateTime.ofInstant(it, java.time.ZoneOffset.UTC) }
        override fun readInstant(rs: java.sql.ResultSet, column: String): java.time.Instant? = rs.getObject(column, java.time.LocalDateTime::class.java)?.toInstant(java.time.ZoneOffset.UTC)
        // 이 시험이 도는 탈퇴 고리는 "있으면 무시" 삽입을 실행하지 않는다(수신함이 생성자에서 문장만 만들어 둔다 - 그래서 던지면 안 된다). 실행되면 진짜 MySQL 방언을 연결하라는 신호로 SQL 이 아닌 글자를 돌려준다
        override fun insertIgnore(table: String, columns: List<String>, conflictColumns: List<String>): String = "not sql: this test dialect does not implement insertIgnore"
    }
}
