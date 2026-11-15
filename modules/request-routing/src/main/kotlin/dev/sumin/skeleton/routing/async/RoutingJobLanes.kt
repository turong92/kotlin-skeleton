package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.common.routing.RouteBaggage
import dev.sumin.skeleton.jobqueue.jdbc.JobLanes
import dev.sumin.skeleton.routing.RequestRoutingProperties

/**
 * 넣는 쪽: 믿은 표시(MDC `route`)에 이 서비스의 대상이 있으면 그 이름이 차선이다 — 손님 요청은 들어올 때 표시가 버려져 늘 기본 차선.
 * 일하는 쪽: 후보 인스턴스는 자기 이름이 차선이고, 원래 서버(`instance` 비움)는 기본 일꾼이다.
 */
class RoutingJobLanes(private val properties: RequestRoutingProperties) : JobLanes {
    override fun enqueueLane(): String? =
        properties.service.takeIf { it.isNotBlank() }?.let { RouteBaggage.current()?.targetOf(it) }

    override fun workerLane(): String? = properties.instance.ifBlank { null }
}
