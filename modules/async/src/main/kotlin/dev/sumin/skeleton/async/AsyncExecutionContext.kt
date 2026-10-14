package dev.sumin.skeleton.async

import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import org.slf4j.MDC
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

data class AsyncExecutionContext(
    val mdc: Map<String, String> = emptyMap(),
    val authentication: Authentication? = null,
    /** 제출한 쪽의 프로젝트(`skeleton.projects`) — 풀 스레드는 프로젝트를 물려받지 않으므로 일과 함께 들려 보낸다. 프로젝트 밖이면 null */
    val project: Project? = null,
) {
    fun wrap(runnable: Runnable): Runnable =
        Runnable {
            val previousMdc = MDC.getCopyOfContextMap()
            val previousSecurityContext = SecurityContextHolder.getContext()

            try {
                if (mdc.isEmpty()) {
                    MDC.clear()
                } else {
                    MDC.setContextMap(mdc)
                }

                val nextSecurityContext = SecurityContextHolder.createEmptyContext()
                nextSecurityContext.authentication = authentication
                SecurityContextHolder.setContext(nextSecurityContext)

                if (project == null) runnable.run() else ProjectContext.runIn(project) { runnable.run() }
            } finally {
                if (previousMdc == null) {
                    MDC.clear()
                } else {
                    MDC.setContextMap(previousMdc)
                }
                SecurityContextHolder.setContext(previousSecurityContext)
            }
        }

    companion object {
        fun capture(): AsyncExecutionContext =
            AsyncExecutionContext(
                mdc = MDC.getCopyOfContextMap()?.toMap().orEmpty(),
                authentication = SecurityContextHolder.getContext().authentication,
                project = ProjectContext.currentOrNull(),
            )
    }
}
