package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.payment.StorePurchaseKind

/**
 * 스토어 상품 한 개가 우리 쪽에서 무엇인가 - 단건은 [itemType] + [itemRef](`PaymentCatalog` 의 품목), 구독은 [plan](`SubscriptionPlans` 의 플랜 코드).
 * 스토어 상품 id 는 쓰는 스토어만 있다.
 */
data class IapProduct(
    val itemType: String? = null,
    val itemRef: String? = null,
    val plan: String? = null,
    val iosProductId: String? = null,
    val playProductId: String? = null,
    val playBasePlanId: String? = null,
    /** 단건만 뜻이 있다 - `false` 면 비소모성(1회 구매 + 권리). 기본 `true`. */
    val consumable: Boolean = true,
) {
    val kind: StorePurchaseKind get() = if (plan != null) StorePurchaseKind.SUBSCRIPTION else StorePurchaseKind.ONE_TIME

    fun storeProductId(platform: IapPlatform): String? = if (platform == IapPlatform.IOS) iosProductId else playProductId

    /** 구글의 기본 요금제 id - 구독이고 구글에서 팔 때만. */
    fun basePlanId(platform: IapPlatform): String? = playBasePlanId.takeIf { platform == IapPlatform.ANDROID }
}

/**
 * **확장 지점** - 상품 지도. 기본은 설정 `skeleton.payment-iap.products`이고, 카탈로그 DB 의 스토어 상품 링크로 바꾸려는 앱이 같은 타입의 빈으로 갈아 끼운다.
 */
interface IapProducts {
    /** 지도의 모든 상품. */
    fun all(): List<IapProduct>

    /** 스토어가 말한 상품 id (구글 구독이면 기본 요금제 id 까지)로 찾는다. 모르면 null - 지도에 없는 상품은 지급하지 않는다. */
    fun find(store: String, storeProductId: String, basePlanId: String?): IapProduct?

    /** 이 플랫폼의 스토어에서 파는 상품 - 설정 응답의 목록. */
    fun forPlatform(platform: IapPlatform): List<IapProduct> = all().filter { it.storeProductId(platform) != null }
}

class ConfiguredIapProducts(properties: IapProperties) : IapProducts {
    private val products = properties.products.map {
        IapProduct(it.itemType?.trim(), it.itemRef?.trim(), it.plan?.trim(), it.iosProductId?.trim()?.takeIf(String::isNotEmpty), it.playProductId?.trim()?.takeIf(String::isNotEmpty), it.playBasePlanId?.trim()?.takeIf(String::isNotEmpty), it.consumable ?: true)
    }

    override fun all(): List<IapProduct> = products

    override fun find(store: String, storeProductId: String, basePlanId: String?): IapProduct? {
        val platform = IapPlatform.entries.firstOrNull { it.store == store } ?: return null
        return products.firstOrNull { it.storeProductId(platform) == storeProductId && (platform == IapPlatform.IOS || it.playBasePlanId == null || it.playBasePlanId == basePlanId) }
    }
}
