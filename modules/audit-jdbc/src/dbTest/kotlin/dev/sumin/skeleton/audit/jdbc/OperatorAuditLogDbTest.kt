package dev.sumin.skeleton.audit.jdbc

import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorResult
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 모듈 마이그레이션을 깔고 두 방언에서 같은 뜻인지 — 기록 · 같은 트랜잭션 · 수정 불가 · 지우기는 30일 뒤부터 · 조회 필터와 커서 · 보관 기한 지우기 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [AuditJdbcTestApplication::class])
class OperatorAuditLogDbTest {
    @Autowired lateinit var log: OperatorAuditLog
    @Autowired lateinit var store: AuditLogStore
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dialect: SqlDialect
    @Autowired lateinit var transactions: PlatformTransactionManager

    private val who = "op_${UUID.randomUUID().toString().take(8)}"   // 다른 시험이 같은 DB 에 남긴 줄과 섞이지 않게 운영자마다 새 id

    private fun mine(query: AuditQuery = AuditQuery()) = store.find(query.copy(actorId = who))

    @Test
    fun `the jdbc log replaces the no-op default and keeps every field`() {
        assertIs<JdbcOperatorAuditLog>(log)
        log.record(OperatorAction(who, "authz.role.created", "role", "CS", mapOf("added" to "payment.read")))

        val line = mine().single()
        assertEquals(listOf("authz.role.created", "role", "CS", "SUCCESS"), listOf(line.action, line.targetType, line.targetId, line.result))
        assertEquals(mapOf("added" to "payment.read"), line.detail)
        assertTrue(Duration.between(line.occurredAt, Instant.now()).abs() < Duration.ofMinutes(5))
    }

    @Test
    fun `a refused or failed attempt is a line of its own result`() {
        log.record(OperatorAction(who, "payment.refund.refused", "payment_order", "ord_1", result = OperatorResult.DENIED))
        assertEquals("DENIED", mine().single().result)
    }

    @Test
    fun `address and user agent come from the request being served`() {
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.7"; addHeader("User-Agent", "AdminConsole/1.0") }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            log.record(OperatorAction(who, "account.suspended", "account", "acc_1"))
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
        val line = mine().single()
        assertEquals("203.0.113.7", line.ip)
        assertEquals("AdminConsole/1.0", line.userAgent)
    }

    @Test
    fun `a line is part of the operator action's transaction - rolled back with it, kept with it`() {
        val tx = TransactionTemplate(transactions)
        assertFailsWith<IllegalStateException> {
            tx.execute {
                log.record(OperatorAction(who, "point.adjusted", "point_entry", "p_1"))
                throw IllegalStateException("the action failed after it was recorded")
            }
        }
        assertEquals(emptyList(), mine(), "the action did not happen, so no line says it did")

        tx.execute { log.record(OperatorAction(who, "point.adjusted", "point_entry", "p_2")) }
        assertEquals(listOf("p_2"), mine().map { it.targetId })
    }

    @Test
    fun `a recent line cannot be changed or deleted - not by the app, not by sql`() {
        log.record(OperatorAction(who, "inquiry.status.changed", "inquiry", "7"))
        val id = mine().single().id

        assertFailsWith<DataAccessException> { jdbc.sql("update operator_audit_log set action = 'forged' where id = :id").param("id", id).update() }
        assertFailsWith<DataAccessException> { jdbc.sql("update operator_audit_log set actor_id = 'someone-else' where id = :id").param("id", id).update() }
        assertFailsWith<DataAccessException> { jdbc.sql("delete from operator_audit_log where id = :id").param("id", id).update() }
        assertEquals("inquiry.status.changed", mine().single().action)
        assertEquals(who, mine().single().actorId)
    }

    @Test
    fun `a line is deleted only after thirty days, and the retention sweep deletes in batches and keeps what is within the keep time`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        repeat(5) { insertRaw("old-$it", now.minus(Duration.ofDays(40))) }
        insertRaw("margin", now.minus(Duration.ofDays(30)).minus(Duration.ofHours(6)))   // 30일은 지났지만 하루 여유 안 - 지우기는 시계가 어긋나도 거절되지 않게 이 줄을 아직 두고 지나간다
        insertRaw("edge", now.minus(Duration.ofDays(20)))   // 30일 안이라 데이터베이스가 지우지 못한다
        insertRaw("fresh", now.minus(Duration.ofDays(1)))
        assertFailsWith<DataAccessException> { jdbc.sql("delete from operator_audit_log where actor_id = :a and target_id = 'edge'").param("a", who).update() }

        val retention = AuditRetention(store, Duration.ofDays(30), TimeProvider.fixed(now), batch = 2)
        assertEquals(5, retention.purge())
        assertEquals(listOf("fresh", "edge", "margin"), mine().map { it.targetId }, "newest first; none is past the keep time plus the one-day margin")
        assertEquals(0, retention.purge(), "a second sweep finds nothing")
    }

    @Test
    fun `the query filters by period, operator and target, newest first, and pages by cursor`() {
        val base = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofHours(1))
        (1..5).forEach { insertRaw("t$it", base.plusSeconds(it * 10L), targetType = if (it % 2 == 0) "role" else "account") }
        val other = "op_other_${UUID.randomUUID().toString().take(6)}"
        insertRaw("t3", base.plusSeconds(30), actor = other)

        assertEquals(listOf("t5", "t4", "t3", "t2", "t1"), mine().map { it.targetId })
        assertEquals(listOf("t4", "t3", "t2"), mine(AuditQuery(from = base.plusSeconds(20), to = base.plusSeconds(50))).map { it.targetId }, "from is inclusive, to is exclusive")
        assertEquals(listOf("t4", "t2"), mine(AuditQuery(targetType = "role")).map { it.targetId })
        assertEquals(listOf(other), store.find(AuditQuery(actorId = other, targetId = "t3")).map { it.actorId })
        assertEquals(listOf("t3"), mine(AuditQuery(targetId = "t3")).map { it.targetId }, "the other operator's t3 is not mine")

        val first = mine(AuditQuery(limit = 2))
        assertEquals(listOf("t5", "t4"), first.map { it.targetId })
        assertEquals(listOf("t3", "t2"), mine(AuditQuery(before = first.last().id, limit = 2)).map { it.targetId })
    }

    private fun insertRaw(target: String, at: Instant, actor: String = who, targetType: String = "account") {
        jdbc.sql(
            "insert into operator_audit_log (occurred_at, actor_id, action, target_type, target_id, result) values (:at, :actor, 'test.action', :type, :target, 'SUCCESS')",
        ).param("at", dialect.instantParam(at)).param("actor", actor).param("type", targetType).param("target", target).update()
    }
}
