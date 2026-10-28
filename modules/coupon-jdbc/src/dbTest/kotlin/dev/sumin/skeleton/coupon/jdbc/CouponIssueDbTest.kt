package dev.sumin.skeleton.coupon.jdbc

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.coupon.Coupon
import dev.sumin.skeleton.coupon.erasure.CouponAccountErasureListener
import dev.sumin.skeleton.coupon.CouponCodes
import dev.sumin.skeleton.coupon.CouponDefinition
import dev.sumin.skeleton.coupon.CouponDefinitionInput
import dev.sumin.skeleton.coupon.CouponDefinitionStatus
import dev.sumin.skeleton.coupon.CouponDefinitions
import dev.sumin.skeleton.coupon.CouponErrorCode
import dev.sumin.skeleton.coupon.CouponException
import dev.sumin.skeleton.coupon.CouponIssuance
import dev.sumin.skeleton.coupon.CouponSource
import dev.sumin.skeleton.coupon.CouponStageInput
import dev.sumin.skeleton.coupon.CouponStore
import dev.sumin.skeleton.coupon.CouponTab
import dev.sumin.skeleton.coupon.CouponWallet
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 발급 한도 · 계정당 한도 · 지급 건 멱등 · 공용 코드가 두 방언(postgresTest · mysqlTest)에서 동시 호출에도 지켜지는지 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [CouponJdbcTestApplication::class])
class CouponIssueDbTest {
    @Autowired lateinit var definitions: CouponDefinitions
    @Autowired lateinit var issuance: CouponIssuance
    @Autowired lateinit var codes: CouponCodes
    @Autowired lateinit var wallet: CouponWallet
    @Autowired lateinit var reports: dev.sumin.skeleton.coupon.CouponReports
    @Autowired lateinit var store: CouponStore
    @Autowired lateinit var issuer: RewardIssuer
    @Autowired lateinit var jdbc: JdbcClient

    private val seq = UUID.randomUUID().toString().take(8)

    private fun define(issueLimit: Int? = null, perAccountLimit: Int = 1): CouponDefinition = definitions.create(
        CouponDefinitionInput(name = "free $seq", stages = listOf(CouponStageInput("FREE", periods = 1)), issueLimit = issueLimit, perAccountLimit = perAccountLimit),
    )

