package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.OperatorOrderFilter
import dev.sumin.skeleton.payment.OperatorOrderScope
import dev.sumin.skeleton.payment.OrderCursor
import dev.sumin.skeleton.payment.PaymentOrderStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * 스펙 §16 의 실측 — `review_reason` 은 거의 전부 NULL 인 열이다. 「확인 필요 목록」 질의가 두 방언 모두에서 `idx_payment_orders_review` 를 타는지,
 * 운영자의 기본 목록(필터 없음, 최신순 커서)이 `idx_payment_orders_created` 를 타는지 실행 계획으로 본다.
 * (부분 인덱스를 쓰지 않고 일반 인덱스만으로 충분한지의 증거.)
 *
 * 인덱스를 **실제로 고른 것**만 센다: PostgreSQL 은 계획 문장에 이름이 있는지, MySQL 은 `explain` 의 `key` 열(실제로 고른 인덱스)을 본다 —
 * `possible_keys` 열에는 쓰지 않은 후보도 올라오므로 행 전체를 이어 붙여 찾으면 전체 훑기(`key` = NULL)도 통과해 버린다.
 */
class PaymentIndexPlanDbTest {
    @BeforeTest
    @AfterTest
    fun clean() = PaymentDb.clean()

    @Test
    fun `the review list uses the review index when almost every row has no review reason`() {
        loadMixed()

        val plans = listOf(
            "select id from payment_orders where review_reason is not null order by created_at, id",
            "select id from payment_orders where review_reason = 'AMOUNT_MISMATCH' order by created_at",
        ).map { it to indexUsed(it) }

        plans.forEach { (query, plan) -> assertThat(plan).`as`("$query\n$plan").containsIgnoringCase("idx_payment_orders_review") }
    }

    @Test
    fun `the operator's default list - no filter, newest first, cursor by created_at and id - uses the created index`() {
        loadMixed()
        val cursor = if (DbTestDatabase.vendor == "postgresql") "'2026-10-08 05:00:00+00'" else "'2026-10-08 05:00:00'"

        val plans = listOf(
            "select id from payment_orders order by created_at desc, id desc limit 50",
            "select id from payment_orders where (created_at, id) < ($cursor, 'ord_plan_9999') order by created_at desc, id desc limit 50",
        ).map { it to indexUsed(it) }

        plans.forEach { (query, plan) -> assertThat(plan).`as`("$query\n$plan").containsIgnoringCase("idx_payment_orders_created") }
    }

    @Test
    fun `the inbox list - one status, newest first, with and without a cursor - uses the status index even when nearly every row is processed`() {
        loadInbox()
        val cursor = if (DbTestDatabase.vendor == "postgresql") "'2026-10-08 05:00:00+00'" else "'2026-10-08 05:00:00'"

        listOf("FAILED", "DEAD", "PROCESSED").flatMap { status ->
            listOf(
                "select id from payment_webhook_events where status = '$status' order by received_at desc, id desc limit 51",
                "select id from payment_webhook_events where status = '$status' and (received_at < $cursor or (received_at = $cursor and id < 99999)) order by received_at desc, id desc limit 51",
            )
        }.forEach { query ->
            assertThat(indexUsed(query)).`as`(query).containsIgnoringCase("idx_payment_webhook_events_status")
            // 인덱스 이름만으로는 전체 인덱스 훑기도 통과한다 — 접근 방식(type)이 ref/range 인지 본다
            if (DbTestDatabase.vendor == "mysql") assertThat(mysqlAccessTypes(query).first()).`as`(query).isIn("range", "ref")
        }
    }

    @Test
    fun `the user's order list with a cursor walks the account index`() {
        loadMixed()
        val cursor = ts("2026-10-08 05:00:00")

        val query = "select id from payment_orders where account_id = 'acc_5' and status <> 'PENDING' and (created_at < $cursor or (created_at = $cursor and id < 'ord_plan_9999')) order by created_at desc, id desc limit 51"

        assertThat(indexUsed(query)).`as`(query).containsIgnoringCase("idx_payment_orders_account")
        if (DbTestDatabase.vendor == "mysql") assertThat(mysqlAccessTypes(query).first()).`as`(query).isIn("ref", "range")
    }


