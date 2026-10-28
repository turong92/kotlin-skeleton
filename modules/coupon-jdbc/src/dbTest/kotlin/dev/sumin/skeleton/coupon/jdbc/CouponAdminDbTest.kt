package dev.sumin.skeleton.coupon.jdbc

import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.discount.CurrencyAmount
import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountTotals
import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountRejectedException
import dev.sumin.skeleton.common.discount.DiscountRejection
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountTarget
import dev.sumin.skeleton.coupon.Coupon
import dev.sumin.skeleton.coupon.CouponDefinition
import dev.sumin.skeleton.coupon.CouponDefinitionInput
import dev.sumin.skeleton.coupon.CouponDefinitions
import dev.sumin.skeleton.coupon.CouponErrorCode
import dev.sumin.skeleton.coupon.CouponException
import dev.sumin.skeleton.coupon.CouponIssuance
import dev.sumin.skeleton.coupon.CouponMoney
import dev.sumin.skeleton.coupon.CouponReports
import dev.sumin.skeleton.coupon.CouponSource
import dev.sumin.skeleton.coupon.CouponStageInput
import dev.sumin.skeleton.coupon.CouponStatus
import dev.sumin.skeleton.coupon.CouponStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient

/** 발행 기간 · 사용 시작 · 현황 · 발급 목록 · 대시보드 기여가 두 방언(postgresTest · mysqlTest)에서 같은 뜻인지 */
@Import(DbTestcontainers::class, CouponTimeConfig::class)
@SpringBootTest(classes = [CouponJdbcTestApplication::class])
class CouponAdminDbTest {
    @Autowired lateinit var definitions: CouponDefinitions
    @Autowired lateinit var issuance: CouponIssuance
    @Autowired lateinit var reports: CouponReports
    @Autowired lateinit var source: DiscountSource
    @Autowired lateinit var store: CouponStore
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var dialect: SqlDialect
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var base: Instant
    private val seq = UUID.randomUUID().toString().take(8)
    private var n = 0

    @BeforeEach
    fun fresh() {
        base = Instant.now().minusSeconds(3600).let { Instant.ofEpochSecond(it.epochSecond) }
        time.current = base
    }

    private fun define(
        name: String = "c $seq ${n++}",
        issueStartsAt: Instant? = null,
        issueEndsAt: Instant? = null,
        validFrom: Instant? = null,
        validUntil: Instant? = null,
        perAccountLimit: Int = 50,
    ): CouponDefinition = definitions.create(
        CouponDefinitionInput(
            name = name, stages = listOf(CouponStageInput("PERCENT", 30, null, 1)), validUntil = validUntil,
            issueStartsAt = issueStartsAt, issueEndsAt = issueEndsAt, validFrom = validFrom, perAccountLimit = perAccountLimit,
        ),
    )

    private fun give(def: CouponDefinition, account: String = "acc_${seq}_${n++}"): Coupon =
        assertNotNull(issuance.issue(def.id, account, CouponSource.ADMIN, "adm_${seq}_${n++}"))

    private fun target(account: String) = DiscountTarget(account, "cheer", "item", null, 10_000, "KRW", DiscountPurpose.ONE_TIME, true)

    @Test
    fun `the window and the use start survive the table, and the issuing boundaries hold in the database`() {
        val opens = base.plusSeconds(100)
        val closes = base.plusSeconds(200)
        val def = define(issueStartsAt = opens, issueEndsAt = closes, validFrom = opens.plusSeconds(10))
        val read = store.findDefinition(def.id)!!
        assertEquals(Triple(opens, closes, opens.plusSeconds(10)), Triple(read.issueStartsAt, read.issueEndsAt, read.validFrom))
        val plain = store.findDefinition(define().id)!!
        assertEquals(Triple(null, null, null), Triple(plain.issueStartsAt, plain.issueEndsAt, plain.validFrom))

        fun closed(ref: String) = assertEquals(CouponErrorCode.ISSUE_CLOSED, assertFailsWith<CouponException> { issuance.issue(def.id, "acc_w_$seq", CouponSource.ADMIN, ref) }.couponError)
        closed("before")
        time.current = opens.minusMillis(1)
        closed("just-before")
        time.current = opens
        assertNotNull(issuance.issue(def.id, "acc_w_$seq", CouponSource.ADMIN, "at-start"))
        time.current = closes.minusMillis(1)
        assertNotNull(issuance.issue(def.id, "acc_w_$seq", CouponSource.ADMIN, "last-instant"))
        time.current = closes
        closed("at-end")
        assertEquals(2, store.findDefinition(def.id)!!.issuedCount, "a closed attempt leaves no coupon and no count")
        assertEquals(2, jdbc.sql("select count(*) from coupons where definition_id = :d").param("d", def.id).query(Long::class.java).single().toInt())
    }

