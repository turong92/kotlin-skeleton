package dev.sumin.skeleton.async

import org.springframework.core.task.TaskDecorator

class AsyncContextTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable =
        AsyncExecutionContext.capture().wrap(runnable)
}
