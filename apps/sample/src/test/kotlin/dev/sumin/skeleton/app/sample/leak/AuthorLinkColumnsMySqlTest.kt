package dev.sumin.skeleton.app.sample.leak

import dev.sumin.skeleton.app.sample.upgrade.SharedMySql
import dev.sumin.skeleton.app.sample.upgrade.UpgradeBaseline
import java.nio.file.Files
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 스펙 §12 7 의 MySQL 쪽 — 이 레포의 `db/migration/mysql` 폴더들을 Flyway 로 새 MySQL 데이터베이스에 끝까지 적용하고 `information_schema` 의 열을
 * [AuthorLinkColumns] 의 공개 / 비공개 표와 맞춘다. 앱은 PostgreSQL 이라 MySQL 은 이 시험이 열 목록만 본다 (값 스캔은 PostgreSQL 쪽 통합 시험).
 */
class AuthorLinkColumnsMySqlTest {
    private fun mysqlLocations(): List<String> =
        Files.walk(UpgradeBaseline.repoRoot).use { paths ->
            paths.filter { Files.isDirectory(it) && it.fileName.toString() == "mysql" && it.parent?.invariantSeparatorsPathString?.endsWith("src/main/resources/db/migration") == true }
                .filter { dir -> UpgradeBaseline.repoRoot.relativize(dir).none { it.toString() in setOf("build", "node_modules", ".claude", ".git") } }
                .map { "filesystem:" + it.toAbsolutePath() }.toList().sorted()
        }

    @Test
    fun `every board, notification and author profile column of the latest MySQL schema is classified public or private`() {
        val dataSource = UpgradeBaseline.dataSource(SharedMySql.newDatabase())
        val locations = mysqlLocations()
        assertTrue(locations.isNotEmpty(), "no db/migration/mysql folders found under ${UpgradeBaseline.repoRoot}")
        Flyway.configure().dataSource(dataSource).locations(*locations.toTypedArray()).load().migrate()

        val actual = AuthorLinkColumns.columnsOf(JdbcClient.create(dataSource), "database()")
        assertTrue("author_profiles.account_id" in actual && "board_posts.author_ref" in actual && "notification_inbox.payload_json" in actual, "the query sees the tables: $actual")
        assertEquals(emptyList(), AuthorLinkColumns.problems(actual))
    }
}
