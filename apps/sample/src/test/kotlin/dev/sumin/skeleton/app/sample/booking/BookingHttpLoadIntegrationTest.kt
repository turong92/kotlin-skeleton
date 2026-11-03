package dev.sumin.skeleton.app.sample.booking

import dev.sumin.skeleton.app.sample.TestcontainersConfiguration
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.NewBookingPool
import dev.sumin.skeleton.booking.NewSeat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * HTTP 부하 (스펙 §7 시험 6): 진짜 서버(RANDOM_PORT) · 진짜 보안 체인 · 진짜 PostgreSQL 에서 가상 스레드 1000 개가 서로 다른 계정으로 같은 10자리를 실제 HTTP 로 찍는다.
 * 자리마다 정확히 한 계정만 잡고(200 열 번), 나머지는 `409 BOOKING.SEAT_TAKEN`, 5xx 는 0. 지연(p50 · p95)은 **기록만** 하고 단정하지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.import=classpath:test-seeds.yml"],
)
@Import(TestcontainersConfiguration::class, SampleIntegrationTest.FakeStorageConfiguration::class)
class BookingHttpLoadIntegrationTest {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var pools: BookingPools
    @Autowired private lateinit var jwt: JwtTokenService

    @Test
    fun `a thousand accounts press the same ten seats over real HTTP - ten win, the rest get SEAT_TAKEN, and nothing is a 5xx`() {
        val pool = pools.create(NewBookingPool("load-${System.nanoTime()}", Instant.now().minusSeconds(3600), maxPerHold = 1))
        val seats = pools.addSeats(pool.id, (1..10).map { NewSeat("A", "1", it, "R") }).map { it.id }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        val tokens = (0 until 1000).map { jwt.issue(CurrentPrincipal(accountId = "load-account-$it")).accessToken }

        class Result(val status: Int, val body: String, val nanos: Long)

        val start = CountDownLatch(1)
        val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = tokens.mapIndexed { i, token ->
                executor.submit<Result> {
                    val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1/booking/pools/${pool.id}/hold/seats"))
                        .timeout(Duration.ofSeconds(120))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"seatIds":["${seats[i % 10]}"]}"""))
                        .build()
                    start.await()
                    val began = System.nanoTime()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    Result(response.statusCode(), response.body(), System.nanoTime() - began)
                }
            }
            start.countDown()
            futures.map { it.get() }
        }

        val byStatus = results.groupingBy { it.status }.eachCount()
        assertEquals(emptyMap(), byStatus.filterKeys { it >= 500 }, "no 5xx: $byStatus")
        assertEquals(mapOf(200 to 10, 409 to 990), byStatus.toSortedMap(), "ten winners and the rest SEAT_TAKEN: $byStatus")
        assertEquals(990, results.count { it.status == 409 && "BOOKING.SEAT_TAKEN" in it.body })
        val sorted = results.map { it.nanos / 1_000_000.0 }.sorted()
        println("BOOKING-HTTP-LOAD requests=1000 ok=10 taken=990 p50=%.1fms p95=%.1fms max=%.1fms".format(sorted[499], sorted[949], sorted.last()))
    }
}
