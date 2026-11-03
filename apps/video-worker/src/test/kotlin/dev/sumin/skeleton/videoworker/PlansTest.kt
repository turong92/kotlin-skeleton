package dev.sumin.skeleton.videoworker

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ffmpeg 명령 조립 — 순수 함수 */
class PlansTest {
    private fun info(
        codec: String = "h264", w: Int = 1920, h: Int = 1080, audio: String? = "aac", hdr: Boolean = false, rotation: Int? = null,
    ) = MediaInfo(
        container = "mov,mp4,m4a,3gp,3g2,mj2", videoCodec = codec, profile = "High", pixFmt = "yuv420p", level = 40, codecTag = if (codec == "hevc") "hev1" else "avc1",
        width = w, height = h, rotation = rotation, fps = 30.0, durationMs = 5_000, bitrate = 5_000_000, audioCodec = audio,
        videoStreams = 1, audioStreams = if (audio == null) 0 else 1, otherStreams = 0, hdr = hdr,
    )

    private fun List<String>.pair(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    @Test
    fun `읽기 허용 목록 — 올린 파일은 mov 묶음 컨테이너와 h264 · hevc · 소리 코덱만, 우리가 만든 조각은 h264 · hevc · aac 만`() {
        val remux = RemuxPlan.args("/work/in", info())
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", remux.pair("-format_whitelist"))
        val codecs = remux.pair("-codec_whitelist")!!.split(',')
        assertTrue(codecs.containsAll(listOf("h264", "hevc", "aac", "mp3", "alac", "pcm_s16le", "ac3", "eac3", "opus", "mjpeg", "png")))
        assertFalse(codecs.any { it in setOf("mpeg4", "vp9", "av1", "prores", "sanm", "smush_video") }, "목록 밖: $codecs")
        assertTrue(remux.indexOf("-codec_whitelist") < remux.indexOf("-i"), "입력 옵션이라 -i 앞에 있어야 걸린다")
        assertEquals(remux.pair("-codec_whitelist"), PosterPlan.args("/work/in", "/work/p.jpg", 5_000).pair("-codec_whitelist"))
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", PosterPlan.args("/work/in", "/work/p.jpg", 5_000).pair("-format_whitelist"))
        val built = "h264,hevc,aac"
        assertEquals(built, CompatPlan.args("http://x/index.m3u8", info(), null).pair("-codec_whitelist"))
        assertEquals(built, LadderPlan.args("http://x/index.m3u8", info(w = 3840, h = 2160), null).pair("-codec_whitelist"))
        assertNull(CompatPlan.args("http://x/index.m3u8", info(), null).pair("-format_whitelist"), "조각 읽기는 hls 로 강제 — mov 묶음 목록을 걸지 않는다")
    }

    @Test
    fun `전 프레임 디코드 — 올린 파일과 같은 격리와 허용 목록 · 오류가 나면 바로 멈춘다 · 쓰는 두 스트림만 푼다`() {
        val args = DecodeCheckPlan.args("/work/in")
        assertEquals("file,pipe", args.pair("-protocol_whitelist"))
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", args.pair("-format_whitelist"))
        assertEquals(RemuxPlan.args("/work/in", info()).pair("-codec_whitelist"), args.pair("-codec_whitelist"))
        assertEquals("mov", args.pair("-f"))
        assertTrue(args.indexOf("-codec_whitelist") < args.indexOf("-i"), "입력 옵션이라 -i 앞에 있어야 걸린다")
        assertContains(args, "-xerror")
        assertEquals(listOf("0:V:0", "0:a:0?"), args.filterIndexed { i, _ -> i > 0 && args[i - 1] == "-map" })
        assertEquals("null", args[args.lastIndexOf("-f") + 1])
        assertEquals("-", args.last())
    }

    @Test
    fun `표지 그림 포스터 — 영상 프레임이 아니라 그 스트림 한 장 · 같은 허용 목록 · 시각 이동 없음`() {
        val cover = PosterPlan.args("/work/in", "/work/p.jpg", durationMs = 5_000, coverStream = 2)
        assertEquals("0:2", cover.filterIndexed { i, _ -> i > 0 && cover[i - 1] == "-map" }.single())
        assertNull(cover.pair("-ss"), "표지 그림은 시각이 없다")
        assertEquals("1", cover.pair("-frames:v"))
        assertEquals(PosterPlan.args("/work/in", "/work/p.jpg", 5_000).pair("-codec_whitelist"), cover.pair("-codec_whitelist"))
        assertEquals("file,pipe", cover.pair("-protocol_whitelist"))
        val frame = PosterPlan.args("/work/in", "/work/p.jpg", 5_000)
        assertEquals("0:V:0", frame[frame.indexOf("-map") + 1], "표지가 없으면 지금처럼 표지를 뺀 첫 영상 스트림")
    }

    @Test
    fun `remux — 영상 · 오디오 첫 스트림만 · 메타데이터와 챕터 제거 · 복사 · bitexact · fMP4 HLS 조각`() {
        val args = RemuxPlan.args("/work/in", info())
        assertEquals("ffmpeg", args.first())
        assertEquals("mov", args.pair("-f"), "입력 형식 강제 — 확장자를 안 믿는다")
        assertEquals("file,pipe", args.pair("-protocol_whitelist"))
        assertEquals("0:V:0", args.filterIndexed { i, _ -> i > 0 && args[i - 1] == "-map" }.first())
        assertContains(args, "0:a:0?")
        assertEquals("-1", args.pair("-map_metadata"))
        assertEquals("-1", args.pair("-map_chapters"))
        assertEquals("copy", args.pair("-c"))
        assertEquals("+bitexact", args.pair("-fflags"))
        assertNull(args.pair("-tag:v"), "H.264 는 표지를 건드리지 않는다")
        assertEquals("/work/in", args[args.indexOf("-i") + 1])
        assertContains(args, "-nostdin")
        assertEquals("hls", args[args.lastIndexOf("-f") + 1], "출력은 HLS 조각 — 단일 mp4 가 아니다")
        assertEquals("fmp4", args.pair("-hls_segment_type"))
        assertEquals("init.mp4", args.pair("-hls_fmp4_init_filename"))
        assertEquals("s%05d.m4s", args.pair("-hls_segment_filename"))
        assertEquals("index.m3u8", args.last())
        assertNull(args.pair("-movflags"), "faststart 단일 mp4 의 흔적이 없다")
        assertEquals("vod", args.pair("-hls_playlist_type"))
        assertEquals("0", args.pair("-hls_list_size"))
    }

    @Test
    fun `remux — HEVC 는 hvc1 표지 · AAC 가 아닌 오디오(PCM · MP3)만 AAC 로`() {
        assertEquals("hvc1", RemuxPlan.args("i", info(codec = "hevc")).pair("-tag:v"))
        val pcm = RemuxPlan.args("i", info(audio = "pcm_s16le"))
        assertEquals("aac", pcm.pair("-c:a"))
        assertEquals("copy", pcm.pair("-c:v"))
        assertNull(RemuxPlan.args("i", info(audio = "aac")).pair("-c:a"))
        assertEquals("aac", RemuxPlan.args("i", info(audio = "mp3")).pair("-c:a"), "fMP4 HLS 의 오디오 목록에 MP3 가 없어 AAC 로")
        assertNull(RemuxPlan.args("i", info(audio = null)).pair("-c:a"))
    }

    @Test
    fun `호환 단 — H264 High 720p 2,8Mbps AAC 128k 6초 fMP4 HLS 한 판 메타데이터 제거`() {
        val args = CompatPlan.args("https://x/o-0123456789abcdef/index.m3u8", info(w = 3840, h = 2160, codec = "hevc"), headers = null)
        assertEquals("libx264", args.pair("-c:v"))
        assertEquals("high", args.pair("-profile:v"))
        assertEquals("veryfast", args.pair("-preset"))
        assertEquals("2800k", args.pair("-b:v"))
        assertEquals("aac", args.pair("-c:a"))
        assertEquals("128k", args.pair("-b:a"))
        assertEquals("-1", args.pair("-map_metadata"))
        assertEquals("yuv420p", args.pair("-pix_fmt"))
        assertEquals("file,http,https,tcp,tls,crypto", args.pair("-protocol_whitelist"))
        assertEquals("hls", args.pair("-f"), "입력 형식 강제 — 원본 단은 HLS 만")
        assertTrue(args.pair("-vf")!!.contains("720"), "짧은 변 720")
        assertEquals("expr:gte(t,n_forced*6)", args.pair("-force_key_frames"))
        assertEquals("index.m3u8", args.last())
        assertNull(args.pair("-movflags"))
    }

    @Test
    fun `호환 단 — 입력이 http 가 아니면 허용 목록에서 http 를 뺀다 · 숨은 Host 헤더는 입력 앞에`() {
        val local = CompatPlan.args("/work/in/index.m3u8", info(), headers = null)
        assertEquals("file,pipe", local.pair("-protocol_whitelist"))
        val hosted = CompatPlan.args("http://s3:8333/x/index.m3u8", info(), headers = "Host: a.b:3902\r\n")
        assertTrue(hosted.indexOf("-headers") < hosted.indexOf("-i"))
        assertEquals("Host: a.b:3902\r\n", hosted.pair("-headers"))
    }

    @Test
    fun `호환판 — HDR 이면 톤매핑 체인이 들어간다`() {
        val sdr = CompatPlan.args("i", info(), null).pair("-vf")!!
        assertFalse(sdr.contains("tonemap"))
        val hdr = CompatPlan.args("i", info(codec = "hevc", hdr = true), null).pair("-vf")!!
        assertTrue(hdr.contains("tonemap") && hdr.contains("zscale"))
    }

    @Test
    fun `사다리 판 선택 — 짧은 변보다 작은 판만 (4K 3판 · 1080p 2판 · 720p 1판 · 480p 없음 · 세로는 짧은 변)`() {
        assertEquals(listOf(1080, 720, 480), LadderPlan.rungs(info(w = 3840, h = 2160)).map { it.height })
        assertEquals(listOf(720, 480), LadderPlan.rungs(info(w = 1920, h = 1080)).map { it.height })
        assertEquals(listOf(480), LadderPlan.rungs(info(w = 1280, h = 720)).map { it.height })
        assertEquals(emptyList(), LadderPlan.rungs(info(w = 854, h = 480)))
        assertEquals(listOf(720, 480), LadderPlan.rungs(info(w = 1080, h = 1920)).map { it.height }, "세로 1080x1920 은 짧은 변 1080")
        assertEquals(listOf(1080, 720, 480), LadderPlan.rungs(info(w = 3840, h = 2160, rotation = 90)).map { it.height }, "회전해도 짧은 변은 2160")
    }

    @Test
    fun `사다리 명령 — 6초 키프레임 · fMP4 HLS · 마스터 목록 · 판마다 오디오 한 줄`() {
        val args = LadderPlan.args("http://s3/o.mp4", info(w = 1920, h = 1080), headers = null)
        assertEquals("libx264", args.pair("-c:v"))
        assertEquals("expr:gte(t,n_forced*6)", args.pair("-force_key_frames"))
        assertEquals("0", args.pair("-sc_threshold"))
        assertEquals("hls", args[args.lastIndexOf("-f") + 1], "출력 형식")
        assertEquals("hls", args.pair("-f"), "입력 형식 강제")
        assertEquals("6", args.pair("-hls_time"))
        assertEquals("fmp4", args.pair("-hls_segment_type"))
        assertEquals("master.m3u8", args.pair("-master_pl_name"))
        assertEquals("v:0,a:0 v:1,a:1", args.pair("-var_stream_map"))
        assertEquals("v%v.m3u8", args.last())
        assertTrue(args.pair("-filter_complex")!!.contains("split=2"))
    }

    @Test
    fun `사다리 명령 — 판이 하나여도 init 파일 이름에 %v 를 남기지 않는다`() {
        val one = LadderPlan.args("i", info(w = 1280, h = 720), null)
        assertEquals("init_0.mp4", one.pair("-hls_fmp4_init_filename"))
        assertEquals("init_%v.mp4", LadderPlan.args("i", info(w = 1920, h = 1080), null).pair("-hls_fmp4_init_filename"))
    }

    @Test
    fun `사다리 명령 — 오디오가 없으면 영상만 묶는다 · HDR 은 SDR 로`() {
        val args = LadderPlan.args("i", info(audio = null), null)
        assertEquals("v:0 v:1", args.pair("-var_stream_map"))
        assertFalse(args.contains("-c:a"))
        assertTrue(LadderPlan.args("i", info(codec = "hevc", hdr = true), null).pair("-filter_complex")!!.contains("tonemap"))
    }

    @Test
    fun `포스터 — 1초 지점(너무 짧으면 0초) 한 장 긴 변 1280 이하`() {
        val args = PosterPlan.args("/work/in", "/work/p.jpg", durationMs = 5_000)
        assertEquals("1.000", args.pair("-ss"))
        assertEquals("1", args.pair("-frames:v"))
        assertTrue(args.pair("-vf")!!.contains("1280"))
        assertEquals("0.000", PosterPlan.args("i", "o", durationMs = 1_000).pair("-ss"))
    }

    @Test
    fun `마스터에 원본 단 — 1080 이하만 자동에 끼운다 · CODECS 를 직접 쓴다 · 상대 주소 · 세로는 화면 크기`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.64001f,mp4a.40.2\"\nv0.m3u8\n"
        assertTrue(OriginalRung.eligible(info(w = 1920, h = 1080)))
        assertFalse(OriginalRung.eligible(info(w = 3840, h = 2160, codec = "hevc")), "4K 원본은 자동 밖 — 대역 폭주를 막는다")
        assertTrue(OriginalRung.eligible(info(w = 1920, h = 1080, rotation = 90)), "회전해도 짧은 변은 1080")
        val patched = OriginalRung.patch(master, "o-0123456789abcdef", info(w = 1920, h = 1080, rotation = 90), "avc1.640028", 12_000_000)
        assertTrue(patched.endsWith("../o-0123456789abcdef/index.m3u8\n"), patched)
        assertContains(patched, "RESOLUTION=1080x1920")
        assertContains(patched, "CODECS=\"avc1.640028,mp4a.40.2\"")
        assertContains(patched, "AVERAGE-BANDWIDTH=19200000")
        val silent = OriginalRung.patch(master, "o-0123456789abcdef", info(audio = null), null, 1_000_000)
        assertContains(silent, "CODECS=\"avc1.")
        assertFalse(silent.substringAfter("v0.m3u8").contains("mp4a"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { OriginalRung.patch(master, "../x", info(), null, 1) }
    }
}
