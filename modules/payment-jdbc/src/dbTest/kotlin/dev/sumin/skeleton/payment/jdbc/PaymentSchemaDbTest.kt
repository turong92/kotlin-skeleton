package dev.sumin.skeleton.payment.jdbc

import java.sql.DatabaseMetaData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException

/** 표 수준 시험(스펙 §5 · §13.2) — 저장소를 거치지 않고 SQL 로 직접 넣어 제약이 DB 에서 막는지 본다. 환불 · 웹훅 표는 B5 · B6 이 쓸 것이지만 표와 유니크는 지금 있다. */
class PaymentSchemaDbTest {
    @BeforeTest
    fun clean() = PaymentDb.clean()



    // ---- check 제약 (이 저장소 마이그레이션에서 처음 쓰였다 — 두 방언이 모두 집행하는지 실측) ----

    @Test
    fun `the check constraints refuse a negative amount, a refund above the paid amount - the ordered amount when nothing was paid -, a zero refund and a five-character card tail`() {
        refusedBy("ck_payment_orders_amounts") { PaymentDb.insertOrderRow(PaymentDb.unique("ord"), amount = -1) }
        // 결제액이 있으면 그것이 상한이다(같으면 된다)
        refusedBy("ck_payment_orders_amounts") { PaymentDb.insertOrderRow(PaymentDb.unique("ord"), amount = 5000, paidAmount = 3000, refundedAmount = 3001) }
        PaymentDb.insertOrderRow(PaymentDb.unique("ord"), amount = 5000, paidAmount = 3000, refundedAmount = 3000)
        // 결제액이 없으면 주문 금액이 상한이다 - coalesce 가 check 안에서 돈다
        refusedBy("ck_payment_orders_amounts") { PaymentDb.insertOrderRow(PaymentDb.unique("ord"), amount = 3000, paidAmount = null, refundedAmount = 3001) }

        val order = PaymentDb.unique("ord")
        PaymentDb.insertOrderRow(order)
        refusedBy("ck_payment_refunds_amount") { PaymentDb.insertRefundRow(PaymentDb.unique("rfd"), order, null, null, null, amount = 0) }
        // 카드 끝 네 자리 칸은 마지막 방어선이다
        assertFailsWith<DataIntegrityViolationException> { PaymentDb.insertOrderRow(PaymentDb.unique("ord"), cardLast4 = "12345") }
    }

    // ---- 유니크 (저장소 시험이 주문의 둘을 재고, 여기서는 환불 · 웹훅의 넷을 16 스레드로 잰다) ----

