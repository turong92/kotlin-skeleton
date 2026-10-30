package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.Reauth
import dev.sumin.skeleton.account.challenge.ChallengeStore
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.SecondFactorGate
import dev.sumin.skeleton.auth.mfa.erasure.MfaAccountErasureListener
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.crypto.TextEncryptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/** 기동 검사를 통과했다는 표지 - 서비스가 이것에 기댄다(없으면 이름을 적고 기동 실패) */
class MfaPrerequisites

/**
 * 2단계 인증(TOTP) - 로그인 두 번째 단계 고리([SecondFactorGate]) · 규칙([MfaService]) · 탈퇴 고리. 경로는 `MfaWebAutoConfiguration`.
 * **`TextEncryptor`(crypto 키) 와 `MfaStore`(`auth-mfa-jdbc`)가 없으면 기동에 실패한다** - 키 없이 비밀을 평문으로 두거나, 저장소 없이 기능이 사라진 채 로그인이 2단계를 건너뛰는 일이 없게.
 */
@AutoConfiguration(after = [AccountAutoConfiguration::class], afterName = ["dev.sumin.skeleton.auth.mfa.jdbc.MfaJdbcAutoConfiguration"])
@EnableConfigurationProperties(MfaProperties::class)
class MfaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun mfaPrerequisites(encryptor: ObjectProvider<TextEncryptor>, store: ObjectProvider<MfaStore>): MfaPrerequisites {
        check(encryptor.getIfAvailable() != null) {
            "auth-mfa needs a TextEncryptor bean to keep TOTP secrets encrypted: add modules:crypto and set skeleton.crypto.primary-key-id + skeleton.crypto.keys.<id> (base64 AES key)"
        }
        check(store.getIfAvailable() != null) {
            "auth-mfa needs an MfaStore bean: add modules:auth-mfa-jdbc (a DataSource + one db-* module) or provide your own MfaStore"
        }
        return MfaPrerequisites()
    }

    /** OPERATORS = 운영 권한이 하나라도 있는 계정(`OperatorAccess`) - 저장된 역할로 판정하는 구현이 들어오면 그것을 따른다 */
    @Bean
    @ConditionalOnMissingBean
    fun mfaPolicy(properties: MfaProperties, access: ObjectProvider<OperatorAccess>): MfaPolicy =
        ConfiguredMfaPolicy(properties.requiredFor) { account ->
            access.getIfAvailable()?.permissionsOf(OperatorRef(account.id, account.roles))?.isNotEmpty() ?: account.roles.isNotEmpty()
        }

    @Bean
    @ConditionalOnMissingBean
    fun mfaRequirement(core: AccountCore, store: MfaStore, policy: MfaPolicy): MfaRequirement =
        MfaRequirement(core.accounts, store, policy, core.time)

    @Bean
    @ConditionalOnMissingBean(name = ["mfaPermissionDeclaration"])
    fun mfaPermissionDeclaration(): PermissionDeclaration = MfaPermissions.declaration

    @Bean
    @ConditionalOnMissingBean
    fun mfaService(
        @Suppress("UNUSED_PARAMETER") prerequisites: MfaPrerequisites,
        store: MfaStore,
        encryptor: TextEncryptor,
        core: AccountCore,
        challengeStore: ChallengeStore,
        authAccounts: AuthAccountRepository,
        reauth: Reauth,
        policy: MfaPolicy,
        requirement: MfaRequirement,
        properties: MfaProperties,
        limits: ObjectProvider<RateLimitStore>,
        environment: Environment,
    ): MfaService {
        val issuer = properties.totp.issuer.ifBlank { environment.getProperty("spring.application.name").orEmpty() }.ifBlank { "Account" }
        return MfaService(store, encryptor, core, challengeStore, authAccounts, reauth, policy, requirement, properties, issuer) { limits.getIfAvailable { FALLBACK_LIMITS } }
    }

    @Bean
    @ConditionalOnMissingBean(name = ["mfaSecondFactorGate"])
    fun mfaSecondFactorGate(store: MfaStore, core: AccountCore, properties: MfaProperties): SecondFactorGate =
        TotpSecondFactorGate(store, core.challenges, properties)

    @Bean
    @ConditionalOnMissingBean(name = ["mfaAccountErasureListener"])
    fun mfaAccountErasureListener(service: MfaService): AccountErasureListener = MfaAccountErasureListener(service)

    private companion object {
        val FALLBACK_LIMITS = InMemoryFixedWindowRateLimitStore()
    }
}
