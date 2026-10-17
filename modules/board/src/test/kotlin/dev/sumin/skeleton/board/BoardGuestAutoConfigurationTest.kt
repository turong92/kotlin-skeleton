package dev.sumin.skeleton.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 비회원 쓰기 설정: 기본은 꺼짐 · 캡차를 켰는데 검증기가 없으면 기동 실패 */
class BoardGuestAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BoardAutoConfiguration::class.java))
        .withUserConfiguration(BoardAutoConfigurationTest.FakeRepositories::class.java)

    @Test
    fun `guests are off by default with PASSWORD secrets and captcha OFF`() {
        runner.run { ctx ->
            val guest = ctx.getBean(BoardProperties::class.java).guest
            assertFalse(guest.enabled)
            assertEquals(GuestSecretMode.PASSWORD, guest.secret)
            assertEquals(GuestCaptcha.OFF, guest.captcha)
            assertNotNull(ctx.getBean(BoardGuests::class.java))
            assertFalse(ctx.getBean(BoardService::class.java).config(null).guestEnabled)
        }
    }

    @Test
    fun `required captcha without a verifier fails startup instead of running unchecked`() {
        runner.withPropertyValues("skeleton.board.guest.enabled=true", "skeleton.board.guest.captcha=REQUIRED").run { ctx ->
            assertNotNull(ctx.startupFailure)
            assertTrue(generateSequence(ctx.startupFailure) { it.cause }.any { it.message.orEmpty().contains("BoardCaptcha") }, ctx.startupFailure.toString())
        }
        runner.withPropertyValues("skeleton.board.guest.enabled=true", "skeleton.board.guest.captcha=REQUIRED")
            .withBean(BoardCaptcha::class.java, { BoardCaptcha { _, _, _ -> true } })
            .run { ctx ->
                assertEquals(null, ctx.startupFailure)
                assertTrue(ctx.getBean(BoardService::class.java).config(null).captchaRequired)
            }
    }

    @Test
    fun `seed boards carry their settings`() {
        runner.withPropertyValues(
            "skeleton.board.seed-boards[0].code=general", "skeleton.board.seed-boards[0].name=General",
            "skeleton.board.seed-boards[0].settings.guest-write=ALL",
        ).run { ctx ->
            ctx.getBeansOfType(org.springframework.boot.ApplicationRunner::class.java).values.forEach { it.run(org.springframework.boot.DefaultApplicationArguments()) }
            assertEquals(GuestWrite.ALL, ctx.getBean(BoardService::class.java).get("general").settings.guestWrite)
        }
    }
}
