package dev.sumin.skeleton.booking

import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.payment.PaymentRefunds
import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import dev.sumin.skeleton.waitingroom.WaitingRooms
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer

/*
 * 예약이 다른 모듈과 이어지는 곳 셋 - 모두 **그 모듈이 클래스패스에 있을 때만** 서고, 없으면 예약은 아무것도 달라지지 않는다.
 * 앱이 같은 포트의 빈을 두면 이쪽이 물러난다.
 */

/**
 * 결제: 늦은 결제 · 둘째 결제의 환불 작업을 큐에 넣는 [BookingRefundQueue] · 그 작업을 하는 `booking-refund` 처리기 · 앱이 결제 훅에서 부르는 [BookingPayments].
 * 환불 작업이 걸릴 일이 없는 무료 풀만 쓰는 앱은 결제를 붙이지 않으면 된다.
 */
@AutoConfiguration(
    after = [BookingAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration", "dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.payment.PaymentRefunds", "dev.sumin.skeleton.jobqueue.jdbc.JobQueue"])
class BookingPaymentAutoConfiguration {
    @Bean
    @ConditionalOnBean(JobQueue::class)
    @ConditionalOnMissingBean(BookingRefundQueue::class)
    fun jobBookingRefundQueue(queue: JobQueue): BookingRefundQueue = JobBookingRefundQueue(queue)

    @Bean
    @ConditionalOnBean(PaymentRefunds::class)
    @ConditionalOnMissingBean(name = ["bookingRefundJobHandler"])
    fun bookingRefundJobHandler(refunds: PaymentRefunds): JobHandler = BookingRefundJobHandler(refunds)

    @Bean
    @ConditionalOnBean(BookingHolds::class, BookingPools::class)
    @ConditionalOnMissingBean
    fun bookingPayments(holds: BookingHolds, pools: BookingPools): BookingPayments = BookingPayments(holds, pools)
}

/** 대기열: 대기실을 단 풀의 입장권 검사. 기본 「늘 허용」([AllowAllBookingAdmission])보다 앞서 서서 갈아 끼운다 */
@AutoConfiguration(
    before = [BookingAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.waitingroom.WaitingRoomAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.waitingroom.WaitingRooms"])
class BookingWaitingRoomAutoConfiguration {
    @Bean
    @ConditionalOnBean(WaitingRooms::class)
    @ConditionalOnMissingBean(BookingAdmission::class)
    fun waitingRoomBookingAdmission(rooms: WaitingRooms): BookingAdmission = WaitingRoomBookingAdmission(rooms)
}

/** 여러 대: `redis-core` 가 있으면 자리 사건을 Redis pub/sub 로 다른 인스턴스에 전한다. 기본 「이 인스턴스 안」([LocalBookingSeatRelay])보다 앞서 서서 갈아 끼운다 */
@AutoConfiguration(
    before = [BookingAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.redis.core.RedisKeyPrefixer", "org.springframework.data.redis.listener.RedisMessageListenerContainer"])
class BookingRedisAutoConfiguration {
    /** 예약 전용 구독 컨테이너 - 앱의 다른 Redis 구독과 섞이지 않는다 */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory::class, RedisKeyPrefixer::class)
    @ConditionalOnMissingBean(name = ["bookingRedisListenerContainer"])
    fun bookingRedisListenerContainer(factory: RedisConnectionFactory): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(factory)
            isAutoStartup = false   // 시작은 중계가 한다 - Redis 가 없어도 앱이 뜨게 붙을 때까지 다시 시도한다
        }

    @Bean
    @ConditionalOnBean(name = ["bookingRedisListenerContainer"])
    @ConditionalOnMissingBean(BookingSeatRelay::class)
    fun redisBookingSeatRelay(
        redis: StringRedisTemplate,
        @Qualifier("bookingRedisListenerContainer") container: RedisMessageListenerContainer,
        prefixer: RedisKeyPrefixer,
    ): BookingSeatRelay = RedisBookingSeatRelay(redis, container, prefixer.key("booking", "seats"))
}
