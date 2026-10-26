package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry

/**
 * 지금 프로젝트의 Cloudflare 설정. 프로젝트 모드에서는 공통 위에 그 프로젝트의 `config` 를 덮되, **계정 id · 사이트 태그 · 토큰은 프로젝트가 자기 `config` 에 직접 적어야** 한다 —
 * 공통에서 물려받은 값으로 남의 사이트 숫자가 이 프로젝트 화면에 나오지 않게, 하나라도 빠지면 그 프로젝트는 연결 안 됨으로 둔다.
 * 프로젝트 문맥이 없으면 공통 값 그대로.
 */
class CloudflareSettings(private val base: CloudflareProperties, private val projects: ProjectRegistry?) {
    private val scoped = projects?.scopedFrom("skeleton.dashboard-cloudflare", CloudflareProperties::class.java) { project, bound ->
        val own = { key: String -> projects.ownValue(project, "dashboard-cloudflare.$key").orEmpty() }
        bound.copy(accountId = own("account-id"), siteTag = own("site-tag"), apiToken = own("api-token"))
    }

    fun current(): CloudflareProperties {
        val project = ProjectContext.currentOrNull()
        return if (scoped != null && project != null) scoped.of(project) else base
    }
}
