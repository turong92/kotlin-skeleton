package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.payment.SubscriptionPeriod
import java.time.Instant

data class ProductTextInput(val name: String?, val description: String? = null)

/** 상품 만들기 입력 - 검증 전의 날값이다. 모양이 틀리면 [CatalogAdmin.createProduct] 가 거절한다 */
data class ProductInput(
    val code: String?,
    val kind: String?,
    val level: Int? = null,
    val highlighted: Boolean = false,
    val sortOrder: Int = 0,
    val texts: Map<String, ProductTextInput> = emptyMap(),
)

/** 상품 고치기 입력 - 비어 있는(null) 칸은 그대로 둔다. 코드 · 종류는 못 바꾼다 */
data class ProductPatch(
    val level: Int? = null,
    val highlighted: Boolean? = null,
    val selling: Boolean? = null,
    val sortOrder: Int? = null,
    val texts: Map<String, ProductTextInput>? = null,
)

/** 가격 만들기 입력. [code] 를 비우면 만들어 준다 */
data class PriceInput(
    val code: String? = null,
    val period: String? = null,
    val amount: Long?,
    val currency: String?,
    val compareAtAmount: Long? = null,
    val saleStartsAt: Instant? = null,
    val saleEndsAt: Instant? = null,
)

/**
 * 운영자가 상품 · 가격을 만들고 고치는 곳. 가격은 **고치지 않는다** - 새 가격을 만들고 옛 가격을 판매 중지한다(바꿀 수 있는 것은 행사의 끝 시각과 결제사 링크뿐).
 * 쓰기는 모두 상품 줄을 잠근 트랜잭션 안에서 하고 감사 기록도 그 안에서 남긴다 - 같은 상품에 행사를 동시에 둘 만들어도 겹침 검사가 서로를 본다.
 */