    @Test
    fun `paid rows nobody fulfilled are found through the unfulfilled index - not by reading every paid order`() {
        loadMixed()

        // 저장소가 보내는 문장 그대로(상태마다 한 문장) — PostgreSQL 은 걸름을 먼저 굳히는 `with … as materialized`, MySQL 은 정렬을 맡는 인덱스
        listOf(PaymentOrderStatus.PAID, PaymentOrderStatus.PARTIALLY_REFUNDED).forEach { status ->
            val statement = PaymentDb.newStore().orderListStatement(OperatorOrderScope.UNFULFILLED, status, OperatorOrderFilter(), T_THRESHOLD, null, 51)
            val plan = planOf(statement)
            // 부분 환불된 줄은 이 데이터에 없다 — 빈 범위라 상태 인덱스를 읽어도 비용이 같다. 어느 쪽이든 created 인덱스로 전부 읽는 계획이 아니면 된다
            val expected = if (status == PaymentOrderStatus.PAID) listOf("idx_payment_orders_unfulfilled") else listOf("idx_payment_orders_unfulfilled", "idx_payment_orders_status")
            assertThat(expected.any { plan.contains(it, ignoreCase = true) }).`as`(statement.sql + "\n" + plan).isTrue()
            if (DbTestDatabase.vendor == "mysql") assertThat(mysqlAccessTypes(statement).first()).`as`(statement.sql).isIn("ref", "range")
        }
    }

