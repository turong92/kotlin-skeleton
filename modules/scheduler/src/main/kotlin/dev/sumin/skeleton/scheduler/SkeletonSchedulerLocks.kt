package dev.sumin.skeleton.scheduler

import java.lang.reflect.Method

data class SkeletonScheduledLockRequest(
    val taskId: String,
    val lockKey: String,
    val beanName: String,
    val method: Method,
)

fun interface SkeletonScheduledLockManager {
    fun execute(
        request: SkeletonScheduledLockRequest,
        action: () -> Unit,
    ): Boolean
}

object NoopSkeletonScheduledLockManager : SkeletonScheduledLockManager {
    override fun execute(
        request: SkeletonScheduledLockRequest,
        action: () -> Unit,
    ): Boolean {
        action()
        return true
    }
}
