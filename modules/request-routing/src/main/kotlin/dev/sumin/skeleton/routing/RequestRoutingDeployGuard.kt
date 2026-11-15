package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import java.time.Duration
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * `instance` 를 적은 인스턴스(후보 · 로컬)가 원래 서버 몫의 일을 가로채거나 운영 DB 를 건드리지 않게 기동 때 막는다 — 환경과 상관없이.
 * 다른 모듈의 빈은 선택 통합이라 **클래스 이름 문자열**로만 찾는다(없으면 없는 것). 메시지엔 속성 이름만 싣고 값은 싣지 않는다.
 */
class RequestRoutingDeployGuard(
    private val properties: RequestRoutingProperties,
    private val targets: RouteTargets,
    private val beans: ListableBeanFactory,
    private val environment: Environment,
    // 다른 모듈의 클래스 이름 — 시험은 자기 패키지의 자리표시 클래스를 넘겨, 진짜 클래스를 가리는 가짜를 시험 클래스패스에 두지 않는다
    private val schedulerClass: String = SCHEDULER,
    private val flywayClass: String = FLYWAY,
) : DeployGuard {
    override val name: String = "request-routing"

    override fun problems(context: DeployContext): List<String> = buildList {
        if (properties.local.maxWindow > MAX_WINDOW_LIMIT) {
            add("skeleton.routing.local.max-window is longer than the 8h limit.")
        }
        if (properties.admin.enabled && !has(LocalRouteWindowStore::class.java.name)) {
            add("skeleton.routing.admin.enabled is on, but no LocalRouteWindowStore bean exists (add modules:request-routing-jdbc or define one); the window API would answer 404.")
        }
        if (properties.instance.isNotBlank()) addAll(instanceProblems())
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (context.protectedEnv && targets.hasLocalTargets && !has(LocalRouteWindowStore::class.java.name)) {
            add("skeleton.routing.services has local targets, but no LocalRouteWindowStore bean exists, so the local window can never be opened.")
        }
        if (has(JOB_QUEUE_WORKER) && "route" !in propagatedMdcKeys()) {
            add("skeleton.job-queue.propagated-mdc-keys has no 'route': routed requests enqueue jobs whose route is not restored when the job runs.")
        }
    }

    private fun instanceProblems(): List<String> = buildList {
        if (properties.service.isBlank()) {
            add("skeleton.routing.instance is set, but skeleton.routing.service is empty: job lanes, message filtering and the proxy are all decided by the service name.")
        }
        if (has(schedulerClass) && !environment.getProperty("skeleton.scheduler.mode").equals("external", ignoreCase = true)) {
            add("skeleton.routing.instance is set, but skeleton.scheduler.mode is not 'external': an external scheduler only calls the original address, so nothing would run here.")
        }
        if (has(JOB_QUEUE_WORKER) && !onRequestQueue() && !workerLaneIsSet()) {
            add("skeleton.routing.instance is set, but this job worker has no lane and would take the original server's jobs: use the request-routing job lanes, or set skeleton.job-queue.mode=on-request.")
        }
        if (has(KAFKA_REGISTRY) && environment.getProperty("spring.kafka.listener.auto-startup", Boolean::class.java, true) &&
            !beans.containsBean(KAFKA_CUSTOMIZER_BEAN)
        ) {
            add("skeleton.routing.instance is set, but the routing Kafka customizer is not installed, so Kafka listeners would share the original consumer group: install it or set spring.kafka.listener.auto-startup=false.")
        }
        if (has(flywayClass)) {
            add("skeleton.routing.instance is set, but Flyway would run this branch's migrations against the shared database: set spring.flyway.enabled=false.")
        }
    }

    private fun has(className: String): Boolean = beanNames(className).isNotEmpty()

    private fun beanNames(className: String): List<String> {
        val type = runCatching { ClassUtils.forName(className, javaClass.classLoader) }.getOrNull() ?: return emptyList()
        return beans.getBeanNamesForType(type, true, false).toList()
    }

    private fun onRequestQueue(): Boolean =
        environment.getProperty("skeleton.job-queue.mode").orEmpty().replace("-", "").replace("_", "").equals("onrequest", ignoreCase = true)

    private fun workerLaneIsSet(): Boolean {
        val bean = beanNames(JOB_LANES).firstOrNull()?.let { beans.getBean(it) } ?: return false
        return ClassUtils.forName(JOB_LANES, javaClass.classLoader).getMethod("workerLane").invoke(bean) != null
    }

    private fun propagatedMdcKeys(): List<String> =
        Binder.get(environment).bind(PROPAGATED_MDC_KEYS, Bindable.listOf(String::class.java)).orElse(listOf("traceId"))!!

    private companion object {
        val MAX_WINDOW_LIMIT: Duration = Duration.ofHours(8)
        const val SCHEDULER = "dev.sumin.skeleton.scheduler.SkeletonScheduledTaskRegistrar"
        const val JOB_QUEUE_WORKER = "dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker"
        const val JOB_LANES = "dev.sumin.skeleton.jobqueue.jdbc.JobLanes"
        const val KAFKA_REGISTRY = "org.springframework.kafka.config.KafkaListenerEndpointRegistry"
        const val FLYWAY = "org.flywaydb.core.Flyway"
        // job-queue 모듈의 키 - 이 모듈의 접두사가 아니라 읽기만 한다
        const val PROPAGATED_MDC_KEYS = "skeleton.job-queue.propagated-mdc-keys"
        const val KAFKA_CUSTOMIZER_BEAN = "routeKafkaContainerCustomizer"
    }
}
