package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.workspace.erasure.WorkspaceAccountErasureListener
import dev.sumin.skeleton.workspace.erasure.WorkspaceDeletionCheck
import dev.sumin.skeleton.workspace.mail.DefaultWorkspaceInvitationMail
import dev.sumin.skeleton.workspace.mail.LogOnlyWorkspaceMailTransport
import dev.sumin.skeleton.workspace.mail.TemplatedWorkspaceInvitationMailer
import dev.sumin.skeleton.workspace.mail.WorkspaceInvitationMail
import dev.sumin.skeleton.workspace.mail.WorkspaceLinks
import dev.sumin.skeleton.workspace.mail.WorkspaceMailTransport
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * 워크스페이스 서비스 · 앱 입구([Workspaces]) · 좌석 계약 · 계정 삭제 고리. 저장소([WorkspaceStore])는 `workspace-jdbc` 가 내놓는다 - 없으면 서비스 빈이 켜지지 않는다.
 * `account` 를 모른다 - 계정 이름(`AccountLabels`)이 있으면 멤버 목록에 끼우고, 삭제 요청 · 탈퇴 고리는 `platform` 계약만 구현해 내놓는다.
 */
@AutoConfiguration(
    afterName = ["dev.sumin.skeleton.workspace.jdbc.WorkspaceJdbcAutoConfiguration", "dev.sumin.skeleton.workspace.mail.WorkspaceMailAutoConfiguration"],
)
@EnableConfigurationProperties(WorkspaceProperties::class)
class WorkspaceAutoConfiguration {
    /** 좌석 상한 없음 - 앱이 같은 타입의 빈을 두면(예: 구독 플랜 → 상한) 그것이 쓰인다 */
    @Bean
    @ConditionalOnMissingBean(WorkspaceSeatPolicy::class)
    fun noWorkspaceSeatLimit(): WorkspaceSeatPolicy = WorkspaceSeatPolicy { null }

    @Bean
    @ConditionalOnBean(WorkspaceStore::class)
    @ConditionalOnMissingBean
    fun workspaceService(
        store: WorkspaceStore,
        time: ObjectProvider<TimeProvider>,
        seats: WorkspaceSeatPolicy,
        labels: ObjectProvider<AccountLabels>,
    ): WorkspaceService = WorkspaceService(store, time.getIfAvailable { TimeProvider.systemUtc() }, seats, labels.getIfAvailable())

    @Bean
    @ConditionalOnBean(WorkspaceService::class)
    @ConditionalOnMissingBean(Workspaces::class)
    fun workspaces(service: WorkspaceService): Workspaces = service

    @Bean
    @ConditionalOnBean(WorkspaceStore::class)
    @ConditionalOnMissingBean(name = ["workspaceDeletionCheck"])
    fun workspaceDeletionCheck(store: WorkspaceStore): AccountDeletionCheck = WorkspaceDeletionCheck(store)

    @Bean
    @ConditionalOnBean(WorkspaceStore::class)
    @ConditionalOnMissingBean(name = ["workspaceAccountErasureListener"])
    fun workspaceAccountErasureListener(store: WorkspaceStore, time: ObjectProvider<TimeProvider>): AccountErasureListener =
        WorkspaceAccountErasureListener(store, time.getIfAvailable { TimeProvider.systemUtc() })

    // 초대 (skeleton.workspace.invitation.enabled=true 일 때만)

    /** 메일 모듈이 없을 때의 길 - 링크는 로컬에서만 로그에 남는다. 메일 모듈이 있으면 [dev.sumin.skeleton.workspace.mail.WorkspaceMailAutoConfiguration] 의 길이 먼저 선다 */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.workspace.invitation", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(WorkspaceMailTransport::class)
    fun logOnlyWorkspaceMailTransport(properties: WorkspaceProperties, environment: Environment): WorkspaceMailTransport = LogOnlyWorkspaceMailTransport(
        when (properties.mail.logLinks) {
            WorkspaceProperties.Mail.LogLinks.ON -> true
            WorkspaceProperties.Mail.LogLinks.OFF -> false
            WorkspaceProperties.Mail.LogLinks.AUTO -> !DeployContext.from(environment).protectedEnv
        },
    )

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.workspace.invitation", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun workspaceInvitationMail(properties: WorkspaceProperties): WorkspaceInvitationMail = DefaultWorkspaceInvitationMail(properties.mail.defaultLocale)

    /** 프로젝트 모드면 링크 주소는 그 프로젝트가 정한 값만 - 비면 공통(다른 프로젝트의 도메인일 수 있다)으로 내려가지 않는다. 가드가 보호 환경에서 막는다 */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.workspace.invitation", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun workspaceLinks(properties: WorkspaceProperties, projects: ObjectProvider<ProjectRegistry>): WorkspaceLinks {
        val registry = projects.getIfAvailable() ?: return WorkspaceLinks { properties.mail.linkBaseUrl }
        val bases = registry.scopedFrom("skeleton.workspace", WorkspaceProperties::class.java) { project, _ ->
            registry.bindOwn(project, "skeleton.workspace", WorkspaceProperties::class.java).mail.linkBaseUrl
        }
        return WorkspaceLinks { bases.get() }
    }

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.workspace.invitation", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(WorkspaceInvitationMailer::class)
    fun workspaceInvitationMailer(templates: WorkspaceInvitationMail, transport: WorkspaceMailTransport, links: WorkspaceLinks): WorkspaceInvitationMailer =
        TemplatedWorkspaceInvitationMailer(templates, transport, links)

    /** 수락은 호출자의 *확인된* 이메일을 초대 주소와 대조한다 - 그 사실을 줄 `AccountFacts`(modules:account)가 없으면 서지 않는다 */
    @Bean
    @ConditionalOnBean(WorkspaceService::class)
    @ConditionalOnProperty(prefix = "skeleton.workspace.invitation", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun workspaceInvitationService(
        store: WorkspaceStore,
        time: ObjectProvider<TimeProvider>,
        seats: WorkspaceSeatPolicy,
        workspaces: WorkspaceService,
        facts: ObjectProvider<AccountFacts>,
        mailer: WorkspaceInvitationMailer,
        properties: WorkspaceProperties,
        labels: ObjectProvider<AccountLabels>,
    ): WorkspaceInvitationService = WorkspaceInvitationService(
        store, time.getIfAvailable { TimeProvider.systemUtc() }, seats, workspaces,
        facts.getIfAvailable() ?: throw IllegalStateException(
            "skeleton.workspace.invitation.enabled=true needs an AccountFacts bean (add modules:account, or define your own AccountFacts): " +
                "accepting an invitation compares the caller's verified email with the invited address",
        ),
        mailer, properties.invitation, labels.getIfAvailable(),
    )

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.workspace.invitation", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["workspaceDeployGuard"])
    fun workspaceDeployGuard(properties: WorkspaceProperties, projects: ObjectProvider<ProjectRegistry>, transport: ObjectProvider<WorkspaceMailTransport>): WorkspaceDeployGuard =
        WorkspaceDeployGuard(
            properties,
            projects.getIfAvailable()?.active.orEmpty().map { p ->
                WorkspaceDeployGuard.ProjectView(p.id, projects.getObject().bindOwn(p, "skeleton.workspace", WorkspaceProperties::class.java).mail.linkBaseUrl)
            },
        ) { transport.getIfUnique() }
}
