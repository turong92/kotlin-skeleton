package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.account.AccountDeletionCheck
import dev.sumin.skeleton.common.account.AccountLabels
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.workspace.erasure.WorkspaceAccountErasureListener
import dev.sumin.skeleton.workspace.erasure.WorkspaceDeletionCheck
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 워크스페이스 서비스 · 앱 입구([Workspaces]) · 좌석 계약 · 계정 삭제 고리. 저장소([WorkspaceStore])는 `workspace-jdbc` 가 내놓는다 - 없으면 서비스 빈이 켜지지 않는다.
 * `account` 를 모른다 - 계정 이름(`AccountLabels`)이 있으면 멤버 목록에 끼우고, 삭제 요청 · 탈퇴 고리는 `platform` 계약만 구현해 내놓는다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.workspace.jdbc.WorkspaceJdbcAutoConfiguration"])
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
}
