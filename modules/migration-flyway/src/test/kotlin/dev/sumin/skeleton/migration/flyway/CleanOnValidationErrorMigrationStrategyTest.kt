package dev.sumin.skeleton.migration.flyway

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class CleanOnValidationErrorMigrationStrategyTest {
    private fun flyway(dir: Path, schema: String) = Flyway.configure()
        .dataSource(db.jdbcUrl, db.username, db.password)
        .schemas(schema).locations("filesystem:$dir").load()

    private fun jdbc() = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))

    @Test
    fun `checksum mismatch cleans and re-applies`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table a.t (id int); insert into a.t values (1);")
        flyway(dir, "a").migrate()
        jdbc().sql("insert into a.t values (2)").update()

        dir.resolve("V20261001000000__t.sql").writeText("create table a.t (id int, v int); insert into a.t values (1, 1);")
        CleanOnValidationErrorMigrationStrategy().migrate(flyway(dir, "a"))

        assertEquals(1, jdbc().sql("select count(*) from a.t").query(Int::class.java).single())
        assertEquals(1, jdbc().sql("select count(*) from information_schema.columns where table_schema = 'a' and table_name = 't' and column_name = 'v'").query(Int::class.java).single())
    }

    @Test
    fun `a new pending migration does not clean`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table b.t (id int);")
        flyway(dir, "b").migrate()
        jdbc().sql("insert into b.t values (42)").update()

        dir.resolve("V20261001000100__u.sql").writeText("create table b.u (id int);")
        CleanOnValidationErrorMigrationStrategy().migrate(flyway(dir, "b"))

        assertEquals(42, jdbc().sql("select id from b.t").query(Int::class.java).single())
        assertEquals(1, jdbc().sql("select count(*) from information_schema.tables where table_schema = 'b' and table_name = 'u'").query(Int::class.java).single())
    }

    @Test
    fun `a migration applied by another branch but absent here (future) does not clean`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table c.t (id int);")
        dir.resolve("V20261005000000__other_branch.sql").writeText("create table c.o (id int);")
        flyway(dir, "c").migrate()
        jdbc().sql("insert into c.t values (7)").update()

        Files.delete(dir.resolve("V20261005000000__other_branch.sql")) // 브랜치를 바꿔 그 파일이 없다 — migrate() 는 *:future 로 통과한다
        CleanOnValidationErrorMigrationStrategy().migrate(flyway(dir, "c"))

        assertEquals(7, jdbc().sql("select id from c.t").query(Int::class.java).single())
    }

    @Test
    fun `with the clean option off (the local default) a changed migration stops startup, keeps the data and the analyzer says what to do`() {
        val dir = Files.createTempDirectory("mig")
        dir.resolve("V20261001000000__t.sql").writeText("create table d.t (id int);")
        flyway(dir, "d").migrate()
        jdbc().sql("insert into d.t values (5)").update()

        dir.resolve("V20261001000000__t.sql").writeText("create table d.t (id int, v int);")
        val failure = kotlin.test.assertFailsWith<org.flywaydb.core.api.exception.FlywayValidateException> { flyway(dir, "d").migrate() }

        assertEquals(5, jdbc().sql("select id from d.t").query(Int::class.java).single())
        val analysis = kotlin.test.assertNotNull(FlywayValidationFailureAnalyzer().analyze(RuntimeException("startup", failure)))
        kotlin.test.assertTrue("checksum" in analysis.description.lowercase() && "docker compose down -v" in analysis.action.orEmpty(), "${analysis.description} / ${analysis.action}")
    }

    companion object {
        val db = PostgreSQLContainer(DockerImageName.parse("postgres:18"))

        @JvmStatic @BeforeAll
        fun start() = db.start()

        @JvmStatic @AfterAll
        fun stop() = db.stop()
    }
}
