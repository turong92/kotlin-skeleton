package dev.sumin.skeleton.payment.iap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 지역 스위치와 상품 지도의 설정 규칙: 기본은 IAP 만(스위치 꺼짐), 웹이 드는 값이 있는데 링크가 없으면 기동 실패, 스토어 상품 id 가 겹치면 기동 실패.
 */
class IapConfigTest {
    private fun properties(
        channels: IapProperties.Channels = IapProperties.Channels(),
        url: String = "",
        products: List<IapProperties.Product> = emptyList(),
    ) = IapProperties(enabled = true, webCheckoutUrl = url, channels = channels, products = products)

    private fun policy(properties: IapProperties) = ConfiguredIapChannelPolicy(properties)

    private val cheer = IapProperties.Product(itemType = "cheer", itemRef = "cheer-3000", iosProductId = "com.example.cheer3000", playProductId = "cheer_3000")

    @Test
    fun `without any setting every country and platform is in-app only`() {
        val policy = policy(properties())

        assertThat(policy.channelOf("US", IapPlatform.IOS)).isEqualTo(IapChannel.IAP_ONLY)
        assertThat(policy.channelOf("KR", IapPlatform.ANDROID)).isEqualTo(IapChannel.IAP_ONLY)
        assertThat(policy.channelOf(null, IapPlatform.IOS)).isEqualTo(IapChannel.IAP_ONLY)
    }

    @Test
    fun `the table is country by platform - a missing cell falls back to the default and the country is read in any case`() {
        val channels = IapProperties.Channels(
            default = IapChannel.IAP_ONLY,
            countries = mapOf("US" to mapOf("ios" to IapChannel.IAP_AND_WEB), "jp" to mapOf("android" to IapChannel.WEB_ONLY)),
        )
        val policy = policy(properties(channels, url = "https://example.com/pricing"))

        assertThat(policy.channelOf("US", IapPlatform.IOS)).isEqualTo(IapChannel.IAP_AND_WEB)
        assertThat(policy.channelOf("us", IapPlatform.IOS)).isEqualTo(IapChannel.IAP_AND_WEB)
        assertThat(policy.channelOf("US", IapPlatform.ANDROID)).isEqualTo(IapChannel.IAP_ONLY)
        assertThat(policy.channelOf("JP", IapPlatform.ANDROID)).isEqualTo(IapChannel.WEB_ONLY)
        assertThat(policy.channelOf("JP", IapPlatform.IOS)).isEqualTo(IapChannel.IAP_ONLY)
        assertThat(policy.channelOf("DE", IapPlatform.IOS)).isEqualTo(IapChannel.IAP_ONLY)
    }

    @Test
    fun `a web channel without a checkout url fails the start - wherever the web value is`() {
        assertThatThrownBy { properties(IapProperties.Channels(default = IapChannel.WEB_ONLY)).validate() }.hasMessageContaining("web-checkout-url")
        assertThatThrownBy { properties(IapProperties.Channels(countries = mapOf("US" to mapOf("ios" to IapChannel.IAP_AND_WEB)))).validate() }.hasMessageContaining("web-checkout-url")
        properties(IapProperties.Channels(countries = mapOf("US" to mapOf("ios" to IapChannel.IAP_AND_WEB))), url = "https://example.com/pricing").validate()
        properties().validate()
    }

    @Test
    fun `an unknown platform or a country that is not two letters fails the start`() {
        assertThatThrownBy { properties(IapProperties.Channels(countries = mapOf("US" to mapOf("andriod" to IapChannel.IAP_ONLY)))).validate() }.hasMessageContaining("andriod")
        assertThatThrownBy { properties(IapProperties.Channels(countries = mapOf("USA" to mapOf("ios" to IapChannel.IAP_ONLY)))).validate() }.hasMessageContaining("USA")
    }

    @Test
    fun `two product lines that share a store product id fail the start - a base plan tells two google subscriptions of one product apart`() {
        val monthly = IapProperties.Product(plan = "premium-monthly", iosProductId = "com.example.premium.monthly", playProductId = "premium", playBasePlanId = "monthly")
        val yearly = IapProperties.Product(plan = "premium-yearly", iosProductId = "com.example.premium.yearly", playProductId = "premium", playBasePlanId = "yearly")
        properties(products = listOf(cheer, monthly, yearly)).validate()

        assertThatThrownBy { properties(products = listOf(cheer, cheer.copy(itemRef = "cheer-5000", playProductId = "cheer_5000"))).validate() }.hasMessageContaining("com.example.cheer3000")
        assertThatThrownBy { properties(products = listOf(cheer, cheer.copy(itemRef = "cheer-5000", iosProductId = "com.example.cheer5000"))).validate() }.hasMessageContaining("cheer_3000")
        assertThatThrownBy { properties(products = listOf(monthly, yearly.copy(playBasePlanId = "monthly"))).validate() }.hasMessageContaining("premium")
    }

