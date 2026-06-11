package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [OutboundHttpAutoConfiguration::class, NotificationAutoConfiguration::class])
@EnableConfigurationProperties(SlackNotificationProperties::class)
class NotificationSlackAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun slackAlertMessageFactory(properties: SlackNotificationProperties): SlackAlertMessageFactory =
        SlackAlertMessageFactory(properties)

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
    ): SlackNotificationForwarder =
        SlackNotificationForwarder(sender, properties, subscriptionRegistry.ifAvailable)

    @Bean
    @ConditionalOnMissingBean
    fun slackExceptionAspect(
        sender: SlackAlertSender,
        contributors: List<SlackAlertContextContributor>,
        properties: SlackNotificationProperties,
    ): SlackExceptionAspect =
        SlackExceptionAspect(sender, contributors, properties)
}
