package dev.sumin.skeleton.catalog.web

import dev.sumin.skeleton.catalog.CatalogAdmin
import dev.sumin.skeleton.catalog.CatalogPermissions
import dev.sumin.skeleton.catalog.PriceInput
import dev.sumin.skeleton.catalog.ProductInput
import dev.sumin.skeleton.catalog.ProductPatch
import dev.sumin.skeleton.catalog.ProductTextInput
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.format.DateTimeParseException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

data class TextRequest(val name: String? = null, val description: String? = null)

data class ProductCreateRequest(
    val code: String? = null,
    val kind: String? = null,
    val level: Int? = null,
    val highlighted: Boolean? = null,
    val sortOrder: Int? = null,
    val texts: Map<String, TextRequest>? = null,
)

data class ProductUpdateRequest(
    val level: Int? = null,
    val highlighted: Boolean? = null,
    val selling: Boolean? = null,
    val sortOrder: Int? = null,
    val texts: Map<String, TextRequest>? = null,
)

data class PriceCreateRequest(
    val code: String? = null,
    val period: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
    val compareAtAmount: Long? = null,
    val saleStartsAt: Instant? = null,
    val saleEndsAt: Instant? = null,
    val replaces: String? = null,
)

data class ProviderRefRequest(val externalId: String? = null)

/**
 * 상품 · 가격 운영자 경로(`skeleton.catalog.admin.enabled=true` 일 때만 - 꺼져 있으면 경로가 없어 404). 모든 핸들러가 `catalog.manage` 를 [OperatorAccess] 로 매 호출 판정한다(익명 401 · 권한 없음 403).
 * 가격의 금액 · 통화 · 주기 · 정가를 바꾸는 길은 없다 - 새 가격을 만들고 옛 가격을 판매 중지한다.
 */
@RestController
@RequestMapping("\${skeleton.catalog.admin.base-path:/api/v1/admin}/catalog")
class CatalogAdminController(
    private val admin: CatalogAdmin,
    private val access: OperatorAccess,
    private val time: TimeProvider,
) {
    @GetMapping("/products")
    fun products(authentication: Authentication?): ListResponse<AdminProductResponse> {
        operator(authentication)
        val now = time.now()
        return Response.ok(admin.entries().map { it.toAdminResponse(now) })
    }

    @PostMapping("/products")
    fun createProduct(authentication: Authentication?, @RequestBody request: ProductCreateRequest): ResponseEntity<DataResponse<AdminProductResponse>> {
        val actor = operator(authentication)
        val created = admin.createProduct(actor.accountId, ProductInput(request.code, request.kind, request.level, request.highlighted ?: false, request.sortOrder ?: 0, request.texts.toInput().orEmpty()))
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(created.toAdminResponse(time.now())))
    }

    @PutMapping("/products/{code}")
    fun updateProduct(authentication: Authentication?, @PathVariable code: String, @RequestBody request: ProductUpdateRequest): DataResponse<AdminProductResponse> {
        val actor = operator(authentication)
        val updated = admin.updateProduct(actor.accountId, code, ProductPatch(request.level, request.highlighted, request.selling, request.sortOrder, request.texts.toInput()))
        return Response.ok(updated.toAdminResponse(time.now()))
    }

    @PostMapping("/products/{code}/prices")
    fun addPrice(authentication: Authentication?, @PathVariable code: String, @RequestBody request: PriceCreateRequest): ResponseEntity<DataResponse<AdminPriceResponse>> {
        val actor = operator(authentication)
        val price = admin.addPrice(actor.accountId, code, PriceInput(request.code, request.period, request.amount, request.currency, request.compareAtAmount, request.saleStartsAt, request.saleEndsAt, request.replaces))
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.ok(priceView(price.code)))
    }

    @PostMapping("/prices/{code}/stop")
    fun stopPrice(authentication: Authentication?, @PathVariable code: String): DataResponse<AdminPriceResponse> {
        val actor = operator(authentication)
        admin.stopPrice(actor.accountId, code)
        return Response.ok(priceView(code))
    }

    /** 본문은 `{ "saleEndsAt": <ISO-8601 | null> }` - 키가 없으면 400 (빈 본문이 끝을 없애지 않게) */
    @PatchMapping("/prices/{code}")
    fun updatePrice(authentication: Authentication?, @PathVariable code: String, @RequestBody body: JsonNode): DataResponse<AdminPriceResponse> {
        val actor = operator(authentication)
        if (!body.has("saleEndsAt")) throw FieldValidationException("saleEndsAt", "Required", "saleEndsAt is required (an ISO-8601 time, or null to remove the end)")
        val raw = body["saleEndsAt"]
        if (raw != null && !raw.isNull && !raw.isString) throw FieldValidationException("saleEndsAt", "Pattern", "saleEndsAt is an ISO-8601 time or null")
        val endsAt = if (raw == null || raw.isNull) null else try {
            Instant.parse(raw.stringValue())
        } catch (e: DateTimeParseException) {
            throw FieldValidationException("saleEndsAt", "Pattern", "saleEndsAt is an ISO-8601 time")
        }
        admin.updateSaleEnd(actor.accountId, code, endsAt)
        return Response.ok(priceView(code))
    }

    @PutMapping("/prices/{code}/provider-refs/{provider}")
    fun setProviderRef(authentication: Authentication?, @PathVariable code: String, @PathVariable provider: String, @RequestBody request: ProviderRefRequest): DataResponse<AdminPriceResponse> {
        val actor = operator(authentication)
        admin.setProviderRef(actor.accountId, code, provider, request.externalId)
        return Response.ok(priceView(code))
    }

    @DeleteMapping("/prices/{code}/provider-refs/{provider}")
    fun removeProviderRef(authentication: Authentication?, @PathVariable code: String, @PathVariable provider: String): DataResponse<AdminPriceResponse> {
        val actor = operator(authentication)
        admin.removeProviderRef(actor.accountId, code, provider)
        return Response.ok(priceView(code))
    }

    @GetMapping("/providers")
    fun providers(authentication: Authentication?): ListResponse<String> {
        operator(authentication)
        return Response.ok(admin.linkableProviders())
    }

    /** 바꾼 뒤의 가격 한 줄을 다시 읽어 `current` 까지 채운다 */
    private fun priceView(priceCode: String): AdminPriceResponse {
        val now = time.now()
        return admin.entries().firstNotNullOf { entry ->
            entry.toAdminResponse(now).prices.firstOrNull { it.code == priceCode }
        }
    }

    private fun operator(authentication: Authentication?): OperatorRef {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        return OperatorRef(authentication.name, roles).also { access.require(it, CatalogPermissions.MANAGE) }
    }

    private fun Map<String, TextRequest>?.toInput(): Map<String, ProductTextInput>? = this?.mapValues { ProductTextInput(it.value.name, it.value.description) }
}