    private fun <T> concurrently(count: Int, block: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { i -> pool.submit<T> { start.await(); block(i) } }
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    private fun issuedCount(def: CouponDefinition) = store.findDefinition(def.id)!!.issuedCount

    @Test
    fun `an issue limit of two with three concurrent issues gives two coupons`() {
        val def = define(issueLimit = 2)
        val results = concurrently(3) { i -> issuance.issue(def.id, "acc_${seq}_$i", CouponSource.ADMIN, "adm_${seq}_$i") }

        assertEquals(2, results.count { it != null })
        assertEquals(2, issuedCount(def))
        assertEquals(2, jdbc.sql("select count(*) from coupons where definition_id = :d").param("d", def.id).query(Long::class.java).single().toInt())
    }

    @Test
    fun `a limit of one per account lets the same account hold one coupon even when asked at once`() {
        val def = define(perAccountLimit = 1)
        val results = concurrently(3) { i -> issuance.issue(def.id, "acc_$seq", CouponSource.ADMIN, "adm_${seq}_$i") }

        assertEquals(1, results.count { it != null })
        assertEquals(1, issuedCount(def))
        assertNull(issuance.issue(def.id, "acc_$seq", CouponSource.ADMIN, "adm_${seq}_again"))
        assertNotNull(issuance.issue(def.id, "acc_other_$seq", CouponSource.ADMIN, "adm_${seq}_other"))
    }

    @Test
    fun `the same grant gives one coupon however many times the worker asks`() {
        val def = define(perAccountLimit = 5)
        val results = concurrently(4) { issuance.issue(def.id, "acc_$seq", CouponSource.GRANT, "g1_$seq") }

        assertEquals(1, results.map { assertNotNull(it).id }.toSet().size, "every call sees the same coupon")
        assertEquals(1, issuedCount(def))
        assertEquals(1, wallet.all("acc_$seq").size)
    }

    @Test
    fun `the reward issuer is idempotent per grant and fails permanently when it cannot issue`() {
        val def = define(perAccountLimit = 5)
        val grant = RewardGrantView("grant_$seq", "acc_$seq", "coupon", mapOf("definitionId" to def.id), "attendance", "k")

        val first = issuer.issue(grant)
        assertEquals(first.resultRef, issuer.issue(grant).resultRef)
        assertEquals(1, issuedCount(def))
        issuer.validate(mapOf("definitionId" to def.id))
        assertFailsWith<IllegalArgumentException> { issuer.validate(mapOf("definitionId" to "cpd_missing")) }
        assertEquals(def.name, issuer.describe(mapOf("definitionId" to def.id), java.util.Locale.ENGLISH).title)

        definitions.setStatus(def.id, CouponDefinitionStatus.ARCHIVED)
        assertFailsWith<RewardPermanentFailure> { issuer.issue(grant.copy(id = "grant_next_$seq")) }
        assertFailsWith<RewardPermanentFailure> { issuer.issue(grant.copy(id = "grant_x_$seq", params = emptyMap())) }
        assertEquals(1, issuedCount(def), "an archived definition issues nothing more")
    }

    @Test
    fun `a limit reached is a permanent failure for a grant`() {
        val def = define(issueLimit = 1)
        issuer.issue(RewardGrantView("grant_a_$seq", "acc_a_$seq", "coupon", mapOf("definitionId" to def.id), "attendance", "k"))
        assertFailsWith<RewardPermanentFailure> {
            issuer.issue(RewardGrantView("grant_b_$seq", "acc_b_$seq", "coupon", mapOf("definitionId" to def.id), "attendance", "k2"))
        }
    }

    @Test
    fun `a shared code - the same account twice is CODE_USED, and a full code is CODE_EXHAUSTED`() {
        val def = define(perAccountLimit = 1)
        val code = codes.create(def.id, "WELCOME_$seq", maxRedemptions = 2)

        val coupon: Coupon = codes.redeem("acc_1_$seq", code.lowercase())
        assertEquals(def.id, coupon.definitionId)
        assertEquals(CouponSource.CODE, coupon.sourceType)
        assertEquals(CouponErrorCode.CODE_USED, assertFailsWith<CouponException> { codes.redeem("acc_1_$seq", code) }.couponError)

        codes.redeem("acc_2_$seq", code)
        assertEquals(CouponErrorCode.CODE_EXHAUSTED, assertFailsWith<CouponException> { codes.redeem("acc_3_$seq", code) }.couponError)
        assertEquals(CouponErrorCode.CODE_INVALID, assertFailsWith<CouponException> { codes.redeem("acc_3_$seq", "NOPE_$seq") }.couponError)
        assertEquals(CouponErrorCode.CODE_EXISTS, assertFailsWith<CouponException> { codes.create(def.id, code) }.couponError)

        assertEquals(1, wallet.list("acc_1_$seq", CouponTab.AVAILABLE).size)
        assertEquals(0, wallet.list("acc_3_$seq", CouponTab.AVAILABLE).size, "a refused redemption leaves nothing behind")
        assertEquals(2, issuedCount(def))
    }

    @Test
    fun `the same account redeeming one code at once gets one coupon`() {
        val def = define(perAccountLimit = 3)
        val code = codes.create(def.id, "RACE_$seq")
        val outcomes = concurrently(3) {
            try {
                codes.redeem("acc_$seq", code)
                "ok"
            } catch (e: CouponException) {
                e.couponError.name
            }
        }

        assertEquals(1, outcomes.count { it == "ok" })
        assertEquals(2, outcomes.count { it == "CODE_USED" })
        assertEquals(1, wallet.all("acc_$seq").size)
    }

    @Test
    fun `a shared code stops working once its definition is not issuing`() {
        val def = define()
        val code = codes.create(def.id)
        definitions.setStatus(def.id, CouponDefinitionStatus.PAUSED)
        assertEquals(CouponErrorCode.CODE_INVALID, assertFailsWith<CouponException> { codes.redeem("acc_$seq", code) }.couponError)
    }

    @Test
    fun `an erased account's coupons stay as anonymous rows and its code redemptions go`() {
        val def = define()
        val code = codes.create(def.id)
        codes.redeem("acc_$seq", code)
        val before = reports.stats(def.id)
        CouponAccountErasureListener(store).erase(ErasureRequest("acc_$seq", AccountTombstone.of("acc_$seq")))
        assertEquals(0, wallet.all("acc_$seq").size)
        assertEquals(false, store.hasRedeemed(code, "acc_$seq"))
        val after = reports.stats(def.id)
        assertEquals(before.issued, after.issued, "the row stays - only the person is cut loose")
        assertEquals(before.available - 1, after.available)
        assertEquals(before.revoked + 1, after.revoked, "an unused coupon of an erased account can not be used")
        val row = reports.coupons(def.id, null, null, 10).items.single().coupon
        assertEquals(AccountTombstone.of("acc_$seq"), row.accountId)
        assertEquals("erased:${row.id}", row.sourceRef, "the code-redemption source key carried the account id")
        CouponAccountErasureListener(store).erase(ErasureRequest("acc_$seq", AccountTombstone.of("acc_$seq")))   // 멱등
    }

    @Test
    fun `definitions and their stages round trip through the table`() {
        val def = definitions.create(
            CouponDefinitionInput(
                name = "first free then half $seq",
                stages = listOf(CouponStageInput("FREE", periods = 1), CouponStageInput("PERCENT", 50, 3000, 3), CouponStageInput("FIXED", 500, null, null)),
                currency = "KRW",
                minAmount = 1000,
                firstPurchaseOnly = true,
                validDays = 30,
                issueLimit = 10,
                perAccountLimit = 2,
            ),
        )
        val read = store.findDefinition(def.id)!!
        assertEquals(def.stages, read.stages)
        assertEquals("KRW", read.currency)
        assertEquals(1000L, read.minAmount)
        assertEquals(true, read.firstPurchaseOnly)
        assertEquals(30, read.validDays)
        assertEquals(10, read.issueLimit)
        assertEquals(2, read.perAccountLimit)
        assertEquals(def.createdAt, read.createdAt)
    }
}
