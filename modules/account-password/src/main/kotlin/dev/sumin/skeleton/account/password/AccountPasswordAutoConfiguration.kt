package dev.sumin.skeleton.account.password

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.PasswordCredentials
import dev.sumin.skeleton.account.PasswordResetLinks
import dev.sumin.skeleton.account.PasswordService
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.RegistrationService
import dev.sumin.skeleton.account.ResetLinkIssuer
import dev.sumin.skeleton.account.abuse.LoginRecorder
import dev.sumin.skeleton.account.abuse.LoginThrottle
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.auth.config.AuthAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 비밀번호 수단 — `account` 위에 가입 · 코드 확인 · 재전송 · 비밀번호 찾기 · 재설정 · 변경, 정책 · 해시, 로그인 수단 `password`, 로그인 한도 · 기록을 얹는다.
 * 설정은 그대로 `skeleton.account.*` (`password` · `sign-up` · `verification` · `reset` · `login`) 다 — 이 모듈은 설정 묶음을 따로 갖지 않는다.
 * `account` 뒤 · `auth` 앞에서 평가해 [PasswordEncoder] 를 이 모듈이 정한다 (`auth` 의 기본 BCrypt 보다 먼저 — argon2 로 바꿔도 로그인 확인이 같은 인코더를 쓴다).
 * 모든 빈은 [ConditionalOnMissingBean] 이라 앱이 같은 타입을 두면 이쪽이 물러난다.
 */
@AutoConfiguration(after = [AccountAutoConfiguration::class], before = [AuthAutoConfiguration::class])
class AccountPasswordAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder::class)
    fun passwordEncoder(properties: AccountProperties): PasswordEncoder = PasswordEncoders.delegating(properties.password)

    /** `account` 의 [PasswordCredentials] 포트도 이 빈이다 (다시 인증 · 시드 계정) */
    @Bean
    @ConditionalOnMissingBean
    fun passwordHasher(encoder: PasswordEncoder): PasswordHasher = PasswordHasher(encoder)

    @Bean
    @ConditionalOnMissingBean(PasswordPolicy::class)
    fun passwordPolicy(properties: AccountProperties, breached: ObjectProvider<BreachedPasswordCheck>): PasswordPolicy =
        DefaultPasswordPolicy(properties.password, breached.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean(name = ["passwordSignInMethod"])
    fun passwordSignInMethod(): SignInMethod = PasswordSignInMethod()

    /** "이미 계정이 있어요" 메일의 재설정 링크 고리 — 비밀번호 찾기 요청과 같은 길이다 */
    @Bean
    @ConditionalOnMissingBean(ResetLinkIssuer::class)
    fun passwordResetLinks(core: AccountCore): ResetLinkIssuer = PasswordResetLinks(core)

    @Bean
    @ConditionalOnMissingBean
    fun registrationService(core: AccountCore, hasher: PasswordHasher, policy: PasswordPolicy): RegistrationService = RegistrationService(core, hasher, policy)

    @Bean
    @ConditionalOnMissingBean
    fun passwordService(core: AccountCore, hasher: PasswordHasher, policy: PasswordPolicy, resetLinks: ResetLinkIssuer): PasswordService =
        PasswordService(core, hasher, policy, resetLinks)

    /** 로그인 한도 — `auth` 의 `LoginHooks` 로 모여 비밀번호 검사 앞에서 돈다 (한도 → 기록 순서) */
    @Bean
    @Order(10)
    @ConditionalOnMissingBean
    fun loginThrottle(core: AccountCore): LoginThrottle = LoginThrottle(core)

    @Bean
    @Order(20)
    @ConditionalOnMissingBean
    fun loginRecorder(core: AccountCore): LoginRecorder = LoginRecorder(core)
}
