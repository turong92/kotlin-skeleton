package dev.sumin.skeleton.notification.slack

fun interface SlackAlertSender {
    fun send(alert: SlackAlert)
}
