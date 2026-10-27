package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardCache
import dev.sumin.skeleton.common.logging.LogMasker
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 적재 멱등: 같은 날을 두 번 적재해도 같은 행, 전환 행은 핵심 이벤트 이름이 있을 때만, 경로에 쿼리가 남지 않는다 */
class GaIngestTest {
    private val api = FakeGaApi()
    private val account = TestServiceAccount("reader@example.iam")
    private val zone = ZoneId.of("Asia/Seoul")
    private val store = MemoryStore()
    private val day = LocalDate.parse("2026-10-26")

    init { api.register(account) }

    @AfterTest
    fun close() = api.close()

    private fun properties(keyEvent: String = "") = GaProperties(propertyId = "123", credentials = account.credentials, keyEvent = keyEvent, baseUrl = api.baseUrl, tokenUrl = api.tokenUrl)

    private fun ingestor(keyEvent: String = ""): GaIngestor {
        val http = httpClient()
        return GaIngestor(GaDataClient(http, GaTokenSource(http) { NOW }), GaSettings(properties(keyEvent), null))
    }

    private fun scripted() {
        api.reports["report:sessionDefaultChannelGroup"] = listOf(FakeGaApi.Out(listOf("Organic Search"), listOf(100, 5)), FakeGaApi.Out(listOf("Direct"), listOf(50, 1)))
        api.reports["report:sessionCampaignName"] = listOf(FakeGaApi.Out(listOf("(not set)"), listOf(120, 6)), FakeGaApi.Out(listOf("spring"), listOf(30, 0)))
        api.reports["report:landingPage"] = listOf(FakeGaApi.Out(listOf("/a?x=1"), listOf(3, 0)), FakeGaApi.Out(listOf("/a?y=2#top"), listOf(2, 1)), FakeGaApi.Out(listOf("/b"), listOf(5, 0)))
        api.reports["report:pageReferrer,pagePath"] = listOf(FakeGaApi.Out(listOf("https://site.test/p?q=1", "/a?z=1"), listOf(7)), FakeGaApi.Out(listOf("https://site.test/p?q=2", "/a"), listOf(1)))
        api.reports["report:newVsReturning"] = listOf(FakeGaApi.Out(listOf("new"), listOf(10)), FakeGaApi.Out(listOf("returning"), listOf(4)))
    }

    @Test
    fun `loading the same day twice leaves the same rows, and the second load reuses the token`() {
        scripted()
        val ingestor = ingestor()
        store.upsert(ingestor.ingest(day, zone))
        val first = store.rows.toMap()
        store.upsert(ingestor.ingest(day, zone))

        assertEquals(first, store.rows)
        assertEquals(100, store.rows[listOf(day, "ga", "channel", "Organic Search")]!!.value)
        assertEquals(10, store.rows[listOf(day, "ga", "returning", "new")]!!.value)
        assertEquals(1, api.tokenRequests.size)
        assertTrue(api.requests.all { it.property == "123" && it.auth == "Bearer tok-reader@example.iam" })
        assertTrue(api.requests.first().body.contains("\"startDate\":\"2026-10-26\"") && api.requests.first().body.contains("\"endDate\":\"2026-10-26\""))
    }

    @Test
    fun `without a key event there are no conversion rows and the metric is not even asked for`() {
        scripted()
        store.upsert(ingestor().ingest(day, zone))

        assertTrue(store.rows.values.none { it.kind.endsWith("_conv") })
        assertTrue(api.requests.none { "keyEvents" in it.body })
    }

    @Test
    fun `with a key event the channel, campaign and landing conversions are loaded`() {
        scripted()
        store.upsert(ingestor("purchase").ingest(day, zone))

        assertEquals(5, store.rows[listOf(day, "ga", "channel_conv", "Organic Search")]!!.value)
        assertEquals(6, store.rows[listOf(day, "ga", "campaign_conv", "(not set)")]!!.value)
        assertEquals(1, store.rows[listOf(day, "ga", "landing_conv", "/a")]!!.value)
        assertTrue(store.rows.values.none { it.kind == "transition_conv" || it.kind == "returning_conv" })
        assertTrue(api.requests.any { "keyEvents:purchase" in it.body })
    }

    @Test
    fun `urls lose their query and fragment and merge, transitions read from to`() {
        scripted()
        store.upsert(ingestor().ingest(day, zone))

        assertEquals(mapOf("/a" to 5L, "/b" to 5L), store.rows.values.filter { it.kind == "landing" }.associate { it.dimension to it.value })
        assertEquals(mapOf("https://site.test/p → /a" to 8L), store.rows.values.filter { it.kind == "transition" }.associate { it.dimension to it.value })
        assertTrue(store.rows.values.none { '?' in it.dimension || '#' in it.dimension })
    }

    @Test
    fun `a share-link token is masked in landing pages and in the path of a full referrer url, with the host kept`() {
        api.reports["report:landingPage"] = listOf(FakeGaApi.Out(listOf("/e/tokenAAAA1111?x=1"), listOf(3, 0)), FakeGaApi.Out(listOf("/e/tokenBBBB2222"), listOf(4, 0)))
        api.reports["report:pageReferrer,pagePath"] = listOf(FakeGaApi.Out(listOf("https://site.test/e/tokenCCCC3333?q=1", "/e/tokenDDDD4444"), listOf(2)), FakeGaApi.Out(listOf("https://site.test", "/a"), listOf(1)))
        val previous = LogMasker.current
        LogMasker.install(LogMasker(pathPatterns = listOf("^/e/(?<value>[^/?#]+)"), patterns = listOf("^/e/(?<value>[^/?#]+)")))
        try {
            store.upsert(ingestor().ingest(day, zone))
        } finally {
            LogMasker.install(previous)
        }

        val landing = store.rows.values.filter { it.kind == "landing" }.associate { it.dimension to it.value }
        assertEquals(1, landing.size, landing.toString())
        assertEquals(7L, landing.values.single())
        val transitions = store.rows.values.filter { it.kind == "transition" }.associate { it.dimension to it.value }
        assertTrue(transitions.keys.none { "token" in it }, transitions.toString())
        assertTrue(transitions.keys.any { it.startsWith("https://site.test/e/") }, transitions.toString())   // 호스트는 남는다
        assertEquals(1L, transitions["https://site.test → /a"])
    }

    @Test
    fun `an unconnected property loads nothing and asks nothing`() {
        val off = GaIngestor(GaDataClient(httpClient(), GaTokenSource(httpClient())), GaSettings(properties().copy(credentials = ""), null))

        assertEquals(emptyList(), off.ingest(day, zone))
        assertEquals(0, api.requests.size)
        assertEquals(0, api.tokenRequests.size)
    }
}
