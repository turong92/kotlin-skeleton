package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.common.logging.RedactionAutoConfiguration
import dev.sumin.skeleton.common.logging.SensitiveValueRedactor
import dev.sumin.skeleton.common.observability.ObservabilityLinkAutoConfiguration
import dev.sumin.skeleton.common.observability.ObservabilityLinkResolver
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(
    after = [
        RedactionAutoConfiguration::class,
        OutboundHttpAutoConfiguration::class,
        NotificationAutoConfiguration::class,
        ObservabilityLinkAutoConfiguration::class,
    ],
)
@EnableConfigurationProperties(SlackNotificationProperties::class)
class NotificationSlackAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun slackAlertMessageFactory(
        properties: SlackNotificationProperties,
        redactor: ObjectProvider<SensitiveValueRedactor>,
    ): SlackAlertMessageFactory =
        SlackAlertMessageFactory(
            properties = properties,
            redactor = redactor.getIfAvailable { SensitiveValueRedactor() },
        )

    @Bean
    @ConditionalOnMissingBean(SlackAlertSender::class)
    fun slackAlertSender(
        httpClient: ExternalHttpClient,
        properties: SlackNotificationProperties,
        messageFactory: SlackAlertMessageFactory,
    ): SlackWebhookAlertSender =
        SlackWebhookAlertSender(httpClient, properties, messageFactory)

    @Bean
    @ConditionalOnMissingBean
    fun slackNotificationForwarder(
        sender: SlackAlertSender,
        properties: SlackNotificationProperties,
        subscriptionRegistry: ObjectProvider<NotificationSubscriptionRegistry>,
        linkResolver: ObjectProvider<ObservabilityLinkResolver>,
    ): SlackNotificationForwarder =
        SlackNotificationForwarder(
            sender = sender,
            properties = properties,
            subscriptionRegistry = subscriptionRegistry.ifAvailable,
            linkResolver = linkResolver.getIfAvailable { ObservabilityLinkResolver { emptyList() } },
        )

    @Bean
    @ConditionalOnMissingBean
    fun slackExceptionAspect(
        sender: SlackAlertSender,
        contributors: List<SlackAlertContextContributor>,
        properties: SlackNotificationProperties,
        linkResolver: ObjectProvider<ObservabilityLinkResolver>,
    ): SlackExceptionAspect =
        SlackExceptionAspect(
            sender = sender,
            contributors = contributors,
            properties = properties,
            linkResolver = linkResolver.getIfAvailable { ObservabilityLinkResolver { emptyList() } },
        )
}
