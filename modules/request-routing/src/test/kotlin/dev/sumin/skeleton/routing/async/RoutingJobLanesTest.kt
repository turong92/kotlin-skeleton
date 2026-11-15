package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.routing.RequestRoutingProperties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.slf4j.MDC

class RoutingJobLanesTest {
    @AfterTest
    fun clean() = MDC.clear()

    private fun lanes(service: String = "orders", instance: String = "") =
        RoutingJobLanes(RequestRoutingProperties(enabled = true, service = service, instance = instance))

    @Test
    fun `enqueueLaneIsSelfTarget`() {
        MDC.put("route", "orders:candidate|api:x")
        assertEquals("candidate", lanes().enqueueLane())
    }

    @Test
    fun `route without this service gives the default lane`() {
        MDC.put("route", "api:x")
        assertNull(lanes().enqueueLane())
    }

    @Test
    fun `customerRequestEnqueuesDefaultLane`() {
        // 손님 요청은 들어올 때 표시가 버려져 MDC 에 route 가 없다
        assertNull(lanes().enqueueLane())
    }

    @Test
    fun `no service name means the default lane even with a route`() {
        MDC.put("route", "orders:candidate")
        assertNull(lanes(service = "").enqueueLane())
    }

    @Test
    fun `workerLaneIsInstance`() {
        assertEquals("candidate", lanes(instance = "candidate").workerLane())
    }

    @Test
    fun `baselineWorkerLaneNull`() {
        assertNull(lanes(instance = "").workerLane())
    }
}
