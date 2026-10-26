package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry

/**
 * 지금 프로젝트의 GA 설정. 프로젝트 모드에서는 공통 위에 그 프로젝트의 `config` 를 덮되, **속성 id 와 서비스 계정 키는 프로젝트가 자기 `config` 에 직접 적어야** 한다 —
 * 공통에서 물려받은 값으로 남의 속성 숫자가 이 프로젝트 화면에 나오지 않게, 하나라도 빠지면 그 프로젝트는 연결 안 됨으로 둔다. 프로젝트 문맥이 없으면 공통 값 그대로.
 */
class GaSettings(private val base: GaProperties, projects: ProjectRegistry?) {
    private val scoped = projects?.scopedFrom("skeleton.dashboard-ga", GaProperties::class.java) { project, bound ->
        val own = { key: String -> projects.ownValue(project, "dashboard-ga.$key").orEmpty() }
        bound.copy(propertyId = own("property-id"), credentials = own("credentials"))
    }

    fun current(): GaProperties {
        val project = ProjectContext.currentOrNull()
        return if (scoped != null && project != null) scoped.of(project) else base
    }
}
