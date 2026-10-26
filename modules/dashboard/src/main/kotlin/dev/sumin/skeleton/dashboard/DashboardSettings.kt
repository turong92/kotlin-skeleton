package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.time.ZoneId

/**
 * 프로젝트마다 다른 대시보드 설정(시간대 · 돈 타일의 먼저 보일 통화). 프로젝트 모드에서는 공통 `skeleton.dashboard.*` 위에 그 프로젝트의 `config`(`dashboard.zone` · `dashboard.currency`)를 덮고,
 * 프로젝트 문맥이 없으면(프로젝트가 없는 앱 · 요청 밖 일) 공통 값 그대로다. 스위치 · 캐시 수명 · 적재 시각은 서버 전체 값이라 프로젝트별이 아니다.
 */
class DashboardSettings(
    private val base: DashboardProperties,
    private val defaultZone: String,
    projects: ProjectRegistry?,
) {
    private val scoped = projects?.scopedFrom("skeleton.dashboard", DashboardProperties::class.java) { _, properties -> properties }

    fun properties(): DashboardProperties {
        val project = ProjectContext.currentOrNull()
        return if (scoped != null && project != null) scoped.of(project) else base
    }

    fun zone(): ZoneId = ZoneId.of(properties().zone.ifBlank { defaultZone })

    fun currency(): String = properties().currency
}
