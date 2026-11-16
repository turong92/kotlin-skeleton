package dev.sumin.skeleton.storage.image.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimatedPlanTest {
    private val props = FfmpegProperties()

    private fun List<String>.pair(flag: String): String = this[indexOf(flag) + 1]

    @Test
    fun `GIF · WebP 머리 바이트로 종류를 가리고 나머지는 거절한다 - 확장자를 믿지 않는다`() {
        assertEquals(AnimatedKind.GIF, AnimatedKind.sniff("GIF89a....".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(AnimatedKind.GIF, AnimatedKind.sniff("GIF87a....".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(AnimatedKind.WEBP, AnimatedKind.sniff("RIFF\u0001\u0002\u0003\u0004WEBPVP8X".toByteArray(Charsets.ISO_8859_1)))
        assertNull(AnimatedKind.sniff("RIFF\u0001\u0002\u0003\u0004WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(AnimatedKind.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)))
        assertNull(AnimatedKind.sniff("hello world, not an image".toByteArray()))
        assertNull(AnimatedKind.sniff(ByteArray(0)))
    }

    @Test
    fun `입력은 gif · webp 허용 목록과 file 프로토콜만 열고 -i 앞에 걸린다`() {
        val gif = AnimatedPlan.args("ffmpeg", "/work/in", AnimatedKind.GIF, props, 1000)
        assertEquals("gif", gif.pair("-format_whitelist"))
        assertEquals("gif", gif.pair("-codec_whitelist"))
        assertEquals("gif", gif.pair("-f"))
        assertEquals("file", gif.pair("-protocol_whitelist"))
        assertTrue(gif.indexOf("-protocol_whitelist") < gif.indexOf("-i") && gif.indexOf("-codec_whitelist") < gif.indexOf("-i") && gif.indexOf("-format_whitelist") < gif.indexOf("-i"))
        val webp = AnimatedPlan.args("ffmpeg", "/work/in", AnimatedKind.WEBP, props, 1000)
        assertEquals("webp_pipe", webp.pair("-format_whitelist"))
        assertEquals("webp", webp.pair("-codec_whitelist"))
        assertEquals("webp_pipe", webp.pair("-f"))
    }

    @Test
    fun `인코딩 기본값은 소리 없음 · libx264 crf 27 · veryfast · yuv420p · 짝수 크기 · faststart 이고 설정으로 바뀐다`() {
        val args = AnimatedPlan.args("/usr/bin/ffmpeg", "/work/in", AnimatedKind.GIF, props, 1000)
        assertEquals("/usr/bin/ffmpeg", args.first())
        assertTrue("-an" in args)
        assertEquals("libx264", args.pair("-c:v"))
        assertEquals("27", args.pair("-crf"))
        assertEquals("veryfast", args.pair("-preset"))
        assertEquals("yuv420p", args.pair("-pix_fmt"))
        assertEquals("+faststart", args.pair("-movflags"))
        assertEquals("passthrough", args.pair("-fps_mode"))
        assertTrue(args.pair("-filter_complex").contains("crop=trunc(iw/2)*2:trunc(ih/2)*2"))

        val tuned = AnimatedPlan.args("ffmpeg", "/work/in", AnimatedKind.GIF, FfmpegProperties(crf = 23, preset = "medium"), 1000)
        assertEquals("23", tuned.pair("-crf"))
        assertEquals("medium", tuned.pair("-preset"))
    }

    @Test
    fun `두 출력 모두 원본 크기에서 쓰기를 멈춘다`() {
        val args = AnimatedPlan.args("ffmpeg", "/work/in", AnimatedKind.GIF, props, 4321)
        assertEquals(listOf("4321", "4321"), args.withIndex().filter { it.value == "-fs" }.map { args[it.index + 1] })
        assertEquals(AnimatedPlan.MP4, args[args.indexOf(AnimatedPlan.MP4)])
        assertTrue(args.indexOf("-fs") < args.indexOf(AnimatedPlan.MP4) && args.lastIndexOf("-fs") < args.indexOf(AnimatedPlan.WEBP))
    }

    @Test
    fun `메타데이터 제거 · 프레임 시간 유지는 출력마다 걸린다 - 첫 출력 앞에만 두면 mp4 · webp 에 안 걸린다`() {
        val args = AnimatedPlan.args("ffmpeg", "/work/in", AnimatedKind.GIF, props, 1000)
        val mp4 = args.subList(args.indexOf("[mp4v]"), args.indexOf(AnimatedPlan.MP4))
        val webp = args.subList(args.indexOf("[webpv]"), args.indexOf(AnimatedPlan.WEBP))
        listOf(mp4, webp).forEach {
            assertEquals("-1", it.pair("-map_metadata"))
            assertEquals("passthrough", it.pair("-fps_mode"))
        }
        assertTrue("-an" in mp4)
    }

    @Test
    fun `스레드 수는 설정 값으로 디코더 · 필터 · 두 인코더에 걸린다`() {
        val args = AnimatedPlan.args("ffmpeg", "/work/in", AnimatedKind.GIF, FfmpegProperties(threads = 1), 1000)
        assertEquals("1", args.pair("-filter_threads"))
        assertEquals("1", args.pair("-filter_complex_threads"))
        assertEquals(3, args.withIndex().count { it.value == "-threads" && args[it.index + 1] == "1" })
        assertEquals(2, FfmpegProperties().threads)
    }

    @Test
    fun `알파 통계는 프레임마다 한 줄이고 최솟값을 쓴다 - 줄이 없으면 불투명`() {
        val stats = AnimatedPlan.parseAlpha("frame:0 pts:0\nlavfi.signalstats.YMIN=255\nframe:1 pts:1\nlavfi.signalstats.YMIN=0\nlavfi.signalstats.YMIN=255\n")
        assertEquals(AnimatedPlan.AlphaStats(frames = 3, minAlpha = 0), stats)
        assertEquals(AnimatedPlan.AlphaStats(frames = 0, minAlpha = 255), AnimatedPlan.parseAlpha(""))
    }

    @Test
    fun `설정 값이 말이 안 되면 기동 전에 거절한다`() {
        assertEquals(listOf(true), listOf(runCatching { FfmpegProperties(crf = 60) }.isFailure))
        assertTrue(runCatching { FfmpegProperties(timeout = java.time.Duration.ZERO) }.isFailure)
        assertTrue(runCatching { FfmpegProperties(maxSourceBytes = 0) }.isFailure)
        assertTrue(runCatching { FfmpegProperties(path = " ") }.isFailure)
        assertTrue(runCatching { FfmpegProperties(preset = "veryfast; rm") }.isFailure)
        assertTrue(runCatching { FfmpegProperties(maxAttempts = 0) }.isFailure)
        assertTrue(runCatching { FfmpegProperties(maxPixels = 0) }.isFailure)
        assertTrue(runCatching { FfmpegProperties(threads = 0) }.isFailure)
    }
}
