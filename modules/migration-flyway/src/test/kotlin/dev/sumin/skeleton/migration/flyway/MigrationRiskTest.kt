package dev.sumin.skeleton.migration.flyway

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 옛 버전을 깨는 마이그레이션 검사(`scripts/migration-risk.pl`, expand/contract) — docs/schema-management.md 「Rolling deploys」.
 * 임시 트리에서 규칙마다 확인하고, 마지막 테스트가 이 레포의 실제 마이그레이션을 지킨다. ./gradlew build 에서 돈다.
 */
class MigrationRiskTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    @TempDir lateinit var root: Path

    private class Result(val code: Int, val out: String)

    private fun risk(at: Path = root): Result {
        val p = ProcessBuilder(listOf("perl", repoRoot.resolve("scripts/migration-risk.pl").toString(), "--root", at.toString()))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "migration-risk.pl timed out" }
        return Result(p.exitValue(), out)
    }

    private fun lockWithBaseline(version: String = "20260101000000") =
        root.resolve("migrations.lock").writeText("baseline $version\n")

    private fun migration(sql: String, vendor: String = "postgresql", version: String = "20260201000000", name: String = "change"): String {
        val rel = "apps/x/src/main/resources/db/migration/$vendor/V${version}__$name.sql"
        root.resolve(rel).also { it.parent.createDirectories(); it.writeText(sql) }
        return rel
    }

    /** sql 한 줄을 검사해 걸린 규칙 이름들을 돌려준다 (없으면 빈 목록) */
    private fun rules(sql: String, vendor: String = "postgresql"): List<String> {
        lockWithBaseline()
        root.resolve("apps").toFile().deleteRecursively()   // 앞 호출이 남긴 다른 방언의 파일이 섞이지 않게
        migration(sql, vendor)
        val r = risk()
        if (r.code == 0) return emptyList()
        return Regex("""\[([a-z-]+)]""").findAll(r.out).map { it.groupValues[1] }.distinct().toList()
    }

    @Test
    fun `dangerous DDL is caught on postgresql`() {
        val cases = mapOf(
            "drop-table" to "drop table notes;",
            "drop-column" to "alter table notes drop column body;",
            "rename-column" to "alter table notes rename column body to text;",
            "rename-table" to "alter table notes rename to memos;",
            "add-not-null-column" to "alter table notes add column owner varchar(64) not null;",
            "set-not-null" to "alter table notes alter column body set not null;",
            "change-column-type" to "alter table notes alter column body type varchar(10);",
            "add-constraint" to "alter table notes add constraint uq unique (body);",
            "truncate" to "truncate table notes;",
        )
        for ((rule, sql) in cases) assertEquals(listOf(rule), rules(sql), sql)
    }

    @Test
    fun `dangerous DDL is caught on mysql`() {
        val cases = listOf(
            "drop-column" to "alter table notes drop column body;",
            "drop-column" to "alter table notes drop body;",
            "rename-table" to "rename table notes to memos;",
            "rename-column" to "alter table notes change body text varchar(255) null;",
            "change-column-type" to "alter table notes modify body varchar(10) null;",
            "add-not-null-column" to "alter table notes add column owner varchar(64) not null;",
        )
        for ((rule, sql) in cases) assertEquals(listOf(rule), rules(sql, "mysql"), sql)
    }

    @Test
    fun `expand-phase DDL passes`() {
        val safe = listOf(
            "create table memos (id bigint primary key, body text not null);",
            "alter table notes add column owner varchar(64);",
            "alter table notes add column owner varchar(64) not null default '';",
            "alter table notes add column n bigint generated always as identity not null;",
            "alter table notes add column if not exists n integer not null default 0, add column m text;",
            "create index idx_notes_body on notes (body);",
            "alter table notes drop constraint uq_old;",
            "drop index idx_old;",
            "alter table notes alter column body drop not null;",
            "alter table notes rename constraint a to b;",
            "-- drop table notes; 주석 안의 말\n/* alter table notes drop column body; */\nselect 'drop table notes;';",
            // 같은 파일에서 더한 (NULL 허용 · 기본값 없는) 컬럼의 UNIQUE 는 옛 버전이 쓰지 않아 NULL 뿐이다 — NULL 은 서로 다르다
            "alter table notes add column code varchar(10);\nalter table notes add constraint uq_code unique (owner, code);",
            "alter table notes add column code varchar(10);\ncreate unique index uq_code on notes (code);",
            // 같은 파일에서 새로 만든 표는 옛 버전이 모른다
            "create table memos (id bigint primary key);\nalter table memos add column owner varchar(64) not null;\nalter table memos add constraint uq unique (id);",
        )
        for (sql in safe) assertEquals(emptyList(), rules(sql), sql)
    }

    @Test
    fun `DDL that older-version code relies on is caught - unnamed unique index, drop default, dropped objects, enum value, schema move`() {
        val cases = listOf(
            "add-constraint" to "create unique index on notes (body);",
            "add-constraint" to "create unique index concurrently if not exists on notes (body);",
            "drop-default" to "alter table notes alter column body drop default;",
            "drop-object" to "drop view notes_v;",
            "drop-object" to "drop type note_kind;",
            "drop-object" to "drop sequence notes_seq;",
            "drop-object" to "drop function notes_fn();",
            "rename-enum-value" to "alter type note_kind rename value 'a' to 'b';",
            "set-schema" to "alter table notes set schema other;",
            "rename-table" to "alter view notes_v rename to memos_v;",
            // NULLS NOT DISTINCT 면 NULL 끼리도 중복이라 새 컬럼이어도 옛 버전의 쓰기가 깨진다
            "add-constraint" to "alter table notes add column code varchar(10);\nalter table notes add constraint uq unique nulls not distinct (code);",
        )
        for ((rule, sql) in cases) assertEquals(listOf(rule), rules(sql), sql)
        assertEquals(listOf("drop-default"), rules("alter table notes alter column body drop default;", "mysql"))
    }

    @Test
    fun `quotes and comments that hide later DDL do not hide it`() {
        val drop = "alter table notes drop column body;"
        // MySQL: # 주석 안의 ' · 백슬래시 이스케이프
        assertEquals(listOf("drop-column"), rules("# it's a note\n$drop", "mysql"))
        assertEquals(listOf("drop-column"), rules("select 'it\\'s';\n$drop", "mysql"))
        // PostgreSQL: E'..' 만 백슬래시 이스케이프, 일반 문자열 끝의 \ 는 이스케이프가 아니다, $tag$ 달러 인용
        assertEquals(listOf("drop-column"), rules("select E'it\\'s';\n$drop"))
        assertEquals(listOf("drop-column"), rules("select 'a\\';\n$drop"))
        assertEquals(listOf("drop-column"), rules("select \$q\$it's\$q\$;\n$drop"))
        // 달러 인용 안의 DDL 은 읽지 않는다(문서의 한계)
        assertEquals(emptyList(), rules("do \$\$ begin execute 'drop table notes'; end \$\$;"))
    }

    @Test
    fun `unique over a new column that has a default is still caught`() {
        assertEquals(
            listOf("add-constraint"),
            rules("alter table notes add column code int not null default 0;\nalter table notes add constraint uq_code unique (code);"),
        )
    }

    @Test
    fun `an alter table with several actions reports each risky one`() {
        assertEquals(
            listOf("drop-column", "add-not-null-column"),
            rules("alter table notes drop column a, add column b int not null, add column c int;"),
        )
    }

    @Test
    fun `an allow-destructive comment with a reason lets the file pass`() {
        lockWithBaseline()
        migration("-- allow-destructive: contract of notes.body, nothing reads it since v2.4\nalter table notes drop column body;")
        val r = risk()
        assertEquals(0, r.code, r.out)
    }

    @Test
    fun `an allow-destructive comment without a reason does not count`() {
        lockWithBaseline()
        migration("-- allow-destructive:\nalter table notes drop column body;")
        val r = risk()
        assertEquals(1, r.code, r.out)
        assertTrue("이유" in r.out, r.out)
    }

    @Test
    fun `only files newer than the baseline are looked at`() {
        lockWithBaseline("20260201000000")
        migration("drop table notes;", version = "20260201000000", name = "old")
        migration("drop table notes;", version = "20260101000000", name = "older")
        val r = risk()
        assertEquals(0, r.code, r.out)
        migration("drop table notes;", version = "20260301000000", name = "new")
        assertEquals(1, risk().code)
    }

    @Test
    fun `the failure names the file, line, rule and the safe way`() {
        lockWithBaseline()
        val rel = migration("create table t (id int);\n\nalter table notes drop column body;")
        val r = risk()
        assertEquals(1, r.code, r.out)
        assertTrue("$rel:3" in r.out, r.out)
        assertTrue("[drop-column]" in r.out && "allow-destructive" in r.out && "expand" in r.out, r.out)
        assertFalse("[truncate]" in r.out, r.out)
    }

    @Test
    fun `test resources and build output are not looked at`() {
        lockWithBaseline()
        root.resolve("apps/x/src/test/resources/db/migration/postgresql/V20260301000000__p.sql").also { it.parent.createDirectories(); it.writeText("drop table t;") }
        root.resolve("apps/x/build/resources/main/db/migration/postgresql/V20260301000000__c.sql").also { it.parent.createDirectories(); it.writeText("drop table t;") }
        assertEquals(0, risk().code)
    }

    @Test
    fun `this repository's new migrations are safe for the old version`() {
        val r = risk(at = repoRoot)
        assertEquals(0, r.code, r.out)
    }
}
