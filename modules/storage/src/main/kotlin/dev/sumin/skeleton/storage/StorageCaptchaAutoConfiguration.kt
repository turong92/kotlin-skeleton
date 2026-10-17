package dev.sumin.skeleton.storage

import dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * `captcha-turnstile` 이 켜져 있으면(검증기 빈이 있으면) 비회원 업로드 발급의 캡차를 그 검증기로 확인한다 (`skeleton.storage.web.guest.captcha=REQUIRED` 일 때만 불린다).
 * storage 는 Turnstile 을 `compileOnly` 로만 안다 - 클래스패스에 없으면 이 설정은 건너뛴다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.captcha.turnstile.TurnstileAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier"])
@ConditionalOnBean(TurnstileVerifier::class)
class StorageCaptchaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(StorageGuestCaptcha::class)
    fun turnstileStorageCaptcha(verifier: TurnstileVerifier): StorageGuestCaptcha = StorageGuestCaptcha { token, ip -> verifier.verify(token, ip).success }
}
