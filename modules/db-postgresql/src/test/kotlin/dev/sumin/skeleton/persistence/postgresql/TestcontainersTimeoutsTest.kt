package dev.sumin.skeleton.persistence.postgresql

import kotlin.test.Test
import kotlin.test.assertTrue
import org.testcontainers.utility.TestcontainersConfiguration

/**
 * 호스트가 바쁠 때(load 60~90) 도커가 Ryuk 컨테이너를 만드는 데 30초 넘게 걸린다(실측: "Container testcontainers/ryuk started in PT30.35S").
 * Testcontainers 의 기본 Ryuk 대기는 30초라 그 JVM 의 첫 DB 시험이 `ExceptionInInitializerError`(RyukResourceReaper)로 죽고 나머지 시험은 전부 `NoClassDefFoundError` 가 된다.
 * 루트 `build.gradle.kts` 가 모든 Test 태스크에 더 긴 대기를 준다 — 이 시험은 JVM 이 실제로 그 값을 받았는지 본다.
 */
class TestcontainersTimeoutsTest {
    @Test
    fun `ryuk gets more than the default 30 seconds to start`() {
        val seconds = TestcontainersConfiguration.getInstance().ryukTimeout
        assertTrue(seconds >= 120, "ryuk.container.timeout is $seconds s; the Test tasks must set TESTCONTAINERS_RYUK_CONTAINER_TIMEOUT (root build.gradle.kts)")
    }
}
