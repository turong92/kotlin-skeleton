package dev.sumin.skeleton.crypto

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties::class)
class CryptoAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @Conditional(ProjectsNotConfigured::class, CryptoKeysConfiguredCondition::class)
    fun textEncryptor(properties: CryptoProperties): TextEncryptor =
        AesGcmTextEncryptor(
            primaryKeyId = properties.primaryKeyId,
            keys = properties.keys
                .filterValues { it.isNotBlank() }
                .mapValues { (_, key) -> AesGcmKey.fromBase64(key) },
        )

    /** 프로젝트 모드: 프로젝트마다 자기 키 (공통 키 위에 덮어) — 부를 때 지금 프로젝트의 것으로 넘긴다. 어디에도 키가 없으면 passive */
    @Bean
    @ConditionalOnMissingBean
    @Conditional(ProjectsConfigured::class, ProjectCryptoKeysConfiguredCondition::class)
    fun projectTextEncryptor(projects: ProjectRegistry): TextEncryptor = ProjectRoutedTextEncryptor.create(projects)

    @Bean
    @ConditionalOnBean(TextEncryptor::class)
    @ConditionalOnMissingBean
    fun encryptedStringWritingConverter(textEncryptor: TextEncryptor): EncryptedStringWritingConverter =
        EncryptedStringWritingConverter(textEncryptor)

    @Bean
    @ConditionalOnBean(TextEncryptor::class)
    @ConditionalOnMissingBean
    fun encryptedStringReadingConverter(textEncryptor: TextEncryptor): EncryptedStringReadingConverter =
        EncryptedStringReadingConverter(textEncryptor)

    @Bean
    @ConditionalOnBean(TextEncryptor::class)
    @ConditionalOnMissingBean
    fun opaqueUrlTokenCodec(textEncryptor: TextEncryptor): OpaqueUrlTokenCodec =
        OpaqueUrlTokenCodec(textEncryptor)

    /** 프로젝트 모드에서 앱이 직접 둔 [TextEncryptor] 는 모든 프로젝트가 같이 쓴다(키 하나로 모든 프로젝트의 암호문) — 보호 환경(stage · prod)에서는 기동 실패, 그 밖은 경고 */
    @Bean
    @ConditionalOnMissingBean(name = ["cryptoSharedEncryptorDeployGuard"])
    @Conditional(ProjectsConfigured::class)
    fun cryptoSharedEncryptorDeployGuard(encryptor: ObjectProvider<TextEncryptor>): DeployGuard = object : DeployGuard {
        override val name = "crypto"
        override fun problems(context: DeployContext) = if (context.protectedEnv) findings() else emptyList()
        override fun warnings(context: DeployContext) = if (context.protectedEnv) emptyList() else findings()
        private fun findings() = if (encryptor.getIfAvailable().let { it == null || it is ProjectRoutedTextEncryptor }) emptyList() else listOf(
            "a TextEncryptor bean defined by the app is shared by every project (one key for all projects' ciphertext); " +
                "remove it and give each project its own skeleton.projects.<id>.config.crypto.keys.<id>",
        )
    }
}
