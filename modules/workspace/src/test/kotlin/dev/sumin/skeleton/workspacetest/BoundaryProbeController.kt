package dev.sumin.skeleton.workspacetest

import dev.sumin.skeleton.workspace.CurrentWorkspace
import dev.sumin.skeleton.workspace.WorkspaceRole
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 앱 컨트롤러가 경계를 거는 모양 그대로 - 한 줄 */
@RestController
@RequestMapping("/probe")
class BoundaryProbeController(private val current: CurrentWorkspace) {
    @GetMapping("/optional")
    fun optional(): String = current.get()?.let { "${it.workspaceId}:${it.role}" } ?: "none"

    @GetMapping("/member")
    fun member(): String = current.require().let { "${it.workspaceId}:${it.role}" }

    @GetMapping("/admin")
    fun admin(): String = current.require(WorkspaceRole.ADMIN).let { "${it.workspaceId}:${it.role}" }
}
