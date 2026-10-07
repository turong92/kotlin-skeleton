package dev.sumin.skeleton.app.sample.upgrade

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient

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
        // 지금은 기준선 == 최신이라 0건 — 앞으로 새 V 파일이 생기면 여기서 그 파일들이 기존 데이터 위에서 돈다
        assertTrue(migrated.migrationsExecuted >= 0)
        val failed = jdbc.sql("select count(*) from flyway_schema_history where success = 0").query(Long::class.java).single()
        assertEquals(0L, failed)
        assertTrue(jdbc.sql("select count(*) from flyway_schema_history where success = 1").query(Long::class.java).single() >= atBaseline)

        UpgradeBaseline.TABLES.forEach { table ->
            assertTrue(jdbc.sql("select count(*) from $table").query(Long::class.java).single() >= 1, "$table lost its baseline row")
        }
        assertEquals("upgrade@example.com", jdbc.sql("select email from accounts where id = 'acc_upgrade'").query(String::class.java).single())
        assertEquals("Before the upgrade", jdbc.sql("select title from board_posts where board_code = 'upgrade'").query(String::class.java).single())
        assertEquals("a comment on the baseline schema", jdbc.sql("select body from board_comments where author_id = 'acc_upgrade'").query(String::class.java).single())
        assertEquals("v1", jdbc.sql("select version from legal_consents where subject_id = 'acc_upgrade'").query(String::class.java).single())
        assertEquals("{\"k\": \"v\"}".replace(" ", ""), jdbc.sql("select payload_json from notification_inbox where event_id = 'evt_upgrade'").query(String::class.java).single().replace(" ", ""))
    }
}
