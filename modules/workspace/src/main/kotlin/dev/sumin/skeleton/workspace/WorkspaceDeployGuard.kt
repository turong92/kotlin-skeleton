package dev.sumin.skeleton.workspace

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.workspace.mail.LogOnlyWorkspaceMailTransport

/**
 * 초대를 켠 앱이 stage · prod 에서 **초대 링크를 못 보내거나 새는** 구성으로 서는 것을 막는다(`account` 의 가드와 같은 결). 메시지에는 속성 · 모듈 이름만 쓴다 (값 없음).
 * 초대를 켜지 않은 앱에는 이 가드가 등록되지 않는다.
 */
class WorkspaceDeployGuard(
    private val props: WorkspaceProperties,
    /** 프로젝트가 있으면 활성 프로젝트마다 - 비어 있으면 공통 값 하나를 본다 */
    private val projects: List<ProjectView> = emptyList(),
    private val transport: () -> Any?,
) : DeployGuard {
    /** 프로젝트가 직접 정한 링크 주소 (공통 값으로 내려가지 않는다 - 다른 프로젝트의 도메인일 수 있다) */
    class ProjectView(val id: String, val ownLinkBaseUrl: String)

    override val name: String = "workspace"

    override fun problems(context: DeployContext): List<String> = buildList {
        if (!context.protectedEnv) return@buildList
        if (transport() is LogOnlyWorkspaceMailTransport) add("workspace invitations have no mail transport, so invitation links cannot be delivered: add modules:notification-mail (skeleton.notification-mail.enabled, from, spring.mail.host) or define a WorkspaceMailTransport bean")
        if (props.mail.logLinks == WorkspaceProperties.Mail.LogLinks.ON) add("skeleton.workspace.mail.log-links=ON writes one-time invitation tokens to the log: remove it outside local")
        if (projects.isEmpty()) {
            if (props.mail.linkBaseUrl.isBlank()) add("skeleton.workspace.mail.link-base-url is empty: set the frontend origin the invitation links should open")
        } else {
            projects.filter { it.ownLinkBaseUrl.isBlank() }.forEach {
                add("project '${it.id}': skeleton.projects.${it.id}.config.workspace.mail.link-base-url is empty: every project sets its own frontend origin (the common value is not used, it could be another project's domain)")
            }
        }
    }
}
