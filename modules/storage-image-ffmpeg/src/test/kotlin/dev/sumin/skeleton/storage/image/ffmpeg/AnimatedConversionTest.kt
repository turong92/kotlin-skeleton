package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import dev.sumin.skeleton.storage.image.ImageProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 실제 ffmpeg 로 합성한 그림을 돌린다(호스트에 ffmpeg 가 없으면 건너뜀). 사고 나는 것만 본다:
 * 투명 판정(최적화 투명은 MP4 · 실제 투명은 WebP) · 변환본이 크면 올리지 않는다 · 허용 목록 밖은 ffmpeg 를 부르지 않는다 · 시간 상한 · 같은 요청이 또 와도 같은 결과
 */
class AnimatedConversionTest {
    private val hash = "a".repeat(64)
    private val sourceKey = "uploads/pic"
    private lateinit var dir: Path
    private val storage = MemoryStorage()
    private val store = MemoryImageStore()
    private val folder = AnimatedDisplays(store, ImageProperties()).folder(hash)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ffmpeg-conversion-test-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun conversion(props: FfmpegProperties = FfmpegProperties(enabled = true)) =
        AnimatedConversion(storage, store, AnimatedDisplays(store, ImageProperties()), props)

    private fun put(bytes: ByteArray, format: String = "gif", animated: Boolean = true) {
        storage.objects[sourceKey] = bytes
        store.readyAnimated(hash, sourceKey, format, animated)
    }

    private fun needFfmpeg() = assumeTrue(Synth.available, "ffmpeg(libx264 · libwebp_anim)가 없어 건너뜀")

