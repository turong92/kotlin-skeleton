package dev.sumin.skeleton.scheduler

import java.lang.reflect.Method
import org.slf4j.LoggerFactory

data class SkeletonScheduledTaskFailure(
    val taskId: String,
    val beanName: String,
    val method: Method,
    val throwable: Throwable,
)

fun interface SkeletonScheduledFailureHandler {
    fun handle(failure: SkeletonScheduledTaskFailure)
}

class LoggingSkeletonScheduledFailureHandler : SkeletonScheduledFailureHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(failure: SkeletonScheduledTaskFailure) {
        log.error(
            "Scheduled task failed taskId={} bean={} method={}: {}",
            failure.taskId,
            failure.beanName,
            failure.method.name,
            failure.throwable.message,
            failure.throwable,
        )
    }
}
