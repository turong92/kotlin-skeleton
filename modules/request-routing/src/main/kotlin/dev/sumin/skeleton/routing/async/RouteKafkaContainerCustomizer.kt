package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.routing.RequestRoutingProperties
import org.springframework.kafka.config.ContainerCustomizer
import org.springframework.kafka.listener.CompositeRecordInterceptor
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.RecordInterceptor

/** 이미 있던 인터셉터와 합친 것 — [RouteKafkaListenerCheck] 가 거르기가 달렸는지 알아보는 표지 */
class RouteCompositeRecordInterceptor(route: RouteRecordInterceptor, existing: RecordInterceptor<Any, Any>) :
    CompositeRecordInterceptor<Any, Any>(route, existing)

/**
 * 리스너 컨테이너마다 [RouteRecordInterceptor] 를 단다(이미 있는 인터셉터는 뒤에 그대로 두고, 이쪽이 먼저 거른다).
 * 후보 인스턴스면 소비 그룹을 `<원래 그룹>-<instance>` 로 바꾸고 `auto.offset.reset=latest` 로 둔다 — 원래 그룹의 메시지를 가로채지 않고, 후보가 뜨기 전 메시지를 되감아 읽지 않는다.
 */
class RouteKafkaContainerCustomizer(private val properties: RequestRoutingProperties) :
    ContainerCustomizer<Any, Any, ConcurrentMessageListenerContainer<Any, Any>> {

    override fun configure(container: ConcurrentMessageListenerContainer<Any, Any>) {
        val route = RouteRecordInterceptor(properties.service, properties.instance)
        val existing = container.recordInterceptor
        container.setRecordInterceptor(if (existing == null) route else RouteCompositeRecordInterceptor(route, existing))
        if (properties.instance.isNotBlank()) {
            // 그룹을 모르면 원래 그룹과 같은 이름으로 떠 메시지를 나눠 가질 수 있다 — 띄우지 않는다
            val group = checkNotNull(container.groupId) { "skeleton.routing.instance is set but the listener has no consumer group id to suffix." }
            container.containerProperties.setGroupId("$group-${properties.instance}")
            container.containerProperties.kafkaConsumerProperties.setProperty("auto.offset.reset", "latest")
        }
    }
}
