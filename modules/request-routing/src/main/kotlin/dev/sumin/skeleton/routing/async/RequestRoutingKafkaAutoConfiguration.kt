package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.routing.RequestRoutingProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.kafka.config.ContainerCustomizer
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer

/**
 * spring-kafka 가 클래스패스에 있을 때만. Boot 의 Kafka 자동 구성은 `ContainerCustomizer` 빈이 **하나일 때** 리스너 컨테이너 팩토리에 단다 —
 * 앱이 자기 꾸미개를 두면 이 빈은 서지 않으니(`@ConditionalOnMissingBean`) 앱의 꾸미개가 [RouteKafkaContainerCustomizer] 를 불러야 한다.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["org.springframework.kafka.listener.ConcurrentMessageListenerContainer"])
@ConditionalOnProperty("skeleton.routing.enabled", havingValue = "true")
@EnableConfigurationProperties(RequestRoutingProperties::class)
class RequestRoutingKafkaAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ContainerCustomizer::class)
    fun routeKafkaContainerCustomizer(
        properties: RequestRoutingProperties,
    ): ContainerCustomizer<Any, Any, ConcurrentMessageListenerContainer<Any, Any>> = RouteKafkaContainerCustomizer(properties)
}
