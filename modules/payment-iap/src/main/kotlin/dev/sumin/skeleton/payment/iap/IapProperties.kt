package dev.sumin.skeleton.payment.iap

import org.springframework.boot.context.properties.ConfigurationProperties

/** 앱이 어느 길로 사게 할지 - 지역 스위치의 값. */
enum class IapChannel(val allowsWeb: Boolean) {
    /** 인앱결제만 (기본). */
    IAP_ONLY(false),

    /** 인앱결제 + 바깥 브라우저의 웹 결제 링크. */
    IAP_AND_WEB(true),

    /** 웹 결제만. 구매 확인 엔드포인트는 열려 있다(예전 구매의 복원). */
    WEB_ONLY(true),
}

/** 앱의 플랫폼 - 어느 스토어에 묻는가. [id] 는 요청의 `platform` 값, [store] 는 결제사 id. */
enum class IapPlatform(val id: String, val store: String) {
    IOS("ios", IapStores.APPSTORE),
    ANDROID("android", IapStores.GOOGLEPLAY),
    ;

    companion object {
        fun of(id: String?): IapPlatform? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

object IapStores {
    const val APPSTORE = "appstore"
    const val GOOGLEPLAY = "googleplay"
    val all: Set<String> = setOf(APPSTORE, GOOGLEPLAY)
}

/**
 * `skeleton.payment-iap` - 앱용 인앱결제 HTTP(설정 · 구매 확인). 기본 꺼짐.
 *
 * @property webCheckoutUrl 웹 결제(예: `payment-toss`) 화면 주소 - 채널에 웹이 드는 값이 하나라도 있으면 비울 수 없다. 앱은 바깥 브라우저로 연다.
 */
@ConfigurationProperties("skeleton.payment-iap")
data class IapProperties(
    val enabled: Boolean = false,
    val webCheckoutUrl: String = "",
    val channels: Channels = Channels(),
    val products: List<Product> = emptyList(),
) {
    /**
     * 지역 스위치. [countries] 는 `나라(두 글자) → 플랫폼(ios · android) → 채널` 이고 빠진 칸은 [default] 다.
     */
    data class Channels(
        val default: IapChannel = IapChannel.IAP_ONLY,
        val countries: Map<String, Map<String, IapChannel>> = emptyMap(),
    )

    /**
     * 상품 지도의 한 줄 - 단건([itemType] + [itemRef]) 또는 구독([plan]) 중 하나. 스토어 상품 id 는 쓰는 스토어만 적는다.
     * [playBasePlanId] 는 구글 구독의 기본 요금제 id(같은 구독 상품의 요금제를 가른다).
     */
    data class Product(
        val itemType: String? = null,
        val itemRef: String? = null,
        val plan: String? = null,
        val iosProductId: String? = null,
        val playProductId: String? = null,
        val playBasePlanId: String? = null,
    )

    /** 기동 때 한 번 - 잘못된 설정은 여기서 던져 앱이 뜨지 않게 한다(링크 없는 「웹 결제」 단추 · 겹치는 상품 id 가 운영에 나가지 않게). */
    fun validate() {
        channels.countries.forEach { (country, platforms) ->
            check(country.length == 2 && country.all { it in 'A'..'Z' || it in 'a'..'z' }) { "skeleton.payment-iap.channels.countries.$country: a country is two letters (US, KR ...)" }
            platforms.keys.forEach { platform ->
                check(IapPlatform.of(platform) != null) { "skeleton.payment-iap.channels.countries.$country.$platform: the platform is ios or android" }
            }
        }
        val webValues = listOf(channels.default) + channels.countries.values.flatMap { it.values }
        check(webCheckoutUrl.isNotBlank() || webValues.none { it.allowsWeb }) {
            "skeleton.payment-iap.web-checkout-url is empty but a channel with the web (IAP_AND_WEB / WEB_ONLY) is set - the app would show a web payment button without a link"
        }
        products.forEachIndexed { index, line ->
            val item = !line.itemType.isNullOrBlank() && !line.itemRef.isNullOrBlank()
            val plan = !line.plan.isNullOrBlank()
            check(item != plan) { "skeleton.payment-iap.products[$index]: a line is either item-type + item-ref or plan, not both and not neither" }
            check(!line.iosProductId.isNullOrBlank() || !line.playProductId.isNullOrBlank()) { "skeleton.payment-iap.products[$index]: a line needs ios-product-id or play-product-id" }
        }
        check(products.mapNotNull { it.iosProductId?.takeIf(String::isNotBlank) }.let { it.size == it.toSet().size }) {
            "skeleton.payment-iap.products: ios-product-id ${products.mapNotNull { it.iosProductId?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys} is used by more than one line"
        }
        val play = products.filter { !it.playProductId.isNullOrBlank() }.map { it.playProductId to it.playBasePlanId?.takeIf(String::isNotBlank) }
        check(play.size == play.toSet().size) {
            "skeleton.payment-iap.products: play-product-id ${play.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { it.first }} is used by more than one line (a base plan tells subscriptions of one product apart)"
        }
    }
}
