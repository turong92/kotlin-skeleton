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
    val json: Json = Json(),
) {
    enum class Mode {
        STANDALONE,
    }

    data class Ssl(
        val enabled: Boolean = false,
        val disablePeerVerificationLocal: Boolean = false,
    )

    data class Timeout(
        val connect: Duration = Duration.ofSeconds(2),
        val command: Duration = Duration.ofSeconds(2),
    )

    data class Json(
        val trustedPackages: List<String> = listOf("dev.sumin.skeleton", "java.time", "java.util"),
    )
}
