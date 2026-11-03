package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.fileSize
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.json.JsonMapper

/** 일 단위 벽시계 한도와 결과 검증 실패 처리 — 실제 ffprobe 는 쓰되 ffmpeg 실행 · 결과 읽기는 바꿔 끼운 도구로 흉내 낸다 */
class JobGuardTest {
    private val json = JsonMapper.builder().build()
    private lateinit var dir: Path
    private lateinit var server: FakeVideoServer
    private val real = Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5))

    @BeforeTest
    fun setUp() {
        assumeTrue(Synth.available, "ffmpeg · ffprobe 가 없어 건너뜀")
        dir = Synth.tempDir()
        server = FakeVideoServer()
    }

    @AfterTest
    fun tearDown() {
        if (this::server.isInitialized) server.close()
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun runner(tools: Tools, jobTimeout: Duration = Duration.ofHours(1)): Runner {
        val config = WorkerConfig(
            apiBase = server.base, token = server.token, workerId = "test-1", workDir = dir.resolve("work"),
            heartbeatInterval = Duration.ofMillis(200), jobTimeout = jobTimeout,
        )
        return Runner(config, WorkerApi(config), Transfer(null), tools)
    }

    private fun fail(jobId: String) = json.readTree(assertNotNull(server.fails[jobId], "fail 이 안 왔다"))

    @Test
    fun `일 시간 한도를 넘으면 ffmpeg 를 죽이고 재시도 가능한 실패로 알린다 — 올리지 않는다`() {
        val orig = Synth.hevc4k(dir, "orig.mp4", sec = 1)
        val killed = AtomicBoolean(false)
        val hanging = object : Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5)) {
            override fun run(args: List<String>, cwd: Path?, cancel: AtomicBoolean, onProgress: ((Long) -> Unit)?) {
                try {
                    while (!cancel.get()) Thread.sleep(20)
                    throw Cancelled("임대를 잃어 ffmpeg 를 멈춘다")
                } finally {
                    killed.set(cancel.get())
                }
            }
        }
        server.enqueue(server.job("job0000101", "COMPAT", server.serveTree("o1", Synth.hlsFolder(dir, orig)), 1))
        val started = System.nanoTime()
        runner(hanging, Duration.ofSeconds(1)).runUntilIdle()
        val took = Duration.ofNanos(System.nanoTime() - started)

        assertTrue(killed.get(), "ffmpeg 에 취소 신호가 갔다")
        assertTrue(took < Duration.ofSeconds(15), "한도(1초) 근처에서 끝난다: $took")
        val f = fail("job0000101")
        assertEquals("PROCESSING_FAILED", f["code"].asString())
        assertTrue(f["retryable"].asBoolean(), "재시도 횟수 상한까지는 다시 줄 선다")
        assertTrue(f["detail"].asString().contains("시간 한도"), f["detail"].asString())
        assertNull(server.completes["job0000101"])
        assertTrue(server.stored.isEmpty())
    }

    @Test
    fun `호환판 결과의 길이가 원본과 어긋나면 올리기 전에 재시도 가능한 실패`() {
        val orig = Synth.hevc4k(dir, "orig.mp4", sec = 2)
        val lying = object : Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5)) {
            override fun probeHls(playlist: String): MediaInfo {
                val info = real.probeHls(playlist)
                return if (playlist.contains("/c/")) info.copy(durationMs = info.durationMs / 2) else info
            }
        }
        server.enqueue(server.job("job0000102", "COMPAT", server.serveTree("o2", Synth.hlsFolder(dir, orig)), 1))
        runner(lying).runUntilIdle()
        val f = fail("job0000102")
        assertEquals("PROCESSING_FAILED", f["code"].asString())
        assertTrue(f["retryable"].asBoolean())
        assertTrue(server.stored.isEmpty(), "어긋난 결과는 올리지 않는다")
    }

    @Test
    fun `호환판 결과에 소리가 빠졌으면 실패 — 사다리는 조각 하나가 없어도 실패`() {
        val orig = Synth.hevc4k(dir, "orig.mp4", sec = 2)
        val mute = object : Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5)) {
            override fun probeHls(playlist: String): MediaInfo {
                val info = real.probeHls(playlist)
                return if (playlist.contains("/c/")) info.copy(audioCodec = null, audioStreams = 0) else info
            }
        }
        server.enqueue(server.job("job0000103", "COMPAT", server.serveTree("o3", Synth.hlsFolder(dir, orig, "o-1111111111111111")), 1))
        runner(mute).runUntilIdle()
        assertTrue(fail("job0000103")["detail"].asString().contains("소리"))

        val dropping = object : Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5)) {
            override fun run(args: List<String>, cwd: Path?, cancel: AtomicBoolean, onProgress: ((Long) -> Unit)?) {
                super.run(args, cwd, cancel, onProgress)
                if (args.contains("hls") && cwd != null) {
                    Files.list(cwd).use { s -> s.filter { it.fileName.toString().endsWith(".m4s") }.findFirst().ifPresent { Files.delete(it) } }
                }
            }
        }
        val ladderSrc = Synth.hevc4k(dir, "orig2.mp4", sec = 7)
        server.enqueue(server.job("job0000104", "LADDER", server.serveTree("o4", Synth.hlsFolder(dir, ladderSrc, "o-2222222222222222")), 1, mapOf("build" to "0123456789abcdef0123456789abcdef", "origFolder" to "o-2222222222222222")))
        runner(dropping).runUntilIdle()
        val f = fail("job0000104")
        assertTrue(f["retryable"].asBoolean())
        assertNull(server.completes["job0000104"])
        assertTrue(server.stored.keys.none { it.contains("job0000104") }, "검증 전에 올리지 않는다")
    }

    @Test
    fun `대기 중인 ffmpeg 는 일 스레드가 인터럽트되면 프로세스를 죽이고 빠져나온다`() {
        // 실행 파일 자리에 sleep — 첫 인자(ffmpeg)를 설정의 실행 파일로 바꿔 돌리는 규칙을 이용한다
        val sleeper = Tools("sleep", "ffprobe", 0, Duration.ofMinutes(5))
        val result = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val t = Thread { result.set(runCatching { sleeper.run(listOf("ffmpeg", "60"), null, AtomicBoolean(false)) }.exceptionOrNull()) }
        t.start()
        Thread.sleep(500)
        val started = System.nanoTime()
        t.interrupt()
        t.join(10_000)
        assertTrue(!t.isAlive, "인터럽트로 빠져나온다")
        assertTrue(result.get() is InterruptedException, "InterruptedException 으로 끝난다: ${result.get()}")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(8))
    }
}
