package dev.sumin.skeleton.redis.core

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redis")
data class RedisCoreProperties(
    val mode: Mode = Mode.STANDALONE,
    val host: String = "localhost",
    val port: Int = 6379,
    val database: Int = 0,
    val username: String = "",
    val password: String = "",
    val ssl: Ssl = Ssl(),
    val timeout: Timeout = Timeout(),
    val keyPrefix: String = "kotlin-skeleton",
) {
    enum class Mode {
        STANDALONE,
    }

    data class Ssl(
        val enabled: Boolean = false,
        val disablePeerVerificationLocal: Boolean = true,
    )

    data class Timeout(
        val connect: Duration = Duration.ofSeconds(2),
        val command: Duration = Duration.ofSeconds(2),
    )
}
