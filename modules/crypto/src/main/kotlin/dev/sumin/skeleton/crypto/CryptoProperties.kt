package dev.sumin.skeleton.crypto

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.crypto")
data class CryptoProperties(
    val primaryKeyId: String = "local",
    val keys: Map<String, String> = emptyMap(),
)
