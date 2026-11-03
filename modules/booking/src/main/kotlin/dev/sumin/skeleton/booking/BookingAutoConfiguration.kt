package dev.sumin.skeleton.booking

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * ```yaml
 * skeleton:
 *   booking:
 *     http:
 *       enabled: true    # false 면 사용자 경로 /api/v1/booking 을 등록하지 않는다
 * ```
 */
@ConfigurationProperties("skeleton.booking")
data class BookingProperties(val http: Http = Http()) {
    data class Http(val enabled: Boolean = true)
}

/**
 * 서버 API([BookingPools] · [BookingHolds]) · 만료 회수 · 자리 사건 중계. 저장소([BookingStore])는 `booking-jdbc` 가 낸다 — 저장소가 없으면 서비스도 서지 않는다.
 * 문([BookingAdmission])의 기본은 늘 허용, 중계의 기본은 이 인스턴스 안([LocalBookingSeatRelay]).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.booking.jdbc.BookingJdbcAutoConfiguration"])
@EnableConfigurationProperties(BookingProperties::class)
class BookingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BookingSeatRelay::class)
    fun localBookingSeatRelay(): BookingSeatRelay = LocalBookingSeatRelay()

    @Bean
    @ConditionalOnMissingBean(BookingAdmission::class)
    fun allowAllBookingAdmission(): BookingAdmission = AllowAllBookingAdmission

    @Bean
    @ConditionalOnBean(BookingStore::class)
    @ConditionalOnMissingBean
    fun bookingPools(store: BookingStore, relay: BookingSeatRelay, time: TimeProvider): BookingPools = BookingPools(store, relay, time)

    @Bean
    @ConditionalOnBean(BookingStore::class)
    @ConditionalOnMissingBean
    fun bookingHolds(store: BookingStore, relay: BookingSeatRelay, admission: BookingAdmission, time: TimeProvider): BookingHolds =
        BookingHolds(store, relay, admission, time)

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 — 멱등 */
    @Bean
    @ConditionalOnBean(BookingHolds::class)
    @ConditionalOnMissingBean(name = ["bookingHoldExpiryTask"])
    fun bookingHoldExpiryTask(holds: BookingHolds): OnRequestTask = SimpleOnRequestTask("booking-hold-expire") { holds.expireDue() }

    /** `scheduler` 모듈이 클래스패스에 있을 때만 - 없으면 이 표시를 읽는 쪽이 없다 */
    @Bean
    @ConditionalOnBean(BookingHolds::class)
    @ConditionalOnClass(name = ["dev.sumin.skeleton.scheduler.SkeletonScheduled"])
    @ConditionalOnMissingBean
    fun bookingExpiryJob(holds: BookingHolds): BookingExpiryJob = BookingExpiryJob(holds)
}
