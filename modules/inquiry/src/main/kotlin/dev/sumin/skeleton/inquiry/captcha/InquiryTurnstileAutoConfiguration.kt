package dev.sumin.skeleton.inquiry.captcha

import dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier
import dev.sumin.skeleton.inquiry.InquiryAutoConfiguration
import dev.sumin.skeleton.inquiry.InquiryHumanCheck
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * `captcha-turnstile` 이 켜져 있으면(검증기 빈이 있으면) 로그인하지 않은 호출의 사람 확인을 그 검증기로 한다.
 * inquiry 는 Turnstile 을 `compileOnly` 로만 안다 - 클래스패스에 없으면 이 설정은 건너뛴다. 앱이 [InquiryHumanCheck] 빈을 두면 그쪽이 이긴다.
 */
@AutoConfiguration(
    before = [InquiryAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.captcha.turnstile.TurnstileAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier"])
@ConditionalOnBean(TurnstileVerifier::class)
class InquiryTurnstileAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(InquiryHumanCheck::class)
    fun turnstileInquiryHumanCheck(verifier: TurnstileVerifier): InquiryHumanCheck = InquiryHumanCheck { token, ip -> verifier.verify(token, ip).success }
}
