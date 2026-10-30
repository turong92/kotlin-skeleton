package dev.sumin.skeleton.workspace.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.workspace.CurrentWorkspace
import dev.sumin.skeleton.workspace.WorkspaceErrorCode
import dev.sumin.skeleton.workspace.WorkspaceException
import dev.sumin.skeleton.workspace.WorkspaceMember
import dev.sumin.skeleton.workspace.WorkspaceRole
import dev.sumin.skeleton.workspace.Workspaces
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 호출자는 요청의 주체(`Authentication.name`, auth 모듈에서는 계정 id)다. 멤버십은 요청마다 DB 에서 한 번 읽는다 */
class RequestCurrentWorkspace(private val workspaces: Workspaces) : CurrentWorkspace {
    override fun get(): WorkspaceMember? {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return null
        val raw = request.getHeader(CurrentWorkspace.HEADER)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        (request.getAttribute(ATTRIBUTE) as? WorkspaceMember)?.takeIf { it.workspaceId == raw }?.let { return it }
        val membership = raw.takeIf { it.length <= MAX_ID }?.let { workspaces.membershipOf(callerId(request), it) }
            ?: run { callerId(request); throw WorkspaceException(WorkspaceErrorCode.NOT_FOUND) }
        request.setAttribute(ATTRIBUTE, membership)
        return membership
    }

    override fun require(min: WorkspaceRole): WorkspaceMember {
        val membership = get() ?: throw WorkspaceException(WorkspaceErrorCode.REQUIRED)
        if (!membership.role.atLeast(min)) throw WorkspaceException(WorkspaceErrorCode.FORBIDDEN)
        return membership
    }

    private fun callerId(request: HttpServletRequest): String =
        request.userPrincipal?.takeIf { it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)

    private companion object {
        const val ATTRIBUTE = "dev.sumin.skeleton.workspace.membership"
        const val MAX_ID = 64
    }
}
