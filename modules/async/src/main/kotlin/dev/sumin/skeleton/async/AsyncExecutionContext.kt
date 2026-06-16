package dev.sumin.skeleton.async

import org.slf4j.MDC
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder

data class AsyncExecutionContext(
    val mdc: Map<String, String> = emptyMap(),
    val authentication: Authentication? = null,
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

                runnable.run()
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
            )
    }
}
