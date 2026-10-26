package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.project.ProjectConfigKeys
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardCache
import java.time.Duration
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.springframework.mock.env.MockEnvironment

/** 격리: 속성 id · 서비스 계정이 프로젝트마다, 실시간은 캐시 주기당 프로젝트당 한 번, 키 없는 프로젝트는 요청 0, 키는 오류에 없다 */
class GaProjectsTest {
    private val api = FakeGaApi()
    private val zone = ZoneId.of("Asia/Seoul")
    private val a = TestServiceAccount("a@example.iam")
    private val b = TestServiceAccount("b@example.iam")
    private val shared = TestServiceAccount("shared@example.iam")

    init { listOf(a, b, shared).forEach(api::register) }

    // 공통에도 값이 있다 — 프로젝트는 그것을 물려받지 않는다 (남의 속성 숫자가 보이면 안 된다)
    private val sharedProperties = GaProperties(propertyId = "999", credentials = shared.credentials, baseUrl = api.baseUrl, tokenUrl = api.tokenUrl)

    private val registry = ProjectRegistry.from(
        MockEnvironment()
            .withProperty("skeleton.dashboard-ga.base-url", api.baseUrl).withProperty("skeleton.dashboard-ga.token-url", api.tokenUrl)
            .withProperty("skeleton.projects.a.hosts", "a.test").withProperty("skeleton.projects.a.schema", "pa")
            .withProperty("skeleton.projects.a.config.dashboard-ga.property-id", "111").withProperty("skeleton.projects.a.config.dashboard-ga.credentials", a.credentials)
            .withProperty("skeleton.projects.b.hosts", "b.test").withProperty("skeleton.projects.b.schema", "pb")
            .withProperty("skeleton.projects.b.config.dashboard-ga.property-id", "222").withProperty("skeleton.projects.b.config.dashboard-ga.credentials", b.credentials)
            .withProperty("skeleton.projects.c.hosts", "c.test").withProperty("skeleton.projects.c.schema", "pc"),
        ProjectConfigKeys.SUPPORTED,
    )
    private val settings = GaSettings(sharedProperties, registry)
    private val http = httpClient()
    private val client = GaDataClient(http, GaTokenSource(http) { NOW })
    private val source = GaSource(client, settings, DailySeries({ MemoryStore() }, { zone }, { NOW }), DashboardCache(Duration.ofMinutes(5)))

    @AfterTest
    fun close() = api.close()

    private fun realtime(id: String) = ProjectContext.runIn(registry.projects.single { it.id == id }) { source.realtime() }

    @Test
    fun `each project asks its own property with its own account, once per cache period`() {
        api.reports["realtime:"] = listOf(FakeGaApi.Out(metrics = listOf(7)))
        api.reports["realtime:unifiedScreenName"] = listOf(FakeGaApi.Out(listOf("Home"), listOf(4)))
        api.reports["realtime:country"] = listOf(FakeGaApi.Out(listOf("KR"), listOf(5)))
        repeat(5) {
            assertEquals(7, realtime("a").data!!.activeUsers)
            assertEquals(7, realtime("b").data!!.activeUsers)
        }

        assertEquals(3, api.requests.count { it.property == "111" })   // 실시간 보고서 셋(합계 · 페이지 · 국가), 한 번
        assertEquals(3, api.requests.count { it.property == "222" })
        assertEquals(setOf("111" to "Bearer tok-a@example.iam", "222" to "Bearer tok-b@example.iam"), api.requests.map { it.property to it.auth }.toSet())
        assertEquals(listOf("a@example.iam", "b@example.iam"), api.tokenRequests.sorted())
        assertEquals(listOf("Home"), realtime("a").data!!.pages.map { it.label })
    }

    @Test
    fun `a project without its own settings is not connected and asks nothing - it does not inherit the shared property`() {
        assertEquals(DashboardResult.Status.NOT_CONNECTED, realtime("c").status)
        assertEquals(DashboardResult.Status.NOT_CONNECTED, ProjectContext.runIn(registry.projects.single { it.id == "c" }) { source.top(dev.sumin.skeleton.common.dashboard.DashboardRange(NOW, NOW, zone), "channel", 5).status })
        assertEquals(0, api.requests.size)
        assertEquals(0, api.tokenRequests.size)
    }

    @Test
    fun `a failing Google makes the card an error and the key is in no message`() {
        api.status = 500
        assertEquals(DashboardResult.Status.ERROR, realtime("a").status)

        val properties = ProjectContext.runIn(registry.projects.single { it.id == "a" }) { settings.current() }
        val failure = runCatching { client.realtime(properties, emptyList(), listOf("activeUsers"), 1) }.exceptionOrNull()
        assertNotNull(failure)
        val text = failure.toString() + failure.message.orEmpty() + failure.cause.toString()
        listOf(a.credentials, a.privatePem.lines()[1], "tok-a@example.iam").forEach { assertFalse(it in text, "secret in: $text") }
        assertFalse(a.credentials in properties.toString())
    }

    @Test
    fun `an unusable service account key fails with a message that does not echo the key`() {
        val broken = sharedProperties.copy(credentials = java.util.Base64.getEncoder().encodeToString("""{"client_email":"x@y","private_key":"not-a-key"}""".toByteArray()))
        val failure = runCatching { client.realtime(broken, emptyList(), listOf("activeUsers"), 1) }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse("not-a-key" in failure.toString())
        assertEquals(0, api.requests.size)
    }
}