    @Test
    fun `the review list's refund branches - orders with a stale pending or an unapplied refund, with the order filters and the cursor in the same statement - start from the refund index`() {
        loadMixed()
        val store = PaymentDb.newStore()
        val cursor = OrderCursor(T_CURSOR, "ord_plan_9999")

        // JdbcPaymentLedgerStore.orderListStatement 가 STALE_REFUND · UNAPPLIED_REFUND 로 만드는 문장 그대로
        val stale = store.orderListStatement(OperatorOrderScope.STALE_REFUND, null, OperatorOrderFilter(accountId = "acc_7"), T_THRESHOLD, cursor, 101)
        val unapplied = store.orderListStatement(OperatorOrderScope.UNAPPLIED_REFUND, null, OperatorOrderFilter(), T_THRESHOLD, cursor, 101)

        assertThat(planOf(stale)).`as`(stale.sql).containsIgnoringCase("idx_payment_refunds_status")
        assertThat(planOf(unapplied)).`as`(unapplied.sql).containsIgnoringCase("idx_payment_refunds_unapplied")
        if (DbTestDatabase.vendor == "postgresql") {
            // 환불 인덱스 이름이 계획에 있다는 것만으로는 주문 표 전체 훑기가 아님을 말하지 못한다 - 주문 표를 순차로도, 만든 시각 인덱스를 거꾸로도 훑지 않는다
            listOf(stale, unapplied).forEach { statement ->
                assertThat(planOf(statement)).`as`(statement.sql).doesNotContain("Seq Scan on payment_orders").doesNotContain("idx_payment_orders_created")
            }
        }
        if (DbTestDatabase.vendor == "mysql") {
            assertThat(mysqlAccessTypes(stale)).`as`(stale.sql).doesNotContain("all")
            assertThat(mysqlAccessTypes(unapplied)).`as`(unapplied.sql).doesNotContain("all")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // 정상 상태 — 지급 · 회수가 모두 끝나 「확인 필요」의 지급 · 회수 갈래가 **0 줄**일 때. 위의 `loadMixed` 는 미지급 여섯 줄이 섞여 있어 계획기가 인덱스를 고르지만, 0 줄이면 추정이
    // 약 1,400 줄(닫힌 주문의 비율이 `fulfilled_at is null` 로 보이고 상태와의 상관을 모른다)이라 `idx_payment_orders_created` 를 거꾸로 훑으며 거르는 쪽(= 전부 읽기)으로 기운다.
    // 운영자 화면은 이 질의를 폴링마다 부르므로 정상 상태가 가장 흔한 경우다. 실제로 읽은 줄 수를 본다: PostgreSQL 은 `explain analyze` 의 `Rows Removed by Filter` 와 인덱스 읽은 줄.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `healthy ledger - the unfulfilled, unapplied and stale refund statements find no rows without reading the closed orders or refunds`() {
        loadHealthy()
        val store = PaymentDb.newStore()

        listOf(null, OrderCursor(T_CURSOR, "ord_plan_999999")).forEach { cursor ->
            listOf(PaymentOrderStatus.PAID, PaymentOrderStatus.PARTIALLY_REFUNDED).forEach { status ->
                // 부분 환불된 줄은 이 데이터에 없다 — 빈 범위라 상태 인덱스를 읽어도 읽는 줄이 0 이다
                val index = if (status == PaymentOrderStatus.PAID) "idx_payment_orders_unfulfilled" else "idx_payment_orders_(unfulfilled|status)"
                assertNoFullRead(store.orderListStatement(OperatorOrderScope.UNFULFILLED, status, OperatorOrderFilter(), T_THRESHOLD, cursor, 51), index, HEALTHY_ORDERS)
            }
            assertNoFullRead(store.orderListStatement(OperatorOrderScope.UNAPPLIED_REFUND, null, OperatorOrderFilter(), T_THRESHOLD, cursor, 101), "idx_payment_refunds_unapplied", HEALTHY_ORDERS)
            assertNoFullRead(store.orderListStatement(OperatorOrderScope.STALE_REFUND, null, OperatorOrderFilter(accountId = "acc_7"), T_THRESHOLD, cursor, 101), "idx_payment_refunds_status", HEALTHY_ORDERS)
        }
    }

    /**
     * 이 문장이 [index] 를 고르고 **전부 읽지 않는다**. PostgreSQL 은 `explain (analyze)` 로 실제 읽은 줄을 본다: 필터가 버린 줄(`Rows Removed by Filter`)의 합이 [total] 의 1% 아래이고
     * 주문 표를 `idx_payment_orders_created` 로 훑지 않는다(죽은 인덱스 항목 때문에 앞선 시험이 남긴 줄이 인덱스 노드의 줄 수에 섞이므로 그 수는 보지 않는다). MySQL 은 접근 방식이 전체 훑기가 아니다.
     */
    private fun assertNoFullRead(statement: JdbcPaymentLedgerStore.OrderListStatement, index: String, total: Int) {
        val plan = planOf(statement, analyze = true)
        assertThat(plan).`as`(statement.sql + "\n" + plan).containsPattern("(?i)$index")
        if (DbTestDatabase.vendor == "postgresql") {
            val removed = Regex("Rows Removed by Filter: (\\d+)").findAll(plan).sumOf { it.groupValues[1].toLong() }
            assertThat(removed).`as`("rows read and thrown away by a filter\n${statement.sql}\n$plan").isLessThan((total / 100).toLong())
            assertThat(plan).`as`("the orders were walked newest first through the created index\n${statement.sql}\n$plan").doesNotContain("idx_payment_orders_created")
        } else {
            // 실제 표(`payment_orders` · `payment_refunds`)의 접근 방식만 본다 - 준조회를 굳힌 임시 표(`<subquery2>`)는 `ALL` 로 보이지만 메모리 안의 작은 집합이다
            val access = PaymentDb.jdbc.queryForList("explain ${statement.sql}", statement.params)
                .filter { row -> !row.entries.first { it.key.equals("table", ignoreCase = true) }.value.toString().startsWith("<") }
                .map { row -> row.entries.first { it.key.equals("type", ignoreCase = true) }.value.toString().lowercase() }
            assertThat(access).`as`(statement.sql).isNotEmpty().doesNotContain("all", "index")
        }
    }

    /** 이 문장의 실행 계획 문자열: PostgreSQL 은 계획 문장(`analyze` 면 실제 줄 수까지), MySQL 은 `key` 열(실제로 고른 인덱스; NULL 이면 "null") 과 refund 쪽 인덱스. */
    private fun planOf(statement: JdbcPaymentLedgerStore.OrderListStatement, analyze: Boolean = false): String {
        val explain = if (DbTestDatabase.vendor == "postgresql" && analyze) "explain (analyze, timing off)" else "explain"
        val rows = PaymentDb.jdbc.queryForList("$explain ${statement.sql}", statement.params)
        val plan = rows.joinToString("\n") { row -> row.values.joinToString(" | ") }
        println("PLAN[${DbTestDatabase.vendor}] ${statement.sql}\n$plan")
        return if (DbTestDatabase.vendor == "postgresql") plan
        else rows.joinToString(",") { row -> row.entries.first { it.key.equals("key", ignoreCase = true) }.value.toString() }
    }

    private fun mysqlAccessTypes(statement: JdbcPaymentLedgerStore.OrderListStatement): List<String> =
        PaymentDb.jdbc.queryForList("explain ${statement.sql}", statement.params).map { row -> row.entries.first { it.key.equals("type", ignoreCase = true) }.value.toString().lowercase() }

    private fun ts(value: String): String = if (DbTestDatabase.vendor == "postgresql") "'$value+00'" else "'$value'"

    /**
     * 3만 주문(거의 전부 지급까지 끝난 `PAID`, 소수의 `FAILED` · `EXPIRED` · 지급 안 된 `PAID` · 오래 `CONFIRMING`) + 환불 만 줄(대부분 회수까지 끝난 `SUCCEEDED`, 소수의 `PENDING` · `REQUESTED` ·
     * 회수 안 된 `SUCCEEDED`) + 이력 3만 줄. 실제 운영에서 「확인 필요」는 극소수라는 모양을 흉내 낸다 — 계획이 소수를 찾으려고 전부 읽지 않는지 본다.
     */
    private fun loadMixed() {
        val base = LocalDateTime.of(2026, 10, 8, 0, 0, 0)
        val zone = if (DbTestDatabase.vendor == "postgresql") "+00" else ""
        fun at(n: Int) = "'${base.plusSeconds(n.toLong()).format(TS)}$zone'"
        (0 until 30_000).chunked(1000).forEach { chunk ->
            val values = chunk.joinToString(",") { n ->
                val status = when {
                    n % 1_000 == 3 -> "CONFIRMING"
                    n % 20 == 1 -> "FAILED"
                    n % 50 == 2 -> "EXPIRED"
                    else -> "PAID"
                }
                val paid = status == "PAID"
                val fulfilled = if (paid && n % 5_000 != 4) at(n + 1) else "null"
                val paidAt = if (paid) at(n) else "null"
                val confirmStarted = if (status == "CONFIRMING") at(n) else "null"
                val paymentKey = if (paid) "'pay_plan_$n'" else "null"
                val review = if (n % 10_000 == 0) "'AMOUNT_MISMATCH'" else "null"
                "('ord_plan_$n', 'acc_${n % 500}', 'ONE_TIME', '$status', 'scripted', 'TEST', 'KRW', 3000, 'cheer', 'cheer-3000', 'Cheer', 'CHECKOUT', $paymentKey, $review, $paidAt, $fulfilled, $confirmStarted, ${at(n)}, ${at(n)}, ${at(n)})"
            }
            PaymentDb.jdbc.jdbcOperations.execute(
                """insert into payment_orders (id, account_id, kind, status, provider, provider_mode, currency, amount, item_type, item_ref, order_name, origin_type,
                                               provider_payment_id, review_reason, paid_at, fulfilled_at, confirm_started_at, checkout_expires_at, created_at, updated_at) values $values""",
            )
        }
        (0 until 10_000).chunked(1000).forEach { chunk ->
            val values = chunk.joinToString(",") { n ->
                val (status, open) = when {
                    n % 3_000 == 7 -> "PENDING" to "'ord_plan_$n'"
                    n % 1_000 == 9 -> "REQUESTED" to "'ord_plan_$n'"
                    else -> "SUCCEEDED" to "null"
                }
                val sent = if (status == "PENDING") at(n) else "null"
                val completed = if (status == "SUCCEEDED") at(n + 2) else "null"
                val applied = if (status == "SUCCEEDED" && n % 2_000 != 5) at(n + 3) else "null"
                "('rfd_plan_$n', 'ord_plan_$n', '$status', 'USER', 1000, 'KRW', 'CUSTOMER_REQUEST', $open, 'acc_${n % 500}', ${at(n)}, $sent, $completed, $applied, ${at(n)}, ${at(n)})"
            }
            PaymentDb.jdbc.jdbcOperations.execute(
                """insert into payment_refunds (id, order_id, status, origin, amount, currency, reason_code, open_key, requested_by, requested_at, sent_at, completed_at, applied_at, created_at, updated_at)
                   values $values""",
            )
        }
        (0 until 30_000).chunked(1000).forEach { chunk ->
            val values = chunk.joinToString(",") { n -> "('ord_plan_${n % 10_000}', 'ORDER_CREATED', 'USER', 'acc_${(n % 10_000) % 500}', ${at(n)})" }
            PaymentDb.jdbc.jdbcOperations.execute("insert into payment_events (order_id, type, actor, actor_id, created_at) values $values")
        }
        listOf("payment_orders", "payment_refunds", "payment_events").forEach { table ->
            if (DbTestDatabase.vendor == "postgresql") PaymentDb.jdbc.jdbcOperations.execute("analyze $table") else PaymentDb.jdbc.jdbcOperations.execute("analyze table $table")
        }
    }

    /**
     * 정상 상태 [HEALTHY_ORDERS] 주문(닫힌 주문 9% — `FAILED` 7% · `EXPIRED` 2%, 지급 시각은 비어 있다; 나머지는 지급까지 끝난 `PAID`) + 환불 [HEALTHY_REFUNDS] 줄(대부분 회수까지 끝난 `SUCCEEDED`,
     * 7% `FAILED` · 3% `REJECTED`). **미지급 `PAID` · 미회수 `SUCCEEDED` · 묶인 `PENDING` 은 0 줄**이다.
     */
    private fun loadHealthy() {
        val base = LocalDateTime.of(2026, 10, 8, 0, 0, 0)
        val zone = if (DbTestDatabase.vendor == "postgresql") "+00" else ""
        fun at(n: Int) = "'${base.plusSeconds(n.toLong()).format(TS)}$zone'"
        (0 until HEALTHY_ORDERS).chunked(2000).forEach { chunk ->
            val values = chunk.joinToString(",") { n ->
                val status = when {
                    n % 100 < 7 -> "FAILED"
                    n % 100 in 7..8 -> "EXPIRED"
                    else -> "PAID"
                }
                val paid = status == "PAID"
                val paidAt = if (paid) at(n) else "null"
                val fulfilled = if (paid) at(n + 1) else "null"
                val paymentKey = if (paid) "'pay_plan_$n'" else "null"
                "('ord_plan_$n', 'acc_${n % 500}', 'ONE_TIME', '$status', 'scripted', 'TEST', 'KRW', 3000, 'cheer', 'cheer-3000', 'Cheer', 'CHECKOUT', $paymentKey, null, $paidAt, $fulfilled, ${at(n)}, ${at(n)}, ${at(n)})"
            }
            PaymentDb.jdbc.jdbcOperations.execute(
                """insert into payment_orders (id, account_id, kind, status, provider, provider_mode, currency, amount, item_type, item_ref, order_name, origin_type,
                                               provider_payment_id, review_reason, paid_at, fulfilled_at, checkout_expires_at, created_at, updated_at) values $values""",
            )
        }
        (0 until HEALTHY_REFUNDS).chunked(2000).forEach { chunk ->
            val values = chunk.joinToString(",") { n ->
                val status = when {
                    n % 100 < 7 -> "FAILED"
                    n % 100 in 7..9 -> "REJECTED"
                    else -> "SUCCEEDED"
                }
                val succeeded = status == "SUCCEEDED"
                val completed = if (succeeded) at(n + 2) else "null"
                val applied = if (succeeded) at(n + 3) else "null"
                "('rfd_plan_$n', 'ord_plan_${n * 3}', '$status', 'USER', 1000, 'KRW', 'CUSTOMER_REQUEST', null, 'acc_${(n * 3) % 500}', ${at(n)}, null, $completed, $applied, ${at(n)}, ${at(n)})"
            }
            PaymentDb.jdbc.jdbcOperations.execute(
                """insert into payment_refunds (id, order_id, status, origin, amount, currency, reason_code, open_key, requested_by, requested_at, sent_at, completed_at, applied_at, created_at, updated_at)
                   values $values""",
            )
        }
        listOf("payment_orders", "payment_refunds").forEach { table ->
            if (DbTestDatabase.vendor == "postgresql") PaymentDb.jdbc.jdbcOperations.execute("analyze $table") else PaymentDb.jdbc.jdbcOperations.execute("analyze table $table")
        }
    }

    /** 3만 줄 — 거의 전부 `PROCESSED`, 열 줄만 `FAILED`, 다섯 줄 `DEAD`. 천 줄씩 한 문장으로 넣는다. */
    private fun loadInbox() {
        val base = LocalDateTime.of(2026, 10, 8, 0, 0, 0)
        val zone = if (DbTestDatabase.vendor == "postgresql") "+00" else ""
        (0 until 30_000).chunked(1000).forEach { chunk ->
            val values = chunk.joinToString(",") { n ->
                val status = when {
                    n % 3_000 == 7 -> "FAILED"
                    n % 6_000 == 11 -> "DEAD"
                    else -> "PROCESSED"
                }
                val ts = "'${base.plusSeconds(n.toLong()).format(TS)}$zone'"
                "('scripted', 'evt-plan-$n', 'PAYMENT_STATUS_CHANGED', 'PAYMENT', 'pay_$n', 'ord_plan_${n % 3_000}', '${"a".repeat(64)}', '$status', $ts)"
            }
            PaymentDb.jdbc.jdbcOperations.execute(
                """insert into payment_webhook_events (provider, event_key, event_type, resource_type, resource_id, order_id, payload_hash, status, received_at) values $values""",
            )
        }
        if (DbTestDatabase.vendor == "postgresql") PaymentDb.jdbc.jdbcOperations.execute("analyze payment_webhook_events") else PaymentDb.jdbc.jdbcOperations.execute("analyze table payment_webhook_events")
    }

    /** 계획이 **실제로 고른** 인덱스를 담은 문자열: PostgreSQL 은 계획 문장, MySQL 은 `key` 열(NULL 이면 "null"). */
    private fun indexUsed(query: String): String {
        val rows = PaymentDb.jdbc.queryForList("explain $query", emptyMap<String, Any>())
        println("PLAN[${DbTestDatabase.vendor}] $query\n" + rows.joinToString("\n") { row -> row.values.joinToString(" | ") })
        return if (DbTestDatabase.vendor == "postgresql") rows.joinToString("\n") { row -> row.values.joinToString(" | ") }
        else rows.joinToString(",") { row -> row.entries.first { it.key.equals("key", ignoreCase = true) }.value.toString() }
    }

    /** MySQL `explain` 의 접근 방식(`type` 열) — `ALL`(전체 테이블) · `index`(전체 인덱스) 가 아닌 `ref` · `range` 인지 가르려고. */
    /** MySQL `explain` 의 모든 행의 접근 방식 — 준조회가 어느 표도 전체 훑기(`all`)로 읽지 않는지. */
    private fun mysqlAccessTypes(query: String): List<String> =
        PaymentDb.jdbc.queryForList("explain $query", emptyMap<String, Any>()).map { row -> row.entries.first { it.key.equals("type", ignoreCase = true) }.value.toString().lowercase() }

    private companion object {
        val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val T_THRESHOLD: Instant = Instant.parse("2026-10-08T06:00:00Z")
        val T_CURSOR: Instant = Instant.parse("2026-10-08T05:00:00Z")
        const val HEALTHY_ORDERS = 120_000
        const val HEALTHY_REFUNDS = 40_000
    }
}
