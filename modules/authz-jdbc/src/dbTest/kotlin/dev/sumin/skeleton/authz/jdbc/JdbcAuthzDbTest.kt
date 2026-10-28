package dev.sumin.skeleton.authz.jdbc

import dev.sumin.skeleton.authz.AuthzRoleException
import dev.sumin.skeleton.authz.AuthzRoleStore
import dev.sumin.skeleton.authz.RoleService
import dev.sumin.skeleton.authz.StoredRole
import dev.sumin.skeleton.common.authz.CachingRolePermissionSource
import dev.sumin.skeleton.common.authz.DeclaredPermission
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.authz.RolePermissionSource
import dev.sumin.skeleton.common.authz.TokenOperatorAccess
import dev.sumin.skeleton.common.authz.YmlRolePermissionSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.context.request.RequestContextHolder

/** 같은 소스가 PostgreSQL · MySQL 두 벌에서 돈다 — 저장소 계약, 첫 기동 씨앗, 캐시 무효화, 보호 역할을 문장이 지키는지 */
@Import(DbTestcontainers::class, JdbcAuthzDbTest.Fixtures::class)
@SpringBootTest(
    classes = [AuthzJdbcTestApplication::class],
    properties = [
        "skeleton.authz.admin.enabled=true",
        "skeleton.authz.roles.ADMIN=*",
        "skeleton.authz.roles.MODERATOR=board.moderate,board.manage",
        "skeleton.authz.roles.CS=payment.read,payment.refund",
        "skeleton.authz.roles.LIMITED=authz.roles,payment.read",
    ],
)
@AutoConfigureMockMvc
class JdbcAuthzDbTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun boardPermissions() = PermissionDeclaration("board", listOf(DeclaredPermission("board.moderate"), DeclaredPermission("board.manage")))
        @Bean fun paymentPermissions() = PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"), DeclaredPermission("payment.refund")))
        @Bean fun audit() = RecordingAudit()
        @Bean fun auditLog(audit: RecordingAudit): OperatorAuditLog = audit
    }

    class RecordingAudit : OperatorAuditLog {
        val actions = CopyOnWriteArrayList<OperatorAction>()

        /** 시험이 쓰기 트랜잭션을 커밋 직전에 붙잡아 두는 자리 */
        @Volatile var onRecord: (OperatorAction) -> Unit = {}
        override fun record(action: OperatorAction) {
            actions += action
            onRecord(action)
        }
    }

    @Autowired lateinit var store: AuthzRoleStore
    @Autowired lateinit var source: RolePermissionSource
    @Autowired lateinit var access: OperatorAccess
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dialect: SqlDialect
    @Autowired lateinit var tx: TransactionOperations
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var audit: RecordingAudit
    @Autowired lateinit var service: RoleService

    private val time = TimeProvider.fixed(Instant.parse("2026-10-24T00:00:00Z"))

    @BeforeEach
    fun reset() {
        // 시험 사이 상태: 씨앗이 넣은 줄만 남긴다
        jdbc.sql("delete from authz_role_includes").update()
        jdbc.sql("delete from authz_roles where name not in ('ADMIN','MODERATOR','CS')").update()
        listOf(
            StoredRole("MODERATOR", null, setOf("board.moderate", "board.manage")),
            StoredRole("CS", null, setOf("payment.read", "payment.refund")),
        ).forEach { if (!store.insert(it)) store.update(it.name, it.description, it.permissions) }
        audit.actions.clear()
    }

    private fun count(table: String) = jdbc.sql("select count(*) from $table").query(Long::class.java).single()

    // ---- seed ----

    @Test
    fun `first boot seeds the yml mapping and the full access role is protected`() {
        val admin = assertNotNull(store.find("ADMIN"))
        assertEquals(setOf("*"), admin.permissions)
        assertTrue(admin.protected)
        assertEquals(setOf("board.manage", "board.moderate"), store.find("MODERATOR")!!.permissions)
        assertFalse(store.find("MODERATOR")!!.protected)
        assertEquals(setOf("payment.read", "payment.refund"), store.find("CS")!!.permissions)
    }

    @Test
    fun `seeding again changes nothing, even with another yml and an operator edited or deleted role`() {
        store.update("MODERATOR", "edited", setOf("board.moderate"))
        store.delete("CS")
        val otherYml = YmlRolePermissionSource(mapOf("ADMIN" to setOf("*"), "MODERATOR" to setOf("board.manage"), "NEWCOMER" to setOf("payment.read")))
        val roles = count("authz_roles")
        val permissions = count("authz_role_permissions")

        AuthzRoleSeeder(jdbc, dialect, tx, time, otherYml, "ADMIN").afterSingletonsInstantiated()

        assertEquals(roles, count("authz_roles"))
        assertEquals(permissions, count("authz_role_permissions"))
        assertEquals(setOf("board.moderate"), store.find("MODERATOR")!!.permissions)
        assertNull(store.find("CS"), "an operator-deleted role is not resurrected")
        assertNull(store.find("NEWCOMER"))
    }

    @Test
    fun `a protected row whose name is not the super role stops the start with a hint`() {
        jdbc.sql("insert into authz_roles (name, description, protected, created_at, updated_at) values ('OLD_ADMIN', null, true, :at, :at)")
            .param("at", dialect.instantParam(time.now())).update()
        try {
            val failure = kotlin.test.assertFailsWith<IllegalStateException> {
                AuthzRoleSeeder(jdbc, dialect, tx, time, YmlRolePermissionSource(mapOf("ADMIN" to setOf("*"))), "ADMIN").afterSingletonsInstantiated()
            }
            assertTrue("OLD_ADMIN" in failure.message.orEmpty() && "super-role" in failure.message.orEmpty(), failure.message)
        } finally {
            jdbc.sql("delete from authz_roles where name = 'OLD_ADMIN'").update()
        }
    }

    // ---- a grant and a delete of the same role are put in a line ----

    @Test
    fun `a grant that starts while the delete holds the role row waits and then finds the role gone`() {
        store.insert(StoredRole("RACE_GONE", null, setOf("payment.read")))
        val deleting = CountDownLatch(1)
        val finishDelete = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val delete = pool.submit {
                tx.executeWithoutResult {
                    store.findForUpdate("RACE_GONE")
                    deleting.countDown()
                    finishDelete.await(10, TimeUnit.SECONDS)
                    store.delete("RACE_GONE")
                }
            }
            assertTrue(deleting.await(10, TimeUnit.SECONDS))
            val grant = pool.submit<Boolean?> { tx.execute { source.lockForGrant("RACE_GONE") } }
            Thread.sleep(500)
            assertFalse(grant.isDone, "the grant waits for the delete")
            finishDelete.countDown()
            delete.get(10, TimeUnit.SECONDS)
            assertEquals(false, grant.get(10, TimeUnit.SECONDS), "the role is gone once the delete committed")
        } finally {
            finishDelete.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a delete that starts after a grant holds the role row waits until the grant committed`() {
        store.insert(StoredRole("RACE_HELD", null, setOf("payment.read")))
        val granting = CountDownLatch(1)
        val finishGrant = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val grant = pool.submit<Boolean?> {
                tx.execute { source.lockForGrant("RACE_HELD").also { granting.countDown(); finishGrant.await(10, TimeUnit.SECONDS) } }
            }
            assertTrue(granting.await(10, TimeUnit.SECONDS))
            val delete = pool.submit { tx.executeWithoutResult { store.findForUpdate("RACE_HELD"); store.delete("RACE_HELD") } }
            Thread.sleep(500)
            assertFalse(delete.isDone, "the delete waits for the grant, so its sweep sees the new assignment")
            finishGrant.countDown()
            assertEquals(true, grant.get(10, TimeUnit.SECONDS))
            delete.get(10, TimeUnit.SECONDS)
            assertNull(store.find("RACE_HELD"))
        } finally {
            finishGrant.countDown()
            pool.shutdownNow()
        }
    }

    // ---- store contract ----

    @Test
    fun `insert find list update delete`() {
        assertTrue(store.insert(StoredRole("AUDITOR", "reads", setOf("payment.read"))))
        assertFalse(store.insert(StoredRole("AUDITOR", "again", setOf("payment.refund"))), "same name is refused")
        assertEquals(StoredRole("AUDITOR", "reads", setOf("payment.read")), store.find("AUDITOR"))
        assertTrue("AUDITOR" in store.list().map { it.name })

        assertTrue(store.update("AUDITOR", null, setOf("payment.read", "payment.refund")))
        assertEquals(StoredRole("AUDITOR", null, setOf("payment.read", "payment.refund")), store.find("AUDITOR"))
        assertFalse(store.update("GHOST", null, emptySet()))

        assertTrue(store.delete("AUDITOR"))
        assertNull(store.find("AUDITOR"))
        assertFalse(store.delete("AUDITOR"))
    }

    @Test
    fun `deleting a role removes its permission rows`() {
        store.insert(StoredRole("TEMP", null, setOf("payment.read", "payment.refund")))
        assertEquals(2, jdbc.sql("select count(*) from authz_role_permissions where role = 'TEMP'").query(Long::class.java).single())
        store.delete("TEMP")
        assertEquals(0, jdbc.sql("select count(*) from authz_role_permissions where role = 'TEMP'").query(Long::class.java).single())
    }

    @Test
    fun `the protected role is untouched by update and delete, also when called at the same time`() {
        val results = race(12) { i -> if (i % 2 == 0) store.update("ADMIN", "x", setOf("payment.read")) else store.delete("ADMIN") }
        assertTrue(results.none { it })
        assertEquals(StoredRole("ADMIN", "Full access", setOf("*"), protected = true), store.find("ADMIN"))
    }

    @Test
    fun `the same new role created at the same time exists once`() {
        val results = race(8) { store.insert(StoredRole("RACE", null, setOf("payment.read"))) }
        assertEquals(1, results.count { it })
        assertEquals(setOf("payment.read"), store.find("RACE")!!.permissions)
        assertEquals(1, jdbc.sql("select count(*) from authz_role_permissions where role = 'RACE'").query(Long::class.java).single())
    }

    // ---- the mapping the checks read ----

    @Test
    fun `the full access role is star whatever the table says`() {
        jdbc.sql("delete from authz_role_permissions where role = 'ADMIN'").update()
        try {
            assertEquals(setOf("*"), JdbcRolePermissionSource(jdbc, "ADMIN").permissionsOf("ADMIN"))
        } finally {
            jdbc.sql("insert into authz_role_permissions (role, permission) values ('ADMIN', '*')").update()
        }
    }

    @Test
    fun `a change shows in the very next check on this instance, other instances after the ttl`() {
        val cs = OperatorRef("acc_cs", setOf("CS"))
        assertTrue(can(cs, "payment.refund"))

        val clock = MutableClock(Instant.parse("2026-10-24T00:00:00Z"))
        val otherInstance = CachingRolePermissionSource(JdbcRolePermissionSource(jdbc, "ADMIN"), Duration.ofSeconds(30), clock)
        assertEquals(setOf("payment.read", "payment.refund"), otherInstance.permissionsOf("CS"))

        store.update("CS", null, setOf("payment.read"))

        assertFalse(can(cs, "payment.refund"), "same instance: invalidated on commit")
        assertEquals(setOf("payment.read", "payment.refund"), otherInstance.permissionsOf("CS"), "other instance still cached")
        clock.now = clock.now.plusSeconds(31)
        assertEquals(setOf("payment.read"), otherInstance.permissionsOf("CS"), "other instance after the ttl")
    }

    @Test
    fun `an edit through the api reaches the next check`() {
        val cs = OperatorRef("acc_cs", setOf("CS"))
        assertTrue(can(cs, "payment.refund"))
        val admin = TestingAuthenticationToken("acc_admin", "n/a", "ROLE_ADMIN")
        mvc.perform(
            put("/api/v1/admin/roles/CS").principal(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"description":"read only","permissions":["payment.read"]}"""),
        ).andExpect(status().isOk)
        assertFalse(can(cs, "payment.refund"))
        assertTrue(can(cs, "payment.read"))
        assertEquals(listOf("authz.role.updated"), audit.actions.map { it.action })
    }

    @Test
    fun `an edit that races a removal cannot bring the removed permission back`() {
        val upper = OperatorRef("acc_upper", setOf("ADMIN"))
        val limited = OperatorRef("acc_limited", setOf("LIMITED"))   // authz.roles 와 payment.read 만 — payment.refund 는 줄 수 없다
        val upperInTransaction = CountDownLatch(1)
        val releaseUpper = CountDownLatch(1)
        audit.onRecord = { if (it.actorId == "acc_upper") { upperInTransaction.countDown(); releaseUpper.await(10, TimeUnit.SECONDS) } }
        val pool = Executors.newFixedThreadPool(2)
        try {
            // 상위가 payment.refund 를 빼는 트랜잭션을 커밋 직전에 붙잡는다
            val removal = pool.submit { service.update(upper, "CS", null, setOf("payment.read")) }
            assertTrue(upperInTransaction.await(10, TimeUnit.SECONDS))
            // 아래 사람이 설명만 바꾸려고 예전 목록(payment.refund 포함)을 그대로 보낸다
            val stale = pool.submit<Throwable?> {
                try { service.update(limited, "CS", "new text", setOf("payment.read", "payment.refund")); null } catch (e: AuthzRoleException) { e }
            }
            Thread.sleep(500)
            releaseUpper.countDown()
            removal.get(10, TimeUnit.SECONDS)
            val refused = stale.get(10, TimeUnit.SECONDS)
            assertEquals("AUTHZ.ESCALATION", (refused as? AuthzRoleException)?.errorCode?.code, "the stale edit must be judged against the committed removal")
            assertEquals(setOf("payment.read"), store.find("CS")!!.permissions)
        } finally {
            releaseUpper.countDown()
            audit.onRecord = {}
            pool.shutdownNow()
        }
    }

    // ---- role hierarchy (includes) ----

    private fun clearRoles() {
        jdbc.sql("delete from authz_role_includes").update()
        jdbc.sql("delete from authz_role_permissions").update()
        jdbc.sql("delete from authz_roles").update()
    }

    @Test
    fun `the seed moves the yml includes once, keeps each role's own permissions, and a second start adds nothing`() {
        clearRoles()
        try {
            val yml = YmlRolePermissionSource(
                mapOf("ADMIN" to setOf("*"), "BASE" to setOf("payment.read"), "TOP" to setOf("payment.refund")),
                mapOf("TOP" to setOf("BASE")),
            )
            repeat(2) { AuthzRoleSeeder(jdbc, dialect, tx, time, yml, "ADMIN").afterSingletonsInstantiated() }

            assertEquals(1, count("authz_role_includes"))
            assertEquals(setOf("BASE"), store.find("TOP")!!.includes)
            assertEquals(setOf("payment.refund"), store.find("TOP")!!.permissions, "the table keeps the direct permissions, not the inherited ones")
            assertEquals(setOf("payment.read", "payment.refund"), JdbcRolePermissionSource(jdbc, "ADMIN").permissionsOf("TOP"))
        } finally {
            clearRoles()
            AuthzRoleSeeder(
                jdbc, dialect, tx, time,
                YmlRolePermissionSource(mapOf("ADMIN" to setOf("*"), "MODERATOR" to setOf("board.moderate", "board.manage"), "CS" to setOf("payment.read", "payment.refund"))),
                "ADMIN",
            ).afterSingletonsInstantiated()
        }
    }

    @Test
    fun `the store keeps includes, leaves them alone when an update has none, and refuses to delete an included role`() {
        assertTrue(store.insert(StoredRole("BASE", null, setOf("payment.read"))))
        assertTrue(store.insert(StoredRole("TOP", null, setOf("payment.refund"), includes = setOf("BASE"))))
        assertEquals(setOf("BASE"), store.find("TOP")!!.includes)
        assertEquals(setOf("BASE"), store.list().single { it.name == "TOP" }.includes)
        assertEquals(setOf("TOP"), store.includedBy("BASE"))

        assertTrue(store.update("TOP", "d", setOf("payment.refund")))
        assertEquals(setOf("BASE"), store.find("TOP")!!.includes, "no includes in the update means keep")
        assertTrue(store.update("TOP", "d", setOf("payment.refund"), emptySet()))
        assertEquals(emptySet(), store.find("TOP")!!.includes)

        assertTrue(store.update("TOP", "d", setOf("payment.refund"), setOf("BASE")))
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> { store.delete("BASE") }
        assertTrue(store.delete("TOP"))
        assertEquals(0, count("authz_role_includes"), "deleting a role takes its include rows along")
        assertEquals(emptySet(), store.includedBy("BASE"))
    }

    @Test
    fun `permissions are expanded through the table, the full access role is never followed, and a loop in the table ends`() {
        store.insert(StoredRole("BASE", null, setOf("payment.read")))
        store.insert(StoredRole("MID", null, setOf("board.manage"), includes = setOf("BASE")))
        store.insert(StoredRole("TOP", null, setOf("payment.refund"), includes = setOf("MID")))
        val jdbcSource = JdbcRolePermissionSource(jdbc, "ADMIN")
        assertEquals(setOf("payment.read", "board.manage", "payment.refund"), jdbcSource.permissionsOf("TOP"))
        assertEquals(setOf("MID"), jdbcSource.includesOf("TOP"))

        // 표를 손으로 만져도 전권이 새지 않는다
        jdbc.sql("insert into authz_role_includes (role, included) values ('BASE', 'ADMIN')").update()
        assertEquals(setOf("payment.read", "board.manage", "payment.refund"), jdbcSource.permissionsOf("TOP"))
        jdbc.sql("delete from authz_role_includes where role = 'BASE'").update()
        // 손으로 만든 순환에도 판정이 끝난다
        jdbc.sql("insert into authz_role_includes (role, included) values ('BASE', 'TOP')").update()
        assertEquals(setOf("payment.read", "board.manage", "payment.refund"), jdbcSource.permissionsOf("BASE"))
    }

    @Test
    fun `a change to an included role reaches the roles above it at once here and after the ttl on another instance`() {
        store.insert(StoredRole("TOP", null, setOf("board.manage"), includes = setOf("CS")))
        val top = OperatorRef("acc_top", setOf("TOP"))
        assertTrue(can(top, "payment.refund"))

        val clock = MutableClock(Instant.parse("2026-10-24T00:00:00Z"))
        val otherInstance = CachingRolePermissionSource(JdbcRolePermissionSource(jdbc, "ADMIN"), Duration.ofSeconds(30), clock)
        assertEquals(setOf("board.manage", "payment.read", "payment.refund"), otherInstance.permissionsOf("TOP"))

        store.update("CS", null, setOf("payment.read"))

        assertFalse(can(top, "payment.refund"), "same instance: the whole cache is dropped on commit")
        assertTrue(can(top, "payment.read"))
        assertEquals(setOf("board.manage", "payment.read", "payment.refund"), otherInstance.permissionsOf("TOP"), "other instance still cached")
        clock.now = clock.now.plusSeconds(31)
        assertEquals(setOf("board.manage", "payment.read"), otherInstance.permissionsOf("TOP"), "other instance after the ttl")
    }

    @Test
    fun `two servers including each other's role at the same time end with one include - the second waits for the first and sees the loop`() {
        store.insert(StoredRole("LOOP_A", null, setOf("payment.read")))
        store.insert(StoredRole("LOOP_B", null, setOf("payment.read")))
        val declarations = listOf(PermissionDeclaration("payment", listOf(DeclaredPermission("payment.read"))))
        // 서버 하나 = 저장소 · 캐시 · 서비스 한 벌(프로세스 잠금도 따로) - 줄 세우는 것은 DB 의 전권 역할 줄 잠금뿐이다
        fun server(): RoleService {
            val cache = CachingRolePermissionSource(JdbcRolePermissionSource(jdbc, "ADMIN"), Duration.ofSeconds(30))
            return RoleService(
                JdbcAuthzRoleStore(jdbc, dialect, tx, time) { cache.invalidate() }, cache, TokenOperatorAccess(cache),
                { declarations }, null, audit, "ADMIN", tx,
            )
        }
        val serverOne = server()
        val serverTwo = server()
        val first = OperatorRef("acc_first", setOf("ADMIN"))
        val second = OperatorRef("acc_second", setOf("ADMIN"))
        val firstInTransaction = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        audit.onRecord = { if (it.actorId == "acc_first") { firstInTransaction.countDown(); releaseFirst.await(10, TimeUnit.SECONDS) } }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val one = pool.submit { serverOne.update(first, "LOOP_A", null, setOf("payment.read"), setOf("LOOP_B")) }
            assertTrue(firstInTransaction.await(10, TimeUnit.SECONDS))
            val two = pool.submit<Throwable?> {
                try { serverTwo.update(second, "LOOP_B", null, setOf("payment.read"), setOf("LOOP_A")); null } catch (e: AuthzRoleException) { e }
            }
            Thread.sleep(500)
            assertFalse(two.isDone, "the second include waits for the first at the hierarchy lock")
            releaseFirst.countDown()
            one.get(10, TimeUnit.SECONDS)
            assertEquals("AUTHZ.ROLE_CYCLE", (two.get(10, TimeUnit.SECONDS) as? AuthzRoleException)?.errorCode?.code)
            assertEquals(setOf("LOOP_B"), store.find("LOOP_A")!!.includes)
            assertEquals(emptySet(), store.find("LOOP_B")!!.includes)
        } finally {
            releaseFirst.countDown()
            audit.onRecord = {}
            pool.shutdownNow()
        }
    }

    /** 스프링 시험은 시험 내내 요청 하나를 열어 두므로 요청 단위 메모(AbstractOperatorAccess)를 매번 비워 「다음 요청」을 흉내 낸다 */
    private fun can(ref: OperatorRef, permission: String): Boolean {
        RequestContextHolder.resetRequestAttributes()
        return access.has(ref, permission)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private fun <T> race(threads: Int, work: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        try {
            return (0 until threads).map { i -> pool.submit(Callable { barrier.await(); work(i) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