    @Test
    fun `a coupon is not reservable before its use start in the database, and is from that instant`() {
        val start = base.plusSeconds(500)
        val def = define(validFrom = start)
        val account = "acc_u_$seq"
        val coupon = give(def, account)
        val claim = DiscountClaim("coupon", coupon.id)

        assertEquals(DiscountRejection.UNAVAILABLE, assertFailsWith<DiscountRejectedException> { source.reserve(claim, target(account), "ord_early_$seq") }.reason)
        assertEquals(CouponStatus.AVAILABLE, store.findCoupon(coupon.id)!!.status)
        time.current = start.minusMillis(1)
        assertFailsWith<DiscountRejectedException> { source.reserve(claim, target(account), "ord_early_$seq") }
        time.current = start
        source.reserve(claim, target(account), "ord_ok_$seq")
        assertEquals(CouponStatus.RESERVED, store.findCoupon(coupon.id)!!.status)
    }

    @Test
    fun `stats read the status the clock says, and the issued list pages newest first with the same status filter`() {
        val def = define(validUntil = base.plusSeconds(1000))
        val coupons = (1..6).map { give(def, "acc_s${it}_$seq") }
        // 2: 쥠, 3: 씀, 4: 회수, 5: 기한 지남(시간을 옮겨 만든다) 은 아래에서
        source.reserve(DiscountClaim("coupon", coupons[1].id), target(coupons[1].accountId), "ord_r_$seq")
        source.reserve(DiscountClaim("coupon", coupons[2].id), target(coupons[2].accountId), "ord_u_$seq")
        source.confirm("ord_u_$seq")
        jdbc.sql("update coupons set status = 'REVOKED' where id = :id").param("id", coupons[3].id).update()

        val before = reports.stats(def.id)
        assertEquals(listOf(6, 3, 1, 1, 0, 1), listOf(before.issued, before.available, before.reserved, before.used, before.expired, before.revoked))
        time.current = base.plusSeconds(1001)
        val after = reports.stats(def.id)
        assertEquals(listOf(6, 0, 1, 1, 3, 1), listOf(after.issued, after.available, after.reserved, after.used, after.expired, after.revoked), "the three untouched coupons lapsed with the clock")

        val firstPage = reports.coupons(def.id, null, null, 4)
        assertEquals(coupons.map { it.id }.reversed().take(4), firstPage.items.map { it.coupon.id })
        assertEquals(coupons.reversed()[3].id, firstPage.nextCursor)
        val secondPage = reports.coupons(def.id, null, firstPage.nextCursor, 4)
        assertEquals(coupons.map { it.id }.reversed().drop(4), secondPage.items.map { it.coupon.id })
        assertEquals(null, secondPage.nextCursor)

        assertEquals(setOf(coupons[0].id, coupons[4].id, coupons[5].id), reports.coupons(def.id, CouponStatus.EXPIRED, null, 50).items.map { it.coupon.id }.toSet())
        assertEquals(emptyList(), reports.coupons(def.id, CouponStatus.AVAILABLE, null, 50).items)
        val used = reports.coupons(def.id, CouponStatus.USED, null, 50).items.single()
        assertEquals(coupons[2].id, used.coupon.id)
        assertEquals("ord_u_$seq", used.coupon.holderRef)
        assertNotNull(used.coupon.usedAt)
        assertEquals(listOf(coupons[3].id), reports.coupons(def.id, CouponStatus.REVOKED, null, 50).items.map { it.coupon.id })
        assertEquals(CouponErrorCode.DEFINITION_NOT_FOUND, assertFailsWith<CouponException> { reports.stats("cpd_nope") }.couponError)
    }

