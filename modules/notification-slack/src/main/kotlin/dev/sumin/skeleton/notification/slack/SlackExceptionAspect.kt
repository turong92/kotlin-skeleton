package dev.sumin.skeleton.notification.slack

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.AfterThrowing
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory

@Aspect
class SlackExceptionAspect(
    private val sender: SlackAlertSender,
    private val contributors: List<SlackAlertContextContributor>,
    private val properties: SlackNotificationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @AfterThrowing(
        pointcut = "@annotation(slackExceptionNotify)",
        throwing = "throwable",
    )
    fun notifyAfterThrowing(
        joinPoint: JoinPoint,
        slackExceptionNotify: SlackExceptionNotify,
        throwable: Throwable,
    ) {
        if (!properties.exceptionAlerts || !shouldNotify(slackExceptionNotify, throwable)) return

        val method = (joinPoint.signature as? MethodSignature)?.method
        val trace = SlackTraceContexts.current()
        val context = SlackExceptionAlertContext(
            throwable = throwable,
            method = method,
            arguments = joinPoint.args?.toList().orEmpty(),
            trace = trace,
        )
        val route = slackExceptionNotify.route.ifBlank { properties.defaultTopic }
        val fields = linkedMapOf<String, String?>(
            "exception" to throwable.javaClass.simpleName,
            "exceptionClass" to throwable.javaClass.name,
            "method" to method?.let { "${it.declaringClass.simpleName}.${it.name}" },
        )
        contributors.forEach { contributor ->
            runCatching { fields.putAll(contributor.contribute(context)) }
                .onFailure { error -> log.warn("Slack alert contributor failed: {}", error.message) }
        }

        runCatching {
            sender.send(
                SlackAlert(
                    title = slackExceptionNotify.title.ifBlank { throwable.javaClass.simpleName },
                    message = throwable.message ?: throwable.javaClass.name,
                    severity = slackExceptionNotify.severity,
                    topic = route,
                    route = route,
                    fields = fields,
                    trace = trace,
                ),
            )
        }.onFailure { error ->
            log.warn("Slack exception alert failed: {}", error.message)
        }
    }

    private fun shouldNotify(
        annotation: SlackExceptionNotify,
        throwable: Throwable,
    ): Boolean {
        if (annotation.exclude.any { it.java.isAssignableFrom(throwable.javaClass) }) return false
        if (annotation.include.isEmpty()) return true
        return annotation.include.any { it.java.isAssignableFrom(throwable.javaClass) }
    }
}
