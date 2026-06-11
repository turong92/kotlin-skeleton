package dev.sumin.skeleton.notification.slack

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SlackExceptionNotify(
    val route: String = "",
    val title: String = "",
    val severity: SlackAlertSeverity = SlackAlertSeverity.ERROR,
    val include: Array<KClass<out Throwable>> = [],
    val exclude: Array<KClass<out Throwable>> = [],
)
