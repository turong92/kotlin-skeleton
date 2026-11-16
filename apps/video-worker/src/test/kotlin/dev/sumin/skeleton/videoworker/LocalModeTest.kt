package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** 로컬 모드 — 서버 · 큐 · 토큰 없이 파일 하나를 출력 폴더의 판들로. 실제 ffmpeg 로 끝까지 돈다(없으면 건너뜀) */
class LocalModeTest {
    private val json = JsonMapper.builder().build()
    private lateinit var dir: Path
    private val local = LocalMode(Tools("ffmpeg", "ffprobe", 0, Duration.ofMinutes(5)))

    @BeforeTest
    fun setUp() {
        assumeTrue(Synth.available, "ffmpeg · ffprobe 가 없어 건너뜀")
        dir = Synth.tempDir()
    }

    @AfterTest
    fun tearDown() {
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun manifest(out: Path): JsonNode = json.readTree(Files.readString(out.resolve("manifest.json")))

    private fun folders(out: Path, prefix: String) = out.listDirectoryEntries().filter { it.isDirectory() && it.fileName.toString().startsWith(prefix) }

    private fun folderBytes(folder: Path) = folder.listDirectoryEntries().sumOf { it.fileSize() }

    @Test
    fun `소리 있는 영상 - 원본 판 · 소리 판 · 포스터 · manifest 가 나오고 작업 흔적은 남지 않는다`() {
        val out = dir.resolve("out")
        local.run(Synth.h264(dir, "snd.mp4", sec = 8), out, compat = false)

        val o = folders(out, "o-").single()
        val a = folders(out, "a-").single()
        assertTrue(folders(out, "c-").isEmpty(), "--compat 이 없으면 호환 단을 안 만든다")
        assertEquals(setOf("manifest.json"), out.listDirectoryEntries().map { it.fileName.toString() }.filter { !it.startsWith("o-") && !it.startsWith("a-") && !it.startsWith("p-") }.toSet(), "임시 작업 폴더가 안 남는다")
        val m = manifest(out)
        assertTrue(m["hasAudio"].asBoolean())
        assertEquals("${o.fileName}/index.m3u8", m["original"]["path"].asString())
        assertEquals("${a.fileName}/index.m3u8", m["audio"]["path"].asString())
        assertEquals(folderBytes(o), m["original"]["bytes"].asLong())
        assertEquals(folderBytes(a), m["audio"]["bytes"].asLong())
        assertEquals(1920, m["original"]["width"].asInt())
        assertEquals(1080, m["original"]["height"].asInt())
        assertEquals("h264", m["original"]["videoCodec"].asString())
        assertEquals("aac", m["original"]["audioCodec"].asString())
        assertEquals("aac", m["audio"]["audioCodec"].asString())
        assertNull(m["audio"]["videoCodec"].takeUnless { it.isNull }, "소리 판에는 영상 코덱이 없다")
        assertTrue(Math.abs(m["original"]["durationMs"].asLong() - 8_000) <= 500)
        assertTrue(Math.abs(m["audio"]["durationMs"].asLong() - 8_000) <= 500)
        val poster = out.resolve(m["poster"]["path"].asString())
        assertTrue(Files.isRegularFile(poster) && m["poster"]["path"].asString().matches(Regex("p-[0-9a-f]{16}\\.jpg")))
        assertEquals(poster.fileSize(), m["poster"]["bytes"].asLong())
        assertTrue(m["poster"]["width"].asInt() > 0 && m["poster"]["height"].asInt() > 0)
        assertFalse(m.has("compat"))
    }

    @Test
    fun `소리 없는 영상 - 소리 판을 만들지 않고 manifest 에도 없다`() {
        val out = dir.resolve("out")
        local.run(Synth.h264(dir, "mute.mp4", audio = false), out, compat = false)

        assertTrue(folders(out, "a-").isEmpty())
        val m = manifest(out)
        assertFalse(m["hasAudio"].asBoolean())
        assertFalse(m.has("audio"))
        assertTrue(m.has("original") && m.has("poster"))
        val audioCodec = m["original"]["audioCodec"]
        assertTrue(audioCodec == null || audioCodec.isNull)
    }

    @Test
    fun `compat 이면 호환 단도 나오고 manifest 에 적힌다 - 720p H264`() {
        val out = dir.resolve("out")
        local.run(Synth.hevc4k(dir, "big.mp4", sec = 3), out, compat = true)

        val c = folders(out, "c-").single()
        val m = manifest(out)
        assertEquals("${c.fileName}/index.m3u8", m["compat"]["path"].asString())
        assertEquals(720, m["compat"]["height"].asInt())
        assertEquals("h264", m["compat"]["videoCodec"].asString())
        assertEquals(folderBytes(c), m["compat"]["bytes"].asLong())
    }

    @Test
    fun `명령줄 - 서버 설정 없이 돌고, 인자가 틀리면 2, 못 읽는 파일이면 1 이고 출력 폴더에 manifest 가 없다`() {
        val out = dir.resolve("cli-out")
        assertEquals(0, local.cli(listOf(Synth.h264(dir, "cli.mp4", sec = 2).toString(), out.toString())))
        assertTrue(Files.isRegularFile(out.resolve("manifest.json")))

        assertEquals(2, local.cli(emptyList()))
        assertEquals(2, local.cli(listOf("only-one")))
        assertEquals(2, local.cli(listOf("a", "b", "--nope")))

        val bad = dir.resolve("bad.mp4").also { Files.writeString(it, "not a video") }
        val badOut = dir.resolve("bad-out")
        assertEquals(1, local.cli(listOf(bad.toString(), badOut.toString())))
        assertFalse(Files.exists(badOut.resolve("manifest.json")))
        assertEquals(1, local.cli(listOf(dir.resolve("missing.mp4").toString(), badOut.toString())))
    }
}
