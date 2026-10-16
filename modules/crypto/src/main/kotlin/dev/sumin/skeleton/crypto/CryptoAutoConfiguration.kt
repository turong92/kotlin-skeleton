package dev.sumin.skeleton.crypto

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
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
}
