package dev.sumin.skeleton.common.logging

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 도우미 자신: 제곱으로 느려지는 정규식은 한도에 걸리고, 선형인 것은 통과한다 */
class RegexBlowupGuardTest {
    @Test
    fun `a quadratic pattern is caught and a linear one passes`() {
        val quadratic = Regex("\\s*$")
        val failure = assertFailsWith<AssertionError> {
            // 공백이 길게 이어지고 끝이 다르면 시작 위치마다 끝까지 다시 훑는다 — 제곱
            RegexBlowupGuard.assertFinishesInTime("quadratic", n = 60_000, limitMillis = 1_500) { quadratic.find(RegexBlowupGuard.interruptible(" ".repeat(it) + "x")) }
        }
        assertTrue("ReDoS" in failure.message.orEmpty(), failure.message)

        RegexBlowupGuard.assertFinishesInTime("linear", n = 60_000, limitMillis = 5_000) { Regex("x$").find(" ".repeat(it) + "x") }
    }
}
