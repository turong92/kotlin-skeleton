package dev.sumin.skeleton.app.sample.subscription

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.SubscriptionRenewalGuard
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 이 앱이 구독(스펙 `docs/superpowers/specs/2026-10-09-subscription-design.md`)에 끼우는 두 계약. 구독은 `skeleton.payment.subscription.enabled=true` 일 때만 서고
 * (모듈 기본은 꺼짐 — 로컬 프로필이 토스를 켰을 때 함께 켜고, 배포는 `SKELETON_PAYMENT_SUBSCRIPTION_ENABLED=true` + 암호 키 `SKELETON_CRYPTO_KEYS_MAIN`), 켜면 이 두 빈이 쓰인다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "skeleton.payment.subscription", name = ["enabled"], havingValue = "true")
class SubscriptionConfiguration {
    /**
     * 플랜 셋 — **금액의 유일한 출처**다(구독 줄에는 만들 때의 값이 박힌다). 주기는 설정값이다: 기본 `P1M`, 로컬 프로필은 `PT3M` 이라 갱신 · 재시도 · 종료를 몇 분 안에 눌러 볼 수 있다.
     */
    @Bean
    fun samplePlans(@Value("\${skeleton.sample.subscription.period:P1M}") period: String): SubscriptionPlans =
        object : SubscriptionPlans {
            private val plans = listOf(
                SubscriptionPlan("basic", "Basic", PaymentAmount(3_900, "KRW"), period, level = 1),
                SubscriptionPlan("standard", "Standard", PaymentAmount(6_900, "KRW"), period, level = 2),
                SubscriptionPlan("pro", "Pro", PaymentAmount(9_900, "KRW"), period, level = 3),
            )

            override fun list(): List<SubscriptionPlan> = plans
        }

    /**
     * 정지되었거나 삭제를 예약한(유예) 계정은 해지할 수 없으니 갱신 과금을 막는다 — 그 구독은 기간 끝에 끝난다. **정상(`ACTIVE`) 계정만 갱신한다**:
     * 계정이 없거나 이메일 확인 전이어도 막는다(돈은 정상 계정에게서만). 갱신 시각마다 계정 한 줄을 읽는다.
     */
    @Bean
    fun renewalGuard(accounts: AccountRepository): SubscriptionRenewalGuard =
        SubscriptionRenewalGuard { accountId -> accounts.findById(accountId)?.status == AccountStatus.ACTIVE }
}
