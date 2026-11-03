package dev.sumin.skeleton.videoworker

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    private fun get(port: Int, path: String, method: String = "GET"): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `health — 200 과 UP 을 주고 다른 경로는 404, 쓰기 메서드는 열지 않는다`() {
        HealthServer.start(0).use { health ->
            val ok = get(health.port, "/health")
            assertEquals(200, ok.statusCode())
            assertTrue(ok.body().contains("UP"), ok.body())
            assertEquals(404, get(health.port, "/other").statusCode())
            assertEquals(405, get(health.port, "/health", "POST").statusCode())
        }
    }
}
