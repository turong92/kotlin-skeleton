package dev.sumin.skeleton.common.project

import org.springframework.util.ClassUtils

/**
 * 아직 프로젝트를 모르는 기능(뒤 작업 일꾼 · 결제 · 소셜 · 메일 · 파일 · 알림 · …)이 빌드에 들어 있는데 **서비스 중인 프로젝트가 둘 이상**이면 기동을 막는다 —
 * 섞인 채 돌지 않게. 방법은 하나: 모듈마다 가드를 두지 않고, 여기 한 표가 모듈의 대표 클래스가 클래스패스에 있는지만 본다(닫힌 프로젝트는 세지 않는다).
 * 모듈이 프로젝트를 알게 되면 이 표에서 뺀다.
 */
object ProjectUnawareFeatures {
    data class Feature(val label: String, val marker: String)

    val DEFAULT = listOf(
        Feature("payment", "dev.sumin.skeleton.payment.PaymentAutoConfiguration"),
        Feature("notification-websocket (STOMP user destinations)", "dev.sumin.skeleton.notification.websocket.NotificationWebSocketAutoConfiguration"),
        Feature("redis-core", "dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration"),
        Feature("crypto", "dev.sumin.skeleton.crypto.CryptoAutoConfiguration"),
        Feature("event-kafka", "dev.sumin.skeleton.event.kafka.EventKafkaAutoConfiguration"),
    )

    fun check(registry: ProjectRegistry, features: List<Feature> = DEFAULT, classLoader: ClassLoader? = ClassUtils.getDefaultClassLoader()) {
        if (registry.active.size < 2) return
        val present = features.filter { ClassUtils.isPresent(it.marker, classLoader) }
        check(present.isEmpty()) {
            "skeleton.projects has ${registry.active.size} active projects, but these features do not know projects yet and would mix them: " +
                present.joinToString { it.label } + ". Remove the module from this app, or keep only one active project (status: closed for the rest)."
        }
    }
}
