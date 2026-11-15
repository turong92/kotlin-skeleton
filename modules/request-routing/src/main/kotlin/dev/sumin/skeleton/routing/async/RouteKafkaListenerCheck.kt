package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.routing.RequestRoutingProperties
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.listener.AbstractMessageListenerContainer
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.listener.MessageListenerContainer

/**
 * 리스너가 시작되기 직전(레지스트리보다 이른 단계)에 실제 컨테이너를 돌며 [RouteKafkaContainerCustomizer] 가 달렸는지 본다 —
 * 앱이 자기 `ConcurrentKafkaListenerContainerFactory` 를 두면 Boot 가 꾸미개를 달아 주지 않아 빈만 있고 컨테이너엔 없다.
 * 아니면 기동을 실패시킨다: 거르기가 없으면 후보가 원래 그룹의 메시지를 가로채거나 직원 메시지를 둘이 처리한다.
 * 후보의 배치 리스너는 레코드 거르기가 닿지 않으므로 늘 실패다. `auto-startup=false` 컨테이너는 보지 않는다.
 */
class RouteKafkaListenerCheck(
    private val properties: RequestRoutingProperties,
    private val containers: () -> Collection<MessageListenerContainer>,
) : SmartLifecycle {
    private var running = false

    fun problems(): List<String> = containers().filterIsInstance<AbstractMessageListenerContainer<*, *>>()
        .filter { it.isAutoStartup }
        .flatMap { problemsOf(it) }

    private fun problemsOf(container: AbstractMessageListenerContainer<*, *>): List<String> = buildList {
        val name = container.listenerId ?: container.containerProperties.topics?.joinToString() ?: "?"
        val interceptor = container.recordInterceptor
        if (interceptor !is RouteRecordInterceptor && interceptor !is RouteCompositeRecordInterceptor) {
            add("Kafka listener '$name' has no routing record interceptor (an app-defined listener container factory skips Boot's ContainerCustomizer): call RouteKafkaContainerCustomizer from it, or set spring.kafka.listener.auto-startup=false.")
        }
        if (properties.instance.isBlank()) return@buildList
        if (container.containerProperties.groupId?.endsWith("-${properties.instance}") != true) {
            add("Kafka listener '$name' is not on the candidate consumer group (it would share the original group's partitions): apply RouteKafkaContainerCustomizer or set spring.kafka.listener.auto-startup=false.")
        }
        val listener = container.containerProperties.messageListener
        if (listener is BatchMessageListener<*, *> || listener is BatchAcknowledgingMessageListener<*, *>) {
            add("Kafka listener '$name' is a batch listener: routing filters records only, so a candidate would process every message. Use a record listener or set spring.kafka.listener.auto-startup=false.")
        }
    }

    override fun start() {
        if (properties.service.isNotBlank()) {
            val found = problems()
            check(found.isEmpty()) { found.joinToString(" ") }
        }
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    /** 레지스트리(`Integer.MAX_VALUE - 100`)가 컨테이너를 시작하기 전 */
    override fun getPhase(): Int = Integer.MAX_VALUE - 101
}