    @Test
    fun `불투명 GIF 는 소리 없는 MP4 가 되어 display 의 다른 형식으로 더해지고 사본은 그대로다 - 같은 요청이 또 와도 다시 하지 않는다`() {
        needFfmpeg()
        val gif = Synth.gifOpaque(dir)
        put(gif)

        val first = conversion().convert(hash)

        assertEquals(AnimatedConversion.Outcome.Converted("mp4"), first)
        val mp4 = assertNotNull(storage.objects["${folder}display.mp4"])
        assertEquals("ftyp", String(mp4, 4, 4), "MP4 컨테이너")
        assertTrue(mp4.size < gif.size, "변환본이 원본보다 작을 때만 올린다")
        assertEquals("video/mp4", storage.contentTypes["${folder}display.mp4"])
        assertEquals(listOf("mp4"), store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).alt)
        assertEquals("gif", store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).ext, "사본 display 는 그대로")
        assertEquals(null, storage.objects["${folder}display.webp"], "불투명이면 WebP 는 올리지 않는다")
        assertTrue(storage.objects.containsKey(sourceKey), "원본은 건드리지 않는다")

        val uploadsAfterFirst = storage.uploads
        assertEquals(first, conversion().convert(hash))
        assertEquals(uploadsAfterFirst, storage.uploads, "이미 올라간 변환본이 있으면 다시 인코딩하지 않는다")
        assertEquals(listOf("mp4"), store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).alt, "같은 ext 가 두 번 들어가지 않는다")
    }

    @Test
    fun `올렸는데 상태 줄에 더하기 전에 죽은 일을 다시 하면 올라간 변환본을 그대로 쓴다`() {
        needFfmpeg()
        put(Synth.gifOpaque(dir))
        storage.objects["${folder}display.mp4"] = byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

        assertEquals(AnimatedConversion.Outcome.Converted("mp4"), conversion(FfmpegProperties(path = "/no/such/ffmpeg")).convert(hash))
        assertEquals(listOf("mp4"), store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).alt)
    }

    @Test
    fun `실제 투명 배경 GIF 는 합성한 화면에 알파가 있어 움직이는 WebP 가 된다`() {
        needFfmpeg()
        put(Synth.gifTransparent(dir))

        assertEquals(AnimatedConversion.Outcome.Converted("webp"), conversion().convert(hash))

        val webp = assertNotNull(storage.objects["${folder}display.webp"])
        assertEquals("WEBP", String(webp, 8, 4))
        assertTrue(String(webp, Charsets.ISO_8859_1).contains("ANIM"), "움직이는 WebP")
        assertEquals("image/webp", storage.contentTypes["${folder}display.webp"])
        assertEquals(null, storage.objects["${folder}display.mp4"], "투명이면 MP4 는 올리지 않는다")
        assertEquals(listOf("webp"), store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).alt)
    }

    @Test
    fun `변환본이 원본보다 작지 않으면 아무것도 올리지 않고 사본을 쓴다`() {
        needFfmpeg()
        put(Synth.gifOpaque(dir, w = 33, h = 21, sec = 1))   // 아주 작은 GIF - MP4 쪽이 더 크다

        val outcome = conversion().convert(hash)

        assertTrue(outcome is AnimatedConversion.Outcome.Skipped, "$outcome")
        assertEquals(0, storage.uploads)
        assertEquals(emptyList(), store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).alt)
    }

    @Test
    fun `GIF 도 WebP 도 아닌 바이트는 ffmpeg 를 부르지 않고 거절한다 - 허용 목록 밖`() {
        // 실행 파일이 없는 경로 - 불렀다면 던졌을 것이다
        val noFfmpeg = FfmpegProperties(path = "/no/such/ffmpeg")
        listOf(Synth.png, "just some text, not an image at all".toByteArray()).forEach { bytes ->
            put(bytes)
            val outcome = conversion(noFfmpeg).convert(hash)
            assertTrue(outcome is AnimatedConversion.Outcome.Skipped, "$outcome")
        }
        assertEquals(0, storage.uploads)
    }

    @Test
    fun `입력 크기 상한을 넘으면 읽지 않고 사본을 쓴다`() {
        put(ByteArray(2000).also { "GIF89a".toByteArray().copyInto(it) })

        val outcome = conversion(FfmpegProperties(path = "/no/such/ffmpeg", maxSourceBytes = 1000)).convert(hash)

        assertTrue(outcome is AnimatedConversion.Outcome.Skipped, "$outcome")
    }

    @Test
    fun `움직이는 READY 이미지가 아니면 할 일이 없다`() {
        put(Synth.png, format = "png", animated = false)
        assertTrue(conversion(FfmpegProperties(path = "/no/such/ffmpeg")).convert(hash) is AnimatedConversion.Outcome.Skipped)
        store.rows.clear()
        assertTrue(conversion(FfmpegProperties(path = "/no/such/ffmpeg")).convert(hash) is AnimatedConversion.Outcome.Skipped, "상태 줄이 없다(지워졌다)")
    }

    @Test
    fun `시간 상한을 넘으면 ffmpeg 를 죽이고 실패로 던진다 - 큐가 다시 하고 소진되면 사본만 남는다`() {
        needFfmpeg()
        put(Synth.gifOpaque(dir, w = 640, h = 480, sec = 10))

        val e = assertFailsWith<FfmpegFailedException> { conversion(FfmpegProperties(timeout = Duration.ofMillis(30))).convert(hash) }

        assertTrue(e.message!!.contains("timed out"), e.message)
        assertEquals(0, storage.uploads)
        assertEquals(emptyList(), store.rows.getValue(hash).variants.getValue(UploadImages.DISPLAY).alt)
    }

    @Test
    fun `움직이는 WebP 는 이 ffmpeg 가 풀면 변환하고 못 풀면 사본을 쓴다 - 입력과 같은 이름 display webp 로 덮어쓰지 않는다`() {
        needFfmpeg()
        put(Synth.webpAnimated(dir), format = "webp")

        val outcome = conversion().convert(hash)

        assertTrue(outcome is AnimatedConversion.Outcome.Converted || outcome is AnimatedConversion.Outcome.Skipped, "$outcome")
        assertEquals(null, storage.objects["${folder}display.webp"])
        assertEquals(ObjectKey("${folder}display.mp4").value in storage.objects, outcome is AnimatedConversion.Outcome.Converted)
    }
}
