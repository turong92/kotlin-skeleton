package dev.sumin.skeleton.routing.async

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.MDC

class RouteRecordInterceptorTest {
    private val consumer = org.apache.kafka.clients.consumer.MockConsumer<Any, Any>("earliest")

    @AfterTest
    fun clean() = MDC.clear()

    private fun record(vararg baggage: String): ConsumerRecord<Any, Any> =
        ConsumerRecord<Any, Any>("orders", 0, 0L, "k", "v").also { r ->
            baggage.forEach { r.headers().add("baggage", it.toByteArray()) }
        }

    private val baseline = RouteRecordInterceptor("orders", "")
    private val candidate = RouteRecordInterceptor("orders", "candidate")

    @Test
    fun `baselineSkipsSelfTargetedRecord`() {
        assertNull(baseline.intercept(record("route=orders:candidate"), consumer))
    }

    @Test
    fun `baselinePassesOthers`() {
        assertNotNull(baseline.intercept(record("route=api:candidate"), consumer))
        assertNotNull(baseline.intercept(record(), consumer))
        assertNotNull(baseline.intercept(record("traceparent=x,a=1"), consumer))
    }

    @Test
    fun `candidatePassesOnlyItsTarget`() {
        assertNotNull(candidate.intercept(record("route=orders:candidate"), consumer))
        assertNull(candidate.intercept(record("route=orders:alice"), consumer))
        assertNull(candidate.intercept(record("route=api:candidate"), consumer))
    }

    @Test
    fun `candidateSkipsUnmarked`() {
        assertNull(candidate.intercept(record(), consumer))
    }

    @Test
    fun `restoresRouteDuringListener`() {
        candidate.intercept(record("a=1,route=orders:candidate|api:x"), consumer)
        assertEquals("orders:candidate|api:x", MDC.get("route"))
        baseline.intercept(record("route=api:x"), consumer)
        assertEquals("api:x", MDC.get("route"))
    }

    @Test
    fun `clearsMdcAfterRecord`() {
        val r = record("route=orders:candidate")
        candidate.intercept(r, consumer)
        candidate.afterRecord(r, consumer)
        assertNull(MDC.get("route"))
    }

    @Test
    fun `clears mdc when the listener fails`() {
        val r = record("route=orders:candidate")
        candidate.intercept(r, consumer)
        candidate.failure(r, IllegalStateException("boom"), consumer)
        assertNull(MDC.get("route"))
    }

    @Test
    fun `a skipped record leaves no route behind for the next one`() {
        MDC.put("route", "stale:x")
        baseline.intercept(record("route=orders:candidate"), consumer)
        assertNull(MDC.get("route"))
    }

    @Test
    fun `malformedBaggageTreatedAsUnmarked`() {
        assertNotNull(baseline.intercept(record("route=orders:https://evil.example.com"), consumer))
        assertNull(MDC.get("route"))
        assertNull(candidate.intercept(record("route=orders:candidate,route=orders:candidate"), consumer))
    }
}
