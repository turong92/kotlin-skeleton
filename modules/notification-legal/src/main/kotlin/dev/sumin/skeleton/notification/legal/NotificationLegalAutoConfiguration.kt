package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.LegalAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.advertising.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

private const val ADVERTISING_TYPES_PROPERTY = "skeleton.notification.advertising.types"

/** `skeleton.notification.advertising.types` 가 비어 있지 않을 때만 참 - 목록이든 쉼표 문자열이든 같은 바인더로 읽는다 */
class AdvertisingTypesConfigured : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val types = Binder.get(context.environment).bind(ADVERTISING_TYPES_PROPERTY, Bindable.listOf(String::class.java)).orElse(emptyList()).orEmpty()
        return if (types.any { it.isNotBlank() }) ConditionOutcome.match("advertising types are set") else ConditionOutcome.noMatch("advertising types are empty - advertising is off")
    }
}

/**
 * 광고 동의 어댑터(채널 무관) - 광고 종류가 설정됐고 `legal` 의 [ConsentService] 가 있을 때만 선다(광고를 안 켜면 아무 빈도 없다 = 이 모듈 없는 앱과 같다).
 * [AdvertisingConsentPolicy] 를 `legal` 의 동의 기록으로 구현한다 - 푸시 채널이든 앱이 직접 부르는 메일 · 슬랙이든 같은 정책 빈을 쓴다.
 */
@AutoConfiguration(before = [NotificationAutoConfiguration::class], after = [LegalAutoConfiguration::class])
@Conditional(AdvertisingTypesConfigured::class)
@ConditionalOnBean(ConsentService::class)
class NotificationLegalAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun legalAdvertisingConsents(service: ConsentService, properties: AdvertisingProperties): LegalAdvertisingConsents =
        LegalAdvertisingConsents(service, properties)

    @Bean
    @ConditionalOnMissingBean(AdvertisingConsentPolicy::class)
    fun legalAdvertisingConsentPolicy(consents: LegalAdvertisingConsents): AdvertisingConsentPolicy = LegalAdvertisingConsentPolicy(consents)
}
