package dev.sumin.skeleton.crypto

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
    @Conditional(CryptoKeysConfiguredCondition::class)
    fun textEncryptor(properties: CryptoProperties): TextEncryptor =
        AesGcmTextEncryptor(
            primaryKeyId = properties.primaryKeyId,
            keys = properties.keys
                .filterValues { it.isNotBlank() }
                .mapValues { (_, key) -> AesGcmKey.fromBase64(key) },
        )

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
}
