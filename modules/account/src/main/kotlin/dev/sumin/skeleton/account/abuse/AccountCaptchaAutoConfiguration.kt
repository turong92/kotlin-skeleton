package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** `captcha-turnstile` 이 켜져 있으면(검증기 빈이 있으면) 가입 · 재설정 · 재전송 요청의 캡차를 그 검증기로 확인한다. 꺼져 있으면 캡차 확인이 없다 */
@AutoConfiguration(
    beforeName = ["dev.sumin.skeleton.account.AccountAutoConfiguration"],
    afterName = ["dev.sumin.skeleton.captcha.turnstile.TurnstileAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier"])
@ConditionalOnBean(TurnstileVerifier::class)
class AccountCaptchaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountCaptcha::class)
    fun turnstileAccountCaptcha(verifier: TurnstileVerifier): AccountCaptcha = AccountCaptcha { token, ip, _ -> verifier.verify(token, ip).success }
}
