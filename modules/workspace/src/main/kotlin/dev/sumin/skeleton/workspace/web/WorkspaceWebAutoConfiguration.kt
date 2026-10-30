package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.workspace.CurrentWorkspace
import dev.sumin.skeleton.workspace.WorkspaceAutoConfiguration
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.workspace.WorkspaceAdminService
import dev.sumin.skeleton.workspace.WorkspaceInvitationService
import dev.sumin.skeleton.workspace.WorkspaceProperties
import dev.sumin.skeleton.workspace.WorkspaceService
import dev.sumin.skeleton.workspace.Workspaces
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 경계(`CurrentWorkspace` + `Vary` 필터)와 사용자 경로(`skeleton.workspace.http.enabled`, 기본 켜짐). 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 * 초대 경로(`skeleton.workspace.invitation.enabled=true` 일 때만)는 사용자 경로와 함께 켜지고 꺼진다. 운영자 읽기 경로(`skeleton.workspace.admin.enabled=true`, 기본 꺼짐 → 404)는 `workspace.read` 를 `OperatorAccess` 로 판정한다.
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

    @Bean
    @ConditionalOnBean(WorkspaceInvitationService::class)
    @ConditionalOnProperty(prefix = "skeleton.workspace.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun workspaceInvitationController(service: WorkspaceInvitationService): WorkspaceInvitationController = WorkspaceInvitationController(service)

    @Bean
    @ConditionalOnBean(WorkspaceInvitationService::class)
    @ConditionalOnProperty(prefix = "skeleton.workspace.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun workspaceInvitationAcceptController(service: WorkspaceInvitationService): WorkspaceInvitationAcceptController = WorkspaceInvitationAcceptController(service)

    /** 미리보기만 로그인 없이 연다(수락은 로그인). 앱은 같은 빈 이름으로 덮어쓸 수 있다 */
    @Bean
    @ConditionalOnBean(WorkspaceInvitationService::class)
    @ConditionalOnProperty(prefix = "skeleton.workspace.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = ["workspaceInvitationPublicEndpointContributor"])
    fun workspaceInvitationPublicEndpointContributor(properties: WorkspaceProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "${properties.invitation.basePath}/preview") }

    @Bean
    @ConditionalOnBean(WorkspaceAdminService::class)
    @ConditionalOnProperty(prefix = "skeleton.workspace.admin", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun workspaceAdminController(admin: WorkspaceAdminService, access: OperatorAccess, labels: ObjectProvider<AccountLabels>): WorkspaceAdminController =
        WorkspaceAdminController(admin, access, labels.getIfAvailable())
}
