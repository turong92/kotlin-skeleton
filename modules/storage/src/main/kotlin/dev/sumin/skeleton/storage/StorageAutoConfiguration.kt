package dev.sumin.skeleton.storage

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(StorageProperties::class)
class StorageAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun storageFileValidator(properties: StorageProperties): StorageFileValidator =
        StorageFileValidator(properties.validation)
}
