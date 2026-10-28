package dev.sumin.skeleton.catalog.jdbc

import dev.sumin.skeleton.catalog.CatalogErrorCode
import dev.sumin.skeleton.catalog.CatalogException
import dev.sumin.skeleton.catalog.CatalogPrice
import dev.sumin.skeleton.catalog.CatalogProduct
import dev.sumin.skeleton.catalog.CatalogProductText
import dev.sumin.skeleton.catalog.CatalogStore
import dev.sumin.skeleton.catalog.ProductKind
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 카탈로그 표 넷(`catalog_products` · `catalog_product_texts` · `catalog_prices` · `catalog_price_provider_refs`). 같은 상품에 가격을 만드는 쓰기는 호출자가 [lockProduct] 로 상품 줄을 먼저 잠근다
 * (`for update` 는 두 DB 모두 현재 값을 읽는다). 코드 · 등급 · 결제사 가격 id 의 「하나뿐」은 유니크 제약이 마지막으로 지키고, 걸리면 [CatalogException] 으로 옮긴다 -
 * PostgreSQL 에서 유니크 위반은 호출자 트랜잭션을 abort 시키므로 바로 던진다.
 */
class JdbcCatalogStore(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val dialect: SqlDialect,
) : CatalogStore {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }!!

    // 상품

    override fun listProducts(): List<CatalogProduct> {
        val rows = jdbc.query("select * from catalog_products order by sort_order, id", emptyMap<String, Any>()) { rs, _ -> rs.toProduct() }
        return withTexts(rows)
    }

    override fun findProduct(code: String): CatalogProduct? =
        jdbc.query("select * from catalog_products where code = :code", mapOf("code" to code)) { rs, _ -> rs.toProduct() }.firstOrNull()?.let { withTexts(listOf(it)).single() }

    override fun findProductById(id: String): CatalogProduct? =
        jdbc.query("select * from catalog_products where id = :id", mapOf("id" to id)) { rs, _ -> rs.toProduct() }.firstOrNull()?.let { withTexts(listOf(it)).single() }

    override fun lockProduct(code: String): CatalogProduct? =
        jdbc.query("select * from catalog_products where code = :code for update", mapOf("code" to code)) { rs, _ -> rs.toProduct() }.firstOrNull()?.let { withTexts(listOf(it)).single() }

    override fun productCodeOfLevel(level: Int): String? =
        jdbc.query("select code from catalog_products where level = :level", mapOf("level" to level)) { rs, _ -> rs.getString("code") }.firstOrNull()

    override fun insertProduct(product: CatalogProduct) {
        try {
            jdbc.update(
                """
                insert into catalog_products (id, code, kind, level, highlighted, selling, sort_order, created_at, updated_at)
                values (:id, :code, :kind, :level, :highlighted, :selling, :sort_order, :created_at, :updated_at)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", product.id)
                    .addValue("code", product.code)
                    .addValue("kind", product.kind.name)
                    .addValue("level", product.level, Types.INTEGER)
                    .addValue("highlighted", product.highlighted)
                    .addValue("selling", product.selling)
                    .addValue("sort_order", product.sortOrder)
                    .addValue("created_at", dialect.instantParam(product.createdAt))
                    .addValue("updated_at", dialect.instantParam(product.updatedAt)),
            )
        } catch (e: DuplicateKeyException) {
            throw if (e.message.orEmpty().contains("uq_catalog_products_level")) CatalogException(CatalogErrorCode.LEVEL_TAKEN, "level ${product.level} is used by another product")
            else CatalogException(CatalogErrorCode.CODE_TAKEN, "product code ${product.code} is taken")
        }
        insertTexts(product)
    }

    override fun updateProduct(product: CatalogProduct) {
        try {
            jdbc.update(
                "update catalog_products set level = :level, highlighted = :highlighted, selling = :selling, sort_order = :sort_order, updated_at = :updated_at where id = :id",
                MapSqlParameterSource()
                    .addValue("id", product.id)
                    .addValue("level", product.level, Types.INTEGER)
                    .addValue("highlighted", product.highlighted)
                    .addValue("selling", product.selling)
                    .addValue("sort_order", product.sortOrder)
                    .addValue("updated_at", dialect.instantParam(product.updatedAt)),
            )
        } catch (e: DuplicateKeyException) {
            throw CatalogException(CatalogErrorCode.LEVEL_TAKEN, "level ${product.level} is used by another product")
        }
        jdbc.update("delete from catalog_product_texts where product_id = :id", mapOf("id" to product.id))
        insertTexts(product)
    }

    private fun insertTexts(product: CatalogProduct) {
        product.texts.forEach { text ->
            jdbc.update(
                "insert into catalog_product_texts (product_id, locale, name, description) values (:product_id, :locale, :name, :description)",
                MapSqlParameterSource().addValue("product_id", product.id).addValue("locale", text.locale).addValue("name", text.name).addValue("description", text.description, Types.VARCHAR),
            )
        }
    }

    private fun withTexts(products: List<CatalogProduct>): List<CatalogProduct> {
        if (products.isEmpty()) return products
        val texts = jdbc.query("select * from catalog_product_texts where product_id in (:ids) order by locale", mapOf("ids" to products.map { it.id })) { rs, _ ->
            rs.getString("product_id") to CatalogProductText(rs.getString("locale"), rs.getString("name"), rs.getString("description"))
        }.groupBy({ it.first }, { it.second })
        return products.map { it.copy(texts = texts[it.id].orEmpty()) }
    }

    // 가격

    override fun listPrices(): List<CatalogPrice> =
        withRefs(jdbc.query("select * from catalog_prices order by created_at, code", emptyMap<String, Any>()) { rs, _ -> rs.toPrice() })

    override fun listPricesOf(productId: String): List<CatalogPrice> =
        withRefs(jdbc.query("select * from catalog_prices where product_id = :id order by created_at, code", mapOf("id" to productId)) { rs, _ -> rs.toPrice() })

    override fun findPrice(code: String): CatalogPrice? =
        jdbc.query("select * from catalog_prices where code = :code", mapOf("code" to code)) { rs, _ -> rs.toPrice() }.firstOrNull()?.let { withRefs(listOf(it)).single() }

    override fun insertPrice(price: CatalogPrice) {
        try {
            jdbc.update(
                """
                insert into catalog_prices (id, code, product_id, period, amount, currency, compare_at_amount, sale_starts_at, sale_ends_at, selling, stopped_at, created_at, updated_at)
                values (:id, :code, :product_id, :period, :amount, :currency, :compare_at_amount, :sale_starts_at, :sale_ends_at, :selling, :stopped_at, :created_at, :updated_at)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", price.id)
                    .addValue("code", price.code)
                    .addValue("product_id", price.productId)
                    .addValue("period", price.period, Types.VARCHAR)
                    .addValue("amount", price.amount)
                    .addValue("currency", price.currency)
                    .addValue("compare_at_amount", price.compareAtAmount, Types.BIGINT)
                    .addValue("sale_starts_at", dialect.instantParam(price.saleStartsAt))
                    .addValue("sale_ends_at", dialect.instantParam(price.saleEndsAt))
                    .addValue("selling", price.selling)
                    .addValue("stopped_at", dialect.instantParam(price.stoppedAt))
                    .addValue("created_at", dialect.instantParam(price.createdAt))
                    .addValue("updated_at", dialect.instantParam(price.updatedAt)),
            )
        } catch (e: DuplicateKeyException) {
            throw CatalogException(CatalogErrorCode.CODE_TAKEN, "price code ${price.code} is taken")
        }
    }

    override fun stopPrice(code: String, at: Instant): Boolean =
        jdbc.update(
            "update catalog_prices set selling = false, stopped_at = :at, updated_at = :at where code = :code and selling = true",
            MapSqlParameterSource().addValue("code", code).addValue("at", dialect.instantParam(at)),
        ) == 1

    override fun updateSaleEnd(code: String, endsAt: Instant?, at: Instant): Boolean =
        jdbc.update(
            "update catalog_prices set sale_ends_at = :ends_at, updated_at = :at where code = :code",
            MapSqlParameterSource().addValue("code", code).addValue("ends_at", dialect.instantParam(endsAt)).addValue("at", dialect.instantParam(at)),
        ) == 1

    private fun withRefs(prices: List<CatalogPrice>): List<CatalogPrice> {
        if (prices.isEmpty()) return prices
        val refs = jdbc.query("select price_id, provider, external_id from catalog_price_provider_refs where price_id in (:ids)", mapOf("ids" to prices.map { it.id })) { rs, _ ->
            rs.getString("price_id") to (rs.getString("provider") to rs.getString("external_id"))
        }.groupBy({ it.first }, { it.second })
        return prices.map { it.copy(providerRefs = refs[it.id].orEmpty().toMap()) }
    }

    // 결제사 링크

    override fun putProviderRef(priceCode: String, provider: String, externalId: String, at: Instant): Boolean {
        val priceId = jdbc.query("select id from catalog_prices where code = :code", mapOf("code" to priceCode)) { rs, _ -> rs.getString("id") }.firstOrNull() ?: return false
        val taken = jdbc.queryForObject(
            "select count(*) from catalog_price_provider_refs where provider = :provider and external_id = :external and price_id <> :price",
            mapOf("provider" to provider, "external" to externalId, "price" to priceId),
            Long::class.java,
        )!! > 0
        if (taken) return false
        val params = MapSqlParameterSource().addValue("price", priceId).addValue("provider", provider).addValue("external", externalId).addValue("at", dialect.instantParam(at))
        try {
            val updated = jdbc.update("update catalog_price_provider_refs set external_id = :external, updated_at = :at where price_id = :price and provider = :provider", params)
            if (updated == 0) {
                jdbc.update("insert into catalog_price_provider_refs (price_id, provider, external_id, created_at, updated_at) values (:price, :provider, :external, :at, :at)", params)
            }
        } catch (e: DuplicateKeyException) {
            return false   // 다른 가격이 같은 결제사 가격 id 를 방금 가져갔다
        }
        return true
    }

    override fun deleteProviderRef(priceCode: String, provider: String): Boolean =
        jdbc.update(
            "delete from catalog_price_provider_refs where provider = :provider and price_id = (select id from catalog_prices where code = :code)",
            mapOf("provider" to provider, "code" to priceCode),
        ) > 0

    override fun providerRefs(provider: String): Map<String, String> =
        jdbc.query(
            "select p.code, r.external_id from catalog_price_provider_refs r join catalog_prices p on p.id = r.price_id where r.provider = :provider",
            mapOf("provider" to provider),
        ) { rs, _ -> rs.getString("code") to rs.getString("external_id") }.toMap()

    override fun providerRefOf(provider: String, priceCode: String): String? =
        jdbc.query(
            "select r.external_id from catalog_price_provider_refs r join catalog_prices p on p.id = r.price_id where r.provider = :provider and p.code = :code",
            mapOf("provider" to provider, "code" to priceCode),
        ) { rs, _ -> rs.getString("external_id") }.firstOrNull()

    // 읽기

    private fun ResultSet.toProduct() = CatalogProduct(
        id = getString("id"),
        code = getString("code"),
        kind = ProductKind.valueOf(getString("kind")),
        level = getInt("level").takeUnless { wasNull() },
        highlighted = getBoolean("highlighted"),
        selling = getBoolean("selling"),
        sortOrder = getInt("sort_order"),
        texts = emptyList(),
        createdAt = checkNotNull(dialect.readInstant(this, "created_at")),
        updatedAt = checkNotNull(dialect.readInstant(this, "updated_at")),
    )

    private fun ResultSet.toPrice() = CatalogPrice(
        id = getString("id"),
        productId = getString("product_id"),
        code = getString("code"),
        period = getString("period"),
        amount = getLong("amount"),
        currency = getString("currency"),
        compareAtAmount = getLong("compare_at_amount").takeUnless { wasNull() },
        saleStartsAt = dialect.readInstant(this, "sale_starts_at"),
        saleEndsAt = dialect.readInstant(this, "sale_ends_at"),
        selling = getBoolean("selling"),
        stoppedAt = dialect.readInstant(this, "stopped_at"),
        createdAt = checkNotNull(dialect.readInstant(this, "created_at")),
        updatedAt = checkNotNull(dialect.readInstant(this, "updated_at")),
    )
}
