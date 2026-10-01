package dev.sumin.skeleton.migration.flyway

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MigrationFileRulesTest {
    private fun repo(vararg files: String): Path {
        val root = Files.createTempDirectory("repo")
        files.forEach { root.resolve(it).also { f -> f.parent.createDirectories(); f.writeText("select 1;") } }
        return root
    }

    @Test
    fun `clean repository has no violations`() {
        val root = repo(
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "modules/a/src/main/resources/db/migration/mysql/V20261001000000__a.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000100__b.sql",
        )
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }

    @Test
    fun `bad names, missing vendor folder, impossible timestamp`() {
        val root = repo(
            "apps/api/src/main/resources/db/migration/postgresql/V25__x.sql",
            "apps/api/src/main/resources/db/migration/V20261001000000__no_vendor.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261399000000__bad_month.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__Camel.sql",
        )
        val v = MigrationFileRules.violations(root).joinToString("\n")
        assertTrue("V25__x.sql" in v, v)
        assertTrue("V20261001000000__no_vendor.sql" in v && "<vendor>" in v, v)
        assertTrue("V20261399000000__bad_month.sql" in v, v)
        assertTrue("V20261001000000__Camel.sql" in v, v)
    }

    @Test
    fun `same version twice in one vendor across modules fails`() {
        val root = repo(
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__b.sql",
        )
        assertTrue(MigrationFileRules.violations(root).single().contains("duplicate version 20261001000000"))
    }

    @Test
    fun `module with both vendors must pair every migration`() {
        val root = repo(
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "modules/a/src/main/resources/db/migration/mysql/V20261001000000__a.sql",
            "modules/a/src/main/resources/db/migration/postgresql/V20261001000100__only_pg.sql",
        )
        assertTrue(MigrationFileRules.violations(root).single().contains("only_pg"))
    }

    @Test
    fun `build output and non-sql files are ignored`() {
        val root = repo(
            "modules/a/build/resources/main/db/migration/postgresql/V1__copied.sql",
            "modules/a/src/main/resources/db/migration/postgresql/README.md",
        )
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }
}