    @Test
    fun `sixteen threads opening a refund on one order leave exactly one open refund - and a closed refund frees the open key`() {
        val order = PaymentDb.unique("ord")
        PaymentDb.insertOrderRow(order)

        val results = race(16) { n -> PaymentDb.insertRefundRow("rfd_$n${PaymentDb.unique("")}", order, openKey = order, dedupeKey = null, providerRefundId = null) }

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is DuplicateKeyException }, results.mapNotNull { it.exceptionOrNull() }.toString())
        PaymentDb.jdbc.update("update payment_refunds set open_key = null, status = 'FAILED' where order_id = :o", mapOf("o" to order))
        PaymentDb.insertRefundRow(PaymentDb.unique("rfd"), order, openKey = order, dedupeKey = null, providerRefundId = null)
    }

    @Test
    fun `sixteen threads matching one provider refund id leave exactly one refund row - the same id on another order is fine`() {
        val order = PaymentDb.unique("ord")
        val other = PaymentDb.unique("ord")
        PaymentDb.insertOrderRow(order)
        PaymentDb.insertOrderRow(other)

        val results = race(16) { n -> PaymentDb.insertRefundRow(PaymentDb.unique("rfd$n"), order, openKey = null, dedupeKey = null, providerRefundId = "txn-1") }

        assertEquals(1, results.count { it.isSuccess })
        PaymentDb.insertRefundRow(PaymentDb.unique("rfd"), other, openKey = null, dedupeKey = null, providerRefundId = "txn-1")
    }

    @Test
    fun `sixteen threads receiving one webhook event key create exactly one inbox row - per provider, and provider ids are case sensitive`() {
        val results = race(16) { PaymentDb.insertWebhookRow("scripted", "tx-1") }

        assertEquals(1, results.count { it.isSuccess })
        PaymentDb.insertWebhookRow("another", "tx-1")
        PaymentDb.insertWebhookRow("scripted", "Tx-A")
        PaymentDb.insertWebhookRow("scripted", "tx-a")
        PaymentDb.insertOrderRow(PaymentDb.unique("ord"), providerPaymentId = "PayKey")
        PaymentDb.insertOrderRow(PaymentDb.unique("ord"), providerPaymentId = "paykey")
    }

    // ---- 인덱스 · 유니크 (이름 → 열 구성까지) ----

    @Test
    fun `every index and unique constraint of the design exists with its name and its columns in order`() {
        val expected = mapOf(
            "payment_orders" to mapOf(
                "idx_payment_orders_account" to listOf("account_id", "created_at", "id"),            // 내 결제 내역
                "idx_payment_orders_status" to listOf("status", "created_at", "id"),                  // 운영자 목록 (상태 필터)
                "idx_payment_orders_origin" to listOf("origin_type", "origin_ref", "created_at"),     // 한 구독의 결제 이력
                "idx_payment_orders_review" to listOf("review_reason", "created_at", "id"),           // 확인 필요 목록 (커서 = created_at, id)
                "idx_payment_orders_created" to listOf("created_at", "id"),                           // 운영자 기본 목록 (필터 없음, 최신순 커서)
                "idx_payment_orders_unfulfilled" to listOf("fulfilled_at", "status", "created_at", "id"),   // 확인 필요: 돈은 받았는데 지급이 안 끝난 줄
                "uq_payment_orders_provider_payment" to listOf("provider", "provider_payment_id"),
                "uq_payment_orders_dedupe" to listOf("dedupe_key"),
            ),
            "payment_refunds" to mapOf(
                "idx_payment_refunds_order" to listOf("order_id", "created_at"),
                "idx_payment_refunds_status" to listOf("status", "requested_at", "id"),               // 환불 요청 목록 (커서)
                "idx_payment_refunds_unapplied" to listOf("applied_at", "status", "requested_at", "id"),   // 확인 필요: 환불은 됐는데 회수가 안 끝난 줄
                "uq_payment_refunds_provider" to listOf("order_id", "provider_refund_id"),
                "uq_payment_refunds_open" to listOf("open_key"),
                "uq_payment_refunds_dedupe" to listOf("dedupe_key"),
            ),
            "payment_events" to mapOf("idx_payment_events_order" to listOf("order_id", "id")),
            "payment_webhook_events" to mapOf(
                "idx_payment_webhook_events_status" to listOf("status", "received_at", "id"),         // 수신함 목록 (커서)
                "idx_payment_webhook_events_resource" to listOf("provider", "resource_type", "resource_id"),
                "idx_payment_webhook_events_order" to listOf("order_id", "received_at"),                // 주문 단위 창 상한 (주문 줄 락 안에서 센다)
                "uq_payment_webhook_events" to listOf("provider", "event_key"),
            ),
        )
        PaymentDb.dataSource.connection.use { connection ->
            expected.forEach { (table, indexes) ->
                val found = linkedMapOf<String, MutableList<Pair<Int, String>>>()
                connection.metaData.getIndexInfo(null, connection.schema, table, false, false).use { rs ->
                    while (rs.next()) {
                        val name = rs.getString("INDEX_NAME")?.lowercase() ?: continue
                        found.getOrPut(name) { mutableListOf() } += rs.getInt("ORDINAL_POSITION") to rs.getString("COLUMN_NAME").lowercase()
                    }
                }
                val columnsByName = found.mapValues { (_, cols) -> cols.sortedBy { it.first }.map { it.second } }
                indexes.forEach { (name, columns) -> assertEquals(columns, columnsByName[name], "$table.$name (found: ${columnsByName.keys})") }
                // 이름 없는 유니크(PostgreSQL 의 `payment_orders_dedupe_key_key` 같은 자동 이름)가 남지 않는다 - 기본키만 자동 이름
                val unnamed = columnsByName.keys.filter { it !in indexes.keys && it != "primary" && !it.endsWith("_pkey") }
                assertEquals(emptyList(), unnamed, "$table: every index and unique constraint is named by the migration")
            }
        }
    }

    @Test
    fun `the columns that hold account ids and the origin reference are binary-collated on MySQL like the order account id`() {
        if (DbTestDatabase.vendor != "mysql") return
        val collations = PaymentDb.jdbc.queryForList(
            """select concat(table_name, '.', column_name) as c, collation_name as n from information_schema.columns
               where table_schema = database() and column_name in ('account_id', 'requested_by', 'decided_by', 'actor_id', 'origin_ref')""",
            emptyMap<String, Any>(),
        ).associate { it["c"].toString().lowercase() to it["n"].toString() }

        // origin_ref: 2단계 구독 id(구독 표의 이진 기본키)와 조인한다 — 정렬이 다르면 MySQL 이 조인을 거절하거나 인덱스를 못 탄다
        assertEquals(
            setOf("payment_orders.account_id", "payment_orders.origin_ref", "payment_refunds.requested_by", "payment_refunds.decided_by", "payment_events.actor_id", "payment_subscriptions.account_id"),
            collations.keys,
        )
        collations.forEach { (column, collation) -> assertEquals("utf8mb4_bin", collation, column) }
    }

    // ---- 열 분류 (스펙 §5.4) ----

    @Test
    fun `every column is classified and the personal data columns are card issuer and last four only`() {
        val actual = PaymentDb.jdbc.queryForList(
            """select table_name as t, column_name as c, data_type as d, character_maximum_length as len from information_schema.columns
               where table_name like 'payment\_%' and table_schema = ${schemaOfConnection()} order by table_name, ordinal_position""",
            emptyMap<String, Any>(),
        ).map { Triple("${it["t"]}.${it["c"]}".lowercase(), it["d"].toString().lowercase(), (it["len"] as Number?)?.toLong()) }

        val unclassified = actual.map { it.first }.filter { it !in COLUMN_CLASSES }
        assertEquals(emptyList(), unclassified, "새 열은 분류표(COLUMN_CLASSES)에 올려야 한다 - 조용히 개인정보를 담지 못하게")
        assertEquals(emptyList(), COLUMN_CLASSES.keys.filter { key -> actual.none { it.first == key } }, "분류표에 있는데 표에 없는 열")

        val personal = COLUMN_CLASSES.filterValues { it == ColumnClass.ERASED_PERSONAL }.keys
        assertEquals(setOf("payment_orders.card_issuer", "payment_orders.card_last4", "payment_subscriptions.card_issuer", "payment_subscriptions.card_last4"), personal)

        // 앱이 쓰는 문장은 이름 하나뿐이다 - 코드 칸으로 분류해 두면 "코드니까 문장이 아니다"로 읽힌다
        val labels = COLUMN_CLASSES.filterValues { it == ColumnClass.LABEL }.keys
        assertEquals(setOf("payment_orders.order_name"), labels)

        // 자유 문장이 들어올 자리가 없다: 글 칸은 수신함의 payload(원문) 하나뿐이고 문자열 칸은 모두 짧다
        val textColumns = actual.filter { it.second in setOf("text", "mediumtext", "longtext") }.map { it.first }
        assertEquals(listOf("payment_webhook_events.payload"), textColumns)
        assertEquals(ColumnClass.RAW, COLUMN_CLASSES["payment_webhook_events.payload"])
        // 빌링키의 암호문은 문장이 아니라 불투명한 값이다 — 길이 상한이 따로 있다
        assertThat(actual.filter { it.third != null && it.first != "payment_webhook_events.payload" && it.first != "payment_subscriptions.billing_token" }.map { it.third!! }).allMatch { it <= 200 }
        assertThat(actual.single { it.first == "payment_subscriptions.billing_token" }.third!!).isLessThanOrEqualTo(512)
    }

    /**
     * 제약 이름까지 확인한다 — 다른 이유로 막힌 것을 check 가 막았다고 착각하지 않게. 예외 종류는 방언마다 다르다
     * (PostgreSQL: DataIntegrityViolationException, MySQL 8.4: 번역되지 않은 UncategorizedSQLException 3819 — 제약은 두 DB 모두 집행한다).
     */
    private fun refusedBy(constraint: String, block: () -> Unit) {
        val failure = assertFailsWith<org.springframework.dao.DataAccessException> { block() }
        assertThat(generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")).contains(constraint)
    }

    private fun schemaOfConnection(): String = if (DbTestDatabase.vendor == "postgresql") "current_schema()" else "database()"

    /** LABEL = 앱이 정하는 문장(결제창 · 내역에 보이는 이름) — 코드가 아니다. 개인정보를 넣지 않는 것은 앱(카탈로그)의 책임이다. */
    private enum class ColumnClass { IDENTIFIER, CODE, AMOUNT, TIME, RAW, ERASED_PERSONAL, LABEL, SECRET }

    private companion object {
        private fun cols(table: String, kind: ColumnClass, vararg names: String) = names.associate { "$table.$it" to kind }

        val COLUMN_CLASSES: Map<String, ColumnClass> = buildMap {
            putAll(cols("payment_orders", ColumnClass.IDENTIFIER, "id", "account_id", "provider_payment_id", "item_ref", "origin_ref", "dedupe_key"))
            putAll(cols("payment_orders", ColumnClass.CODE, "kind", "status", "provider", "provider_mode", "currency", "item_type", "origin_type", "method", "failure_code", "review_reason", "quantity"))
            putAll(cols("payment_orders", ColumnClass.LABEL, "order_name"))
            putAll(cols("payment_orders", ColumnClass.AMOUNT, "amount", "paid_amount", "tax_amount", "refunded_amount"))
            putAll(cols("payment_orders", ColumnClass.TIME, "checkout_expires_at", "confirm_started_at", "paid_at", "fulfilled_at", "closed_at", "account_erased_at", "created_at", "updated_at"))
            putAll(cols("payment_orders", ColumnClass.ERASED_PERSONAL, "card_issuer", "card_last4"))

            // 구독 — 카드 표시 두 칸은 지워지는 개인정보이고, 고객 키 · 빌링키(암호문)는 비밀이다(응답 · 로그에 나가지 않는다)
            putAll(cols("payment_subscriptions", ColumnClass.IDENTIFIER, "id", "account_id", "charge_order_id", "active_key", "dedupe_key", "provider_subscription_id"))
            putAll(cols("payment_subscriptions", ColumnClass.CODE, "provider", "provider_mode", "status", "plan_code", "currency", "period", "period_seq", "attempt", "pending_plan_code", "end_reason", "country"))
            putAll(cols("payment_subscriptions", ColumnClass.AMOUNT, "amount", "pending_amount"))
            putAll(cols("payment_subscriptions", ColumnClass.TIME, "anchor_at", "current_period_start", "current_period_end", "ended_at", "account_erased_at", "created_at", "updated_at", "provider_synced_at"))
            putAll(cols("payment_subscriptions", ColumnClass.ERASED_PERSONAL, "card_issuer", "card_last4"))
            putAll(cols("payment_subscriptions", ColumnClass.SECRET, "customer_key", "billing_token"))

            putAll(cols("payment_refunds", ColumnClass.IDENTIFIER, "id", "order_id", "open_key", "dedupe_key", "provider_refund_id", "requested_by", "decided_by"))
            putAll(cols("payment_refunds", ColumnClass.CODE, "status", "origin", "currency", "reason_code", "failure_code"))
            putAll(cols("payment_refunds", ColumnClass.AMOUNT, "amount", "expected_remaining", "requested_amount"))
            putAll(cols("payment_refunds", ColumnClass.TIME, "requested_at", "sent_at", "completed_at", "applied_at", "created_at", "updated_at"))

            putAll(cols("payment_events", ColumnClass.IDENTIFIER, "id", "order_id", "refund_id", "actor_id", "provider_trace_id", "trace_id"))
            putAll(cols("payment_events", ColumnClass.CODE, "type", "from_status", "to_status", "actor", "provider_code"))
            putAll(cols("payment_events", ColumnClass.TIME, "created_at"))

            putAll(cols("payment_webhook_events", ColumnClass.IDENTIFIER, "id", "provider", "event_key", "resource_id", "order_id", "payload_hash"))
            putAll(cols("payment_webhook_events", ColumnClass.CODE, "event_type", "resource_type", "status", "deliveries", "last_error"))
            putAll(cols("payment_webhook_events", ColumnClass.RAW, "payload"))
            putAll(cols("payment_webhook_events", ColumnClass.TIME, "received_at", "processed_at"))
        }
    }
}
