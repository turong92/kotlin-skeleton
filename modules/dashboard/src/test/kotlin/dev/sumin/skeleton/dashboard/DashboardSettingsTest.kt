package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.project.ProjectConfigKeys
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.mock.env.MockEnvironment

/** 시간대 · 통화는 프로젝트마다: 프로젝트가 안 적으면 공통 값, 프로젝트 밖이면 공통 값 그대로 */
class DashboardSettingsTest {
    private val registry = ProjectRegistry.from(
        MockEnvironment()
            .withProperty("skeleton.dashboard.zone", "Asia/Seoul").withProperty("skeleton.dashboard.currency", "KRW")
            .withProperty("skeleton.projects.a.hosts", "a.test").withProperty("skeleton.projects.a.schema", "pa")
            .withProperty("skeleton.projects.a.config.dashboard.zone", "America/New_York").withProperty("skeleton.projects.a.config.dashboard.currency", "USD")
            .withProperty("skeleton.projects.b.hosts", "b.test").withProperty("skeleton.projects.b.schema", "pb"),
        ProjectConfigKeys.SUPPORTED,
    )
    private val settings = DashboardSettings(DashboardProperties(zone = "Asia/Seoul", currency = "KRW"), "Asia/Seoul", registry)

    @Test
    fun `zone and currency follow the project in context`() {
        val (a, b) = registry.projects
        assertEquals(ZoneId.of("America/New_York") to "USD", ProjectContext.runIn(a) { settings.zone() to settings.currency() })
        assertEquals(ZoneId.of("Asia/Seoul") to "KRW", ProjectContext.runIn(b) { settings.zone() to settings.currency() })
        assertEquals(ZoneId.of("Asia/Seoul") to "KRW", settings.zone() to settings.currency())
    }
}
