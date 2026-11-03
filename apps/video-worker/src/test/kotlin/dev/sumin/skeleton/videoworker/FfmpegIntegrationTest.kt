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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.json.JsonMapper

/**
 * 실제 ffmpeg 로 돈다 — 호스트에 ffmpeg · ffprobe 가 있을 때만(없으면 건너뜀). 영상은 모두 lavfi 로 합성한 것이다.
 * 가짜 서버(FakeVideoServer)가 일꾼 API · 저장소 노릇을 해 claim → 처리 → 올리기 → complete 한 바퀴를 본다
 */
class FfmpegIntegrationTest {
    private val json = JsonMapper.builder().build()
    private lateinit var dir: Path
    private lateinit var server: FakeVideoServer
    private lateinit var runner: Runner
    private val tools = Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5))

    @BeforeTest
    fun setUp() {
        assumeTrue(Synth.available, "ffmpeg · ffprobe 가 없어 건너뜀")
        dir = Synth.tempDir()
        server = FakeVideoServer()
        val config = WorkerConfig(apiBase = server.base, token = server.token, workerId = "test-1", workDir = dir.resolve("work"), heartbeatInterval = Duration.ofMillis(200))
        runner = Runner(config, WorkerApi(config), Transfer(null), tools)
    }

    @AfterTest
    fun tearDown() {
        if (this::server.isInitialized) server.close()
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun complete(jobId: String) = json.readTree(assertNotNull(server.completes[jobId], "complete 가 안 왔다: ${server.fails[jobId]}"))

    private fun remuxJob(id: String, file: Path, extra: Map<String, Any?> = emptyMap()) = server.job(id, "REMUX", server.serve(file.fileName.toString(), file), file.fileSize(), extra)

    /** 일꾼이 올린 조각 폴더를 작업 폴더에 되살린다 — `/store/<jobId>/<folder>/…` → `<dir>/<folder>/…` */
    private fun materialize(jobId: String, folder: String): Path {
        val out = Files.createDirectories(dir.resolve("m-$folder"))
        val prefix = "/store/$jobId/$folder/"
        server.stored.filterKeys { it.startsWith(prefix) }.forEach { (k, v) -> Files.write(out.resolve(k.removePrefix(prefix)), v) }
        return out
    }

    private fun folderOf(done: tools.jackson.databind.JsonNode, key: String) = done[key]["name"].asString().substringBefore('/')

    private fun storedNames(jobId: String, folder: String) =
        server.stored.keys.filter { it.startsWith("/store/$jobId/$folder/") }.map { it.removePrefix("/store/$jobId/$folder/") }.sorted()

    private fun probeFolder(folder: Path): MediaInfo = tools.probeHls(folder.resolve("index.m3u8").toString())

    private fun ffprobeText(vararg args: String): String =
        ProcessBuilder("ffprobe", "-v", "error", *args).start().inputStream.readAllBytes().toString(Charsets.UTF_8).trim()

    @Test
    fun `1080p H264 — 원본 단은 조각 폴더 · 호환이라 건너뜀 표시 · 위치 태그 없음 · 포스터 · 헤더 · 공개 파일 하나가 없다`() {
        val input = Synth.h264(dir)
        server.enqueue(remuxJob("job0000001", input))
        assertEquals(1, runner.runUntilIdle())
        val done = complete("job0000001")
        val sha = done["orig"]["sha256"].asString()
        val folder = folderOf(done, "orig")
        assertEquals("o-${sha.take(16)}", folder)
        assertEquals("o-${sha.take(16)}/index.m3u8", done["orig"]["name"].asString())
        assertTrue(done["compatible"].asBoolean(), "H.264 1080p AAC 는 호환 조건 안")
        assertEquals(1920, done["probe"]["width"].asInt())
        assertTrue(done["probe"]["codec"].asString().startsWith("avc1."))
        val names = storedNames("job0000001", folder)
        assertTrue(names.containsAll(listOf("index.m3u8", "init.mp4", "s00000.m4s")), names.toString())
        assertTrue(names.all { Regex("index\\.m3u8|init\\.mp4|s\\d{5}\\.m4s").matches(it) }, "서버 허용 이름만: $names")
        val total = names.sumOf { server.stored["/store/job0000001/$folder/$it"]!!.size.toLong() }
        assertEquals(done["orig"]["bytes"].asLong(), total, "bytes 는 폴더 합")
        assertEquals("public, max-age=31536000, immutable", server.storedHeaders["/store/job0000001/$folder/index.m3u8"]!!["cache-control"], "서명이 준 헤더를 그대로 보낸다")
        val posterName = done["poster"]["name"].asString()
        assertEquals(0xFF.toByte(), server.stored["/store/job0000001/$posterName"]!![0], "JPEG")
        assertTrue(done["poster"]["width"].asInt() <= 1280 && done["poster"]["width"].asInt() > 0)
        assertTrue(server.stored.keys.none { it.endsWith(".mp4") && !it.endsWith("/init.mp4") }, "공개 영상 한 덩어리 mp4 가 없다: ${server.stored.keys}")
        assertEquals(done["orig"]["sha256"].asString(), Transfer.sha256(names.map { materialize("job0000001", folder).resolve(it) }), "해시는 폴더 내용")
    }

    @Test
    fun `4K HEVC hev1 은 hvc1 로 조각을 내고 4K 그대로 둔다 — 호환은 아님`() {
        val input = Synth.hevc4k(dir)
        server.enqueue(remuxJob("job0000002", input))
        runner.runUntilIdle()
        val done = complete("job0000002")
        assertFalse(done["compatible"].asBoolean())
        assertEquals(3840, done["probe"]["width"].asInt())
        assertTrue(done["probe"]["codec"].asString().startsWith("hvc1."))
        val folder = materialize("job0000002", folderOf(done, "orig"))
        assertEquals("hvc1", ffprobeText("-select_streams", "v:0", "-show_entries", "stream=codec_tag_string", "-of", "csv=p=0", folder.resolve("init.mp4").toString()))
    }

    @Test
    fun `PCM MOV — 오디오만 AAC 로 · 위치와 기기 태그가 사라진다`() {
        val input = Synth.pcmMovWithLocation(dir)
        val before = ProcessBuilder("ffprobe", "-v", "error", "-show_format", "-show_streams", input.toString()).start().inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertTrue(before.contains("TestPhone") || before.contains("ISO6709"), "합성 입력에 위치 태그가 들어 있어야 시험이 의미 있다")
        server.enqueue(remuxJob("job0000003", input))
        runner.runUntilIdle()
        val done = complete("job0000003")
        assertEquals("aac", done["probe"]["audioCodec"].asString())
        val name = folderOf(done, "orig")
        val all = storedNames("job0000003", name).joinToString("\n") { String(server.stored["/store/job0000003/$name/$it"]!!, Charsets.ISO_8859_1) }
        assertFalse(listOf("ISO6709", "TestPhone", "com.apple", "location").any { all.contains(it) }, "위치 · 기기 태그가 남았다")
    }

    @Test
    fun `회전 MOV 세로 영상 — 회전 표지는 init 에 남고 화면 크기는 세로로 보고하고 목록 프로브도 회전을 읽는다`() {
        val rotated = Synth.rotated(dir, Synth.h264(dir, "base.mp4", 1280, 720, 2))
        server.enqueue(remuxJob("job0000004", rotated))
        runner.runUntilIdle()
        val done = complete("job0000004")
        assertEquals(720, done["probe"]["width"].asInt(), "회전을 적용한 가로")
        assertEquals(1280, done["probe"]["height"].asInt())
        assertNotNull(done["probe"]["rotation"])
        val folder = materialize("job0000004", folderOf(done, "orig"))
        assertTrue(ffprobeText("-show_entries", "stream_side_data=rotation", folder.resolve("init.mp4").toString()).contains("rotation="), "init 에 회전 표지")
        val made = probeFolder(folder)
        assertEquals(720 to 1280, made.displayWidth to made.displayHeight)
    }

    @Test
    fun `가변 프레임율 VFR — 길이 · 소리가 유지되고 원본 단은 정상 조각으로 나온다`() {
        val input = Synth.vfr(dir)
        val source = tools.probe(input.toString(), null)
        server.enqueue(remuxJob("job0000010", input))
        runner.runUntilIdle()
        val done = complete("job0000010")
        val made = probeFolder(materialize("job0000010", folderOf(done, "orig")))
        assertEquals(1, made.videoStreams)
        assertEquals("aac", made.audioCodec)
        assertTrue(kotlin.math.abs(made.durationMs - source.durationMs) <= ResultCheck.durationTolerance(source.durationMs), "${made.durationMs} vs ${source.durationMs}")
    }

    @Test
    fun `조각난 MP4 fMP4 입력 — 조각 폴더로 다시 싸고 길이가 맞다`() {
        val input = Synth.fragmented(dir)
        val source = tools.probe(input.toString(), null)
        server.enqueue(remuxJob("job0000011", input))
        runner.runUntilIdle()
        val done = complete("job0000011")
        val made = probeFolder(materialize("job0000011", folderOf(done, "orig")))
        assertTrue(kotlin.math.abs(made.durationMs - source.durationMs) <= ResultCheck.durationTolerance(source.durationMs))
    }

    @Test
    fun `키프레임 간격이 긴 영상 — 조각이 6초보다 길어도 받아들이고 조각 길이는 키프레임 간격이다`() {
        val input = dir.resolve("longgop.mp4")
        Synth.ffmpeg("-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=20", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "300", "-keyint_min", "300", "-sc_threshold", "0", input.toString())
        server.enqueue(remuxJob("job0000012", input))
        runner.runUntilIdle()
        val done = complete("job0000012")
        val folder = folderOf(done, "orig")
        val playlist = String(server.stored["/store/job0000012/$folder/index.m3u8"]!!)
        assertTrue(ResultCheck.longestSegmentSeconds(playlist) >= 9.9, "GOP 10초 → 조각 10초: $playlist")
    }

    @Test
    fun `웹엠 · 깨진 파일은 거절하고 fail 로 알린다 — 올리지 않는다`() {
        val webm = Synth.webm(dir)
        val garbage = dir.resolve("garbage.mp4").also { Files.write(it, ByteArray(4096) { i -> (i * 31).toByte() }) }
        server.enqueue(remuxJob("job0000005", webm))
        server.enqueue(remuxJob("job0000006", garbage))
        assertEquals(2, runner.runUntilIdle())
        assertEquals("UNSUPPORTED_FORMAT", json.readTree(server.fails["job0000005"])["code"].asString())
        assertEquals("CORRUPT", json.readTree(server.fails["job0000006"])["code"].asString())
        assertFalse(json.readTree(server.fails["job0000006"])["retryable"].asBoolean(), "깨진 파일은 다시 해도 같다")
        assertTrue(server.stored.isEmpty())
        assertNull(server.completes["job0000005"])
    }

    @Test
    fun `허용 목록 밖 — 마트로스카 · AVI · 허용 밖 영상 코덱은 UNSUPPORTED_FORMAT 으로 다시 시도 없이 끝난다`() {
        val src = "testsrc2=size=320x240:rate=30:duration=1"
        fun make(name: String, vararg args: String): Path = dir.resolve(name).also { Synth.ffmpeg("-f", "lavfi", "-i", src, *args, it.toString()) }
        val mkv = make("x.mkv", "-c:v", "libx264", "-pix_fmt", "yuv420p")
        val avi = make("x.avi", "-c:v", "mpeg4")
        val mpeg4InMp4 = make("mpeg4.mp4", "-c:v", "mpeg4")
        val cases = listOf("job0000021" to mkv, "job0000022" to avi, "job0000023" to mpeg4InMp4)
        cases.forEach { (id, file) -> server.enqueue(remuxJob(id, file)) }
        assertEquals(cases.size, runner.runUntilIdle())
        cases.forEach { (id, file) ->
            val fail = json.readTree(assertNotNull(server.fails[id], "${file.fileName} 이 거절되지 않았다"))
            assertEquals("UNSUPPORTED_FORMAT", fail["code"].asString(), "${file.fileName}: $fail")
            assertFalse(fail["retryable"].asBoolean(), "${file.fileName} 은 다시 해도 같다")
            assertNull(server.completes[id])
        }
        assertTrue(server.stored.isEmpty())
    }

    @Test
    fun `허용 목록 안 — ac3 · eac3 · opus 소리는 AAC 로 바꿔 통과한다`() {
        val names = listOf("ac3" to "ac3", "eac3" to "eac3", "libopus" to "opus")
        names.forEachIndexed { i, (encoder, name) ->
            val file = dir.resolve("$name.mp4")
            Synth.ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=2", "-f", "lavfi", "-i", "sine=duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", encoder, "-strict", "-2", "-shortest", file.toString())
            server.enqueue(remuxJob("job00000${30 + i}", file))
        }
        assertEquals(3, runner.runUntilIdle())
        names.indices.forEach { i -> assertEquals("aac", complete("job00000${30 + i}")["probe"]["audioCodec"].asString()) }
    }

    @Test
    fun `표지 그림이 든 MP4 — 통과하고 표지는 포스터가 되며 조각에는 영상 한 줄뿐이다`() {
        val cover = Synth.coverJpeg(dir)
        val mp4 = Synth.withCover(dir, cover)
        server.enqueue(remuxJob("job0000040", mp4))
        assertEquals(1, runner.runUntilIdle())
        val done = complete("job0000040")
        assertEquals(96 to 96, done["poster"]["width"].asInt() to done["poster"]["height"].asInt(), "포스터는 영상 프레임(320x240)이 아니라 표지 그림이다")
        assertEquals(0xFF.toByte(), server.stored["/store/job0000040/" + done["poster"]["name"].asString()]!![0], "JPEG")
        val info = probeFolder(materialize("job0000040", folderOf(done, "orig")))
        assertEquals("h264", info.videoCodec)
        assertEquals(1, info.videoStreams)
        assertEquals(0, info.otherStreams, "표지 트랙은 조각에 실리지 않는다")
    }

    @Test
    fun `허용 목록 안 — 타임코드 데이터 트랙이 든 MOV 와 24비트 PCM 소리도 통과한다`() {
        val mov = dir.resolve("tc.mov")
        Synth.ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30:duration=2", "-f", "lavfi", "-i", "sine=duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-c:a", "pcm_s24le", "-shortest", "-timecode", "01:00:00:00", mov.toString())
        server.enqueue(remuxJob("job0000025", mov))
        assertEquals(1, runner.runUntilIdle())
        complete("job0000025")
    }

    @Test
    fun `편집 앱식 파일 — 타임코드 · 표지 그림 · 가변 프레임율 · 자막 · 챕터가 든 MP4 도 검증 디코드를 켜고 통과한다`() {
        val editor = Synth.editorStyle(dir, Synth.coverJpeg(dir))
        server.enqueue(remuxJob("job0000041", editor, mapOf("verifyDecode" to true)))
        assertEquals(1, runner.runUntilIdle())
        val done = complete("job0000041")
        assertEquals(96, done["poster"]["width"].asInt(), "표지 그림이 포스터")
        val made = probeFolder(materialize("job0000041", folderOf(done, "orig")))
        assertEquals(1, made.videoStreams)
        assertEquals("aac", made.audioCodec)
        assertEquals(0, made.otherStreams, "타임코드 · 자막 · 표지는 조각에 안 실린다")
        assertNull(server.fails["job0000041"])
    }

    @Test
    fun `가변 프레임율 VFR 은 검증 디코드를 켜도 통과한다 — 타임스탬프 경고는 거절 사유가 아니다`() {
        server.enqueue(remuxJob("job0000042", Synth.vfr(dir), mapOf("verifyDecode" to true)))
        runner.runUntilIdle()
        complete("job0000042")
    }

    @Test
    fun `검증 디코드 — 영상 데이터가 망가진 파일은 CORRUPT 로 다시 시도 없이 거절하고 올리지 않는다`() {
        val damaged = Synth.damaged(dir, Synth.h264(dir, "good.mp4", 640, 360, 3))
        assertNotNull(tools.probe(damaged.toString(), null), "머리는 멀쩡해 프로브는 읽힌다")
        server.enqueue(remuxJob("job0000043", damaged, mapOf("verifyDecode" to true)))
        runner.runUntilIdle()
        val fail = json.readTree(assertNotNull(server.fails["job0000043"], "거절되지 않았다"))
        assertEquals("CORRUPT", fail["code"].asString())
        assertFalse(fail["retryable"].asBoolean())
        assertNull(server.completes["job0000043"])
        assertTrue(server.stored.isEmpty(), "공개하지 않는다")
    }

    @Test
    fun `검증 디코드를 끄면(verifyDecode false) 같은 파일을 디코드 없이 그대로 받는다`() {
        val damaged = Synth.damaged(dir, Synth.h264(dir, "good2.mp4", 640, 360, 3))
        server.enqueue(remuxJob("job0000044", damaged, mapOf("verifyDecode" to false)))
        runner.runUntilIdle()
        complete("job0000044")
    }

    /** 일꾼이 올린 원본 단을 서명 재생 목록처럼 내놓는다 — 목록 안의 init · 조각이 질의(서명)가 붙은 절대 주소 */
    private fun servedSigned(jobId: String, input: Path): Pair<String, String> {
        server.enqueue(remuxJob(jobId, input))
        runner.runUntilIdle()
        val folder = folderOf(complete(jobId), "orig")
        val tree = materialize(jobId, folder)
        val prefix = "${server.base}/store/in/$jobId-$folder/"
        val rewritten = Files.readAllLines(tree.resolve("index.m3u8")).joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-MAP:") -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"$prefix${it.groupValues[1]}?X-Amz-Signature=init\"" }
                line.isNotBlank() && !line.startsWith("#") -> "$prefix$line?X-Amz-Signature=seg"
                else -> line
            }
        } + "\n"
        Files.writeString(tree.resolve("index.m3u8"), rewritten)
        return folder to server.serveTree("$jobId-$folder", tree) + "?X-Amz-Signature=playlist"
    }

    @Test
    fun `비공개 입력 — 호환 · 사다리가 서명 재생 목록 주소(목록 · init · 조각 모두 질의 서명)를 따라가 읽는다`() {
        val (folder, url) = servedSigned("job0000050", Synth.hevc4k(dir, "sig.mp4", sec = 7))
        server.enqueue(server.job("job0000051", "COMPAT", url, 1, mapOf("origFolder" to folder)))
        server.enqueue(server.job("job0000052", "LADDER", url, 1, mapOf("build" to "0123456789abcdef0123456789abcdef", "origFolder" to folder)))
        assertEquals(2, runner.runUntilIdle())
        assertTrue(complete("job0000051")["compat"]["name"].asString().startsWith("c-"))
        assertEquals(listOf("1080", "720", "480"), complete("job0000052")["rungs"].toList().map { it["name"].asString() })
    }

    @Test
    fun `heartbeat 에 진행률이 실린다 — 줄지 않고 완료 전에는 99 를 넘지 않는다`() {
        val (folder, url) = servedOrig("job0000053", Synth.hevc4k(dir, "prog.mp4", sec = 10))
        server.enqueue(server.job("job0000054", "COMPAT", url, 1, mapOf("origFolder" to folder)))
        runner.runUntilIdle()
        complete("job0000054")
        val seen = server.progresses["job0000054"]?.toList().orEmpty()
        assertTrue(seen.isNotEmpty(), "진행률이 한 번도 안 갔다")
        assertEquals(seen.sorted(), seen, "줄어들지 않는다: $seen")
        assertTrue(seen.all { it in 0..99 }, seen.toString())
    }

    /** 일꾼이 올린 원본 단을 그대로 내놓는 입력 — 호환 · 사다리 일은 공개된 원본 단 주소를 읽는다 */
    private fun servedOrig(jobId: String, input: Path): Pair<String, String> {
        server.enqueue(remuxJob(jobId, input))
        runner.runUntilIdle()
        val folder = folderOf(complete(jobId), "orig")
        return folder to server.serveTree("$jobId-$folder", materialize(jobId, folder))
    }

    @Test
    fun `호환 단 차선 — 4K HEVC 원본 단을 주소로 읽어 H264 720p HLS 한 판을 만든다`() {
        val (folder, url) = servedOrig("job0000013", Synth.hevc4k(dir, "orig.mp4"))
        server.enqueue(server.job("job0000007", "COMPAT", url, 1, mapOf("origFolder" to folder)))
        runner.runUntilIdle()
        val done = complete("job0000007")
        val name = done["compat"]["name"].asString()
        assertTrue(Regex("c-[0-9a-f]{16}/index\\.m3u8").matches(name), name)
        val names = storedNames("job0000007", name.substringBefore('/'))
        assertTrue(names.containsAll(listOf("index.m3u8", "init.mp4")))
        assertTrue(server.stored.keys.none { it.startsWith("/store/job0000007/") && it.endsWith(".mp4") && !it.endsWith("/init.mp4") })
        val made = probeFolder(materialize("job0000007", name.substringBefore('/')))
        assertEquals("h264", made.videoCodec)
        assertEquals(720, made.shortSide)
        assertEquals(1280 to 720, made.displayWidth to made.displayHeight)
        assertEquals("aac", made.audioCodec)
        assertTrue(made.compatible)
    }

    @Test
    fun `호환 단 차선 — 세로(회전 표지) 원본 단은 구워서 세로 720 으로`() {
        val (folder, url) = servedOrig("job0000014", Synth.rotated(dir, Synth.h264(dir, "b.mp4", 1920, 1080, 2)))
        server.enqueue(server.job("job0000015", "COMPAT", url, 1, mapOf("origFolder" to folder)))
        runner.runUntilIdle()
        val done = complete("job0000015")
        val made = probeFolder(materialize("job0000015", done["compat"]["name"].asString().substringBefore('/')))
        assertEquals(720 to 1280, made.displayWidth to made.displayHeight)
    }

    @Test
    fun `사다리 차선 — 4K 는 1080 · 720 · 480 세 판과 마스터 목록, 마스터에 4K 원본 단은 안 끼운다`() {
        val (folder, url) = servedOrig("job0000016", Synth.hevc4k(dir, "orig.mp4", sec = 7))
        server.enqueue(server.job("job0000008", "LADDER", url, 1, mapOf("build" to "0123456789abcdef0123456789abcdef", "origFolder" to folder, "codec" to "hvc1.1.6.L153.B0")))
        runner.runUntilIdle()
        val done = complete("job0000008")
        assertEquals("0123456789abcdef0123456789abcdef", done["ladderBuild"].asString())
        assertEquals(listOf("1080", "720", "480"), done["rungs"].toList().map { it["name"].asString() })
        val files = server.stored.keys.filter { it.startsWith("/store/job0000008/") }.map { it.substringAfter("/store/job0000008/") }
        assertTrue(files.contains("h-0123456789abcdef0123456789abcdef/master.m3u8"), files.toString())
        assertTrue(files.count { it.endsWith(".m4s") } >= 6, "6초 조각 7초 영상 3판 — 판마다 조각 둘 이상: $files")
        assertTrue(files.any { it.endsWith("init_0.mp4") })
        val master = String(server.stored["/store/job0000008/h-0123456789abcdef0123456789abcdef/master.m3u8"]!!)
        assertEquals(3, Regex("#EXT-X-STREAM-INF").findAll(master).count(), master)
        assertFalse(master.contains("../o-"), "4K 원본 단은 자동(마스터)에 안 낀다")
        assertTrue(files.all { Regex("h-[0-9a-f]{32}/[A-Za-z0-9][A-Za-z0-9_-]*\\.(m3u8|m4s|mp4)").matches(it) }, "서버가 허용하는 이름만: $files")
    }

    @Test
    fun `사다리 차선 — 1080p 는 720 · 480 두 판에 원본 단이 마스터 맨 끝에 CODECS 와 함께 낀다`() {
        val (folder, url) = servedOrig("job0000017", Synth.h264(dir, "orig1080.mp4", 1920, 1080, 7))
        val origBytes = complete("job0000017")["orig"]["bytes"].asLong()
        server.enqueue(server.job("job0000018", "LADDER", url, origBytes, mapOf("build" to "0123456789abcdef0123456789abcdef", "origFolder" to folder, "codec" to "avc1.640028")))
        runner.runUntilIdle()
        val master = String(server.stored["/store/job0000018/h-0123456789abcdef0123456789abcdef/master.m3u8"]!!)
        assertEquals(3, Regex("#EXT-X-STREAM-INF").findAll(master).count(), master)
        assertTrue(master.trimEnd().endsWith("../$folder/index.m3u8"), master)
        assertTrue(master.contains("RESOLUTION=1920x1080") && master.contains("CODECS=\"avc1.640028,mp4a.40.2\""), master)
    }

    @Test
    fun `사다리 차선 — 세로 1080x1920 원본 단의 마스터는 화면 크기로 · 아랫단은 회전을 구운 세로`() {
        val (folder, url) = servedOrig("job0000019", Synth.rotated(dir, Synth.h264(dir, "b2.mp4", 1920, 1080, 7)))
        server.enqueue(server.job("job0000020", "LADDER", url, 1_000_000, mapOf("build" to "0123456789abcdef0123456789abcdef", "origFolder" to folder)))
        runner.runUntilIdle()
        val prefix = "/store/job0000020/h-0123456789abcdef0123456789abcdef/"
        val master = String(server.stored[prefix + "master.m3u8"]!!)
        assertTrue(master.contains("RESOLUTION=1080x1920"), master)
        val low = materialize("job0000020", "h-0123456789abcdef0123456789abcdef")
        val made = tools.probeHls(low.resolve("v1.m3u8").toString())
        assertTrue(made.displayHeight > made.displayWidth, "아랫단도 세로: ${made.displayWidth}x${made.displayHeight}")
    }

    @Test
    fun `사다리 차선 — 720p 는 480 한 판만, 파일 이름에 %v 가 남지 않는다`() {
        val (folder, url) = servedOrig("job0000021", Synth.h264(dir, "orig720.mp4", 1280, 720, 7))
        server.enqueue(server.job("job0000009", "LADDER", url, 1, mapOf("build" to "0123456789abcdef0123456789abcdef", "origFolder" to folder)))
        runner.runUntilIdle()
        val done = complete("job0000009")
        assertEquals(listOf("480"), done["rungs"].toList().map { it["name"].asString() })
        val files = server.stored.keys.filter { it.startsWith("/store/job0000009/") }.map { it.substringAfter("/store/job0000009/") }
        assertTrue(files.none { it.contains("%") }, "ffmpeg 가 풀지 않은 %v 이름: $files")
        assertTrue(files.all { Regex("h-[0-9a-f]{32}/[A-Za-z0-9][A-Za-z0-9_-]*\\.(m3u8|m4s|mp4)").matches(it) }, "서버가 허용하는 이름만: $files")
        val playlist = String(server.stored["/store/job0000009/h-0123456789abcdef0123456789abcdef/v0.m3u8"]!!)
        assertTrue(playlist.contains("init_0.mp4"), playlist)
    }

    @Test
    fun `ffmpeg 처리 시각 콜백 — 늘어나는 값이 영상 길이까지 오고 진행 줄은 실패 꼬리에 섞이지 않는다`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Long>()
        tools.run(
            listOf("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=4", "-c:v", "libx264", "-preset", "ultrafast", "-f", "null", "-"),
            null, AtomicBoolean(false),
        ) { seen.add(it) }
        assertTrue(seen.isNotEmpty(), "콜백이 안 불렸다")
        assertEquals(seen.sorted(), seen.toList())
        assertTrue(seen.last() in 3_500..4_100, seen.toString())
        val e = kotlin.test.assertFailsWith<ToolFailure> { tools.run(listOf("ffmpeg", "-v", "error", "-nostdin", "-i", dir.resolve("nope.mp4").toString(), "-f", "null", "-"), null, AtomicBoolean(false)) { } }
        assertFalse(e.tail.contains("progress="), e.tail)
    }

    @Test
    fun `임대를 잃으면(취소 표시가 서면) ffmpeg 를 멈추고 Cancelled — 일을 버린다`() {
        val orig = Synth.hevc4k(dir, "orig.mp4", sec = 3)
        val cancel = AtomicBoolean(true)
        val e = kotlin.test.assertFailsWith<Cancelled> {
            tools.run(RemuxPlan.args(orig.toString(), tools.probe(orig.toString(), null)), Files.createDirectories(dir.resolve("xo")), cancel)
        }
        assertTrue(e.message!!.contains("임대"))
    }

    @Test
    fun `토큰이 틀린 서버 응답 401 은 ApiException — 일꾼은 죽지 않고 상주 루프가 다시 시도한다`() {
        val config = WorkerConfig(apiBase = server.base, token = "wrong-" + "t".repeat(40), workerId = "test-1", workDir = dir.resolve("work3"))
        val api = WorkerApi(config)
        val e = kotlin.test.assertFailsWith<ApiException> { api.claim(setOf("REMUX")) }
        assertEquals(401, e.status)
        assertEquals(1, server.rejectedAuth.get())
        assertFalse(config.toString().contains(config.token), "설정을 찍어도 토큰은 안 나온다")
    }
}
