package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.authz.NoOpOperatorAuditLog
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.authz.PermissionDeclaration
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationChannelDeclaration
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/** 푸시가 푸시로 보낼 종류를 정하는 설정 키 - 공지 화면이 「이 종류는 푸시로 갈 수 있나」를 잠그려고 읽기만 한다 (푸시 모듈을 모른 채) */
private const val PUSH_TYPES_KEY = "skeleton.notification.push.types"

/**
 * 공지 발송. **모듈 기본은 꺼짐** - `skeleton.notification.broadcast.enabled=true` 일 때만 선다. 저장소 · 작업 포트(`BroadcastStore` · `BroadcastTasks`)는 `notification-broadcast-jdbc` 가 낸다.
 * 켰는데 그 포트 · 공지 종류 · 대상 빈이 없거나 `kind` 가 겹치거나 `batch-size` 가 범위 밖이면 **시작을 멈추고** 무엇을 하면 되는지 말한다.
 */
@AutoConfiguration(
    after = [NotificationAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.broadcast.jdbc.BroadcastJdbcAutoConfiguration"],
)
@EnableConfigurationProperties(BroadcastProperties::class)
@ConditionalOnProperty(prefix = "skeleton.notification.broadcast", name = ["enabled"], havingValue = "true")
class BroadcastAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["broadcastPermissionDeclaration"])
    fun broadcastPermissionDeclaration(): PermissionDeclaration = BroadcastPermissions.declaration()

    @Bean
    fun broadcastSetupCheck(
        properties: BroadcastProperties,
        store: ObjectProvider<BroadcastStore>,
        tasks: ObjectProvider<BroadcastTasks>,
        sources: ObjectProvider<AudienceSource>,
    ): BroadcastSetupCheck {
        val missing = listOfNotNull("BroadcastStore".takeIf { store.getIfAvailable() == null }, "BroadcastTasks".takeIf { tasks.getIfAvailable() == null })
        check(missing.isEmpty()) {
            "skeleton.notification.broadcast.enabled=true needs ${missing.joinToString()} - add the notification-broadcast-jdbc module " +
                "(implementation(project(\":modules:notification-broadcast-jdbc\"))) and its migrations, with job-queue-jdbc enabled"
        }
        check(properties.typeList.isNotEmpty()) { "skeleton.notification.broadcast.types must list the notification types operators may broadcast (e.g. notice.service)" }
        val kinds = sources.orderedStream().toList().map { it.kind }
        check(kinds.isNotEmpty()) { "skeleton.notification.broadcast.enabled=true needs at least one AudienceSource bean - add the account module (the all and role audiences) or define your own" }
        val clash = kinds.groupBy { it }.filterValues { it.size > 1 }.keys
        check(clash.isEmpty()) { "AudienceSource kinds must be unique, found more than one for: ${clash.joinToString()}" }
        check(properties.batchSize in 1..MAX_BATCH_SIZE) { "skeleton.notification.broadcast.batch-size must be between 1 and $MAX_BATCH_SIZE" }
        check(properties.recipientsPerMinute >= 1) { "skeleton.notification.broadcast.recipients-per-minute must be at least 1" }
        check(properties.stepMaxAttempts >= 1) { "skeleton.notification.broadcast.step-max-attempts must be at least 1" }
        return BroadcastSetupCheck
    }

    @Bean
    @ConditionalOnMissingBean
    fun broadcasts(
        @Suppress("UNUSED_PARAMETER") setup: BroadcastSetupCheck,
        store: BroadcastStore,
        tasks: BroadcastTasks,
        publisher: NotificationPublisher,
        inbox: NotificationInboxRepository,
        sources: ObjectProvider<AudienceSource>,
        channels: ObjectProvider<NotificationChannelDeclaration>,
        gate: AdvertisingGate,
        properties: BroadcastProperties,
        audit: ObjectProvider<OperatorAuditLog>,
        time: ObjectProvider<TimeProvider>,
        environment: Environment,
    ): Broadcasts {
        val pushTypes = (Binder.get(environment).bind(PUSH_TYPES_KEY, Bindable.listOf(String::class.java)).orElse(null) ?: emptyList()).map { it.trim() }.toSet()
        return Broadcasts(
            store, tasks, publisher, inbox,
            { sources.orderedStream().toList() },
            { channels.orderedStream().toList().map { it.key }.toSet() },
            gate, pushTypes, properties, audit.getIfAvailable { NoOpOperatorAuditLog() },
            time.getIfAvailable { TimeProvider.systemUtc() }, BroadcastEvents(gate, properties.topic),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun broadcastStepRunner(
        @Suppress("UNUSED_PARAMETER") setup: BroadcastSetupCheck,
        store: BroadcastStore,
        tasks: BroadcastTasks,
        publisher: NotificationPublisher,
        sources: ObjectProvider<AudienceSource>,
        gate: AdvertisingGate,
        properties: BroadcastProperties,
        time: ObjectProvider<TimeProvider>,
    ): BroadcastStepRunner =
        BroadcastStepRunner(
            store, tasks, publisher, { sources.orderedStream().toList() }, gate, properties,
            time.getIfAvailable { TimeProvider.systemUtc() }, BroadcastEvents(gate, properties.topic),
        )

    /** 채널이 알리는 전달 과정(넣음 · 결과 · 클릭)을 공지 통계로 세고, 취소된 공지의 큐 속 푸시를 멈춘다 */
    @Bean
    @ConditionalOnMissingBean
    fun broadcastDeliveryHook(@Suppress("UNUSED_PARAMETER") setup: BroadcastSetupCheck, store: BroadcastStore, time: ObjectProvider<TimeProvider>): BroadcastDeliveryHook =
        BroadcastDeliveryHook(store, time.getIfAvailable { TimeProvider.systemUtc() })

    private companion object {
        const val MAX_BATCH_SIZE = 1000
    }
}

/** 공지 설정 검사가 끝났다는 표지 - 이 빈을 받는 빈은 검사를 먼저 거친다 */
object BroadcastSetupCheck
