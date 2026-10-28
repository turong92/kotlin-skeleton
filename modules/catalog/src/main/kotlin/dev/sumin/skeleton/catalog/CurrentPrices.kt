package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.payment.SubscriptionPeriod
import java.time.Instant

/**
 * 「지금 살 수 있는 가격」을 고르는 순수 규칙. 열쇠 = (주기, 통화) - 같은 상품에서 열쇠마다 지금 가격이 하나다.
 * 현재 가격 = 판매 중이고 지금이 판매 기간 안인 가격 가운데 **기간이 있는 것(행사)이 먼저**, 없으면 상시. 기간의 끝은 열린 구간이다(끝 시각 정각에는 이미 상시로 돌아온다).
 * 행사 중에는 상시 가격이 현재 가격이 아니다 - 정가로 사는 길을 막는다.
 */
object CurrentPrices {
    /** 한 상품의 가격들 가운데 [now] 의 현재 가격 - 열쇠(주기, 통화)마다 하나 */
    fun current(prices: List<CatalogPrice>, now: Instant): List<CatalogPrice> =
        prices.filter { it.selling && inWindow(it, now) }
            .groupBy { key(it) }
            .values
            .map { group -> group.filter { it.windowed }.maxWithOrNull(compareBy<CatalogPrice> { it.saleStartsAt ?: Instant.MIN }.thenBy { it.code }) ?: group.minBy { it.code } }
            .sortedWith(compareBy<CatalogPrice> { periodLength(it.period) }.thenBy { it.currency })

    fun isCurrent(price: CatalogPrice, siblings: List<CatalogPrice>, now: Instant): Boolean = current(siblings, now).any { it.code == price.code }

    /**
     * [candidate] 가 같은 열쇠의 판매 중인 [existing] 가격과 겹치는가 - 상시는 하나뿐이고, 행사끼리는 기간이 겹치면 안 된다. 상시와 행사는 겹치지 않는다(행사가 이긴다).
     * [candidate] 자신(같은 코드)은 비교하지 않는다 - 끝 시각을 고칠 때도 같은 규칙으로 쓴다.
     */
    fun overlaps(existing: List<CatalogPrice>, candidate: CatalogPrice): Boolean =
        existing.any { other ->
            other.selling && other.code != candidate.code && key(other) == key(candidate) && when {
                !other.windowed && !candidate.windowed -> true
                !other.windowed || !candidate.windowed -> false
                else -> (candidate.saleStartsAt ?: Instant.MIN) < (other.saleEndsAt ?: Instant.MAX) && (other.saleStartsAt ?: Instant.MIN) < (candidate.saleEndsAt ?: Instant.MAX)
            }
        }

    private fun inWindow(price: CatalogPrice, now: Instant): Boolean =
        (price.saleStartsAt == null || !now.isBefore(price.saleStartsAt)) && (price.saleEndsAt == null || now.isBefore(price.saleEndsAt))

    /** 주기의 길이 순(짧은 것 먼저, 주기 없는 단건은 맨 앞) - 같은 기준 시각에서 한 주기 뒤로 잰다 */
    private fun periodLength(period: String?): Instant = period?.let { SubscriptionPeriod.parse(it).after(LENGTH_ANCHOR, 1) } ?: Instant.MIN

    private val LENGTH_ANCHOR: Instant = Instant.parse("2000-01-01T00:00:00Z")

    private fun key(price: CatalogPrice) = price.period to price.currency
}
