package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.workspace.CurrentWorkspace
import dev.sumin.skeleton.workspace.WorkspaceAutoConfiguration
import dev.sumin.skeleton.workspace.WorkspaceProperties
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspace.Workspaces
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 경계(`CurrentWorkspace` + `Vary` 필터)와 사용자 경로(`skeleton.workspace.http.enabled`, 기본 켜짐). 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 * 초대 · 운영자 경로는 다음 조각이 이 클래스에 더한다.
 */
@AutoConfiguration(after = [WorkspaceAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
class WorkspaceWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(Workspaces::class)
    @ConditionalOnMissingBean(CurrentWorkspace::class)
    fun currentWorkspace(workspaces: Workspaces): CurrentWorkspace = RequestCurrentWorkspace(workspaces)

    @Bean
    @ConditionalOnBean(Workspaces::class)
    @ConditionalOnMissingBean
    fun workspaceVaryFilter(): WorkspaceVaryFilter = WorkspaceVaryFilter()

    @Bean
    @ConditionalOnBean(WorkspaceService::class)
    @ConditionalOnProperty(prefix = "skeleton.workspace.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun workspaceController(service: WorkspaceService, properties: WorkspaceProperties): WorkspaceController = WorkspaceController(service, properties)
}
