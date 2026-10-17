package dev.sumin.skeleton.board.captcha

import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardCaptcha
import dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * `captcha-turnstile` 이 켜져 있으면(검증기 빈이 있으면) 비회원 만들기의 캡차를 그 검증기로 확인한다 (`skeleton.board.guest.captcha=REQUIRED` 일 때만 불린다).
 * board 는 Turnstile 을 `compileOnly` 로만 안다 — 클래스패스에 없으면 이 설정은 건너뛴다.
 */
@AutoConfiguration(
    before = [BoardAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.captcha.turnstile.TurnstileAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier"])
@ConditionalOnBean(TurnstileVerifier::class)
class BoardCaptchaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BoardCaptcha::class)
    fun turnstileBoardCaptcha(verifier: TurnstileVerifier): BoardCaptcha = BoardCaptcha { token, ip, _ -> verifier.verify(token, ip).success }
}
