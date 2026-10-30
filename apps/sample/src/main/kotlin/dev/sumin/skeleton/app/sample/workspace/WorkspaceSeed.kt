package dev.sumin.skeleton.app.sample.workspace

import dev.sumin.skeleton.workspace.WorkspaceService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** 로컬 프로필의 씨앗 - 시드 계정 `acc_user`(user@example.com)가 소유자인 워크스페이스 「Example Team」(slug `example-team`). 이미 있으면 아무것도 하지 않는다 */
@Component
@Profile("local")
class WorkspaceSeedRunner(private val workspaces: WorkspaceService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = WorkspaceSeed(workspaces).run()
}

class WorkspaceSeed(private val workspaces: WorkspaceService) {
    fun run() {
        if (workspaces.list(OWNER).none { it.workspace.slug == SLUG }) workspaces.create(OWNER, NAME, SLUG)
    }

    companion object {
        const val OWNER = "acc_user"
        const val NAME = "Example Team"
        const val SLUG = "example-team"
    }
}
