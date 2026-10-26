package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.dashboard.DashboardRange
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
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

/** 격리: 사이트 태그 · 토큰이 프로젝트마다, 캐시 주기당 프로젝트당 한 번, 키 없는 프로젝트는 요청 0, 토큰은 오류에 없다 */
class CloudflareProjectsTest {
    private val api = FakeCloudflareApi()
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = DashboardRange(java.time.Instant.parse("2026-10-26T15:00:00Z"), java.time.Instant.parse("2026-10-27T15:00:00Z"), zone)

    // 공통에도 값이 있다 — 프로젝트는 그것을 물려받지 않는다 (남의 사이트 숫자가 보이면 안 된다)
    private val shared = CloudflareProperties(accountId = "shared-acc", siteTag = "shared-site", apiToken = "shared-token", baseUrl = api.baseUrl)

    private val registry = ProjectRegistry.from(
        MockEnvironment()
            .withProperty("skeleton.dashboard-cloudflare.base-url", api.baseUrl)   // 공통 값 — 프로젝트의 properties 도 이 주소를 물려받는다
            .withProperty("skeleton.projects.a.hosts", "a.test").withProperty("skeleton.projects.a.schema", "pa")
            .withProperty("skeleton.projects.a.config.dashboard-cloudflare.account-id", "acc-a")
            .withProperty("skeleton.projects.a.config.dashboard-cloudflare.site-tag", "site-a")
            .withProperty("skeleton.projects.a.config.dashboard-cloudflare.api-token", "token-a-secret")
            .withProperty("skeleton.projects.b.hosts", "b.test").withProperty("skeleton.projects.b.schema", "pb")
            .withProperty("skeleton.projects.b.config.dashboard-cloudflare.account-id", "acc-b")
            .withProperty("skeleton.projects.b.config.dashboard-cloudflare.site-tag", "site-b")
            .withProperty("skeleton.projects.b.config.dashboard-cloudflare.api-token", "token-b-secret")
            .withProperty("skeleton.projects.c.hosts", "c.test").withProperty("skeleton.projects.c.schema", "pc"),
        ProjectConfigKeys.SUPPORTED,
    )
    private val source = CloudflareSource(
        CloudflareAnalyticsClient(httpClient()), CloudflareSettings(shared, registry), DailySeries({ MemoryStore() }, { zone }, { NOW }),
        DashboardCache(Duration.ofMinutes(5)), { zone },
    )

    @AfterTest
    fun close() = api.close()

    private fun inProject(id: String) = registry.projects.single { it.id == id }

    private fun totals(id: String) = ProjectContext.runIn(inProject(id)) { source.totals(today) }

    @Test
    fun `each project asks for its own site with its own token, once per cache period`() {
        api.rows["total"] = listOf(FakeCloudflareApi.Row(count = 10, visits = 4))
        repeat(5) {
            assertEquals(OK to (4L to 10L), totals("a").let { it.status to it.data })
            assertEquals(OK to (4L to 10L), totals("b").let { it.status to it.data })
        }

        assertEquals(listOf("site-a", "site-b"), api.requests.map { it.siteTag }.sorted())
        assertEquals(setOf("acc-a" to "Bearer token-a-secret", "acc-b" to "Bearer token-b-secret"), api.requests.map { it.accountTag to it.auth }.toSet())
    }

    @Test
    fun `a project without its own settings is not connected and asks nothing - it does not inherit the shared site`() {
        assertEquals(DashboardResult.Status.NOT_CONNECTED, totals("c").status)
        assertEquals(0, api.requests.size)
    }

    @Test
    fun `a failing Cloudflare makes the card an error and the token is in no message`() {
        api.status = 500
        val result = totals("a")
        assertEquals(DashboardResult.Status.ERROR, result.status)

        val failure = runCatching { CloudflareAnalyticsClient(httpClient()).query(inProject("a").let { ProjectContext.runIn(it) { CloudflareSettings(shared, registry).current() } }, today.from, today.to, listOf(CloudflareAnalyticsClient.Grouping.TOTAL)) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse("token-a-secret" in failure.toString() + failure.message.orEmpty() + (failure.cause?.toString() ?: ""))
        assertFalse("token-a-secret" in CloudflareSettings(shared, registry).current().toString())
        assertTrue(CloudflareProperties(apiToken = "x").toString().contains("<redacted>"))
    }

    private companion object {
        val OK = DashboardResult.Status.OK
    }
}
