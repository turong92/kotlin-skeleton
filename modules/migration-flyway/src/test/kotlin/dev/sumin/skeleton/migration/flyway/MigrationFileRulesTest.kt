package dev.sumin.skeleton.migration.flyway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
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

    @Test
    fun `nested worktree or repository with its own git entry is not walked`() {
        val root = repo(
            ".git",
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            // 레포 안에 둔 다른 워크트리 (.git 파일) — 같은 마이그레이션 사본 + 옛 규칙 파일
            "other-checkout/.git",
            "other-checkout/apps/api/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "other-checkout/apps/api/src/main/resources/db/migration/V1__init.sql",
        )
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }

    @Test
    fun `claude worktrees folder is not walked`() {
        val root = repo(
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            ".claude/worktrees/x/apps/api/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            ".claude/worktrees/x/apps/api/src/main/resources/db/migration/V1__init.sql",
        )
        assertEquals(emptyList(), MigrationFileRules.violations(root))
    }

    @Test
    fun `skipped directories are not descended`() {
        val root = repo(
            "apps/api/src/main/resources/db/migration/postgresql/V20261001000000__a.sql",
            "apps/web/node_modules/pkg/index.js",
        )
        // node_modules 안의 읽을 수 없는 폴더 — node_modules 로 들어가 훑으면 AccessDeniedException 으로 깨진다
        val inside = root.resolve("apps/web/node_modules/pkg")
        inside.setPosixFilePermissions(PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(inside), "root 로 돌면 권한이 안 막혀 이 테스트가 아무것도 증명하지 못한다")
            assertEquals(emptyList(), MigrationFileRules.violations(root))
        } finally {
            inside.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `mysql inline index, foreign key and collate sit inside jooq ignore markers`() {
        val root = Files.createTempDirectory("repo")
        fun mysql(name: String, sql: String) = root.resolve("modules/a/src/main/resources/db/migration/mysql/$name").also { it.parent.createDirectories(); it.writeText(sql) }
        mysql("V20261001000001__bare_index.sql", "create table t (id int primary key,\n  a int,\n  index idx_t_a (a)\n);")
        mysql("V20261001000002__bare_fk.sql", "create table t (id int primary key, a int,\n  foreign key (a) references u (id)\n);")
        mysql("V20261001000003__bare_collate.sql", "create table t (id int primary key, a varchar(9) character set utf8mb4 collate utf8mb4_bin);")
        mysql("V20261001000004__marked.sql", "create table t (id int primary key, a varchar(9) /* [jooq ignore start] */ collate utf8mb4_bin /* [jooq ignore stop] */\n  /* [jooq ignore start] */,\n  index idx_t_a (a),\n  foreign key (a) references u (id)\n  /* [jooq ignore stop] */\n);")
        mysql("V20261001000005__commented.sql", "-- index idx_x (a) is in a comment, collate too\ncreate table t (id int primary key);")
        val v = MigrationFileRules.violations(root).joinToString("\n")
        assertTrue("bare_index" in v, v)
        assertTrue("bare_fk" in v, v)
        assertTrue("bare_collate" in v, v)
        assertTrue("marked" !in v, v)
        assertTrue("commented" !in v, v)
    }
}
