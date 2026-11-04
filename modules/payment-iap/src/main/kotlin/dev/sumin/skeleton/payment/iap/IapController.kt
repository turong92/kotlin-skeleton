package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentStoreGateway
import dev.sumin.skeleton.payment.StoreAccountTokens
import dev.sumin.skeleton.payment.StoreClaim
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.web.PaymentCallers
import dev.sumin.skeleton.payment.web.PaymentCountryRouting
import dev.sumin.skeleton.payment.web.PaymentOrderRateLimiter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

data class IapProductResponse(
    /** `ONE_TIME` · `SUBSCRIPTION`. */
    val kind: String,
    val itemType: String?,
    val itemRef: String?,
    /** 구독의 플랜 코드 (단건이면 null). */
    val plan: String?,
    /** 이 플랫폼의 스토어 상품 id - 앱이 `in_app_purchase` 에 묻는 값. */
    val storeProductId: String,
    /** 구글 구독의 기본 요금제 id. */
    val basePlanId: String?,
)

data class IapConfigResponse(
    /** `IAP_ONLY` · `IAP_AND_WEB` · `WEB_ONLY`. */
    val channel: String,
    /** 채널에 웹이 들 때만 - 바깥 브라우저로 연다(웹뷰 금지). */
    val webCheckoutUrl: String?,
    /** 이 플랫폼의 스토어 결제사 id (`appstore` · `googleplay`) - 꺼져 있거나 키가 없으면 null. */
    val store: String?,
    /** 앱이 구매에 박을 계정 토큰 (`applicationUserName` → `appAccountToken` · `obfuscatedAccountId`). */
    val appAccountToken: String,
    val products: List<IapProductResponse>,
)

data class IapSubscriptionResponse(val id: String, val status: String, val planCode: String, val currentPeriodEnd: Instant?)

data class IapPurchaseResponse(
    /** `GRANTED` · `PENDING`. */
    val result: String,
    val kind: String? = null,
    val orderId: String? = null,
    val subscription: IapSubscriptionResponse? = null,
)

/**
 * 앱용 인앱결제 HTTP 둘(스펙 §3) - 설정 응답과 구매 확인. 로그인이 필요하고, 구매 확인에는 주문 만들기와 같은 요청 수 한도가 걸린다(스토어 API 할당량 보호).
 * `@Transactional` 없음 - 스토어 호출이 트랜잭션 안에 들어가면 안 된다.
 */
@RestController
@RequestMapping("\${skeleton.payment.http.base-path:/api/v1/payments}/iap")
@Tag(name = "Payment IAP")
class IapController(
    private val callers: PaymentCallers,
    private val purchases: IapPurchases,
    private val products: IapProducts,
    private val channels: IapChannelPolicy,
    private val routing: PaymentCountryRouting,
    private val router: PaymentProviderRouter,
    private val limiter: PaymentOrderRateLimiter,
    private val clientIps: ClientIps,
    private val properties: IapProperties,
) {
    @Operation(
        summary = "How this app sells (in-app only, in-app + web link, web only), the account token and the store products",
        description = "platform=ios|android is required. The country is storefront (what the app read from the store: three letters at Apple, two at Google) first, then the country of the connection, then the default; " +
            "a lying app only gets a web checkout link - the money and the entitlement have no way around the store. webCheckoutUrl is set only when the channel has the web (open it in the outside browser, not a web view). " +
            "store is null when this platform's store is off or has no keys. appAccountToken goes into PurchaseParam.applicationUserName. WEB_ONLY does not close POST /{store}/purchases (restoring older purchases).",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = IapConfigResponse::class)
    @GetMapping("/config")
    fun config(
        authentication: Authentication?,
        request: HttpServletRequest,
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) storefront: String?,
    ): DataResponse<IapConfigResponse> {
        val account = callers.account(authentication)
        val target = IapPlatform.of(platform) ?: throw FieldValidationException("platform", "Pattern", "platform must be ios or android")
        val country = StorefrontCountries.alpha2(storefront) ?: routing.of(request).country
        val channel = channels.channelOf(country, target)
        val store = target.store.takeIf { it in router.providerIds(PaymentStoreGateway::class) }
        val items = products.forPlatform(target).map {
            IapProductResponse(it.kind.name, it.itemType, it.itemRef, it.plan, checkNotNull(it.storeProductId(target)), it.basePlanId(target))
        }
        return Response.ok(IapConfigResponse(channel.name, properties.webCheckoutUrl.takeIf { channel.allowsWeb }, store, StoreAccountTokens.of(account), items))
    }

    @Operation(
        summary = "Have the server check what the app bought in the store and grant it",
        description = "appstore: { transactionId }. googleplay: { productId, purchaseToken } (productId only picks the API to ask and must be what the store answers). The server asks the store itself - nothing the app sends is believed. " +
            "200 GRANTED (granted now or before - the app finishes the purchase) · 202 PENDING (the store has not completed it - wait) · 404 IAP_PURCHASE_NOT_FOUND (finish) · " +
            "409 IAP_PURCHASE_OWNED_ELSEWHERE (another account's purchase - do NOT finish) · 409 ALREADY_SUBSCRIBED (finish) · 422 IAP_PRODUCT_UNKNOWN / IAP_PURCHASE_REVOKED / IAP_PURCHASE_NOT_ELIGIBLE (finish) · " +
            "429 over the per-account / per-IP limit · 503 UNAVAILABLE (the store is not reachable or off - do not finish, send it again on the next run). There is no Idempotency-Key: the purchase is its own key.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = IapPurchaseResponse::class)
    @PostMapping("/{store}/purchases")
    fun confirm(
        authentication: Authentication?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable store: String,
        @RequestBody body: JsonNode,
    ): ResponseEntity<DataResponse<IapPurchaseResponse>> {
        val account = callers.account(authentication)
        if (store !in IapStores.all) throw ApplicationException("Unknown store", PlatformErrorCode.NOT_FOUND)
        limiter.acquire(account, clientIps.of(request).limitKey, bucket = "payment-iap")?.let { retryAfter ->
            response.setHeader("Retry-After", retryAfter.toString())
            throw ApplicationException(message = "Too many requests", errorCode = PlatformErrorCode.TOO_MANY_REQUESTS, data = mapOf("retryAfterSeconds" to retryAfter))
        }
        val claim = if (store == IapStores.APPSTORE) StoreClaim(text(body["transactionId"], "transactionId", 100)) else StoreClaim(text(body["purchaseToken"], "purchaseToken", 4096), text(body["productId"], "productId", 200))
        return when (val result = purchases.confirm(store, account, claim)) {
            is IapResult.Granted -> ResponseEntity.ok(Response.ok(IapPurchaseResponse("GRANTED", result.kind.name, result.orderId, result.subscription?.let { IapSubscriptionResponse(it.id, it.status.name, it.planCode, it.currentPeriodEnd) })))
            IapResult.Pending -> ResponseEntity.accepted().body(Response.ok(IapPurchaseResponse("PENDING")))
        }
    }

    private fun text(node: JsonNode?, field: String, max: Int): String {
        if (node == null || node.isNull) throw FieldValidationException(field, "Required", "$field is required")
        if (!node.isString) throw FieldValidationException(field, "Pattern", "$field must be a string")
        val value = node.stringValue()
        if (value.isBlank() || value.length > max) throw FieldValidationException(field, "Size", "$field must be 1 to $max characters")
        return value
    }
}
