package dev.sumin.skeleton.booking

import dev.sumin.skeleton.booking.web.ReadyEvent
import dev.sumin.skeleton.booking.web.SeatEvent
import dev.sumin.skeleton.booking.web.SeatsEvent
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class BookingNativeHintsTest {
    @Test
    fun `the stream event bodies are registered for reflection - without it the native image fails when the seat stream is opened`() {
        val hints = RuntimeHints()
        BookingNativeHints().registerHints(hints, javaClass.classLoader)
        listOf(ReadyEvent::class.java, SeatsEvent::class.java, SeatEvent::class.java).forEach { type ->
            assertTrue(RuntimeHintsPredicates.reflection().onType(type).test(hints), "${type.simpleName} must be registered")
        }
    }
}
