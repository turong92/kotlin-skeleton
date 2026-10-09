package dev.sumin.skeleton.payment.jdbc

import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager

/**
 * 진짜 DB(PostgreSQL · MySQL 묶음마다 하나)에 모듈 마이그레이션(이 모듈 + job-queue-jdbc 의 `jobs`)을 깔고 연결 풀을 준다 — 테스트 클래스들이 공유한다.
 * 컨테이너는 JVM 당 하나(`DbTestDatabase`), 풀은 16 스레드 경합 시험이 연결을 못 얻어 막히지 않게 넉넉하다.
 */
object PaymentDb {
    private val raw: HikariDataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply {
            jdbcUrl = source.url
            username = source.username
            password = source.password
            maximumPoolSize = 48
        }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }

    val dataSource: DataSource = raw
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val transactionManager: PlatformTransactionManager = DataSourceTransactionManager(dataSource)

    fun newStore(): JdbcPaymentLedgerStore = JdbcPaymentLedgerStore(jdbc, transactionManager, DbTestDatabase.dialect)

    /** 이 시험이 쓰는 표의 줄을 모두 비운다 — 시험마다 깨끗한 표에서 시작한다(자식 표부터). */
    fun clean() {
        listOf("payment_webhook_events", "payment_events", "payment_refunds", "payment_orders", "jobs").forEach { jdbc.update("delete from $it", emptyMap<String, Any>()) }
    }

    fun count(sql: String, vararg params: Pair<String, Any?>): Long = jdbc.queryForObject(sql, params.toMap(), Long::class.java) ?: 0L

    private val sequence = AtomicInteger()

    /** 시험마다 겹치지 않는 id 조각. */
    fun unique(prefix: String): String = "${prefix}_${sequence.incrementAndGet()}_${System.nanoTime()}"

    /** 주문 줄을 SQL 로 직접 넣는다(저장소를 거치지 않는 표 수준 시험용) — 안 쓰는 칸은 기본값. */
    fun insertOrderRow(
        id: String,
        amount: Long = 3000,
        paidAmount: Long? = null,
        refundedAmount: Long = 0,
        cardLast4: String? = null,
        providerPaymentId: String? = null,
        dedupeKey: String? = null,
    ) {
        val now = DbTestDatabase.dialect.instantParam(Instant.parse("2026-10-08T00:00:00Z"))
        jdbc.update(
            """insert into payment_orders (id, account_id, kind, status, provider, provider_mode, provider_payment_id, currency, amount, paid_amount, refunded_amount,
                                           item_type, item_ref, order_name, origin_type, dedupe_key, card_last4, checkout_expires_at, created_at, updated_at)
               values (:id, 'acc_1', 'ONE_TIME', 'PAID', 'scripted', 'TEST', :ppid, 'KRW', :amount, :paid, :refunded,
                       'cheer', 'cheer-3000', 'Cheer', 'CHECKOUT', :dedupe, :last4, :now, :now, :now)""",
            MapSqlParameterSource().addValue("id", id).addValue("ppid", providerPaymentId).addValue("amount", amount).addValue("paid", paidAmount)
                .addValue("refunded", refundedAmount).addValue("dedupe", dedupeKey).addValue("last4", cardLast4).addValue("now", now),
        )
    }

    fun insertRefundRow(id: String, orderId: String, openKey: String?, dedupeKey: String?, providerRefundId: String?, amount: Long = 1000, status: String = "PENDING") {
        val now = DbTestDatabase.dialect.instantParam(Instant.parse("2026-10-08T00:00:00Z"))
        jdbc.update(
            """insert into payment_refunds (id, order_id, status, origin, amount, currency, reason_code, open_key, dedupe_key, provider_refund_id, requested_at, created_at, updated_at)
               values (:id, :order, :status, 'USER', :amount, 'KRW', 'CUSTOMER_REQUEST', :open, :dedupe, :prid, :now, :now, :now)""",
            MapSqlParameterSource().addValue("id", id).addValue("order", orderId).addValue("status", status).addValue("amount", amount)
                .addValue("open", openKey).addValue("dedupe", dedupeKey).addValue("prid", providerRefundId).addValue("now", now),
        )
    }

    fun insertWebhookRow(provider: String, eventKey: String) {
        val now = DbTestDatabase.dialect.instantParam(Instant.parse("2026-10-08T00:00:00Z"))
        jdbc.update(
            """insert into payment_webhook_events (provider, event_key, event_type, resource_type, resource_id, payload_hash, status, received_at)
               values (:provider, :key, 'PAYMENT_STATUS_CHANGED', 'PAYMENT', 'pay_1', :hash, 'RECEIVED', :now)""",
            MapSqlParameterSource().addValue("provider", provider).addValue("key", eventKey).addValue("hash", "a".repeat(64)).addValue("now", now),
        )
    }
}

/** [threads] 개 스레드가 같은 순간에 출발해 [block] 을 돈다(인덱스를 받는다). 예외는 결과로 돌려준다 — 경합 시험이 성공 · 실패를 센다. */
fun <T> race(threads: Int, block: (Int) -> T): List<Result<T>> {
    val pool = Executors.newFixedThreadPool(threads)
    val ready = CountDownLatch(threads)
    val start = CountDownLatch(1)
    try {
        val futures = (0 until threads).map { i ->
            pool.submit<Result<T>> {
                ready.countDown()
                start.await()
                runCatching { block(i) }
            }
        }
        ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
