package dev.sumin.skeleton.scheduler

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ScheduledFuture
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.CronTrigger
import org.springframework.scheduling.support.PeriodicTrigger
import org.springframework.util.ReflectionUtils

class SkeletonScheduledTaskRegistrar(
    private val applicationContext: ConfigurableApplicationContext,
    private val taskScheduler: TaskScheduler,
    private val scheduleResolver: SkeletonScheduleResolver,
    private val executionGuard: SkeletonSchedulerExecutionGuard,
    private val lockManager: SkeletonScheduledLockManager,
    private val failureHandlers: List<SkeletonScheduledFailureHandler>,
    private val properties: SkeletonSchedulerProperties,
) : ApplicationListener<ApplicationReadyEvent>,
    DisposableBean {
    private val registeredTasks = mutableListOf<SkeletonRegisteredTask>()
    private var registered = false

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        registerTasks()
    }

    @Synchronized
    fun registerTasks(): List<SkeletonRegisteredTask> {
        if (registered) return registeredTasks.toList()
        registered = true

        applicationContext.beanDefinitionNames
            .flatMap(::discoverTasks)
            .forEach { candidate ->
                if (executionGuard.canExecute(candidate.annotation)) {
                    registeredTasks += schedule(candidate)
                }
            }

        return registeredTasks.toList()
    }

    override fun destroy() {
        registeredTasks.forEach { task -> task.future.cancel(false) }
    }

    private fun discoverTasks(beanName: String): List<SkeletonScheduledTaskCandidate> {
        val bean = runCatching { applicationContext.getBean(beanName) }.getOrNull() ?: return emptyList()
        val targetClass = AopUtils.getTargetClass(bean)
        val candidates = mutableListOf<SkeletonScheduledTaskCandidate>()

        ReflectionUtils.doWithMethods(
            targetClass,
            { method ->
                val annotation = AnnotatedElementUtils.findMergedAnnotation(method, SkeletonScheduled::class.java)
                    ?: return@doWithMethods
                require(method.parameterCount == 0) {
                    "@SkeletonScheduled method must have no parameters: ${targetClass.name}.${method.name}"
                }
                val invocableMethod = AopUtils.selectInvocableMethod(method, bean.javaClass)
                candidates += SkeletonScheduledTaskCandidate(
                    taskId = annotation.id.ifBlank { "${targetClass.name}.${method.name}" },
                    beanName = beanName,
                    bean = bean,
                    method = invocableMethod,
                    annotation = annotation,
                    schedule = scheduleResolver.resolve(annotation),
                )
            },
            ReflectionUtils.USER_DECLARED_METHODS,
        )

        return candidates
    }

    private fun schedule(candidate: SkeletonScheduledTaskCandidate): SkeletonRegisteredTask {
        val runnable = Runnable { runTask(candidate) }
        val trigger = when (val schedule = candidate.schedule) {
            is SkeletonSchedule.FixedDelay -> PeriodicTrigger(schedule.interval).apply {
                setInitialDelay(schedule.initialDelay)
                isFixedRate = false
            }
            is SkeletonSchedule.FixedRate -> PeriodicTrigger(schedule.interval).apply {
                setInitialDelay(schedule.initialDelay)
                isFixedRate = true
            }
            is SkeletonSchedule.Cron -> CronTrigger(schedule.expression, schedule.zoneId)
            is SkeletonSchedule.FixedLocalTime -> DailyLocalTimeTrigger(
                localTime = schedule.localTime,
                zoneId = schedule.zoneId,
                clock = taskScheduler.clock,
            )
        }
        val future = requireNotNull(taskScheduler.schedule(runnable, trigger)) {
            "TaskScheduler did not schedule taskId=${candidate.taskId}"
        }

        return SkeletonRegisteredTask(
            taskId = candidate.taskId,
            beanName = candidate.beanName,
            method = candidate.method,
            schedule = candidate.schedule,
            future = future,
        )
    }

    private fun runTask(candidate: SkeletonScheduledTaskCandidate) {
        if (!executionGuard.canExecute(candidate.annotation)) return

        runCatching {
            val action = { invoke(candidate) }
            if (candidate.annotation.lockKey.isNotBlank() && properties.locking.enabled) {
                lockManager.execute(
                    request = SkeletonScheduledLockRequest(
                        taskId = candidate.taskId,
                        lockKey = candidate.annotation.lockKey,
                        beanName = candidate.beanName,
                        method = candidate.method,
                    ),
                    action = action,
                )
            } else {
                action()
            }
        }.onFailure { throwable ->
            val failure = SkeletonScheduledTaskFailure(
                taskId = candidate.taskId,
                beanName = candidate.beanName,
                method = candidate.method,
                throwable = unwrapInvocationTarget(throwable),
            )
            failureHandlers.forEach { handler ->
                runCatching { handler.handle(failure) }
            }
        }
    }

    private fun invoke(candidate: SkeletonScheduledTaskCandidate) {
        ReflectionUtils.makeAccessible(candidate.method)
        candidate.method.invoke(candidate.bean)
    }

    private fun unwrapInvocationTarget(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException) {
            throwable.targetException ?: throwable
        } else {
            throwable
        }
}

data class SkeletonRegisteredTask(
    val taskId: String,
    val beanName: String,
    val method: Method,
    val schedule: SkeletonSchedule,
    val future: ScheduledFuture<*>,
)

private data class SkeletonScheduledTaskCandidate(
    val taskId: String,
    val beanName: String,
    val bean: Any,
    val method: Method,
    val annotation: SkeletonScheduled,
    val schedule: SkeletonSchedule,
)
