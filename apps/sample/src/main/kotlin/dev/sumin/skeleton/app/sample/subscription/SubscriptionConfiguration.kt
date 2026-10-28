package dev.sumin.skeleton.app.sample.subscription

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.payment.SubscriptionRenewalGuard
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 이 앱이 구독(스펙 `docs/superpowers/specs/2026-10-09-subscription-design.md`)에 끼우는 계약. 플랜은 코드가 아니라 `catalog` 의 표가 내고(씨앗: `catalog/CatalogSeed`) 운영자가 화면에서 고친다. 구독은 `skeleton.payment.subscription.enabled=true` 일 때만 서고
 * (모듈 기본은 꺼짐 — 로컬 프로필이 토스를 켰을 때 함께 켜고, 배포는 `SKELETON_PAYMENT_SUBSCRIPTION_ENABLED=true` + 암호 키 `SKELETON_CRYPTO_KEYS_MAIN`), 켜면 이 빈이 쓰인다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "skeleton.payment.subscription", name = ["enabled"], havingValue = "true")
class SubscriptionConfiguration {
    /**
     * 정지되었거나 삭제를 예약한(유예) 계정은 해지할 수 없으니 갱신 과금을 막는다 — 그 구독은 기간 끝에 끝난다. **정상(`ACTIVE`) 계정만 갱신한다**:
     * 계정이 없거나 이메일 확인 전이어도 막는다(돈은 정상 계정에게서만). 갱신 시각마다 계정 한 줄을 읽는다.
     */
    @Bean
    fun renewalGuard(accounts: AccountRepository): SubscriptionRenewalGuard =
        SubscriptionRenewalGuard { accountId -> accounts.findById(accountId)?.status == AccountStatus.ACTIVE }
}
