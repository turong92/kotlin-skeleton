package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationChannelDeclaration
import dev.sumin.skeleton.notification.NotificationDeliveryHooks
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.TaskScheduler

/** 로그인 세션 연동(`auth`)이 먼저 자기 [PushSessionIdResolver] 를 올린다 — 없으면 이 클래스의 「항상 null」이 선다 */
@AutoConfiguration(
    after = [NotificationAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.push.jdbc.PushJdbcAutoConfiguration"],
)
@EnableConfigurationProperties(PushProperties::class)
@ConditionalOnProperty(prefix = "skeleton.notification.push", name = ["enabled"], havingValue = "true")
class PushAutoConfiguration {
    /** 기기 저장소 · 끄기 저장소 · 작업 포트는 `notification-push-jdbc` 가 내놓는다 — 인메모리 대용은 없다(재시작하면 구독이 사라져 쓸모없다). 없으면 무엇을 얹을지 말하고 시작을 멈춘다 */
    @Bean
    fun pushPortsCheck(
        devices: ObjectProvider<PushDeviceStore>,
        mutes: ObjectProvider<PushTypeMuteStore>,
        tasks: ObjectProvider<PushTasks>,
    ): PushPortsCheck {
        val missing = listOfNotNull(
            "PushDeviceStore".takeIf { devices.getIfAvailable() == null },
            "PushTypeMuteStore".takeIf { mutes.getIfAvailable() == null },
            "PushTasks".takeIf { tasks.getIfAvailable() == null },
        )
        check(missing.isEmpty()) {
            "skeleton.notification.push.enabled=true needs ${missing.joinToString()} - add the notification-push-jdbc module " +
                "(implementation(project(\":modules:notification-push-jdbc\"))) and its migrations, with job-queue-jdbc enabled"
        }
        return PushPortsCheck
    }

    @Bean
    @ConditionalOnMissingBean(PushSessionIdResolver::class)
    fun pushSessionIdResolver(): PushSessionIdResolver = PushSessionIdResolver { null }

    @Bean
    @ConditionalOnMissingBean(PushMessageFactory::class)
    fun pushMessageFactory(properties: PushProperties, gate: AdvertisingGate): PushMessageFactory = DefaultPushMessageFactory(properties, gate)

    @Bean
    @ConditionalOnMissingBean
    fun pushNotificationChannel(
        @Suppress("UNUSED_PARAMETER") ports: PushPortsCheck,
        registry: NotificationSubscriptionRegistry,
        properties: PushProperties,
        factory: PushMessageFactory,
        devices: PushDeviceStore,
        mutes: PushTypeMuteStore,
        tasks: PushTasks,
        senders: ObjectProvider<PushSender>,
        gate: AdvertisingGate,
        hooks: NotificationDeliveryHooks,
    ): PushNotificationChannel {
        // 광고 푸시는 야간 보류(최대 11시간)를 견뎌야 한다 - 푸시 만료(ttl)가 그보다 짧으면 보류된 광고가 만료돼 사라진다
        check(!gate.enabled || properties.ttl >= MIN_ADVERTISING_TTL) { "skeleton.notification.push.ttl must be at least 12 hours when skeleton.notification.advertising.types is set (a night hold can last 11 hours and must not expire the advertisement)" }
        return PushNotificationChannel(registry, properties, factory, devices, mutes, tasks, { senders.orderedStream().toList() }, gate, hooks)
    }

    @Bean
    @ConditionalOnMissingBean
    fun pushDeliveryWorker(
        @Suppress("UNUSED_PARAMETER") ports: PushPortsCheck,
        devices: PushDeviceStore,
        mutes: PushTypeMuteStore,
        senders: ObjectProvider<PushSender>,
        time: ObjectProvider<TimeProvider>,
        gate: AdvertisingGate,
        tasks: PushTasks,
        hooks: NotificationDeliveryHooks,
        openTokens: ObjectProvider<PushOpenTokens>,
    ): PushDeliveryWorker =
        PushDeliveryWorker(devices, mutes, { senders.orderedStream().toList() }, time.getIfAvailable { TimeProvider.systemUtc() }, gate, tasks, hooks, openTokens.getIfAvailable())

    /** 붙어 있는 채널 이름표 - `NotificationEvent.channels` 에 쓰는 `push` */
    @Bean
    @ConditionalOnMissingBean(name = ["pushChannelDeclaration"])
    fun pushChannelDeclaration(): NotificationChannelDeclaration = NotificationChannelDeclaration(PushChannel.KEY)

    /** 클릭 보고 토큰 - `open-report.secret` 이 있을 때만(없으면 페이로드에 `open` 도 보고 경로도 없다) */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(OPEN_REPORT_ON)
    fun pushOpenTokens(properties: PushProperties): PushOpenTokens = PushOpenTokens(properties.openReport.secret)

    /** 클릭 기록 저장소(`PushOpenStore`)는 `notification-push-jdbc` 가 내놓는다 - 보고를 켰는데 없으면 시작을 멈춘다 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(OPEN_REPORT_ON)
    fun pushOpenReporter(
        @Suppress("UNUSED_PARAMETER") ports: PushPortsCheck,
        tokens: PushOpenTokens,
        devices: PushDeviceStore,
        store: ObjectProvider<PushOpenStore>,
        hooks: NotificationDeliveryHooks,
        time: ObjectProvider<TimeProvider>,
    ): PushOpenReporter =
        PushOpenReporter(
            tokens, devices,
            checkNotNull(store.getIfAvailable()) { "skeleton.notification.push.open-report.secret is set but there is no PushOpenStore - add the notification-push-jdbc module and its migrations" },
            hooks, time.getIfAvailable { TimeProvider.systemUtc() },
        )

    @Bean
    @ConditionalOnMissingBean
    fun pushDeviceService(
        @Suppress("UNUSED_PARAMETER") ports: PushPortsCheck,
        devices: PushDeviceStore,
        tasks: PushTasks,
        senders: ObjectProvider<PushSender>,
        properties: PushProperties,
        time: ObjectProvider<TimeProvider>,
    ): PushDeviceService =
        PushDeviceService(devices, tasks, { senders.orderedStream().toList() }, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.notification.push.stale-cleanup", name = ["enabled"], havingValue = "true")
    fun pushStaleCleanup(
        devices: PushDeviceStore,
        properties: PushProperties,
        time: ObjectProvider<TimeProvider>,
    ): PushStaleCleanup = PushStaleCleanup(devices, properties.staleCleanup, time.getIfAvailable { TimeProvider.systemUtc() })

    /**
     * `interval` 마다 오래 안 본 기기를 지운다(알림 보관 정리와 같은 꼴). `scheduler` 모듈의 `skeletonTaskScheduler` 가 없으면 경고만 남기고 돌지 않는다
     * (앱이 [PushStaleCleanup.run] 을 직접 부를 수 있다). 프로젝트 모드면 서비스 중인 프로젝트마다 그 문맥 안에서 돈다. 실패해도 다음 주기에 다시 돈다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.notification.push.stale-cleanup", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["pushStaleCleanupSchedule"])
    fun pushStaleCleanupSchedule(
        cleanup: PushStaleCleanup,
        properties: PushProperties,
        @Qualifier("skeletonTaskScheduler") schedulers: ObjectProvider<TaskScheduler>,
        projects: ObjectProvider<ProjectRegistry>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val log = LoggerFactory.getLogger(PushStaleCleanup::class.java)
        val scheduler = schedulers.getIfAvailable()
        if (scheduler == null) {
            log.warn("push stale-cleanup is on but there is no skeletonTaskScheduler bean (add the scheduler module) - old devices are only removed when the app calls PushStaleCleanup.run")
        } else {
            val interval = properties.staleCleanup.interval
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        val registry = projects.getIfAvailable()
                        if (registry == null) {
                            cleanup.run()
                        } else {
                            registry.forEachProject { project ->
                                try {
                                    cleanup.run()
                                } catch (e: Exception) {
                                    log.warn("push stale-cleanup failed for project '{}': {}", project.id, e.toString())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("push stale-cleanup failed: {}", e.toString())
                    }
                },
                Instant.now().plus(interval),
                interval,
            )
        }
    }
}

/** 세 포트가 모두 있다는 표지 — 이 빈을 받는 빈은 포트 검사를 먼저 거친다 */
object PushPortsCheck

private val MIN_ADVERTISING_TTL: java.time.Duration = java.time.Duration.ofHours(12)

/** 클릭 보고 비밀이 비어 있지 않을 때(빈 문자열은 「속성 있음」으로 읽히므로 `@ConditionalOnProperty` 를 쓰지 않는다) */
private const val OPEN_REPORT_ON = "#{'\${skeleton.notification.push.open-report.secret:}' != ''}"
