package dev.sumin.skeleton.migration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.migration")
data class MigrationProperties(
    /** true 면 체크섬 불일치 같은 검증 실패 때 DB 를 clean 하고 다시 migrate 한다. [cleanAllowedProfiles] 밖에서는 기동 실패. */
    val cleanOnValidationError: Boolean = false,
    val cleanAllowedProfiles: List<String> = listOf("local"),
)
