package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.point.PointAutoConfiguration
import dev.sumin.skeleton.point.PointWallet
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * AdMob SSV 수신 — 서블릿 웹 앱이고 `skeleton.ads-admob.enabled=true` 이고 지갑([PointWallet] 빈 — `point` + `point-jdbc`)이 있을 때만 선다.
 * 경로는 구글이 인증 없이 부르므로 [PublicEndpointContributor] 로 연다(`auth` 의 기본 체인이 이 목록을 읽어 `permitAll` 을 건다) — GET 한 개뿐이다.
 */
@AutoConfiguration(after = [PointAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "skeleton.ads-admob", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(PointWallet::class)
@EnableConfigurationProperties(AdmobProperties::class)
class AdmobAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun admobKeySource(properties: AdmobProperties): AdmobKeySource = HttpAdmobKeySource(properties.keysUrl, properties.keyFetchTimeout)

    @Bean
    @ConditionalOnMissingBean
    fun admobVerifierKeys(source: AdmobKeySource, time: TimeProvider, properties: AdmobProperties): AdmobVerifierKeys =
        CachingAdmobVerifierKeys(source, time, properties.keyCacheTtl)

    @Bean
    @ConditionalOnMissingBean
    fun admobSsvVerifier(keys: AdmobVerifierKeys, properties: AdmobProperties): AdmobSsvVerifier = AdmobSsvVerifier(keys, properties)

    @Bean
    @ConditionalOnMissingBean
    fun admobSsvController(verifier: AdmobSsvVerifier, wallet: PointWallet, properties: AdmobProperties, time: TimeProvider): AdmobSsvController =
        AdmobSsvController(verifier, wallet, properties, time)

    @Bean
    @ConditionalOnMissingBean(name = ["admobSsvPublicEndpointContributor"])
    fun admobSsvPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("GET", "/api/v1/ads/admob/ssv") }
}
