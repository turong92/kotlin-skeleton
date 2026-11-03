package dev.sumin.skeleton.app.sample.notes

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.StoredVariant
import dev.sumin.skeleton.storage.image.imageKeyHash
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

/**
 * 견본은 `storage-image` 와 `video` 를 둘 다 켜 두었으니 움직이는 이미지의 표시본 변환도 선다(따로 켤 것이 없다) - 진짜 PostgreSQL(상태 줄 · 변환 일 표) · 진짜 일꾼 API.
 * 일꾼(ffmpeg 이미지)은 띄우지 않고 시험이 일꾼 API 를 직접 불러 한 바퀴를 대신한다: 변환 요청 -> claim -> upload-urls -> complete -> 표시본이 영상으로 바뀐다. 실패하면 사본 그대로.
 */
class AnimatedDisplayJourneyIntegrationTest : SampleIntegrationTest() {
    @Autowired lateinit var imageStore: ImageStatusStore
    @Autowired lateinit var converter: AnimatedDisplayConverter
    @Autowired lateinit var images: UploadImages

    @BeforeEach
    fun cleanImages() {
        jdbc.sql("delete from video_display_jobs").update()
        jdbc.sql("delete from storage_images").update()
    }

    /** 사본 display(gif)로 `READY` 가 된 움직이는 이미지 - 처리기가 끝낸 뒤의 모습 */
    private fun readyAnimatedImage(key: String): String {
        val hash = imageKeyHash(key)
        storage.objects[key] = ByteArray(5_000)
        imageStore.register(hash, key) { }
        imageStore.markReady(hash, 60, 40, "gif", true, mapOf(UploadImages.THUMB to StoredVariant(60, 40, "jpg"), UploadImages.DISPLAY to StoredVariant(60, 40, "gif")))
        return hash
    }

    private fun displayOf(key: String) = images.status(key)!!.variants.getValue(UploadImages.DISPLAY)

    @Test
    fun `a converted display turns the animated image into a video in the status answer - the thumbnail stays`() {
        val key = "uploads/7/a/anim.gif"
        val hash = readyAnimatedImage(key)
        converter.requestConversion(hash, key)
        assertEquals("image/gif", displayOf(key).contentType, "변환 전에는 사본이 표시본이다")

        worker("/claim", """{"workerId":"w","lanes":["ANIMATED"]}""").andExpect { status { isOk() }; jsonPath("$.value.lane") { value("ANIMATED") }; jsonPath("$.value.jobId") { value(hash) } }
        val signed = worker("/jobs/$hash/upload-urls", """{"workerId":"w","names":["display.mp4"]}""").andExpect { status { isOk() } }
        val url = JsonPath.read<String>(signed.andReturn().response.contentAsString, "$.values[0].url")
        val folder = "${images.variantPrefix}/${hash.take(32)}/"
        assertEquals("$folder" + "display.mp4", url.removePrefix("http://storage.test/"), "일꾼은 이미지의 변환본 폴더 안에만 쓴다")
        storage.objects[folder + "display.mp4"] = ByteArray(900)
        worker("/jobs/$hash/complete", """{"workerId":"w","display":{"name":"display.mp4","bytes":900}}""").andExpect { status { isNoContent() } }

        val display = displayOf(key)
        assertEquals("image/gif", display.contentType, "표시본은 이미지 사본 그대로 - 영상은 다른 형식으로 실린다")
        assertEquals(listOf(folder + "display.mp4"), display.alternates.map { it.key })
        assertEquals(listOf("video/mp4"), display.alternates.map { it.contentType })
        assertEquals("image/jpeg", images.status(key)!!.variants.getValue(UploadImages.THUMB).contentType)
    }

    @Test
    fun `a failed conversion leaves the copy as the display`() {
        val key = "uploads/7/a/anim-fail.gif"
        val hash = readyAnimatedImage(key)
        converter.requestConversion(hash, key)

        worker("/claim", """{"workerId":"w","lanes":["ANIMATED"]}""").andExpect { status { isOk() } }
        worker("/jobs/$hash/fail", """{"workerId":"w","code":"PROCESSING_FAILED","retryable":false}""").andExpect { status { isNoContent() } }

        assertEquals("image/gif", displayOf(key).contentType)
        worker("/claim", """{"workerId":"w","lanes":["ANIMATED"]}""").andExpect { status { isNoContent() } }
    }

    private fun worker(path: String, json: String): ResultActionsDsl =
        mockMvc.post("/api/internal/video$path") { header("Authorization", "Bearer $WORKER_TOKEN"); contentType = MediaType.APPLICATION_JSON; content = json }

    private companion object {
        /** 시험 설정(config/application.yml)의 `skeleton.video.worker-token-sha256` 가 이 문자열의 SHA-256 이다 */
        const val WORKER_TOKEN = "test-only-video-worker-token"
    }
}
