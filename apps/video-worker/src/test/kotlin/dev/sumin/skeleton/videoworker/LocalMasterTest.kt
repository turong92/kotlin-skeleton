package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 로컬 모드의 코덱 문자열 · 마스터 목록 — 순수 함수(ffmpeg 없이) */
class LocalMasterTest {
    private val dir: Path = Files.createTempDirectory("local-master-test-")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun info(
        codec: String = "h264", profile: String? = "High", level: Int? = 40, w: Int = 1920, h: Int = 1080, audio: String? = "aac", durationMs: Long = 12_000,
    ) = MediaInfo(
        container = "hls", videoCodec = codec, profile = profile, pixFmt = "yuv420p", level = level, codecTag = null,
        width = w, height = h, rotation = null, fps = 30.0, durationMs = durationMs, bitrate = null, audioCodec = audio,
        videoStreams = if (codec.isEmpty()) 0 else 1, audioStreams = if (audio == null) 0 else 1, otherStreams = 0, hdr = false,
    )

    /** 6초 조각 둘 — 첫 조각 60_000바이트(6초 → 80_000bps), 둘째 30_000바이트, init 1_000바이트 */
    private fun tier(folder: String, info: MediaInfo): BuiltTier {
        val root = Files.createDirectories(dir.resolve(folder))
        val playlist = root.resolve("index.m3u8")
        Files.writeString(playlist, "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\ns00000.m4s\n#EXTINF:6.0,\ns00001.m4s\n#EXT-X-ENDLIST\n")
        val media = mapOf("init.mp4" to 1_000, "s00000.m4s" to 60_000, "s00001.m4s" to 30_000).map { (name, size) -> root.resolve(name).also { Files.write(it, ByteArray(size)) } }
        return BuiltTier(folder, media + listOf(playlist), "x", info)
    }

    @Test
    fun `코덱 문자열 - H264 는 avc1 PPCCLL 에 AAC 를 붙이고 소리가 없으면 영상만이다`() {
        assertEquals("avc1.640028,mp4a.40.2", LocalMaster.codec(info()))
        assertEquals("avc1.4d001f", LocalMaster.codec(info(profile = "Main", level = 31, audio = null)))
    }

    @Test
    fun `코덱 문자열 - 소리 판은 AAC 만이고 모르는 코덱 · 레벨이 없는 영상은 null 이다`() {
        assertEquals("mp4a.40.2", LocalMaster.codec(info(codec = "", audio = "aac")))
        assertNull(LocalMaster.codec(info(codec = "vp9")), "모르는 영상 코덱")
        assertNull(LocalMaster.codec(info(level = null)), "레벨을 못 읽으면 지어내지 않는다")
        assertNull(LocalMaster.codec(info(audio = "opus")), "AAC 가 아닌 소리는 문자열을 모른다")
    }

    @Test
    fun `대역폭 - BANDWIDTH 는 가장 큰 조각의 비트레이트 AVERAGE 는 init 까지 합친 평균이다`() {
        val t = tier("c-0000000000000001", info())
        val (peak, average) = LocalMaster.bandwidth(t)
        assertEquals(80_000, peak)
        assertEquals(60_667, average, "91_000바이트 × 8 / 12초, 올림")
    }

    @Test
    fun `마스터 - 낮은 단부터 BANDWIDTH · RESOLUTION · CODECS 가 든 변형 줄이고 경로는 출력 폴더 기준이다`() {
        val compat = tier("c-0000000000000001", info(w = 1280, h = 720, level = 31))
        val original = tier("o-0000000000000002", info(w = 3840, h = 2160, level = 51))

        val text = LocalMaster.text(listOf(original, compat))!!
        val lines = text.trimEnd().lines()

        assertEquals("#EXTM3U", lines[0])
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
        assertEquals(2, variants.size)
        // 두 판 모두 같은 조각 크기라 대역폭이 같다 — 같으면 해상도가 낮은 쪽이 앞이다
        assertTrue(variants[0].contains("RESOLUTION=1280x720") && variants[0].contains("CODECS=\"avc1.64001f,mp4a.40.2\""))
        assertTrue(variants[1].contains("RESOLUTION=3840x2160") && variants[1].contains("CODECS=\"avc1.640033,mp4a.40.2\""))
        assertTrue(variants.all { Regex("BANDWIDTH=80000,AVERAGE-BANDWIDTH=\\d+,").containsMatchIn(it) })
        assertEquals("c-0000000000000001/index.m3u8", lines[lines.indexOf(variants[0]) + 1])
        assertEquals("o-0000000000000002/index.m3u8", lines[lines.indexOf(variants[1]) + 1])
    }

    @Test
    fun `마스터 - 한 판이라도 코덱 문자열을 모르면 마스터를 만들지 않는다`() {
        val compat = tier("c-0000000000000001", info())
        val unknown = tier("o-0000000000000002", info(codec = "vp9"))
        assertNull(LocalMaster.text(listOf(unknown, compat)))
    }
}