class CatalogAdmin(
    private val store: CatalogStore,
    private val time: TimeProvider,
    private val audit: OperatorAuditLog,
    private val ids: SortableIds = SortableIds.system,
) {
    /** 모든 상품과 그 가격 전부(지난 가격 · 판매 중지까지, 최신 먼저) */
    fun entries(): List<CatalogEntry> {
        val prices = store.listPrices().groupBy { it.productId }
        return store.listProducts().map { CatalogEntry(it, newestFirst(prices[it.id].orEmpty())) }
    }

    fun entry(code: String): CatalogEntry {
        val product = store.findProduct(code) ?: throw notFound("product", code)
        return CatalogEntry(product, newestFirst(store.listPricesOf(product.id)))
    }

    fun createProduct(actorId: String, input: ProductInput): CatalogEntry {
        val code = codeOf(input.code, "code")
        val kind = ProductKind.entries.firstOrNull { it.name == input.kind?.trim()?.uppercase() }
            ?: throw invalid("kind is SUBSCRIPTION or ONE_TIME")
        val level = when (kind) {
            ProductKind.SUBSCRIPTION -> input.level?.takeIf { it >= 1 } ?: throw invalid("a subscription product needs a level of 1 or more")
            ProductKind.ONE_TIME -> if (input.level != null) throw invalid("a one-time product has no level") else null
        }
        val texts = textsOf(input.texts)
        val now = time.now()
        return store.inTransaction {
            if (store.findProduct(code) != null) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "product code $code is taken")
            if (level != null) requireLevelFree(level, code)
            val product = CatalogProduct(ids.newId(PRODUCT_PREFIX), code, kind, level, input.highlighted, true, input.sortOrder, texts, now, now)
            store.insertProduct(product)
            audit.record(OperatorAction(actorId, "catalog.product.created", TARGET_PRODUCT, code, mapOf("kind" to kind.name)))
            CatalogEntry(product, emptyList())
        }
    }

    fun updateProduct(actorId: String, code: String, patch: ProductPatch): CatalogEntry {
        val texts = patch.texts?.let(::textsOf)
        val now = time.now()
        return store.inTransaction {
            val current = store.lockProduct(code) ?: throw notFound("product", code)
            val level = when (current.kind) {
                ProductKind.SUBSCRIPTION -> patch.level ?: current.level
                ProductKind.ONE_TIME -> if (patch.level != null) throw invalid("a one-time product has no level") else null
            }
            if (level != null && level < 1) throw invalid("a subscription level is 1 or more")
            if (level != null && level != current.level) requireLevelFree(level, code)
            val updated = current.copy(
                level = level,
                highlighted = patch.highlighted ?: current.highlighted,
                selling = patch.selling ?: current.selling,
                sortOrder = patch.sortOrder ?: current.sortOrder,
                texts = texts ?: current.texts,
                updatedAt = now,
            )
            store.updateProduct(updated)
            audit.record(OperatorAction(actorId, "catalog.product.updated", TARGET_PRODUCT, code, mapOf("selling" to updated.selling.toString())))
            CatalogEntry(updated, newestFirst(store.listPricesOf(updated.id)))
        }
    }

    fun addPrice(actorId: String, productCode: String, input: PriceInput): CatalogPrice {
        val now = time.now()
        return store.inTransaction {
            val product = store.lockProduct(productCode) ?: throw notFound("product", productCode)
            val period = periodOf(product.kind, input.period)
            val amount = input.amount?.takeIf { it > 0 } ?: throw priceInvalid("amount must be positive (the smallest unit of the currency)")
            val currency = input.currency?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) } ?: throw priceInvalid("currency is three letters")
            val compareAt = input.compareAtAmount?.also { if (it <= amount) throw priceInvalid("compareAtAmount (the list price) must be more than amount") }
            if (input.saleStartsAt != null && input.saleEndsAt != null && !input.saleStartsAt.isBefore(input.saleEndsAt)) throw priceInvalid("saleStartsAt must be before saleEndsAt")
            if (input.saleEndsAt != null && !input.saleEndsAt.isAfter(now)) throw priceInvalid("saleEndsAt is already past")
            val siblings = store.listPricesOf(product.id)
            val code = input.code?.let { codeOf(it, "price code") }?.also { if (store.findPrice(it) != null) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "price code $it is taken") }
                ?: freeCode(product, period, currency)
            val price = CatalogPrice(ids.newId(PRICE_PREFIX), product.id, code, period, amount, currency, compareAt, input.saleStartsAt, input.saleEndsAt, true, null, now, now)
            if (CurrentPrices.overlaps(siblings, price)) throw CatalogException(CatalogErrorCode.PRICE_OVERLAP, "another $currency price of ${product.code} already covers that period")
            store.insertPrice(price)
            audit.record(OperatorAction(actorId, "catalog.price.created", TARGET_PRICE, code, mapOf("product" to product.code, "amount" to "$amount $currency")))
            price
        }
    }

    /** 판매 중지 - 되돌리지 않는다. 이미 중지됐으면 그대로 돌려준다. 그 가격의 기존 구독은 영향이 없다 */
    fun stopPrice(actorId: String, priceCode: String): CatalogPrice {
        val now = time.now()
        return withPriceLocked(priceCode) { price, _ ->
            if (!store.stopPrice(priceCode, now)) return@withPriceLocked price
            audit.record(OperatorAction(actorId, "catalog.price.stopped", TARGET_PRICE, priceCode))
            price.copy(selling = false, stoppedAt = now, updatedAt = now)
        }
    }

    /** 행사의 끝 시각을 바꾼다(연장 · 조기 종료) - 겹치지 않는 범위에서만. null 이면 끝을 없앤다(시작이 있는 행사만) */
    fun updateSaleEnd(actorId: String, priceCode: String, endsAt: Instant?): CatalogPrice {
        val now = time.now()
        return withPriceLocked(priceCode) { price, siblings ->
            if (!price.selling) throw priceInvalid("a stopped price cannot be changed")
            if (!price.windowed) throw priceInvalid("only a sale price has an end to change")
            if (endsAt == null && price.saleStartsAt == null) throw priceInvalid("a sale needs a start or an end")
            if (endsAt != null && price.saleStartsAt != null && !price.saleStartsAt.isBefore(endsAt)) throw priceInvalid("saleEndsAt must be after saleStartsAt")
            val changed = price.copy(saleEndsAt = endsAt, updatedAt = now)
            if (CurrentPrices.overlaps(siblings, changed)) throw CatalogException(CatalogErrorCode.PRICE_OVERLAP, "that end would overlap another price of the product")
            store.updateSaleEnd(priceCode, endsAt, now)
            audit.record(OperatorAction(actorId, "catalog.price.sale-end-changed", TARGET_PRICE, priceCode, mapOf("saleEndsAt" to (endsAt?.toString() ?: "none"))))
            changed
        }
    }

    fun setProviderRef(actorId: String, priceCode: String, provider: String, externalId: String?): CatalogPrice {
        val providerId = provider.trim().lowercase().takeIf { PROVIDER.matches(it) } ?: throw invalid("provider is a provider id")
        val external = externalId?.trim()?.takeIf { EXTERNAL_ID.matches(it) } ?: throw invalid("externalId is 1 to 128 characters without spaces")
        val now = time.now()
        return withPriceLocked(priceCode) { price, _ ->
            if (!store.putProviderRef(priceCode, providerId, external, now)) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "that $providerId price id is already linked to another price")
            audit.record(OperatorAction(actorId, "catalog.price.link-set", TARGET_PRICE, priceCode, mapOf("provider" to providerId)))
            price.copy(providerRefs = price.providerRefs + (providerId to external))
        }
    }

    fun removeProviderRef(actorId: String, priceCode: String, provider: String): CatalogPrice {
        val providerId = provider.trim().lowercase().takeIf { PROVIDER.matches(it) } ?: throw invalid("provider is a provider id")
        return withPriceLocked(priceCode) { price, _ ->
            if (store.deleteProviderRef(priceCode, providerId)) audit.record(OperatorAction(actorId, "catalog.price.link-removed", TARGET_PRICE, priceCode, mapOf("provider" to providerId)))
            price.copy(providerRefs = price.providerRefs - providerId)
        }
    }

    private fun <T> withPriceLocked(priceCode: String, block: (CatalogPrice, List<CatalogPrice>) -> T): T {
        val product = store.findPrice(priceCode)?.let { store.findProductById(it.productId) } ?: throw notFound("price", priceCode)
        return store.inTransaction {
            store.lockProduct(product.code) ?: throw notFound("product", product.code)
            val price = store.findPrice(priceCode) ?: throw notFound("price", priceCode)   // 잠근 뒤에 다시 읽는다
            block(price, store.listPricesOf(product.id))
        }
    }

    private fun requireLevelFree(level: Int, code: String) {
        val owner = store.productCodeOfLevel(level)
        if (owner != null && owner != code) throw CatalogException(CatalogErrorCode.LEVEL_TAKEN, "level $level belongs to product $owner")
    }

    private fun periodOf(kind: ProductKind, raw: String?): String? = when (kind) {
        ProductKind.ONE_TIME -> if (raw != null) throw priceInvalid("a one-time product has no period") else null
        ProductKind.SUBSCRIPTION -> {
            val text = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: throw priceInvalid("a subscription price needs a period (ISO-8601: P1M, P1Y)")
            try {
                SubscriptionPeriod.parse(text)
            } catch (e: IllegalArgumentException) {
                throw priceInvalid("period must be an ISO-8601 period or duration (P1M, P1Y, PT3M)")
            }
            text
        }
    }

    /** 코드를 안 주면 `<상품>-<주기>-<통화>` 에서 비어 있는 첫 이름 */
    private fun freeCode(product: CatalogProduct, period: String?, currency: String): String {
        val base = listOfNotNull(product.code, period?.lowercase(), currency.lowercase()).joinToString("-").take(MAX_CODE - 4)
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { store.findPrice(it) == null }
    }

    private fun codeOf(raw: String?, what: String): String = raw?.trim()?.takeIf { CODE.matches(it) } ?: throw invalid("$what is lowercase letters, digits, - or _ (1 to $MAX_CODE characters)")

    private fun textsOf(raw: Map<String, ProductTextInput>): List<CatalogProductText> {
        if (raw.isEmpty()) throw invalid("texts needs at least one language")
        return raw.entries.map { (locale, text) ->
            val tag = locale.trim().takeIf { LOCALE.matches(it) } ?: throw invalid("language '$locale' is a language tag (en, ko, pt-BR)")
            val name = text.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= NAME_MAX } ?: throw invalid("name of '$tag' is 1 to $NAME_MAX characters")
            val description = text.description?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > DESCRIPTION_MAX) throw invalid("description of '$tag' is at most $DESCRIPTION_MAX characters") }
            CatalogProductText(tag, name, description)
        }.sortedBy { it.locale }
    }

    private fun newestFirst(prices: List<CatalogPrice>) = prices.sortedWith(compareByDescending<CatalogPrice> { it.createdAt }.thenByDescending { it.code })

    private fun invalid(message: String) = ApplicationException(message, PlatformErrorCode.PARAMETER_VALIDATION_FAILED)

    private fun priceInvalid(message: String) = CatalogException(CatalogErrorCode.PRICE_INVALID, message)

    private fun notFound(what: String, code: String) = CatalogException(CatalogErrorCode.NOT_FOUND, "$what $code not found")

    companion object {
        const val MAX_CODE = 64
        private const val NAME_MAX = 100
        private const val DESCRIPTION_MAX = 500
        private const val PRODUCT_PREFIX = "prd_"
        private const val PRICE_PREFIX = "prc_"
        private const val TARGET_PRODUCT = "catalog-product"
        private const val TARGET_PRICE = "catalog-price"
        private val CODE = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val CURRENCY = Regex("[A-Z]{3}")
        private val LOCALE = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")
        private val PROVIDER = Regex("[a-z0-9][a-z0-9-]{0,31}")
        private val EXTERNAL_ID = Regex("\\S{1,128}")
    }
}
