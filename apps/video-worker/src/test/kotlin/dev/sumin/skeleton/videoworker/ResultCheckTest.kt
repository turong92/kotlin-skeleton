package dev.sumin.skeleton.videoworker

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 호환판 · 사다리 결과 검증 — 순수 함수 (올리기 전에 어긋난 결과를 막는다) */
class ResultCheckTest {
    private fun info(
        codec: String = "h264", w: Int = 1920, h: Int = 1080, audio: String? = "aac", durationMs: Long = 10_000, videoStreams: Int = 1,
        audioStreams: Int = if (audio == null) 0 else 1,
    ) = MediaInfo(
        container = "mov,mp4,m4a,3gp,3g2,mj2", videoCodec = codec, profile = "High", pixFmt = "yuv420p", level = 40, codecTag = "avc1",
        width = w, height = h, rotation = null, fps = 30.0, durationMs = durationMs, bitrate = 5_000_000, audioCodec = audio,
        videoStreams = videoStreams, audioStreams = audioStreams, otherStreams = 0, hdr = false,
    )

    private val source = info(codec = "hevc", w = 3840, h = 2160, durationMs = 60_000)

    @Test
    fun `호환판 — 모양 · 길이 · 소리가 맞으면 통과`() {
        assertNull(ResultCheck.compat(source, info(w = 1280, h = 720, durationMs = 60_100)))
    }

    @Test
    fun `호환판 — 길이가 허용 오차(2% 또는 0,5초)를 넘게 다르면 거절`() {
        assertNotNull(ResultCheck.compat(source, info(w = 1280, h = 720, durationMs = 50_000)))
        assertNotNull(ResultCheck.compat(source, info(w = 1280, h = 720, durationMs = 61_300)))
        assertNull(ResultCheck.compat(source, info(w = 1280, h = 720, durationMs = 61_100)))
    }

    @Test
    fun `호환판 — 원본에 소리가 있는데 결과에 없으면(또는 반대) 거절`() {
        assertNotNull(ResultCheck.compat(source, info(w = 1280, h = 720, durationMs = 60_000, audio = null)))
        assertNotNull(ResultCheck.compat(info(audio = null, durationMs = 60_000), info(w = 1280, h = 720, durationMs = 60_000)))
        assertNull(ResultCheck.compat(info(audio = null, durationMs = 60_000), info(w = 1280, h = 720, durationMs = 60_000, audio = null)))
    }

    @Test
    fun `호환판 — 영상 트랙이 없거나 코덱 · 크기가 틀리면 거절`() {
        assertNotNull(ResultCheck.compat(source, info(w = 1280, h = 720, durationMs = 60_000, videoStreams = 0)))
        assertNotNull(ResultCheck.compat(source, info(codec = "hevc", w = 1280, h = 720, durationMs = 60_000)))
        assertNotNull(ResultCheck.compat(source, info(w = 1920, h = 1080, durationMs = 60_000)))
    }

    @Test
    fun `사다리 판 — 짧은 변 · 길이 · 영상 소리 트랙이 맞아야 한다`() {
        val rung = Rung(720, 2800)
        assertNull(ResultCheck.ladderRung(source, rung, info(w = 1280, h = 720, durationMs = 60_000)))
        assertNotNull(ResultCheck.ladderRung(source, rung, info(w = 854, h = 480, durationMs = 60_000)), "짧은 변이 판 높이와 다르다")
        assertNotNull(ResultCheck.ladderRung(source, rung, info(w = 1280, h = 720, durationMs = 30_000)), "길이가 반토막")
        assertNotNull(ResultCheck.ladderRung(source, rung, info(w = 1280, h = 720, durationMs = 60_000, audio = null)), "소리가 빠졌다")
        assertNotNull(ResultCheck.ladderRung(source, rung, info(w = 1280, h = 720, durationMs = 60_000, videoStreams = 0)), "영상이 없다")
    }

    @Test
    fun `사다리 목록 — 조각 수가 길이 ÷ 6초 와 맞고 모든 조각 파일이 있어야 한다`() {
        fun playlist(n: Int) = "#EXTM3U\n#EXT-X-MAP:URI=\"init_0.mp4\"\n" + (0 until n).joinToString("") { "#EXTINF:6.0,\nv0_%03d.m4s\n".format(it) } + "#EXT-X-ENDLIST\n"
        val files = (0 until 10).map { "v0_%03d.m4s".format(it) }.toSet() + "init_0.mp4"
        assertNull(ResultCheck.ladderPlaylist(playlist(10), 60_000, files))
        assertNotNull(ResultCheck.ladderPlaylist(playlist(4), 60_000, files), "조각이 모자라다")
        assertNotNull(ResultCheck.ladderPlaylist(playlist(10), 60_000, files - "v0_004.m4s"), "조각 파일이 없다")
        assertNotNull(ResultCheck.ladderPlaylist(playlist(10).replace("#EXT-X-ENDLIST\n", ""), 60_000, files), "끝 표시가 없다")
    }

    @Test
    fun `사다리 마스터 — 판 수만큼 STREAM-INF 가 있어야 한다`() {
        val master = "#EXTM3U\n" + "#EXT-X-STREAM-INF:BANDWIDTH=1\nv0.m3u8\n".repeat(3)
        assertNull(ResultCheck.ladderMaster(master, 3))
        assertTrue(ResultCheck.ladderMaster(master, 2) != null)
    }
}
