package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardCache
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 적재 멱등: 같은 날을 두 번 적재해도 같은 행, 표본 환산이 맞고, 경로에 쿼리가 남지 않는다 */
class CloudflareIngestTest {
    private val api = FakeCloudflareApi()
    private val zone = ZoneId.of("Asia/Seoul")
    private val store = MemoryStore()
    private val properties = CloudflareProperties(accountId = "acc1", siteTag = "site1", apiToken = "tok-secret-1", baseUrl = api.baseUrl)
    private val source = CloudflareSource(CloudflareAnalyticsClient(httpClient()), CloudflareSettings(properties, null), DailySeries({ store }, { zone }, { NOW }), DashboardCache(Duration.ofMinutes(5)), { zone })
    private val ingestor = CloudflareIngestor(source)
    private val day = LocalDate.parse("2026-10-26")

    @AfterTest
    fun close() = api.close()

    private fun scripted() {
        api.rows["total"] = listOf(FakeCloudflareApi.Row(count = 10, visits = 4, sampleInterval = 10.0))
        // 실측 모양: 리퍼러 없음은 빈 문자열, 자기 사이트 안의 이동은 자기 호스트 + 방문 0 (페이지뷰만 있다)
        api.rows["referrer"] = listOf(FakeCloudflareApi.Row("google.com", 7, 3), FakeCloudflareApi.Row("", 3, 1), FakeCloudflareApi.Row("shop.example.test", 90, 0))
        api.rows["path"] = listOf(FakeCloudflareApi.Row("/a?token=abc", 3, 2), FakeCloudflareApi.Row("/a?x=1#top", 2, 1), FakeCloudflareApi.Row("/b", 5, 3))
        api.rows["country"] = listOf(FakeCloudflareApi.Row("KR", 8, 3))
        api.rows["device"] = listOf(FakeCloudflareApi.Row("mobile", 9, 4))
    }

    @Test
    fun `loading the same day twice leaves the same rows, and the values are Cloudflare's estimates as they are`() {
        scripted()
        store.upsert(ingestor.ingest(day, zone))
        val first = store.rows.toMap()
        store.upsert(ingestor.ingest(day, zone))

        assertEquals(first, store.rows)
        fun value(kind: String, dimension: String = "") = store.rows[listOf(day, "cloudflare", kind, dimension)]!!.value
        assertEquals(4, value("visits"))           // sampleInterval 10 이어도 다시 곱하지 않는다 — count · visits 는 이미 추정값이다(실측)
        assertEquals(10, value("pageviews"))
        assertEquals(3, value("referrer", "google.com"))
        assertEquals(1, value("referrer", "(direct)"))   // 리퍼러 없음: 빈 차원은 전체 값(빈 문자열)과 섞이지 않는다
        assertTrue(store.rows.values.none { it.kind == "referrer" && it.dimension == "shop.example.test" }, "visits 0 (internal navigation) is not a source")
    }

    @Test
    fun `paths lose their query and fragment and merge`() {
        scripted()
        store.upsert(ingestor.ingest(day, zone))

        val paths = store.rows.values.filter { it.kind == "path" }.associate { it.dimension to it.value }
        assertEquals(mapOf("/a" to 5L, "/b" to 5L), paths)
        assertTrue(store.rows.values.none { '?' in it.dimension || '#' in it.dimension })
    }

    @Test
    fun `the request asks for that day in the dashboard time zone, for this site`() {
        scripted()
        ingestor.ingest(day, zone)

        val request = api.requests.single()
        assertEquals("2026-10-25T15:00:00Z", request.from)
        assertEquals("2026-10-26T15:00:00Z", request.to)
        assertEquals("site1", request.siteTag)
        assertEquals("Bearer tok-secret-1", request.auth)
    }

    @Test
    fun `an unconnected source loads nothing and asks nothing`() {
        val off = CloudflareIngestor(CloudflareSource(CloudflareAnalyticsClient(httpClient()), CloudflareSettings(properties.copy(apiToken = ""), null), DailySeries({ store }, { zone }, { NOW }), DashboardCache(Duration.ZERO), { zone }))

        assertEquals(emptyList(), off.ingest(day, zone))
        assertEquals(0, api.requests.size)
    }
}
