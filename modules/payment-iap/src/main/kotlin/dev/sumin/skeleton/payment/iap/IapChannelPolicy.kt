package dev.sumin.skeleton.payment.iap

import java.util.Locale

/**
 * **확장 지점** - 이 나라 · 이 플랫폼에서 앱이 어느 길(인앱 / 인앱 + 웹 / 웹)로 팔지. 기본은 설정 `skeleton.payment-iap.channels` 의 표이고,
 * 나라 · 플랫폼이 아닌 다른 기준(앱 판 등)으로 정하려는 앱이 같은 타입의 빈으로 갈아 끼운다. 나라는 스토어 나라(두 글자 대문자)이거나 모르면 null.
 */
fun interface IapChannelPolicy {
    fun channelOf(country: String?, platform: IapPlatform): IapChannel
}

class ConfiguredIapChannelPolicy(properties: IapProperties) : IapChannelPolicy {
    private val default = properties.channels.default
    private val countries: Map<String, Map<IapPlatform, IapChannel>> = properties.channels.countries.entries.associate { (country, cells) ->
        country.trim().uppercase() to cells.entries.associate { (platform, channel) -> checkNotNull(IapPlatform.of(platform)) { "unknown platform $platform" } to channel }
    }

    override fun channelOf(country: String?, platform: IapPlatform): IapChannel =
        country?.trim()?.uppercase()?.let { countries[it]?.get(platform) } ?: default
}

/** 앱이 읽은 스토어 나라(애플은 세 글자 `USA` · 구글은 두 글자 `US`)를 두 글자 대문자로 - 읽을 수 없으면 null. */
object StorefrontCountries {
    private val byAlpha3: Map<String, String> = Locale.getISOCountries().associateBy { Locale.of("", it).isO3Country }

    fun alpha2(storefront: String?): String? {
        val value = storefront?.trim()?.uppercase()?.takeIf { v -> v.all { it in 'A'..'Z' } } ?: return null
        return when (value.length) {
            2 -> value.takeIf { it in Locale.getISOCountries() }
            3 -> byAlpha3[value]
            else -> null
        }
    }
}
