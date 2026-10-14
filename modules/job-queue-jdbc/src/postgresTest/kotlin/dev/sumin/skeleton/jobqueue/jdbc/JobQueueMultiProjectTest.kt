package dev.sumin.skeleton.jobqueue.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.ProjectRoutingDataSource
import dev.sumin.skeleton.persistence.postgresql.PostgresSqlDialect
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 프로젝트 모드 — 일꾼이 프로젝트마다 그 스키마의 `jobs` 를 집는다 (PostgreSQL, 라우팅 DataSource).
 * 일감을 넣을 때의 프로젝트는 넣은 스키마로 정해지므로 따로 실어 나르지 않는다.
 */
class JobQueueMultiProjectTest {
    class MutableTime(var now: Instant = Instant.parse("2026-10-01T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = now
    }

    /** 처리한 잡이 어느 프로젝트의 문맥에서 돌았는지 기록한다 */
    class ProjectRecordingHandler(override val type: String) : JobHandler {
        val seen = CopyOnWriteArrayList<Pair<String?, String>>() // (프로젝트, payload)
        override fun handle(job: Job) { seen += ProjectContext.currentOrNull()?.id to job.payloadJson }
    }

    private val admin = SharedPostgres.newDatabase()
    private val env = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("t", mapOf(
            "skeleton.projects.a.hosts[0]" to "a.test", "skeleton.projects.a.schema" to "jq_a",
            "skeleton.projects.b.hosts[0]" to "b.test", "skeleton.projects.b.schema" to "jq_b",
            "skeleton.projects.gone.hosts[0]" to "gone.test", "skeleton.projects.gone.status" to "closed",
        )))
    }
    private val registry = ProjectRegistry.from(env)
    private val a = registry.projects.first { it.id == "a" }
    private val b = registry.projects.first { it.id == "b" }
    private val time = MutableTime()
    private val handler = ProjectRecordingHandler("task")
    private val properties = JobQueueProperties(workerEnabled = false, batchSize = 1, retention = JobQueueProperties.Retention(enabled = true))
    private val pools = mutableListOf<HikariDataSource>()
    private val dataSource = ProjectRoutingDataSource(registry.active) { project ->
        HikariDataSource().apply {
            jdbcUrl = admin.jdbcUrl; username = admin.username; password = admin.password
            schema = project.schema; maximumPoolSize = 2; minimumIdle = 0
        }.also(pools::add)
    }.also { it.afterPropertiesSet() }
    private val jdbc = JdbcClient.create(dataSource)
    private val repository = JdbcJobRepository(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), PostgresSqlDialect())
    private val queue = JdbcJobQueue(repository, properties, time)

    @BeforeEach
    fun schemas() {
        val migrations = listOf("V20260910010000__jobs.sql", "V20261005175044__jobs_log_context.sql")
            .map { javaClass.getResource("/db/migration/postgresql/$it")!!.readText() }
        listOf(a, b).forEach { p ->
            java.sql.DriverManager.getConnection(admin.jdbcUrl, admin.username, admin.password).use { c ->
                c.createStatement().use { s ->
                    s.execute("create schema if not exists ${p.schema}")
                    s.execute("set search_path = ${p.schema}")
                    migrations.forEach { s.execute(it) }
                }
            }
        }
    }

    private fun worker(retention: JobRetention? = null) =
        JobQueueWorker(repository, listOf(handler), properties.copy(workerId = "w"), time, projects = registry, retention = retention)

    private fun enqueueIn(project: dev.sumin.skeleton.common.project.Project, payload: String) =
        ProjectContext.runIn(project) { queue.enqueue("task", payload) }

    private fun statusesIn(project: dev.sumin.skeleton.common.project.Project): List<String> =
        ProjectContext.runIn(project) { jdbc.sql("select status from jobs order by id").query(String::class.java).list().filterNotNull() }

    @Test
    fun `A 의 잡은 A 스키마에서만 처리되고 B 의 잡은 B 의 문맥에서 처리된다`() {
        enqueueIn(a, "from-a")
        enqueueIn(b, "from-b")

        assertEquals(2, worker().pollOnce())

        assertEquals(setOf("a" to "from-a", "b" to "from-b"), handler.seen.toSet())
        assertEquals(listOf("DONE"), statusesIn(a))
        assertEquals(listOf("DONE"), statusesIn(b))
        // 닫힌 프로젝트는 풀도 스키마도 없다 — 건드리려 하지 않는다 (예외 없이 위가 통과했다)
    }

    @Test
    fun `drain 은 판마다 프로젝트를 모두 돌아 일감이 많은 프로젝트가 다른 프로젝트를 굶기지 않고 모두 비운다`() {
        repeat(3) { enqueueIn(a, "a$it") }
        enqueueIn(b, "b0")

        val result = worker().drain(Duration.ofSeconds(30)) // batchSize = 1

        assertEquals(4, result.processed)
        assertEquals(listOf("DONE", "DONE", "DONE"), statusesIn(a))
        assertEquals(listOf("DONE"), statusesIn(b))
        assertEquals(setOf("a", "b"), handler.seen.map { it.first }.toSet())
    }

    @Test
    fun `일감이 많은 프로젝트가 다른 프로젝트를 굶기지 않는다 - 한 번 돌 때 프로젝트마다 한 묶음씩`() {
        repeat(5) { enqueueIn(a, "a$it") }
        enqueueIn(b, "b0")

        worker().pollOnce() // batchSize = 1

        assertEquals(listOf("DONE", "PENDING", "PENDING", "PENDING", "PENDING"), statusesIn(a))
        assertEquals(listOf("DONE"), statusesIn(b))
    }

    @Test
    fun `한 프로젝트의 DB 오류가 다른 프로젝트의 잡 처리를 막지 않는다`() {
        enqueueIn(a, "from-a")
        enqueueIn(b, "from-b")
        ProjectContext.runIn(a) { jdbc.sql("drop table jobs").update() } // A 의 표가 망가졌다

        assertEquals(1, worker().pollOnce())

        assertEquals(listOf("b" to "from-b"), handler.seen.toList())
        assertEquals(listOf("DONE"), statusesIn(b))
    }

    @Test
    fun `끝난 줄 정리는 프로젝트마다 돈다`() {
        listOf(a, b).forEach { p ->
            ProjectContext.runIn(p) {
                jdbc.sql("insert into jobs (job_type, payload_json, status, attempts, max_attempts, next_run_at, created_at, updated_at) values ('task','{}','DONE',1,3,:t,:t,:t)")
                    .param("t", java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z")).update()
            }
        }
        val retention = JobRetention(repository, properties.retention, time) // 간격 1시간 — 프로젝트 하나의 정리가 다른 프로젝트의 정리를 가리면 안 된다

        worker(retention).pollOnce()

        assertEquals(emptyList(), statusesIn(a))
        assertEquals(emptyList(), statusesIn(b))
    }

    @Test
    fun `처리기 없는 종류의 기한 넘긴 잡도 프로젝트마다 DEAD 가 된다`() {
        ProjectContext.runIn(a) { queue.enqueue("unknown", "{}") }
        ProjectContext.runIn(b) { queue.enqueue("unknown", "{}") }
        time.now = time.now.plus(properties.unhandledTypeGrace).plus(Duration.ofMinutes(1))

        worker().pollOnce()

        assertEquals(listOf("DEAD"), statusesIn(a))
        assertEquals(listOf("DEAD"), statusesIn(b))
        assertTrue(handler.seen.isEmpty())
    }
}
