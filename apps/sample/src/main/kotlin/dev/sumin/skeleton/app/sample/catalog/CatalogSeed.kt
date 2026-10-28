package dev.sumin.skeleton.app.sample.catalog

import dev.sumin.skeleton.catalog.CatalogAdmin
import dev.sumin.skeleton.catalog.CatalogErrorCode
import dev.sumin.skeleton.catalog.CatalogStore
import dev.sumin.skeleton.catalog.PriceInput
import dev.sumin.skeleton.catalog.ProductInput
import dev.sumin.skeleton.catalog.ProductTextInput
import dev.sumin.skeleton.common.ApplicationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 견본 상품 · 가격의 씨앗 - 모든 프로필에서 **없는 코드만** 만든다(다시 떠도 늘지 않고, 운영 화면에서 만든 것을 덮지 않는다). 가격은 만든 뒤 못 고치니 씨앗을 바꿔도 이미 있는 가격은 그대로다.
 * 월 가격 코드는 예전 코드 플랜과 같은 `basic` · `standard` · `pro`(기존 구독 줄 · 쿠폰 범위가 그대로 맞는다), 연은 `-yearly`, 달러는 `-usd` · `-yearly-usd`.
 * 주기는 설정값이다: 월 `skeleton.sample.subscription.period`(기본 `P1M`, 로컬 `PT3M`), 연 `skeleton.sample.subscription.yearly-period`(기본 `P1Y`, 로컬 `PT36M`).
 */
@Component
class CatalogSeedRunner(
    private val admin: CatalogAdmin,
    private val store: CatalogStore,
    @Value("\${skeleton.sample.subscription.period:P1M}") private val period: String,
    @Value("\${skeleton.sample.subscription.yearly-period:P1Y}") private val yearlyPeriod: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = CatalogSeed(admin, store, period, yearlyPeriod).run()
}

class CatalogSeed(
    private val admin: CatalogAdmin,
    private val store: CatalogStore,
    private val period: String,
    private val yearlyPeriod: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Tier(val code: String, val level: Int, val en: String, val ko: String, val highlighted: Boolean, val krw: Long, val usdCents: Long)

    fun run() {
        TIERS.forEach { tier ->
            if (store.findProduct(tier.code) == null) {
                attempt("product ${tier.code}") {
                    admin.createProduct(
                        ACTOR,
                        ProductInput(tier.code, "SUBSCRIPTION", tier.level, tier.highlighted, tier.level, mapOf("en" to ProductTextInput(tier.en), "ko" to ProductTextInput(tier.ko))),
                    )
                }
            }
            if (store.findProduct(tier.code) == null) return@forEach
            price(tier, tier.code, period, tier.krw, "KRW")
            price(tier, "${tier.code}-yearly", yearlyPeriod, tier.krw * 10, "KRW")   // 열 달 치
            price(tier, "${tier.code}-usd", period, tier.usdCents, "USD")
            price(tier, "${tier.code}-yearly-usd", yearlyPeriod, tier.usdCents * 10, "USD")
        }
    }

    private fun price(tier: Tier, code: String, period: String, amount: Long, currency: String) {
        if (store.findPrice(code) != null) return
        attempt("price $code") { admin.addPrice(ACTOR, tier.code, PriceInput(code, period, amount, currency)) }
    }

    /** 운영자가 먼저 같은 칸을 채웠거나(겹침 · 등급) 다른 인스턴스가 동시에 씨앗을 뿌렸으면 그 칸만 건너뛴다 */
    private fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: ApplicationException) {
            // 이미 있는 칸(코드 · 등급 · 겹침)은 평범한 일이고, 씨앗이 잘못된 값을 내는 것(입력 오류)은 눈에 띄어야 한다
            if (e.errorCode in TAKEN) log.info("catalog seed skipped {}: {}", what, e.errorCode.code) else log.warn("catalog seed could not create {}: {} {}", what, e.errorCode.code, e.message)
        }
    }

    companion object {
        const val ACTOR = "system:catalog-seed"
        private val TAKEN = setOf<dev.sumin.skeleton.common.ErrorCode>(CatalogErrorCode.CODE_TAKEN, CatalogErrorCode.LEVEL_TAKEN, CatalogErrorCode.PRICE_OVERLAP)
        private val TIERS = listOf(
            Tier("basic", 1, "Basic", "베이직", false, 3_900, 300),
            Tier("standard", 2, "Standard", "스탠다드", false, 6_900, 500),
            Tier("pro", 3, "Pro", "프로", true, 9_900, 800),
        )
    }
}
