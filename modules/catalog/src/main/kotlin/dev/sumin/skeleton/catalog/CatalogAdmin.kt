package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.search.SearchIndexer
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
    /** 이 가격이 대신하는 옛 가격 코드(같은 상품) - 같은 잠금 안에서 옛 가격을 판매 중지하고 새 가격을 만든다(둘이 같이 되거나 같이 안 된다) */
    val replaces: String? = null,
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
    /** 가격 id 를 쓰는 켜진 결제사 - 결제사 링크는 이 id 에만 둘 수 있다 */
    private val providers: () -> List<String> = { emptyList() },
    /** 검색 색인에 밀어 넣는 쪽 - 검색을 안 붙인 앱은 null 을 돌려줘 아무 일도 없다. 호출자(쓰기) 트랜잭션 안에서 부른다 */
    private val searchIndexer: () -> SearchIndexer? = { null },
    /** 검색 문서의 제목으로 쓰는 언어 ([CatalogProperties.defaultLocale]) */
    private val defaultLocale: String = "en",
) {
    fun linkableProviders(): List<String> = providers()

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
            ?: throw field("kind", "Pattern", "kind is SUBSCRIPTION or ONE_TIME")
        val level = when (kind) {
            ProductKind.SUBSCRIPTION -> input.level?.takeIf { it >= 1 } ?: throw field("level", if (input.level == null) "Required" else "Min", "a subscription product needs a level of 1 or more")
            ProductKind.ONE_TIME -> if (input.level != null) throw field("level", "Invalid", "a one-time product has no level") else null
        }
        val texts = textsOf(input.texts)
        val now = time.now()
        return store.inTransaction {
            if (store.findProduct(code) != null) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "product code $code is taken")
            if (level != null) requireLevelFree(level, code)
            val product = CatalogProduct(ids.newId(PRODUCT_PREFIX), code, kind, level, input.highlighted, true, input.sortOrder, texts, now, now)
            store.insertProduct(product)
            pushToSearch(product)
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
                ProductKind.ONE_TIME -> if (patch.level != null) throw field("level", "Invalid", "a one-time product has no level") else null
            }
            if (level != null && level < 1) throw field("level", "Min", "a subscription level is 1 or more")
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
            pushToSearch(updated)
            audit.record(OperatorAction(actorId, "catalog.product.updated", TARGET_PRODUCT, code, mapOf("selling" to updated.selling.toString())))
            CatalogEntry(updated, newestFirst(store.listPricesOf(updated.id)))
        }
    }

    fun addPrice(actorId: String, productCode: String, input: PriceInput): CatalogPrice {
        val now = time.now()
        return store.inTransaction {
            val product = store.lockProduct(productCode) ?: throw notFound("product", productCode)
            val period = periodOf(product.kind, input.period)
            val amount = input.amount?.takeIf { it > 0 } ?: throw priceField("amount", if (input.amount == null) "Required" else "Min", "amount must be positive (the smallest unit of the currency)")
            val currency = input.currency?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) } ?: throw priceField("currency", "Pattern", "currency is three letters")
            val compareAt = input.compareAtAmount?.also { if (it <= amount) throw priceField("compareAtAmount", "Min", "compareAtAmount (the list price) must be more than amount") }
            // 행사 = 정가와 기간을 단 가격 줄: 기간이 있으면 정가가 있어야 하고(화면의 취소선 · 해지의 정가), 상시 가격에는 정가가 없다
            val windowed = input.saleStartsAt != null || input.saleEndsAt != null
            if (windowed && compareAt == null) throw priceField("compareAtAmount", "Required", "a sale price (with saleStartsAt or saleEndsAt) needs compareAtAmount, the list price")
            if (!windowed && compareAt != null) throw priceField("compareAtAmount", "Invalid", "an evergreen price has no compareAtAmount - add saleStartsAt or saleEndsAt to make it a sale")
            val saleStartsAt = input.saleStartsAt ?: if (input.saleEndsAt != null) now else null   // 끝만 있는 행사는 지금 시작한다
            if (saleStartsAt != null && input.saleEndsAt != null && !saleStartsAt.isBefore(input.saleEndsAt)) throw priceField("saleEndsAt", "Invalid", "saleStartsAt must be before saleEndsAt")
            if (input.saleEndsAt != null && !input.saleEndsAt.isAfter(now)) throw priceField("saleEndsAt", "Invalid", "saleEndsAt is already past")
            val all = store.listPricesOf(product.id)
            val old = input.replaces?.let { oldCode -> all.firstOrNull { it.code == oldCode } ?: throw priceField("replaces", "Invalid", "replaces is a price code of the same product") }
            // 행사가 상시 가격을 대신하면 행사가 끝난 뒤 그 열쇠에 현재 가격이 없어 가격이 조용히 사라진다
            if (windowed && old != null && !old.windowed) throw priceField("replaces", "Invalid", "a sale price cannot replace an evergreen price - add the sale next to it, or replace the evergreen price with another evergreen price")
            val siblings = all.map { if (it.code == old?.code) it.copy(selling = false) else it }   // 대신하는 옛 가격은 겹침 검사에서 빠진다
            val code = input.code?.let { codeOf(it, "price code") }?.also { if (store.findPrice(it) != null) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "price code $it is taken") }
                ?: freeCode(product, period, currency)
            val price = CatalogPrice(ids.newId(PRICE_PREFIX), product.id, code, period, amount, currency, compareAt, saleStartsAt, input.saleEndsAt, true, null, now, now)
            if (CurrentPrices.overlaps(siblings, price)) throw CatalogException(CatalogErrorCode.PRICE_OVERLAP, "another $currency price of ${product.code} already covers that period")
            if (old != null && store.stopPrice(old.code, now)) audit.record(OperatorAction(actorId, "catalog.price.stopped", TARGET_PRICE, old.code, mapOf("replacedBy" to code)))
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
            if (!price.windowed) throw priceField("saleEndsAt", "Invalid", "only a sale price has an end to change")
            if (endsAt == null && price.saleStartsAt == null) throw priceField("saleEndsAt", "Required", "a sale needs a start or an end")
            if (endsAt != null && price.saleStartsAt != null && !price.saleStartsAt.isBefore(endsAt)) throw priceField("saleEndsAt", "Invalid", "saleEndsAt must be after saleStartsAt")
            val changed = price.copy(saleEndsAt = endsAt, updatedAt = now)
            if (CurrentPrices.overlaps(siblings, changed)) throw CatalogException(CatalogErrorCode.PRICE_OVERLAP, "that end would overlap another price of the product")
            store.updateSaleEnd(priceCode, endsAt, now)
            audit.record(OperatorAction(actorId, "catalog.price.sale-end-changed", TARGET_PRICE, priceCode, mapOf("saleEndsAt" to (endsAt?.toString() ?: "none"))))
            changed
        }
    }

    fun setProviderRef(actorId: String, priceCode: String, provider: String, externalId: String?): CatalogPrice {
        val providerId = provider.trim().lowercase().takeIf { PROVIDER.matches(it) } ?: throw field("provider", "Pattern", "provider is a provider id")
        val external = externalId?.trim()?.takeIf { EXTERNAL_ID.matches(it) } ?: throw field("externalId", if (externalId.isNullOrBlank()) "Required" else "Pattern", "externalId is 1 to 128 characters without spaces")
        if (providerId !in providers()) throw field("provider", "Invalid", "provider $providerId is not a provider that uses price ids")
        val now = time.now()
        return withPriceLocked(priceCode) { price, _ ->
            if (!store.putProviderRef(priceCode, providerId, external, now)) throw CatalogException(CatalogErrorCode.CODE_TAKEN, "that $providerId price id is already linked to another price")
            audit.record(OperatorAction(actorId, "catalog.price.link-set", TARGET_PRICE, priceCode, mapOf("provider" to providerId)))
            price.copy(providerRefs = price.providerRefs + (providerId to external))
        }
    }

    fun removeProviderRef(actorId: String, priceCode: String, provider: String): CatalogPrice {
        val providerId = provider.trim().lowercase().takeIf { PROVIDER.matches(it) } ?: throw field("provider", "Pattern", "provider is a provider id")
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

    /** 판매 중이면 문서를 덮고, 중지면 지운다 - 가격 쪽 변경은 상품 문서에 영향이 없다 */
    private fun pushToSearch(product: CatalogProduct) {
        val indexer = searchIndexer() ?: return
        if (product.selling) indexer.upsert(product.toSearchDocument(defaultLocale)) else indexer.delete(PRODUCT_SEARCH_TYPE, product.code)
    }

    private fun requireLevelFree(level: Int, code: String) {
        val owner = store.productCodeOfLevel(level)
        if (owner != null && owner != code) throw CatalogException(CatalogErrorCode.LEVEL_TAKEN, "level $level belongs to product $owner")
    }

    private fun periodOf(kind: ProductKind, raw: String?): String? = when (kind) {
        ProductKind.ONE_TIME -> if (raw != null) throw priceField("period", "Invalid", "a one-time product has no period") else null
        ProductKind.SUBSCRIPTION -> {
            val text = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: throw priceField("period", "Required", "a subscription price needs a period (ISO-8601: P1M, P1Y)")
            try {
                SubscriptionPeriod.parse(text)
            } catch (e: IllegalArgumentException) {
                throw priceField("period", "Pattern", "period must be an ISO-8601 period or duration (P1M, P1Y, PT3M)")
            }
            text
        }
    }

    /** 코드를 안 주면 `<상품>-<주기>-<통화>` 에서 비어 있는 첫 이름 */
    private fun freeCode(product: CatalogProduct, period: String?, currency: String): String {
        val base = listOfNotNull(product.code, period?.lowercase(), currency.lowercase()).joinToString("-").take(MAX_CODE - 4)
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { store.findPrice(it) == null }
    }

    private fun codeOf(raw: String?, what: String): String =
        raw?.trim()?.takeIf { CODE.matches(it) } ?: throw field("code", if (raw.isNullOrBlank()) "Required" else "Pattern", "$what is lowercase letters, digits, - or _ (1 to $MAX_CODE characters)")

    private fun textsOf(raw: Map<String, ProductTextInput>): List<CatalogProductText> {
        if (raw.isEmpty()) throw field("texts", "Required", "texts needs at least one language")
        return raw.entries.map { (locale, text) ->
            val tag = locale.trim().takeIf { LOCALE.matches(it) }?.let { java.util.Locale.forLanguageTag(it).toLanguageTag() }?.takeIf { it != "und" } ?: throw field("texts", "Pattern", "language '$locale' is a language tag (en, ko, pt-BR)")
            val name = text.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= NAME_MAX } ?: throw field("texts.$tag.name", if (text.name.isNullOrBlank()) "Required" else "Size", "name of '$tag' is 1 to $NAME_MAX characters")
            val description = text.description?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > DESCRIPTION_MAX) throw field("texts.$tag.description", "Size", "description of '$tag' is at most $DESCRIPTION_MAX characters") }
            CatalogProductText(tag, name, description)
        }.also { list -> list.groupBy { it.locale }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw field("texts", "Invalid", "language '$it' is given twice (tags are compared in their canonical form)") } }.sortedBy { it.locale }
    }

    private fun newestFirst(prices: List<CatalogPrice>) = prices.sortedWith(compareByDescending<CatalogPrice> { it.createdAt }.thenByDescending { it.code })

    /** 요청 본문의 한 칸이 틀렸다 - `400 COMMON.VALIDATION_FAILED` + `errors[{field, code, message}]`. 칸 이름은 요청 본문 이름이다 */
    private fun field(field: String, fieldCode: String, message: String) = FieldValidationException(field, fieldCode, message)

    /** 가격 입력의 한 칸이 틀렸다 - 같은 `errors[]` 모양에 에러 코드는 `CATALOG.PRICE_INVALID` */
    private fun priceField(field: String, fieldCode: String, message: String) = FieldValidationException(field, fieldCode, message, CatalogErrorCode.PRICE_INVALID)

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
        private val LOCALE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")
        private val PROVIDER = Regex("[a-z0-9][a-z0-9-]{0,31}")
        private val EXTERNAL_ID = Regex("\\S{1,128}")
    }
}
