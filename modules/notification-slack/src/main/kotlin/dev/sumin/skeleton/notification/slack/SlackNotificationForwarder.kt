package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.observability.ObservabilityContext
import dev.sumin.skeleton.common.observability.ObservabilityLinkResolver
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.slf4j.LoggerFactory

class SlackNotificationForwarder(
    private val sender: SlackAlertSender,
    private val properties: SlackNotificationProperties,
    subscriptionRegistry: NotificationSubscriptionRegistry?,
    private val linkResolver: ObservabilityLinkResolver = ObservabilityLinkResolver { emptyList() },
    /** 프로젝트 모드(`skeleton.projects`)인가 — 켜져 있고 알림 전달도 켜져 있으면 모든 프로젝트의 알림이 한 채널로 모인다는 경고를 남긴다 */
    projectMode: Boolean = false,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (projectMode && properties.notificationEvents) {
            log.warn(
                "skeleton.notification.slack.notification-events is on while skeleton.projects has projects: notifications of EVERY project " +
                    "(recipient ids, payload fields, message) go to the one Slack channel, tagged with the project id. Turn it off if the channel is not for all of them.",
            )
        }
    }

    private val subscription: NotificationSubscription? =
        if (properties.notificationEvents) {
            subscriptionRegistry?.subscribe(subscriber = NotificationSubscriber { event -> forward(event) })
        } else {
            null
        }

    private fun forward(event: NotificationEvent) {
        // 프로젝트 모드: 브로커가 발행한 스레드에서 부르므로 문맥에 발행 프로젝트가 있다 — 어느 서비스 알림인지 제목과 필드에 싣는다
        val project = dev.sumin.skeleton.common.project.ProjectContext.currentOrNull()?.id
        val fields = event.fields() + listOfNotNull(project?.let { "project" to it })
        val linkContext = ObservabilityContext.fromMdc(
            fields + mapOf(
                "topic" to event.topic,
                "type" to event.type,
                "route" to event.topic,
            ),
        )
        val links = runCatching { linkResolver.resolve(linkContext) }
            .onFailure { error -> log.warn("Slack observability link resolution failed: {}", error.message) }
            .getOrElse { emptyList() }

        sender.send(
            SlackAlert(
                title = (event.title ?: event.type).let { if (project == null) it else "[$project] $it" },
                message = event.message ?: event.type,
                severity = event.severity.toSlackSeverity(),
                topic = event.topic,
                route = event.topic,
                fields = fields,
                trace = SlackTraceContexts.current(),
                links = links,
                occurredAt = event.createdAt,
            ),
        )
    }

    override fun close() {
        subscription?.close()
    }

    private fun NotificationSeverity.toSlackSeverity(): SlackAlertSeverity =
        when (this) {
            NotificationSeverity.INFO -> SlackAlertSeverity.INFO
            NotificationSeverity.SUCCESS -> SlackAlertSeverity.SUCCESS
            NotificationSeverity.WARNING -> SlackAlertSeverity.WARNING
            NotificationSeverity.ERROR -> SlackAlertSeverity.ERROR
        }

    private fun NotificationEvent.fields(): Map<String, String?> =
        buildMap {
            put("id", id)
            put("type", type)
            payload.forEach { (name, value) ->
                put(name, value?.toString())
            }
        }
}
