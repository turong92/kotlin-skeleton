package dev.sumin.skeleton.videoworker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ffprobe JSON → 판정 (가장자리 사례 · 거절 · 호환 조건) */
class ProbeTest {
    private fun video(
        codec: String = "h264", profile: String = "High", w: Int = 1920, h: Int = 1080, pix: String = "yuv420p", level: Int = 40,
        tag: String = "avc1", rotation: Int? = null, fps: String = "30/1", transfer: String = "bt709",
    ): String {
        val side = rotation?.let { ""","side_data_list":[{"side_data_type":"Display Matrix","rotation":$it}]""" }.orEmpty()
        return """{"index":0,"codec_type":"video","codec_name":"$codec","profile":"$profile","codec_tag_string":"$tag","width":$w,"height":$h,
            "pix_fmt":"$pix","level":$level,"avg_frame_rate":"$fps","r_frame_rate":"$fps","color_transfer":"$transfer"$side}"""
    }

    private fun audio(codec: String = "aac") = """{"index":1,"codec_type":"audio","codec_name":"$codec","channels":2}"""

    private fun probe(streams: List<String>, durationSec: Double = 5.0, bitrate: Long = 5_000_000, format: String = "mov,mp4,m4a,3gp,3g2,mj2") =
        Probe.parse("""{"streams":[${streams.joinToString(",")}],"format":{"format_name":"$format","duration":"$durationSec","bit_rate":"$bitrate"}}""")

    @Test
    fun `H264 1080p AAC 는 호환 조건 안 — 호환판을 건너뛴다`() {
        val info = probe(listOf(video(), audio()))
        assertNull(Probe.rejection(info))
        assertTrue(info.compatible)
        assertEquals("avc1.640028", info.codecString)
        assertEquals(1920 to 1080, info.displayWidth to info.displayHeight)
        assertEquals(5_000L, info.durationMs)
        assertEquals("aac", info.audioCodec)
    }

    @Test
    fun `호환 조건 경계 — 1081p · 21Mbps · 10bit · HEVC · 오디오 PCM 은 아니다`() {
        assertTrue(probe(listOf(video(w = 1920, h = 1080)), bitrate = 20_000_000).compatible)
        assertFalse(probe(listOf(video(w = 1920, h = 1081)), bitrate = 5_000_000).compatible, "1081p")
        assertFalse(probe(listOf(video()), bitrate = 20_000_001).compatible, "20Mbps 초과")
        assertFalse(probe(listOf(video(pix = "yuv420p10le", profile = "High 10"))).compatible, "10bit")
        assertFalse(probe(listOf(video(codec = "hevc", profile = "Main", tag = "hvc1"))).compatible, "HEVC")
        assertFalse(probe(listOf(video(), audio("pcm_s16le"))).compatible, "PCM 오디오")
        assertTrue(probe(listOf(video(), audio("mp3"))).compatible.not(), "AAC 아니면 호환판 대상")
        assertTrue(probe(listOf(video())).compatible, "오디오 없는 영상도 괜찮다")
        assertFalse(probe(listOf(video(w = 3840, h = 2160))).compatible, "4K")
    }

    @Test
    fun `세로 영상은 회전을 적용한 화면 크기로 — 1080x1920 세로는 호환 범위`() {
        val info = probe(listOf(video(w = 1920, h = 1080, rotation = -90)))
        assertEquals(1080 to 1920, info.displayWidth to info.displayHeight)
        assertEquals(-90, info.rotation)
        assertTrue(info.compatible)
        assertFalse(probe(listOf(video(w = 2160, h = 3840, rotation = 0))).compatible)
    }

    @Test
    fun `HEVC 코덱 문자열 — Main 은 hvc1 1 6 · Main10 은 2 4`() {
        assertEquals("hvc1.1.6.L153.B0", probe(listOf(video(codec = "hevc", profile = "Main", level = 153, tag = "hev1"))).codecString)
        assertEquals("hvc1.2.4.L153.B0", probe(listOf(video(codec = "hevc", profile = "Main 10", level = 153, tag = "hvc1", pix = "yuv420p10le"))).codecString)
        assertTrue(probe(listOf(video(codec = "hevc", profile = "Main", tag = "hev1"))).needsHvc1Tag)
    }

    @Test
    fun `허용 밖 코덱 · 영상 없음 · 모르는 컨테이너는 UNSUPPORTED_FORMAT`() {
        for (codec in listOf("vp9", "av1", "prores", "mpeg4")) {
            assertEquals("UNSUPPORTED_FORMAT", Probe.rejection(probe(listOf(video(codec = codec))))?.code, codec)
        }
        assertEquals("UNSUPPORTED_FORMAT", Probe.rejection(probe(listOf(audio())))?.code)
        assertEquals("UNSUPPORTED_FORMAT", Probe.rejection(probe(listOf(video()), format = "matroska,webm"))?.code)
    }

    @Test
    fun `기술 상한 — 긴 변 8192 · 면적 곱 프레임율 · 길이 6시간`() {
        assertNotNull(Probe.rejection(probe(listOf(video(w = 8200, h = 1000)))), "긴 변")
        assertEquals("LIMITS_EXCEEDED", Probe.rejection(probe(listOf(video(w = 7680, h = 4320, fps = "60/1"))))?.code, "면적 × 프레임율")
        assertNull(Probe.rejection(probe(listOf(video(w = 7680, h = 4320, fps = "30/1", codec = "hevc", profile = "Main", tag = "hvc1")))))
        assertEquals("LIMITS_EXCEEDED", Probe.rejection(probe(listOf(video()), durationSec = 6 * 3600.0 + 1))?.code, "6시간")
        assertNull(Probe.rejection(probe(listOf(video()), durationSec = 6 * 3600.0)))
    }

    @Test
    fun `스트림 수 — 영상 첫 번째 · 오디오 첫 번째만 싼다는 걸 알린다`() {
        val info = probe(listOf(video(), audio(), audio("aac"), """{"index":3,"codec_type":"data","codec_name":"bin_data"}"""))
        assertEquals(1, info.videoStreams)
        assertEquals(2, info.audioStreams)
        assertEquals(1, info.otherStreams)
    }

    @Test
    fun `표지 그림 스트림 번호를 알려 준다 — 주 영상으로 세지 않는다 · 없으면 null`() {
        val cover = """{"index":3,"codec_type":"video","codec_name":"mjpeg","width":96,"height":96,"disposition":{"attached_pic":1}}"""
        val withCover = probe(listOf(video(), audio(), cover))
        assertEquals(3, withCover.coverStream)
        assertEquals(1, withCover.videoStreams)
        assertEquals("h264", withCover.videoCodec)
        assertNull(probe(listOf(video(), audio())).coverStream)
    }

    @Test
    fun `프레임율은 평균 프레임율 — VFR 도 0 이 아니게`() {
        assertEquals(29.97, probe(listOf(video(fps = "30000/1001"))).fps!!, 0.01)
        assertNull(probe(listOf(video(fps = "0/0"))).fps)
    }

    @Test
    fun `HDR 전달 특성은 톤매핑 대상으로 안다`() {
        assertTrue(probe(listOf(video(transfer = "smpte2084", codec = "hevc", profile = "Main 10", tag = "hvc1", pix = "yuv420p10le"))).hdr)
        assertTrue(probe(listOf(video(transfer = "arib-std-b67", codec = "hevc", profile = "Main 10", tag = "hvc1", pix = "yuv420p10le"))).hdr)
        assertFalse(probe(listOf(video())).hdr)
    }
}
