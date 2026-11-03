package dev.sumin.skeleton.booking.web

import dev.sumin.skeleton.booking.BookingAdmission
import dev.sumin.skeleton.booking.BookingAutoConfiguration
import dev.sumin.skeleton.booking.BookingHolds
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.BookingSeatRelay
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 예약 HTTP — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 서비스([BookingHolds] 빈)가 있을 때만.
 * 사용자 경로 `/api/v1/booking` 은 `skeleton.booking.http.enabled=false` 로 끈다. 풀 정보(`GET /pools/{id}`)만 공개다.
 */
@AutoConfiguration(after = [BookingAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(BookingHolds::class)
@ConditionalOnProperty(prefix = "skeleton.booking.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BookingWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun bookingSeatStreamService(relay: BookingSeatRelay): BookingSeatStreamService = BookingSeatStreamService(relay)

    @Bean
    @ConditionalOnMissingBean
    fun bookingController(pools: BookingPools, holds: BookingHolds, streams: BookingSeatStreamService, admission: BookingAdmission): BookingController =
        BookingController(pools, holds, streams, admission)

    @Bean
    @ConditionalOnMissingBean(name = ["bookingPublicEndpointContributor"])
    fun bookingPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("GET", "/api/v1/booking/pools/*") }
}
