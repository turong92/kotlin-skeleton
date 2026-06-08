package dev.sumin.skeleton.common.http

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

@ConfigurationProperties("skeleton.http")
data class OutboundHttpProperties(
    val defaultConnectTimeout: Duration = Duration.ofSeconds(2),
    val defaultResponseTimeout: Duration = Duration.ofSeconds(5),
    val maxInMemorySize: DataSize = DataSize.ofMegabytes(2),
    val logging: Logging = Logging(),
    val clients: Map<String, Client> = emptyMap(),
) {
    data class Client(
        val baseUrl: String = "",
        val connectTimeout: Duration? = null,
        val responseTimeout: Duration? = null,
        val defaultHeaders: Map<String, String> = emptyMap(),
    )

    data class Logging(
        val enabled: Boolean = true,
        val includeQuery: Boolean = false,
        val includeHeaders: Boolean = false,
        val includeBody: Boolean = false,
    )
}
