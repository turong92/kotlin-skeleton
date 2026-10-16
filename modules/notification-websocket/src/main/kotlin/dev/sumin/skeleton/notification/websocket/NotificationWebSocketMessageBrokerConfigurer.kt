package dev.sumin.skeleton.notification.websocket

import kotlin.math.min
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.TaskScheduler
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

class NotificationWebSocketMessageBrokerConfigurer(
    private val properties: NotificationWebSocketProperties,
    private val traceInterceptor: NotificationWebSocketTraceInterceptor,
    private val authenticationInterceptor: NotificationWebSocketAuthenticationInterceptor,
    private val inboundAuthorizationInterceptor: NotificationWebSocketInboundAuthorizationInterceptor,
    private val heartbeatTaskScheduler: TaskScheduler,
    /** 프로젝트 모드(프로젝트 레지스트리가 있을 때)에만 넘어온다 — 핸드셰이크가 프로젝트를 적고, 공개 토픽 구독에 선택자가 붙는다 */
    private val projectSelectorInterceptor: NotificationWebSocketProjectSelectorInterceptor? = null,
) : WebSocketMessageBrokerConfigurer {
    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val endpointRegistration = registry.addEndpoint(properties.endpoint.path)
        if (projectSelectorInterceptor != null) endpointRegistration.addInterceptors(ProjectHandshakeInterceptor())
        if (properties.endpoint.allowedOriginPatterns.isNotEmpty()) {
            endpointRegistration.setAllowedOriginPatterns(*properties.endpoint.allowedOriginPatterns.toTypedArray())
        }
        if (properties.endpoint.sockJsEnabled) {
            endpointRegistration.withSockJS()
        }
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        if (properties.broker.applicationDestinationPrefixes.isNotEmpty()) {
            registry.setApplicationDestinationPrefixes(*properties.broker.applicationDestinationPrefixes.toTypedArray())
        }
        registry.setUserDestinationPrefix(properties.broker.userDestinationPrefix)

        val broker = registry.enableSimpleBroker(*properties.broker.simpleBrokerDestinationPrefixes.toTypedArray())
        broker.setTaskScheduler(heartbeatTaskScheduler)
        if (projectSelectorInterceptor != null) broker.setSelectorHeaderName(SELECTOR_HEADER)
        broker.setHeartbeatValue(
            longArrayOf(
                properties.heartbeat.serverInterval.toMillis(),
                properties.heartbeat.clientInterval.toMillis(),
            ),
        )
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(*listOfNotNull(traceInterceptor, authenticationInterceptor, inboundAuthorizationInterceptor, projectSelectorInterceptor).toTypedArray())
        registration.applyExecutor(properties.inboundChannel)
    }

    override fun configureClientOutboundChannel(registration: ChannelRegistration) {
        registration.applyExecutor(properties.outboundChannel)
    }

    override fun configureWebSocketTransport(registration: WebSocketTransportRegistration) {
        registration.setMessageSizeLimit(properties.transport.messageSizeLimit.toIntBytes())
        registration.setSendBufferSizeLimit(properties.transport.sendBufferSizeLimit.toIntBytes())
        registration.setSendTimeLimit(properties.transport.sendTimeLimit.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun ChannelRegistration.applyExecutor(properties: NotificationWebSocketProperties.ChannelExecutor) {
        taskExecutor()
            .corePoolSize(properties.corePoolSize)
            .maxPoolSize(properties.maxPoolSize)
            .queueCapacity(properties.queueCapacity)
            .keepAliveSeconds(properties.keepAlive.seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun org.springframework.util.unit.DataSize.toIntBytes(): Int =
        min(toBytes(), Int.MAX_VALUE.toLong()).toInt()
}
