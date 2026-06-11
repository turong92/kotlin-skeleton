package dev.sumin.skeleton.notification.websocket

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

@ConfigurationProperties("skeleton.notification-websocket")
data class NotificationWebSocketProperties(
    val enabled: Boolean = true,
    val endpoint: Endpoint = Endpoint(),
    val broker: Broker = Broker(),
    val authentication: Authentication = Authentication(),
    val heartbeat: Heartbeat = Heartbeat(),
    val inboundChannel: ChannelExecutor = ChannelExecutor(),
    val outboundChannel: ChannelExecutor = ChannelExecutor(),
    val transport: Transport = Transport(),
) {
    data class Endpoint(
        val path: String = "/ws/notifications",
        val allowedOriginPatterns: List<String> = listOf("*"),
        val sockJsEnabled: Boolean = false,
    )

    data class Broker(
        val applicationDestinationPrefixes: List<String> = listOf("/app"),
        val simpleBrokerDestinationPrefixes: List<String> = listOf("/topic", "/queue"),
        val userDestinationPrefix: String = "/user",
        val notificationDestinationPrefix: String = "/topic/notifications",
        val userNotificationDestination: String = "/queue/notifications",
        val userIdPayloadKey: String = "userId",
        val bridgeEnabled: Boolean = true,
        val bridgeTopics: Set<String> = emptySet(),
    )

    data class Authentication(
        val enabled: Boolean = false,
        val authorizationHeader: String = "Authorization",
        val bearerPrefix: String = "Bearer ",
        val tokenHeader: String = "access_token",
    )

    data class Heartbeat(
        val serverInterval: Duration = Duration.ofSeconds(10),
        val clientInterval: Duration = Duration.ofSeconds(10),
    )

    data class ChannelExecutor(
        val corePoolSize: Int = 1,
        val maxPoolSize: Int = 4,
        val queueCapacity: Int = 100,
        val keepAlive: Duration = Duration.ofSeconds(60),
    )

    data class Transport(
        val messageSizeLimit: DataSize = DataSize.ofKilobytes(64),
        val sendBufferSizeLimit: DataSize = DataSize.ofKilobytes(512),
        val sendTimeLimit: Duration = Duration.ofSeconds(10),
    )
}
