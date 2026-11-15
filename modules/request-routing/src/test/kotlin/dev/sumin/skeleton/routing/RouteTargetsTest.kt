package dev.sumin.skeleton.routing

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTargetsTest {
    private fun properties(
        service: String = "orders",
        target: String = "alice",
        url: String = "https://alice.example.com",
        local: Boolean = false,
    ) = RequestRoutingProperties(
        enabled = true,
        services = mapOf(service to RequestRoutingProperties.Service(mapOf(target to RequestRoutingProperties.Target(url = url, local = local)))),
    )

    @Test
    fun `rejects a url with a path and names the property, not the value`() {
        val failure = assertFailsWith<IllegalStateException> { RouteTargets(properties(url = "https://alice.example.com/base")) }

        assertTrue("skeleton.routing.services.orders.targets.alice.url" in failure.message.orEmpty())
        assertFalse("alice.example.com" in failure.message.orEmpty())
    }

    @Test
    fun `rejects a url with credentials, a query or a fragment`() {
        listOf("https://user:pw@alice.example.com", "https://alice.example.com?x=1", "https://alice.example.com#f").forEach { url ->
            assertFailsWith<IllegalStateException>(url) { RouteTargets(properties(url = url)) }
        }
    }

    @Test
    fun `rejects a non http scheme`() {
        listOf("ftp://alice.example.com", "file:///etc/passwd", "alice.example.com", "").forEach { url ->
            assertFailsWith<IllegalStateException>(url) { RouteTargets(properties(url = url)) }
        }
    }

    @Test
    fun `rejects names outside the baggage name shape`() {
        assertFailsWith<IllegalStateException> { RouteTargets(properties(service = "Orders")) }
        assertFailsWith<IllegalStateException> { RouteTargets(properties(target = "a_b")) }
    }

    @Test
    fun `finds a target by service and name`() {
        val targets = RouteTargets(properties(url = "http://localhost:8081/", local = true))

        val found = targets.find("orders", "alice")!!
        assertEquals(URI.create("http://localhost:8081"), found.origin)
        assertTrue(found.local)
        assertNull(targets.find("orders", "nobody"))
        assertNull(targets.find("payments", "alice"))
        assertTrue(targets.knowsService("orders"))
        assertFalse(targets.knowsService("payments"))
        assertTrue(targets.hasLocalTargets)
    }

    @Test
    fun `propagated services are the service keys`() {
        val targets = RouteTargets(properties())

        assertEquals(setOf("orders"), targets.propagatedServices)
        assertFalse(targets.hasLocalTargets)
    }
}
