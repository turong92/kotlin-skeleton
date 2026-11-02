package dev.sumin.skeleton.app.sample.board

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.notes.SampleIntegrationTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 샘플 앱에 이미지 모듈을 얹은 한 바퀴(진짜 PostgreSQL · 작업 큐 · 보안 체인, 메모리 저장소):
 * 올림 완료 호출 -> 변환 작업 -> 게시판 글 공개 -> 공개 영역에는 EXIF · GPS 가 없는 변환본만 있고 응답에 폭 · 높이 · 썸네일 주소가 실린다.
 */
class StorageImagesIntegrationTest : SampleIntegrationTest() {
    private val secret = "GPS-SECRET-MARKER-37.5665N-126.9780E"

    /** 앞에 APP1(Exif) 마디를 끼워 비밀 표식을 담은 JPEG - 원본 그대로는 남에게 가면 안 된다 */
    private fun jpegWithExif(): ByteArray {
        val plain = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB).also { img -> for (x in 0 until 800) for (y in 0 until 600) img.setRGB(x, y, (x * 31 + y * 17) and 0xFFFFFF) }, "jpg", it) }.toByteArray()
        val payload = "Exif\u0000\u0000MM\u0000*\u0000\u0000\u0000\b\u0000\u0000\u0000\u0000\u0000\u0000$secret".toByteArray(Charsets.ISO_8859_1)
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), ((payload.size + 2) and 0xFF).toByte()) + payload
        return plain.copyOfRange(0, 2) + segment + plain.copyOfRange(2, plain.size)
    }

    private fun contains(haystack: ByteArray, needle: String) = String(haystack, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun `an uploaded photo is converted and the public area gets only the metadata-free variants`() {
        val key = mockMvc.post("/api/v1/storage/presign") {
            header("Authorization", "Bearer $user"); contentType = MediaType.APPLICATION_JSON
            content = """{"fileName":"trip.jpg","contentType":"image/jpeg","sizeBytes":1024}"""
        }.andExpect { status { isOk() } }.andReturn().let { JsonPath.read<String>(it.response.contentAsString, "$.value.key") }
        val original = jpegWithExif()
        storage.objects[key] = original   // 브라우저가 서명 주소로 올린 것을 흉내 낸다
        storage.types[key] = "image/jpeg"
        assertTrue(contains(original, secret) && contains(original, "Exif"), "the fixture really carries the secret")

        mockMvc.post("/api/v1/storage/complete") {
            header("Authorization", "Bearer $user"); contentType = MediaType.APPLICATION_JSON; content = """{"key":"$key"}"""
        }.andExpect { status { isOk() }; jsonPath("$.value.image.status") { value("PENDING") } }

        val deadline = System.currentTimeMillis() + 30_000
        var state = "PENDING"
        while (state == "PENDING" && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            state = mockMvc.post("/api/v1/storage/images") {
                header("Authorization", "Bearer $user"); contentType = MediaType.APPLICATION_JSON; content = """{"keys":["$key"]}"""
            }.andExpect { status { isOk() } }.andReturn().let { JsonPath.read<String>(it.response.contentAsString, "$.value.items[0].status") }
        }
        assertEquals("READY", state, "the variant job ran on the queue")

        val profile = mockMvc.get("/api/v1/author-profiles") { header("Authorization", "Bearer $user") }.andReturn().let { JsonPath.read<String>(it.response.contentAsString, "$.values[0].id") }
        val postId = mockMvc.post("/api/v1/boards/general/posts") {
            header("Authorization", "Bearer $user"); header("Idempotency-Key", UUID.randomUUID().toString()); contentType = MediaType.APPLICATION_JSON
            content = """{"title":"여행","body":"사진","authorId":"$profile","attachments":["$key"]}"""
        }.andExpect { status { isCreated() } }.andReturn().let { JsonPath.read<Number>(it.response.contentAsString, "$.value.id").toLong() }

        val detail = mockMvc.get("/api/v1/boards/general/posts/$postId") { header("Authorization", "Bearer $other") }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val image = JsonPath.read<Map<String, Any>>(detail, "$.value.attachmentImages").getValue(key) as Map<*, *>
        assertTrue((image["display"] as String).startsWith("http://cdn.test/app/"), "a public address, not a signed one: $image")
        assertTrue((image["thumb"] as String).contains(".thumb."), image.toString())
        assertEquals(600 to 800, (image["height"] as Number).toInt() to (image["width"] as Number).toInt(), "the size of the display copy (not enlarged)")

        val publicObjects = storage.objects.filterKeys { it.startsWith("public/") }
        assertEquals(2, publicObjects.size, "the display copy and the thumbnail copy: ${publicObjects.keys}")
        publicObjects.forEach { (name, bytes) ->
            assertFalse(contains(bytes, secret), "$name must not carry the GPS marker")
            assertFalse(contains(bytes, "Exif"), "$name must carry no EXIF segment")
            assertEquals("image/jpeg", storage.types[name])
        }
        assertTrue(storage.objects.getValue(key).contentEquals(original), "the original stays private and untouched")
    }
}
