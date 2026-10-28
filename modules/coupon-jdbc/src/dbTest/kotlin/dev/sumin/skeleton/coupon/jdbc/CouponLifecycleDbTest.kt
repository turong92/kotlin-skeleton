package dev.sumin.skeleton.coupon.jdbc

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountConfirmOutcome
import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountRejectedException
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountTarget
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.coupon.Coupon
import dev.sumin.skeleton.coupon.CouponDefinitionInput
import dev.sumin.skeleton.coupon.CouponDefinitions
import dev.sumin.skeleton.coupon.CouponIssuance
import dev.sumin.skeleton.coupon.CouponSource
import dev.sumin.skeleton.coupon.CouponStageInput
import dev.sumin.skeleton.coupon.CouponStatus
import dev.sumin.skeleton.coupon.CouponStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

class MutableTime(@Volatile var current: Instant = Instant.now().minusSeconds(60)) : TimeProvider {
    override fun now(): Instant = TimeProvider.truncateToDatabasePrecision(current)
}

@TestConfiguration(proxyBeanMethods = false)
class CouponTimeConfig {
    @Bean @Primary fun mutableTime() = MutableTime()
}

/** 쿠폰 한 장의 예약 · 확정 · 해제 · 복원이 두 방언(postgresTest · mysqlTest)에서 같은 뜻인지 - 돈이 걸린 상태 전이라 동시 호출까지 본다 */
@Import(DbTestcontainers::class, CouponTimeConfig::class)
@SpringBootTest(classes = [CouponJdbcTestApplication::class])
class CouponLifecycleDbTest {
    @Autowired lateinit var definitions: CouponDefinitions
    @Autowired lateinit var issuance: CouponIssuance
    @Autowired lateinit var source: DiscountSource
    @Autowired lateinit var store: CouponStore
    @Autowired lateinit var time: MutableTime

    private lateinit var seq: String
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    @BeforeEach
    fun fresh() {
        time.current = Instant.now().minusSeconds(60)
        seq = java.util.UUID.randomUUID().toString().take(8)
    }

    private fun coupon(validUntil: Instant? = null, accountId: String = "acc_$seq"): Coupon {
        val def = definitions.create(
            CouponDefinitionInput(name = "30%", stages = listOf(CouponStageInput("PERCENT", 30, 5000, 1)), validUntil = validUntil),
        )
        return assertNotNull(issuance.issue(def.id, accountId, CouponSource.ADMIN, "adm_${seq}_${counter.incrementAndGet()}"))
    }

    private fun target(accountId: String = "acc_$seq") =
        DiscountTarget(accountId, "cheer", "item", null, 10_000, "KRW", DiscountPurpose.ONE_TIME, true)

    private fun status(c: Coupon) = store.findCoupon(c.id)!!.status

    @Test
    fun `two orders reserving the same coupon at once - only one holds it`() {
        val c = coupon()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = listOf("ord_a_$seq", "ord_b_$seq").map { holder ->
            pool.submit<Boolean> {
                start.await()
                try {
                    source.reserve(DiscountClaim("coupon", c.id), target(), holder)
                    true
                } catch (_: DiscountRejectedException) {
                    false
                }
            }
        }
        start.countDown()
        val won = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, won.count { it }, "exactly one reservation wins")
        assertEquals(CouponStatus.RESERVED, status(c))
        assertEquals(if (won[0]) "ord_a_$seq" else "ord_b_$seq", store.findCoupon(c.id)!!.holderRef)
    }

    @Test
    fun `release lets the coupon be reserved again, and a release by another holder is ignored`() {
        val c = coupon()
        val claim = DiscountClaim("coupon", c.id)
        source.reserve(claim, target(), "ord_1_$seq")

        source.release("ord_other_$seq")
        assertEquals(CouponStatus.RESERVED, status(c))

        source.release("ord_1_$seq")
        assertEquals(CouponStatus.AVAILABLE, status(c))
        source.reserve(claim, target(), "ord_2_$seq")
        assertEquals("ord_2_$seq", store.findCoupon(c.id)!!.holderRef)
        assertFailsWith<DiscountRejectedException> { source.reserve(claim, target(), "ord_3_$seq") }
    }

    @Test
    fun `restore of a coupon past its expiry leaves it expired, before it makes it available`() {
        val live = coupon()
        source.reserve(DiscountClaim("coupon", live.id), target(), "ord_live_$seq")
        assertEquals(DiscountConfirmOutcome.CONFIRMED, source.confirm("ord_live_$seq"))
        source.restore("ord_live_$seq")
        assertEquals(CouponStatus.AVAILABLE, status(live))
        assertEquals(null, store.findCoupon(live.id)!!.usedAt)

        val dated = coupon(validUntil = time.current.plus(Duration.ofHours(1)), accountId = "acc_dated_$seq")
        source.reserve(DiscountClaim("coupon", dated.id), target("acc_dated_$seq"), "ord_dated_$seq")
        source.confirm("ord_dated_$seq")
        time.current = time.current.plus(Duration.ofHours(2))
        source.restore("ord_dated_$seq")
        assertEquals(CouponStatus.EXPIRED, status(dated))
    }

    @Test
    fun `a release after the expiry also leaves it expired`() {
        val dated = coupon(validUntil = time.current.plus(Duration.ofHours(1)))
        source.reserve(DiscountClaim("coupon", dated.id), target(), "ord_1_$seq")
        time.current = time.current.plus(Duration.ofHours(2))
        source.release("ord_1_$seq")
        assertEquals(CouponStatus.EXPIRED, status(dated))
    }

    @Test
    fun `a late confirm is CONFIRMED, ALREADY or CONFLICT`() {
        val c = coupon()
        val claim = DiscountClaim("coupon", c.id)

        // 정상: 쥔 채로 확정, 다시 부르면 ALREADY
        source.reserve(claim, target(), "ord_1_$seq")
        assertEquals(DiscountConfirmOutcome.CONFIRMED, source.confirm("ord_1_$seq"))
        assertEquals(CouponStatus.USED, status(c))
        assertEquals(DiscountConfirmOutcome.ALREADY, source.confirm("ord_1_$seq"))

        // 늦은 PAID: 주문이 만료로 풀었는데 아무도 안 쥐었으면 다시 잡아 USED
        val d = coupon(accountId = "acc_d_$seq")
        source.reserve(DiscountClaim("coupon", d.id), target("acc_d_$seq"), "ord_late_$seq")
        source.release("ord_late_$seq")
        assertEquals(CouponStatus.AVAILABLE, status(d))
        assertEquals(DiscountConfirmOutcome.CONFIRMED, source.confirm("ord_late_$seq"))
        assertEquals(CouponStatus.USED, status(d))

        // 늦은 PAID 인데 그사이 다른 주문이 쥐었다: 둘 다 그대로
        val e = coupon(accountId = "acc_e_$seq")
        val claimE = DiscountClaim("coupon", e.id)
        source.reserve(claimE, target("acc_e_$seq"), "ord_first_$seq")
        source.release("ord_first_$seq")
        source.reserve(claimE, target("acc_e_$seq"), "ord_second_$seq")
        assertEquals(DiscountConfirmOutcome.CONFLICT, source.confirm("ord_first_$seq"))
        assertEquals(CouponStatus.RESERVED, status(e))
        assertEquals("ord_second_$seq", store.findCoupon(e.id)!!.holderRef)
        assertEquals(DiscountConfirmOutcome.CONFIRMED, source.confirm("ord_second_$seq"))
    }
}