    @Test
    fun `a line is either an item or a plan and sells in at least one store`() {
        assertThatThrownBy { properties(products = listOf(cheer.copy(plan = "premium"))).validate() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { properties(products = listOf(cheer.copy(itemType = null, itemRef = null))).validate() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { properties(products = listOf(cheer.copy(itemRef = null))).validate() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { properties(products = listOf(cheer.copy(iosProductId = null, playProductId = null))).validate() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `consumable is a one-time line setting that defaults to true and a subscription line cannot carry it`() {
        val ads = cheer.copy(itemType = "ads-removal", itemRef = "v1", iosProductId = "com.example.adsfree", playProductId = "ads_free", consumable = false)
        val monthly = IapProperties.Product(plan = "premium-monthly", iosProductId = "com.example.premium.monthly", playProductId = "premium")
        val products = ConfiguredIapProducts(properties(products = listOf(cheer, ads, monthly)))

        assertThat(products.find("appstore", "com.example.cheer3000", null)?.consumable).isTrue()
        assertThat(products.find("appstore", "com.example.adsfree", null)?.consumable).isFalse()
        properties(products = listOf(cheer, ads)).validate()
        assertThatThrownBy { properties(products = listOf(monthly.copy(consumable = false))).validate() }.hasMessageContaining("consumable")
        assertThatThrownBy { properties(products = listOf(monthly.copy(consumable = true))).validate() }.hasMessageContaining("consumable")
    }

    @Test
    fun `lines of one item must agree on consumable - an iOS line false and a Play line left empty fails the start`() {
        val ios = cheer.copy(itemType = "ads-removal", itemRef = "v1", iosProductId = "com.example.adsfree", playProductId = null, consumable = false)
        val play = ios.copy(iosProductId = null, playProductId = "ads_free", consumable = null)

        assertThatThrownBy { properties(products = listOf(ios, play)).validate() }.hasMessageContaining("consumable").hasMessageContaining("ads-removal")
        properties(products = listOf(ios, play.copy(consumable = false))).validate()
    }

    @Test
    fun `the product map finds a purchase by its store product id and tells the platforms apart`() {
        val monthly = IapProperties.Product(plan = "premium-monthly", iosProductId = "com.example.premium.monthly", playProductId = "premium", playBasePlanId = "monthly")
        val products = ConfiguredIapProducts(properties(products = listOf(cheer, monthly)))

        assertThat(products.find("appstore", "com.example.cheer3000", null)?.itemRef).isEqualTo("cheer-3000")
        assertThat(products.find("googleplay", "cheer_3000", null)?.itemRef).isEqualTo("cheer-3000")
        assertThat(products.find("googleplay", "premium", "monthly")?.plan).isEqualTo("premium-monthly")
        assertThat(products.find("googleplay", "premium", "yearly")).isNull()
        assertThat(products.find("appstore", "cheer_3000", null)).isNull()
        assertThat(products.find("googleplay", "com.example.cheer3000", null)).isNull()
        assertThat(products.find("appstore", "com.example.unknown", null)).isNull()
        assertThat(products.forPlatform(IapPlatform.ANDROID).map { it.storeProductId(IapPlatform.ANDROID) }).containsExactly("cheer_3000", "premium")
    }

    @Test
    fun `a storefront is read as a two letter country from two or three letters`() {
        assertThat(StorefrontCountries.alpha2("USA")).isEqualTo("US")
        assertThat(StorefrontCountries.alpha2("kor")).isEqualTo("KR")
        assertThat(StorefrontCountries.alpha2("KR")).isEqualTo("KR")
        assertThat(StorefrontCountries.alpha2(" jp ")).isEqualTo("JP")
        assertThat(StorefrontCountries.alpha2("ZZZ")).isNull()
        assertThat(StorefrontCountries.alpha2("1x")).isNull()
        assertThat(StorefrontCountries.alpha2("")).isNull()
        assertThat(StorefrontCountries.alpha2(null)).isNull()
    }

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IapAutoConfiguration::class.java))

    @Test
    fun `off by default - no bean at all`() {
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(IapProducts::class.java).doesNotHaveBean(IapChannelPolicy::class.java) }
    }

    @Test
    fun `the settings bind from yml keys and a web channel without a url stops the application from starting`() {
        runner.withPropertyValues(
            "skeleton.payment-iap.enabled=true",
            "skeleton.payment-iap.web-checkout-url=https://example.com/pricing",
            "skeleton.payment-iap.channels.countries.US.ios=IAP_AND_WEB",
            "skeleton.payment-iap.products[0].item-type=cheer",
            "skeleton.payment-iap.products[0].item-ref=cheer-3000",
            "skeleton.payment-iap.products[0].ios-product-id=com.example.cheer3000",
        ).run {
            assertThat(it).hasNotFailed()
            assertThat(it.getBean(IapChannelPolicy::class.java).channelOf("US", IapPlatform.IOS)).isEqualTo(IapChannel.IAP_AND_WEB)
            assertThat(it.getBean(IapProducts::class.java).find("appstore", "com.example.cheer3000", null)).isNotNull()
        }
        runner.withPropertyValues("skeleton.payment-iap.enabled=true", "skeleton.payment-iap.channels.default=WEB_ONLY")
            .run { assertThat(it).hasFailed() }
    }
}
