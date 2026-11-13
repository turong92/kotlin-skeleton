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
    private val externalTasks = mutableMapOf<String, SkeletonScheduledTaskCandidate>()
    private var registered = false

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        registerTasks()
    }

    /** LOCAL 이면 타이머에 건다. EXTERNAL 이면 타이머 없이 찾아 두기만 하고 [runExternally] 가 부를 때 돈다(같은 프로세스에서 둘이 같이 돌지 않는다) */
    @Synchronized
    fun registerTasks(): List<SkeletonRegisteredTask> {
        if (registered) return registeredTasks.toList()

        val external = properties.mode == SkeletonSchedulerProperties.Mode.EXTERNAL
        try {
            applicationContext.beanDefinitionNames
                .flatMap(::discoverTasks)
                .forEach { candidate ->
                    if (executionGuard.canExecute(candidate.annotation)) {
                        if (external) {
                            require('/' !in candidate.taskId) { "@SkeletonScheduled taskId with '/' cannot be called in external mode: ${candidate.taskId}" }
                            require(externalTasks.putIfAbsent(candidate.taskId, candidate) == null) {
                                "Duplicate @SkeletonScheduled taskId in external mode: ${candidate.taskId}"
                            }
                        } else {
                            registeredTasks += schedule(candidate)
                        }
                    }
                }
        } catch (e: RuntimeException) {
            // 반쯤 등록된 채 남지 않게 되돌린다 — 밖의 호출이 기동 완료보다 먼저 와서 여기서 실패해도, 기동 완료 때 다시 시도해 기동 실패로 드러난다
            registeredTasks.forEach { it.future.cancel(false) }
            registeredTasks.clear()
            externalTasks.clear()
            throw e
        }
        registered = true

        return registeredTasks.toList()
    }

    /** 이 앱의 `@SkeletonScheduled` 작업 전부(환경 가드와 상관없이) — 밖의 스케줄러 목록을 만들 때 쓴다 ([SkeletonExternalSchedules]) */
    fun discoveredTasks(): List<SkeletonDiscoveredTask> =
        applicationContext.beanDefinitionNames
            .flatMap(::discoverTasks)
            .map { SkeletonDiscoveredTask(it.taskId, it.schedule, it.annotation) }

    /**
     * 밖의 호출로 작업 하나를 **이 요청 안에서 끝까지** 돌린다(mode=EXTERNAL). 락 · 실패 핸들러는 타이머와 같은 길을 거친다.
     * 실패는 실패 핸들러에 알린 뒤 다시 던진다 — 호출자가 5xx 를 받고 재시도하게.
     */
    fun runExternally(taskId: String): SkeletonExternalRunResult {
        registerTasks()
        val candidate = synchronized(this) { externalTasks[taskId] } ?: return SkeletonExternalRunResult.NOT_FOUND
        if (!executionGuard.canExecute(candidate.annotation)) return SkeletonExternalRunResult.NOT_FOUND
        val ran = try {
            execute(candidate)
        } catch (throwable: Throwable) {
            val cause = unwrapInvocationTarget(throwable)
            reportFailure(candidate, cause)
            throw cause
        }
        return if (ran) SkeletonExternalRunResult.RAN else SkeletonExternalRunResult.SKIPPED_LOCKED
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

        runCatching { execute(candidate) }
            .onFailure { throwable -> reportFailure(candidate, unwrapInvocationTarget(throwable)) }
    }

    /** 락이 있으면 락을 거쳐 돌린다. 락을 못 잡아 건너뛰면 false */
    private fun execute(candidate: SkeletonScheduledTaskCandidate): Boolean {
        val action = { invoke(candidate) }
        return if (candidate.annotation.lockKey.isNotBlank() && properties.locking.enabled) {
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
            true
        }
    }

    private fun reportFailure(candidate: SkeletonScheduledTaskCandidate, throwable: Throwable) {
        val failure = SkeletonScheduledTaskFailure(
            taskId = candidate.taskId,
            beanName = candidate.beanName,
            method = candidate.method,
            throwable = throwable,
        )
        failureHandlers.forEach { handler ->
            runCatching { handler.handle(failure) }
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

data class SkeletonDiscoveredTask(
    val taskId: String,
    val schedule: SkeletonSchedule,
    val annotation: SkeletonScheduled,
)

enum class SkeletonExternalRunResult { RAN, SKIPPED_LOCKED, NOT_FOUND }

private data class SkeletonScheduledTaskCandidate(
    val taskId: String,
    val beanName: String,
    val bean: Any,
    val method: Method,
    val annotation: SkeletonScheduled,
    val schedule: SkeletonSchedule,
)
