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
        audioProfile: String? = "LC",
    ) = MediaInfo(
        container = "hls", videoCodec = codec, profile = profile, pixFmt = "yuv420p", level = level, codecTag = null,
        width = w, height = h, rotation = null, fps = 30.0, durationMs = durationMs, bitrate = null, audioCodec = audio,
        videoStreams = if (codec.isEmpty()) 0 else 1, audioStreams = if (audio == null) 0 else 1, otherStreams = 0, hdr = false,
        audioProfile = audioProfile,
    )

    /** 6초 조각 둘 — 첫 조각 [first] 바이트, 둘째 30_000바이트, init 1_000바이트. [tail] 이 있으면 0.2초짜리 짧은 마지막 조각을 더한다 */
    private fun tier(folder: String, info: MediaInfo, first: Int = 60_000, tail: Int? = null): BuiltTier {
        val root = Files.createDirectories(dir.resolve(folder))
        val playlist = root.resolve("index.m3u8")
        Files.writeString(
            playlist,
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\ns00000.m4s\n#EXTINF:6.0,\ns00001.m4s\n" +
                (if (tail != null) "#EXTINF:0.2,\ns00002.m4s\n" else "") + "#EXT-X-ENDLIST\n",
        )
        val sizes = mapOf("init.mp4" to 1_000, "s00000.m4s" to first, "s00001.m4s" to 30_000) + (if (tail != null) mapOf("s00002.m4s" to tail) else emptyMap())
        val media = sizes.map { (name, size) -> root.resolve(name).also { Files.write(it, ByteArray(size)) } }
        return BuiltTier(folder, media + listOf(playlist), "x", info)
    }

    @Test
    fun `코덱 문자열 - H264 는 avc1 PPCCLL 에 AAC 를 붙이고 소리가 없으면 영상만이다`() {
        assertEquals("avc1.640028,mp4a.40.2", LocalMaster.codec(info()))
        assertEquals("avc1.4d001f", LocalMaster.codec(info(profile = "Main", level = 31, audio = null, audioProfile = null)))
    }

    @Test
    fun `코덱 문자열 - 소리 판은 AAC 만이고 모르는 코덱 · 레벨 · 프로파일 · HE-AAC 는 null 이다`() {
        assertEquals("mp4a.40.2", LocalMaster.codec(info(codec = "", audio = "aac")))
        assertNull(LocalMaster.codec(info(codec = "vp9")), "모르는 영상 코덱")
        assertNull(LocalMaster.codec(info(level = null)), "레벨을 못 읽으면 지어내지 않는다")
        assertNull(LocalMaster.codec(info(profile = null)), "프로파일을 못 읽으면 High 로 지어내지 않는다")
        assertNull(LocalMaster.codec(info(profile = "Something Else")))
        assertNull(LocalMaster.codec(info(audio = "opus")), "AAC 가 아닌 소리는 문자열을 모른다")
        assertNull(LocalMaster.codec(info(audioProfile = "HE-AAC")), "HE-AAC 를 mp4a.40.2(LC)로 적지 않는다")
        assertNull(LocalMaster.codec(info(audioProfile = null)))
    }

    @Test
    fun `대역폭 - BANDWIDTH 는 가장 큰 조각의 비트레이트 AVERAGE 는 init 까지 합친 평균이다`() {
        val (peak, average) = LocalMaster.bandwidth(tier("c-0000000000000001", info()))
        assertEquals(80_000, peak)
        assertEquals(60_667, average, "91_000바이트 × 8 / 12초, 올림")
    }

    @Test
    fun `대역폭 - 짧은 마지막 조각 하나(I-프레임)가 BANDWIDTH 를 부풀리지 않는다`() {
        // 0.2초에 20_000바이트면 조각 하나만 재면 800_000bps — 목표 길이 절반(3초)이 찰 때까지 앞 조각과 묶어 잰다
        val (peak, _) = LocalMaster.bandwidth(tier("c-0000000000000001", info(), tail = 20_000))
        assertEquals(80_000, peak)
    }

    private fun small() = tier("c-0000000000000001", info(w = 1280, h = 720, level = 31), first = 30_000)
    private fun big() = tier("o-0000000000000002", info(w = 1920, h = 1080, level = 40))

    @Test
    fun `마스터 - 낮은 단부터 BANDWIDTH · RESOLUTION · CODECS 가 든 변형 줄이고 경로는 출력 폴더 기준이다`() {
        val lines = LocalMaster.text(big(), small())!!.trimEnd().lines()

        assertEquals("#EXTM3U", lines[0])
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
        assertEquals(2, variants.size)
        assertTrue(variants[0].contains("RESOLUTION=1280x720") && variants[0].contains("CODECS=\"avc1.64001f,mp4a.40.2\"") && variants[0].contains("BANDWIDTH=40667,"), "피크 40000 < 평균 40667(init 포함)이면 평균이 BANDWIDTH 다")
        assertTrue(variants[1].contains("RESOLUTION=1920x1080") && variants[1].contains("CODECS=\"avc1.640028,mp4a.40.2\"") && variants[1].contains("BANDWIDTH=80000,"))
        assertTrue(variants.all { it.contains("AVERAGE-BANDWIDTH=") })
        assertEquals("c-0000000000000001/index.m3u8", lines[lines.indexOf(variants[0]) + 1])
        assertEquals("o-0000000000000002/index.m3u8", lines[lines.indexOf(variants[1]) + 1])
    }

    @Test
    fun `마스터 - 한 판이라도 코덱 문자열을 모르면 만들지 않는다`() {
        assertNull(LocalMaster.text(tier("o-0000000000000002", info(codec = "vp9")), small()))
    }

    @Test
    fun `마스터 - 짧은 변이 1080 을 넘는 원본은 자동에 넣지 않는다`() {
        assertNull(LocalMaster.text(tier("o-0000000000000002", info(w = 3840, h = 2160, level = 51)), small()))
    }

    @Test
    fun `마스터 - 호환 판이 더 작고 더 가볍지 않으면 만들지 않는다`() {
        assertNull(LocalMaster.text(big(), tier("c-0000000000000001", info(w = 1920, h = 1080))), "같은 해상도")
        assertNull(LocalMaster.text(big(), tier("c-0000000000000001", info(w = 1280, h = 720, level = 31), first = 90_000)), "대역폭이 더 크다")
    }

    @Test
    fun `마스터 - 영상 코덱 계열이 다르면 만들지 않는다`() {
        val hevc = tier("o-0000000000000002", info(codec = "hevc", profile = "Main", level = 120))
        assertNull(LocalMaster.text(hevc, small()))
    }
}