    @Test
    fun `used coupon ids page by id for one definition, and the report sums what the ledger says per chunk`() {
        val def = define()
        val used = (1..5).map { give(def) }
        val open = give(def)
        val other = give(define())
        used.forEach {
            source.reserve(DiscountClaim("coupon", it.id), target(it.accountId), "ord_${it.id}")
            source.confirm("ord_${it.id}")
        }
        val sorted = used.map { it.id }.sorted()
        assertEquals(sorted.take(2), store.usedCouponIds(def.id, null, 2))
        assertEquals(sorted.drop(2).take(2), store.usedCouponIds(def.id, sorted[1], 2))
        assertEquals(sorted.drop(4), store.usedCouponIds(def.id, sorted[3], 2))
        assertEquals(emptyList(), store.usedCouponIds(def.id, sorted.last(), 2))
        assertEquals(false, store.usedCouponIds(def.id, null, 50).any { it == open.id || it == other.id }, "only used coupons of this definition")

        val asked = mutableListOf<List<String>>()
        val ledger = DiscountTotals { kind, refs ->
            assertEquals("coupon", kind)
            asked += refs.toList()
            listOf(CurrencyAmount("KRW", 100L * refs.size))
        }
        val stats = CouponReports(store, time) { ledger }.stats(def.id)
        assertEquals(listOf(CouponMoney("KRW", 500)), stats.discountTotals)
        assertEquals(sorted, asked.flatten())
        assertEquals(emptyList(), reports.stats(def.id).discountTotals, "the sample assembly has no ledger bean here - unknown, not zero")
    }

    @Test
    fun `the dashboard contribution counts coupons used inside the range and ranks the most used`() {
        // 다른 시험이 같은 DB 에 남긴 사용 기록과 섞이지 않게 아무도 안 쓴 먼 날에서 센다
        time.current = Instant.parse("2031-03-03T10:00:00Z")
        val popular = define(name = "popular $seq")
        val quiet = define(name = "quiet $seq")
        fun use(def: CouponDefinition, at: Instant): Coupon {
            val c = give(def)
            source.reserve(DiscountClaim("coupon", c.id), target(c.accountId), "ord_${c.id}")
            time.current = at
            source.confirm("ord_${c.id}")
            return c
        }
        val zone = ZoneOffset.UTC
        val day0 = time.current.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        use(popular, day0.plusSeconds(100))
        use(popular, day0.plusSeconds(200))
        use(quiet, day0.plusSeconds(300))
        use(popular, day0.plus(Duration.ofDays(1)).plusSeconds(10))
        use(popular, day0.minus(Duration.ofDays(5)))
        val restored = use(quiet, day0.plusSeconds(400))
        source.restore("ord_${restored.id}")

        val template = NamedParameterJdbcTemplate(dataSource)
        val range = DashboardRange(day0, day0.plus(Duration.ofDays(2)), zone)
        val metric = CouponUsedMetric(template, dialect)
        assertEquals(4L, metric.value(range).data!!.single().amount, "a restored coupon is not a use")
        val series = metric.series(range, DashboardInterval.DAY).data!!
        assertEquals(listOf(3L, 1L), series.map { it.value })

        val ranking = CouponTopUsedRanking(template, dialect).top(range, 10)
        assertEquals(DashboardResult.Status.OK, ranking.status)
        assertEquals(listOf("popular $seq" to 3L, "quiet $seq" to 1L), ranking.data!!.map { it.label to it.values.single() })
        assertEquals(1, CouponTopUsedRanking(template, dialect).top(range, 1).data!!.size)
    }
}
