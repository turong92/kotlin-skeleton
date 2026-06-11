package dev.sumin.skeleton.notification.slack

import java.lang.reflect.Method

data class SlackExceptionAlertContext(
    val throwable: Throwable,
    val method: Method?,
    val arguments: List<Any?>,
    val trace: SlackTraceContext,
)

fun interface SlackAlertContextContributor {
    fun contribute(context: SlackExceptionAlertContext): Map<String, String?>
}
