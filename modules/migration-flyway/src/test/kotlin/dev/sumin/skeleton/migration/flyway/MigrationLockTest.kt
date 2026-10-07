package dev.sumin.skeleton.migration.flyway

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 동결된 마이그레이션 잠금(`migrations.lock`, 도구 `scripts/migrations-lock.pl`) — docs/schema-management.md 「When you may edit a migration」.
 * 앞의 테스트들은 스크립트의 동작을 임시 트리에서 확인하고, 마지막 테스트가 이 레포의 실제 잠금을 지킨다. ./gradlew build 에서 돈다.
 */
class MigrationLockTest {
    private val repoRoot: Path = Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    @TempDir lateinit var root: Path

    private class Result(val code: Int, val out: String)

    private fun lock(vararg args: String, at: Path = root): Result {
        val p = ProcessBuilder(listOf("perl", repoRoot.resolve("scripts/migrations-lock.pl").toString(), "--root", at.toString()) + args)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "migrations-lock.pl timed out" }
        return Result(p.exitValue(), out)
    }

    private fun migration(rel: String, sql: String = "create table t (id int);\n"): Path =
        root.resolve(rel).also { it.parent.createDirectories(); it.writeText(sql) }

    private val a = "modules/a-jdbc/src/main/resources/db/migration/postgresql/V20260101000000__a.sql"
    private val b = "modules/b-jdbc/src/main/resources/db/migration/postgresql/V20260201000000__b.sql"

    /** a · b 두 파일을 잠근 상태로 시작한다 */
    private fun locked() {
        migration(a); migration(b)
        assertEquals(0, lock("--regenerate").code)
        assertEquals(0, lock("--check").code)
    }

    @Test
    fun `regenerate writes sorted path-hash lines and a baseline at the newest version`() {
        migration(b); migration(a)
        assertEquals(0, lock("--regenerate").code)
        val lines = root.resolve("migrations.lock").readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("baseline 20260201000000", lines.first())
        val entries = lines.drop(1)
        assertEquals(listOf(a, b), entries.map { it.substringBefore("  ") })
        assertTrue(entries.all { Regex("^[0-9a-f]{64}$").matches(it.substringAfter("  ")) }, entries.toString())
    }

    @Test
    fun `an edited locked migration fails the check and says to add a new V file`() {
        locked()
        root.resolve(a).writeText("create table t (id int, extra int);\n")
        val r = lock("--check")
        assertEquals(1, r.code, r.out)
        assertTrue(a in r.out && "새 V 파일" in r.out && "--rewrite $a" in r.out, r.out)
    }

    @Test
    fun `a deleted locked migration fails the check`() {
        locked()
        Files.delete(root.resolve(b))
        val r = lock("--check")
        assertEquals(1, r.code, r.out)
        assertTrue(b in r.out && "사라" in r.out && "새 V 파일" in r.out, r.out)
    }

    @Test
    fun `a new migration newer than every locked one fails until the lock adds it - one command`() {
        locked()
        val c = "modules/c-jdbc/src/main/resources/db/migration/postgresql/V20260301000000__c.sql"
        migration(c)
        val failed = lock("--check")
        assertEquals(1, failed.code, failed.out)
        assertTrue(c in failed.out && "perl scripts/migrations-lock.pl" in failed.out, failed.out)

        assertEquals(0, lock().code)
        assertEquals(0, lock("--check").code)
        assertTrue(c in root.resolve("migrations.lock").readText())
        assertTrue("baseline 20260201000000" in root.resolve("migrations.lock").readText(), "baseline does not move when files are added")
    }

    @Test
    fun `a new migration between locked versions is out of order and the update refuses it`() {
        locked()
        val mid = "modules/c-jdbc/src/main/resources/db/migration/postgresql/V20260115000000__mid.sql"
        migration(mid)
        val failed = lock("--check")
        assertEquals(1, failed.code, failed.out)
        assertTrue("out-of-order" in failed.out && mid in failed.out && "newMigration" in failed.out, failed.out)

        val update = lock()
        assertEquals(1, update.code, update.out)
        assertFalse(mid in root.resolve("migrations.lock").readText())
    }

    @Test
    fun `the update never accepts a changed hash - only an explicit rewrite does`() {
        locked()
        root.resolve(a).writeText("create table t (id bigint);\n")
        val update = lock()
        assertEquals(1, update.code, update.out)
        assertEquals(1, lock("--check").code)

        val rewrite = lock("--rewrite", a)
        assertEquals(0, rewrite.code, rewrite.out)
        assertEquals(0, lock("--check").code)
    }

    @Test
    fun `rewrite of a deleted file drops its line and rewrite of an untouched file is refused`() {
        locked()
        Files.delete(root.resolve(b))
        assertEquals(0, lock("--rewrite", b).code)
        assertEquals(0, lock("--check").code)
        assertFalse(b in root.resolve("migrations.lock").readText())

        val r = lock("--rewrite", a)
        assertEquals(1, r.code, r.out)
    }

    @Test
    fun `test resources and build output are not part of the lock`() {
        locked()
        migration("apps/x/src/test/resources/db/migration/postgresql/V20250101000000__probe.sql")
        migration("modules/a-jdbc/build/resources/main/db/migration/postgresql/V20250202000000__copy.sql")
        assertEquals(0, lock("--check").code)
    }

    @Test
    fun `versions are compared per vendor`() {
        locked()
        // mysql 에는 아직 잠긴 것이 없다 — 오래된 버전이어도 postgresql 의 최대 버전과 비교하지 않는다
        migration("modules/a-jdbc/src/main/resources/db/migration/mysql/V20260101000000__a.sql")
        val r = lock("--check")
        assertEquals(1, r.code, r.out)
        assertFalse("out-of-order" in r.out, r.out)
    }

    @Test
    fun `this repository's migrations match the committed lock`() {
        val r = lock("--check", at = repoRoot)
        assertEquals(0, r.code, r.out)
    }
}
