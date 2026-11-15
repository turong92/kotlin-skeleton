package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.PeriodicRunCheck
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.LegalAutoConfiguration
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationChannelDeclaration
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingConsentPolicy
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingNoticeStore
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.scheduling.TaskScheduler

private const val ADVERTISING_TYPES_PROPERTY = "skeleton.notification.advertising.types"

/** `skeleton.notification.advertising.types` 가 비어 있지 않을 때만 참 - 목록이든 쉼표 문자열이든 같은 바인더로 읽는다 */
class AdvertisingTypesConfigured : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val types = Binder.get(context.environment).bind(ADVERTISING_TYPES_PROPERTY, Bindable.listOf(String::class.java)).orElse(emptyList()).orEmpty()
        return if (types.any { it.isNotBlank() }) ConditionOutcome.match("advertising types are set") else ConditionOutcome.noMatch("advertising types are empty - advertising is off")
    }
}

/**
 * 광고 동의 어댑터(채널 무관) - 광고 종류가 설정됐고 `legal` 의 [ConsentService] 가 있을 때만 선다(광고를 안 켜면 아무 빈도 없다 = 이 모듈 없는 앱과 같다).
 * [AdvertisingConsentPolicy] 를 `legal` 의 동의 기록으로 구현한다 - 푸시 채널이든 메일 채널이든 같은 정책 빈을 쓴다.
 * 동의 변경 통지([AdvertisingConsentNotifier])와 2년 재확인 안내 스캔([AdvertisingReconfirmScanner])도 여기서 선다 - 둘 다 알림 이벤트(`NotificationPublisher`)로 내보내므로 **푸시가 없어도** 돈다
 * (받은편지함 · SSE 는 늘 가고, 푸시 · 메일 등은 앱이 켠 채널이 `advertising.consent.*` 종류를 집어 간다).
 */
@AutoConfiguration(before = [NotificationAutoConfiguration::class], after = [LegalAutoConfiguration::class])
@Conditional(AdvertisingTypesConfigured::class)
@ConditionalOnBean(ConsentService::class)
class NotificationLegalAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun legalAdvertisingConsents(service: ConsentService, properties: AdvertisingProperties): LegalAdvertisingConsents =
        LegalAdvertisingConsents(service, properties)

    @Bean
    @ConditionalOnMissingBean(AdvertisingConsentPolicy::class)
    fun legalAdvertisingConsentPolicy(consents: LegalAdvertisingConsents): AdvertisingConsentPolicy = LegalAdvertisingConsentPolicy(consents)

    @Bean
    @ConditionalOnMissingBean
    fun advertisingConsentNotifier(publisher: NotificationPublisher, properties: AdvertisingProperties, gate: AdvertisingGate): AdvertisingConsentNotifier =
        AdvertisingConsentNotifier(publisher, properties, gate)

    /** 표시 저장소가 없으면 **시작이 실패한다** - 없는 채로 두면 2년 재확인 안내가 조용히 안 나간다 */
    @Bean
    @ConditionalOnMissingBean
    fun advertisingReconfirmScanner(
        store: ConsentStore,
        notices: ObjectProvider<AdvertisingNoticeStore>,
        publisher: NotificationPublisher,
        gate: AdvertisingGate,
        properties: AdvertisingProperties,
        time: ObjectProvider<TimeProvider>,
        environment: Environment,
    ): AdvertisingReconfirmScanner {
        // 스캔을 돌릴 길이 없으면(scheduler 도 요청 구동 모드도 없다) 2년 재확인 안내가 한 통도 안 나가는데 아무 소리도 없다
        PeriodicRunCheck.require("skeleton.notification.advertising.types (the reconfirm notices)", environment)
        return AdvertisingReconfirmScanner(
            store,
            notices.getIfAvailable { error("skeleton.notification.advertising.types needs an AdvertisingNoticeStore bean (the reconfirm notices are sent once per consent cycle) - add notification-jdbc, or define the bean") },
            publisher, gate, properties, time.getIfAvailable { TimeProvider.systemUtc() },
        )
    }

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - 안내 표시가 멱등이라 타이머와 겹쳐 돌아도 한 번만 나간다 */
    @Bean
    @ConditionalOnMissingBean(name = ["advertisingReconfirmTask"])
    fun advertisingReconfirmTask(scanner: AdvertisingReconfirmScanner): OnRequestTask = SimpleOnRequestTask("advertising-reconfirm") { scanner.run() }

    /**
     * 법정 통지는 전송매체가 있어야 닿는다 - 받은편지함 · SSE 는 앱 안 기록이다. 푸시 채널도 메일 통지(`notification-mail` + `ContactAddresses`)도 없으면 경고한다
     * (앱이 SMS 같은 자기 채널로 `advertising.consent.*` 를 집어 갈 수도 있어 막지는 않는다).
     */
    @Bean
    @ConditionalOnMissingBean(name = ["advertisingNoticeMediumCheck"])
    fun advertisingNoticeMediumCheck(context: ListableBeanFactory, channels: ObjectProvider<NotificationChannelDeclaration>): SmartInitializingSingleton = SmartInitializingSingleton {
        val push = channels.orderedStream().anyMatch { it.key == "push" }
        if (!push && !context.containsBean("advertisingNoticeMailer")) {
            LoggerFactory.getLogger(AdvertisingConsentNotifier::class.java).warn(
                "advertising consent notices (advertising.consent.*) have no delivery medium: push is off and the mail notice is not wired (needs notification-mail + a ContactAddresses bean from account). They only reach the inbox, which is not a transmission - deliver them by your own channel",
            )
        }
    }

    /**
     * `reconfirm-scan-interval` 마다 재확인 안내를 스캔한다. `scheduler` 모듈의 `skeletonTaskScheduler` 가 없으면 경고만 남기고 돌지 않는다(앱이 [AdvertisingReconfirmScanner.run] 을 직접 부를 수 있다).
     * 프로젝트 모드면 서비스 중인 프로젝트마다 그 문맥 안에서 돈다. 실패해도 다음 주기에 다시 돈다.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["advertisingReconfirmSchedule"])
    fun advertisingReconfirmSchedule(
        scanner: AdvertisingReconfirmScanner,
        properties: AdvertisingProperties,
        @Qualifier("skeletonTaskScheduler") schedulers: ObjectProvider<TaskScheduler>,
        projects: ObjectProvider<ProjectRegistry>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val log = LoggerFactory.getLogger(AdvertisingReconfirmScanner::class.java)
        val scheduler = schedulers.getIfAvailable()
        if (scheduler == null) {
            log.warn("advertising reconfirm scan has no skeletonTaskScheduler bean (add the scheduler module) - reminders are only sent when the app calls AdvertisingReconfirmScanner.run")
        } else {
            val interval = properties.reconfirmScanInterval
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        val registry = projects.getIfAvailable()
                        if (registry == null) {
                            scanner.run()
                        } else {
                            registry.forEachProject { project ->
                                try {
                                    scanner.run()
                                } catch (e: Exception) {
                                    log.warn("advertising reconfirm scan failed for project '{}': {}", project.id, e.toString())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("advertising reconfirm scan failed: {}", e.toString())
                    }
                },
                Instant.now().plus(interval),
                interval,
            )
        }
    }
}
