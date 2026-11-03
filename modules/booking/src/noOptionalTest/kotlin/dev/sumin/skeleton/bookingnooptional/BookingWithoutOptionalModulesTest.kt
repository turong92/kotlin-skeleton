package dev.sumin.skeleton.bookingnooptional

import dev.sumin.skeleton.booking.AllowAllBookingAdmission
import dev.sumin.skeleton.booking.BookingAdmission
import dev.sumin.skeleton.booking.BookingAutoConfiguration
import dev.sumin.skeleton.booking.BookingHolds
import dev.sumin.skeleton.booking.BookingPaymentAutoConfiguration
import dev.sumin.skeleton.booking.BookingPools
import dev.sumin.skeleton.booking.BookingRedisAutoConfiguration
import dev.sumin.skeleton.booking.BookingSeatRelay
import dev.sumin.skeleton.booking.BookingStore
import dev.sumin.skeleton.booking.BookingWaitingRoomAutoConfiguration
import dev.sumin.skeleton.booking.LocalBookingSeatRelay
import dev.sumin.skeleton.common.time.TimeProvider
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 「예약만」 조립 (스펙 §7 조립): 대기열 · 결제(무료 풀만) · Redis · 스케줄러 모듈이 클래스패스에 없어도 예약이 서고, 기본 문(늘 허용) · 기본 중계(이 인스턴스 안)가 선다.
 * 세 이음새 자동설정은 그 모듈이 없을 때 조용히 물러나야 한다(클래스를 읽다 죽지 않는다).
 */
class BookingWithoutOptionalModulesTest {
    private val store = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BookingStore::class.java)) { _, method, _ -> error("not used: ${method.name}") } as BookingStore

    @Test
    fun `booking stands with the default door and the in-process relay when payment, waiting-room, redis and the scheduler are not on the classpath`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    BookingWaitingRoomAutoConfiguration::class.java, BookingRedisAutoConfiguration::class.java,
                    BookingAutoConfiguration::class.java, BookingPaymentAutoConfiguration::class.java,
                ),
            )
            .withBean(TimeProvider::class.java, { TimeProvider { java.time.Instant.now() } })
            .withBean(BookingStore::class.java, { store })
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(BookingHolds::class.java) != null && context.getBean(BookingPools::class.java) != null)
                assertSame(AllowAllBookingAdmission, context.getBean(BookingAdmission::class.java))
                assertTrue(context.getBean(BookingSeatRelay::class.java) is LocalBookingSeatRelay)
                listOf("bookingPayments", "bookingRefundJobHandler", "jobBookingRefundQueue", "redisBookingSeatRelay", "waitingRoomBookingAdmission", "bookingExpiryJob")
                    .forEach { name -> assertTrue(!context.containsBean(name), "$name must not exist without its module") }
            }
    }
}
