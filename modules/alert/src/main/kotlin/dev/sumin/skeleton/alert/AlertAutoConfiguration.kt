package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.notification.mail.MailSender
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.env.Environment

private const val WEBHOOK_SET = "'\${skeleton.alert.webhook-url:}'.trim().length() > 0"

/**
 * `skeleton.alert.enabled=false` 로 끈다. 웹훅 주소(`skeleton.alert.webhook-url`)가 없으면 [OwnerAlerts] 는 로그만 남기고
 * 아래 트리거(5xx 몰림 필터)와 채널은 등록되지 않는다.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "skeleton.alert", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AlertProperties::class)
class AlertAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun alertStore(): AlertStore = InMemoryAlertStore()

    @Bean
    @ConditionalOnMissingBean(name = ["webhookAlertChannel"])
    @ConditionalOnExpression(WEBHOOK_SET)
    fun webhookAlertChannel(properties: AlertProperties): AlertChannel =
        WebhookAlertChannel(
            requireNotNull(properties.webhookUri) { "skeleton.alert.webhook-url must be an http(s) address with a host and no user info" },
            properties.webhookTimeout,
        )

    @Bean
    @ConditionalOnMissingBean
    fun ownerAlerts(
        store: AlertStore,
        channels: ObjectProvider<AlertChannel>,
        properties: AlertProperties,
        timeProvider: ObjectProvider<TimeProvider>,
        environment: Environment,
    ): OwnerAlerts = DefaultOwnerAlerts(
        store,
        channels.orderedStream().toList(),
        properties.copy(environment = properties.environment.ifBlank { environment.activeProfiles.firstOrNull() ?: "default" }),
        timeProvider.getIfAvailable { TimeProvider.systemUtc() },
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(WEBHOOK_SET)
    fun serverErrorSurge(alerts: OwnerAlerts, properties: AlertProperties, timeProvider: ObjectProvider<TimeProvider>) =
        ServerErrorSurge(alerts, properties.surge, timeProvider.getIfAvailable { TimeProvider.systemUtc() })

    /** 가장 바깥쪽에 가깝게 — 안쪽 필터 · 핸들러가 만든 5xx 를 모두 본다 */
    @Bean
    @ConditionalOnMissingBean(name = ["serverErrorSurgeFilterRegistration"])
    @ConditionalOnExpression(WEBHOOK_SET)
    fun serverErrorSurgeFilterRegistration(surge: ServerErrorSurge): FilterRegistrationBean<ServerErrorSurgeFilter> =
        FilterRegistrationBean(ServerErrorSurgeFilter(surge)).apply { order = Ordered.HIGHEST_PRECEDENCE + 5 }
}

/** `notification-mail` 이 클래스패스에 있고 `MailSender` 빈과 받는 주소가 있을 때 두 번째 채널 */
@AutoConfiguration(after = [AlertAutoConfiguration::class], afterName = ["dev.sumin.skeleton.notification.mail.NotificationMailAutoConfiguration"])
// 클래스 리터럴이 아니라 이름 문자열: 모듈이 없을 때 어노테이션 값을 읽다가 클래스를 로드하지 않게 한다
@ConditionalOnClass(name = ["dev.sumin.skeleton.notification.mail.MailSender"])
@ConditionalOnProperty(prefix = "skeleton.alert", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AlertProperties::class)
class AlertMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["mailAlertChannel"])
    @ConditionalOnBean(MailSender::class)
    @ConditionalOnExpression(WEBHOOK_SET)
    fun mailAlertChannel(sender: MailSender, properties: AlertProperties): AlertChannel? =
        properties.mailRecipients.takeIf { it.isNotEmpty() }?.let { MailAlertChannel(sender, it) }
}

/** `job-queue-jdbc` 가 클래스패스에 있을 때 죽은 작업(DEAD)을 경보로 */
@AutoConfiguration(after = [AlertAutoConfiguration::class])
@ConditionalOnClass(name = ["dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener"])
@ConditionalOnProperty(prefix = "skeleton.alert", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AlertJobQueueAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["alertDeadJobListener"])
    @ConditionalOnExpression(WEBHOOK_SET)
    fun alertDeadJobListener(alerts: OwnerAlerts): JobDeadListener = AlertDeadJobListener(alerts)
}
