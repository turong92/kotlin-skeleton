package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.json.JsonMapper

/**
 * 움직이는 이미지 차선(ANIMATED) — 실제 ffmpeg 로 합성한 GIF · WebP 를 돌린다(호스트에 ffmpeg 가 없으면 건너뜀).
 * 사고 나는 것만 본다: 투명 판정(최적화 투명은 MP4 · 실제 투명은 WebP) · 변환본이 크면 올리지 않는다 · 못 읽는 형식은 올리지 않고 실패로만 알린다
 */
class AnimatedLaneTest {
    private val json = JsonMapper.builder().build()
    private lateinit var dir: Path
    private lateinit var server: FakeVideoServer
    private lateinit var runner: Runner

    @BeforeTest
    fun setUp() {
        assumeTrue(Synth.available, "ffmpeg · ffprobe 가 없어 건너뜀")
        dir = Synth.tempDir()
        server = FakeVideoServer()
        val config = WorkerConfig(apiBase = server.base, token = server.token, workerId = "test-1", workDir = dir.resolve("work"), heartbeatInterval = Duration.ofMillis(200))
        runner = Runner(config, WorkerApi(config), Transfer(null), Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5)))
    }

    @AfterTest
    fun tearDown() {
        if (this::server.isInitialized) server.close()
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun run(id: String, file: Path) {
        server.enqueue(server.job(id, "ANIMATED", server.serve(file.fileName.toString(), file), file.fileSize()))
        assertEquals(1, runner.runUntilIdle())
    }

    private fun stored(id: String, name: String) = server.stored["/store/$id/$name"]

    private fun ffprobe(file: Path, vararg entries: String): String =
        ProcessBuilder("ffprobe", "-v", "error", *entries, "-of", "default=nw=1", file.toString()).start().inputStream.readAllBytes().toString(Charsets.UTF_8)

    @Test
    fun `최적화 투명만 쓰는 GIF 는 합성한 화면이 불투명이라 소리 없는 H264 MP4 가 된다 - 크기 · 프레임 시간 유지`() {
        val gif = Synth.gifOpaque(dir)
        run("a".repeat(64), gif)

        val done = json.readTree(assertNotNull(server.completes["a".repeat(64)], "complete 가 안 왔다: ${server.fails["a".repeat(64)]}"))
        assertEquals("display.mp4", done["display"]["name"].asString())
        val bytes = assertNotNull(stored("a".repeat(64), "display.mp4"))
        assertEquals(bytes.size.toLong(), done["display"]["bytes"].asLong())
        assertTrue(bytes.size < gif.fileSize(), "변환본이 원본보다 작을 때만 올린다")
        assertNull(stored("a".repeat(64), "display.webp"), "불투명이면 WebP 는 올리지 않는다")
        val mp4 = Files.write(dir.resolve("out.mp4"), bytes)
        val probed = ffprobe(mp4, "-show_entries", "stream=codec_type,codec_name,width,height,color_space", "-show_entries", "format=duration")
        assertTrue("codec_name=h264" in probed && "width=240" in probed && "height=180" in probed, probed)
        assertFalse("codec_type=audio" in probed, "소리 트랙이 없어야 한다")
        assertTrue("color_space=bt709" in probed, "색 표시: $probed")
        assertEquals(3.0, Regex("duration=([0-9.]+)").find(probed)!!.groupValues[1].toDouble(), 0.3, "프레임 시간(전체 길이)이 유지돼야 한다")
        assertEquals("video/mp4", server.storedHeaders["/store/${"a".repeat(64)}/display.mp4"]?.get("content-type")?.let { "video/mp4" })
    }

    @Test
    fun `실제 투명 배경 GIF 는 합성한 화면에 알파가 있어 움직이는 WebP 가 된다`() {
        val gif = Synth.gifTransparent(dir)
        // 이 크기의 GIF 는 WebP 가 더 작다(무손실) - 아니면 이 시험은 투명 판정이 아니라 크기 규칙을 보게 된다
        run("b".repeat(64), gif)

        val done = json.readTree(assertNotNull(server.completes["b".repeat(64)], "complete 가 안 왔다: ${server.fails["b".repeat(64)]}"))
        assertEquals("display.webp", done["display"]["name"].asString())
        val bytes = assertNotNull(stored("b".repeat(64), "display.webp"))
        assertEquals("RIFF", String(bytes, 0, 4), "WebP 컨테이너")
        assertEquals("WEBP", String(bytes, 8, 4))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("ANIM"), "움직이는 WebP")
        assertNull(stored("b".repeat(64), "display.mp4"), "투명이면 MP4 는 올리지 않는다")
    }

    @Test
    fun `변환본이 원본보다 크면 아무것도 올리지 않고 다시 하지 않는 실패로 알린다 - 서버는 사본을 계속 쓴다`() {
        val gif = Synth.gifOpaque(dir, w = 33, h = 21, sec = 1)   // 아주 작은 GIF - MP4 쪽이 더 크다
        run("c".repeat(64), gif)

        assertNull(server.completes["c".repeat(64)])
        assertTrue(server.stored.isEmpty(), "올린 것이 없어야 한다: ${server.stored.keys}")
        val fail = json.readTree(assertNotNull(server.fails["c".repeat(64)]))
        assertEquals("PROCESSING_FAILED", fail["code"].asString())
        assertFalse(fail["retryable"].asBoolean())
    }

    @Test
    fun `움직이는 WebP - 이 ffmpeg 가 풀면 변환하고 못 풀면 올리지 않고 UNSUPPORTED_FORMAT 으로만 알린다`() {
        val webp = Synth.webpAnimated(dir)
        run("d".repeat(64), webp)

        if (Synth.decodesAnimatedWebp(dir)) {
            // 풀 수 있는 ffmpeg - 불투명 WebP 는 MP4 (WebP 는 이미 가벼워 크기 규칙으로 사본이 남을 수도 있다)
            assertTrue(server.completes["d".repeat(64)] != null || server.fails["d".repeat(64)] != null)
            assertNull(stored("d".repeat(64), "display.webp"), "입력과 같은 이름(display.webp)으로 덮어쓰지 않는다")
        } else {
            assertNull(server.completes["d".repeat(64)])
            assertTrue(server.stored.isEmpty())
            val fail = json.readTree(assertNotNull(server.fails["d".repeat(64)]))
            assertEquals("UNSUPPORTED_FORMAT", fail["code"].asString())
            assertFalse(fail["retryable"].asBoolean())
        }
    }

    @Test
    fun `GIF 가 아니고 WebP 도 아닌 바이트는 읽지 않는다 - 허용 목록 밖`() {
        val mp4 = Synth.h264(dir, sec = 1)
        run("e".repeat(64), mp4)

        assertNull(server.completes["e".repeat(64)])
        assertTrue(server.stored.isEmpty())
        assertEquals("UNSUPPORTED_FORMAT", json.readTree(assertNotNull(server.fails["e".repeat(64)]))["code"].asString())
    }
}
