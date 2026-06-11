package dev.sumin.skeleton.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.storage")
data class StorageProperties(
    val validation: StorageFileValidationRule = StorageFileValidationRule(),
)
