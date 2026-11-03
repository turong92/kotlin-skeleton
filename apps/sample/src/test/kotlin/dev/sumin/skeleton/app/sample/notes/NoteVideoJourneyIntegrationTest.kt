package dev.sumin.skeleton.app.sample.notes

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ListedObjects
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StoredObjectSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 노트의 영상 한 편이 올리기부터 재생까지 가는 한 여정 - 진짜 PostgreSQL · 진짜 보안 체인 · 진짜 영상 모듈, 저장소만 메모리다.
 * 일꾼(ffmpeg 이미지)은 띄우지 않는다: 시험이 일꾼 API(`/api/internal/video` 아래 경로)를 일꾼 토큰으로 직접 불러 claim -> upload-urls -> complete 를 대신 한다.
 */
@Import(NoteVideoJourneyIntegrationTest.ListingStorageConfiguration::class)
class NoteVideoJourneyIntegrationTest : SampleIntegrationTest() {
    /** 폴더 나열(`list`)까지 되는 메모리 저장소 - 일꾼 결과 확인이 폴더를 나열해 대조한다 */
    class ListingStorage : FakePresignedStorage() {
        override fun list(request: ListObjectsRequest): ListedObjects =
            ListedObjects(objects.filterKeys { it.startsWith(request.prefix) }.map { (key, bytes) -> StoredObjectSummary(ObjectKey(key), sizeBytes = bytes.size.toLong()) })
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ListingStorageConfiguration {
        @Bean
        @Primary
        fun listingStorage(): ListingStorage = ListingStorage()
    }

    @BeforeEach
    fun cleanVideos() {
        jdbc.sql("delete from videos").update()
    }

    @Test
    fun `a private note video goes from upload through the worker to a playback only its owner gets`() {
        val videoId = startUpload(user)
        finishUpload(user, videoId, bytes = UPLOAD_BYTES)
        val noteId = createdId(user, title = "영상 노트")
        putNote(user, noteId, """{"title":"영상 노트","body":"","status":"DRAFT","pinned":false,"videoId":"$videoId"}""")
            .andExpect { status { isOk() }; jsonPath("$.value.videoId") { value(videoId) } }

        // 가짜 일꾼: 집고 -> 결과 올릴 주소를 받고 -> 올리고 -> 마친다
        val claim = worker("/claim", """{"workerId":"$WORKER_ID","lanes":["REMUX"]}""").andExpect { status { isOk() }; jsonPath("$.value.lane") { value("REMUX") }; jsonPath("$.value.jobId") { value(videoId) } }
        assertNotNull(JsonPath.read<String>(claim.andReturn().response.contentAsString, "$.value.inputUrl"))
        val names = listOf("o-$HASH16/index.m3u8", "o-$HASH16/init.mp4", "o-$HASH16/s00001.m4s", "p-$HASH16.jpg")
        val signed = worker("/jobs/$videoId/upload-urls", """{"workerId":"$WORKER_ID","names":${names.joinToString(",", "[", "]") { "\"$it\"" }}}""").andExpect { status { isOk() } }
        val urls = JsonPath.read<List<String>>(signed.andReturn().response.contentAsString, "$.values[*].url")
        val folder = "videos/p/$videoId/"
        names.forEachIndexed { i, name ->
            val key = urls[i].removePrefix("http://storage.test/")
            assertEquals("$folder$name", key, "the worker may write only inside the video's own folder")
            storage.objects[key] = if (name.endsWith(".m3u8")) PLAYLIST.toByteArray() else ByteArray(if (name.endsWith(".jpg")) POSTER_BYTES else 1_000)
        }
        val folderBytes = PLAYLIST.toByteArray().size + 2 * 1_000L
        worker(
            "/jobs/$videoId/complete",
            """{"workerId":"$WORKER_ID","orig":{"name":"o-$HASH16/index.m3u8","bytes":$folderBytes,"sha256":"$SHA256"},
              "poster":{"name":"p-$HASH16.jpg","bytes":$POSTER_BYTES,"width":640,"height":360},
              "probe":{"container":"mov,mp4","codec":"avc1.640028","width":640,"height":360,"rotation":0,"durationMs":3000,"fps":30.0,"bitrate":1000000,"audioCodec":"aac"},
              "compatible":true}""",
        ).andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/videos/$videoId") { header("Authorization", "Bearer $user") }
            .andExpect { status { isOk() }; jsonPath("$.value.status") { value("PLAYABLE") }; jsonPath("$.value.compat") { value("SKIPPED") }; jsonPath("$.value.ladder") { value("SKIPPED") } }

        // 재생 허가: 주인만. 비공개라 주소에 출입증이 실린다
        val grant = mockMvc.post("/api/v1/videos/$videoId/playback") { header("Authorization", "Bearer $user") }
            .andExpect { status { isOk() }; jsonPath("$.value.expiresAt") { exists() }; jsonPath("$.value.w") { value(640) } }.andReturn()
        val src = grant.readString("$.value.src")
        assertTrue(src.startsWith("/api/v1/videos/play/v1.") && src.endsWith("/o-$HASH16/index.m3u8"), src)
        mockMvc.get(src).andExpect { status { isOk() } }.andReturn().response.contentAsString.let {
            assertTrue("storage.test/$folder" + "o-$HASH16/s00001.m4s" in it, "the playlist's segment lines are signed addresses: $it")
        }

        // 다른 계정: 재생 허가 · 조회 · 지우기 모두 404, 내 노트에 남의 영상을 걸 수도 없다
        mockMvc.post("/api/v1/videos/$videoId/playback") { header("Authorization", "Bearer $other") }.andExpect { status { isNotFound() }; jsonPath("$.code") { value("VIDEO.NOT_FOUND") } }
        mockMvc.get("/api/v1/videos/$videoId") { header("Authorization", "Bearer $other") }.andExpect { status { isNotFound() } }
        mockMvc.delete("/api/v1/videos/$videoId") { header("Authorization", "Bearer $other") }.andExpect { status { isNotFound() } }
        val othersNote = createdId(other, title = "남의 노트")
        putNote(other, othersNote, """{"title":"남의 노트","body":"","status":"DRAFT","pinned":false,"videoId":"$videoId"}""").andExpect { status { isNotFound() } }

        // 노트를 지우면 그 영상도 지워진다(커밋 뒤)
        deleteNote(user, noteId).andExpect { status { isNoContent() } }
        await { jdbc.sql("select status from videos where id = :id").param("id", videoId).query(String::class.java).single().takeIf { it == "DELETED" } }
    }

    @Test
    fun `this app takes only private note videos`() {
        start(user, """{"bytes":$UPLOAD_BYTES,"contentType":"video/mp4","purpose":"note","visibility":"PUBLIC"}""").andExpect { status { isForbidden() }; jsonPath("$.code") { value("VIDEO.FORBIDDEN") } }
        start(user, """{"bytes":$UPLOAD_BYTES,"contentType":"video/mp4","purpose":"trailer","visibility":"PRIVATE"}""").andExpect { status { isForbidden() } }
    }

    @Test
    fun `only an admin issues worker tokens`() {
        val body = """{"name":"extra-worker","lanes":["COMPAT"]}"""
        mockMvc.post("/api/v1/videos/admin/workers") { header("Authorization", "Bearer $user"); contentType = MediaType.APPLICATION_JSON; content = body }.andExpect { status { isForbidden() } }
        val issued = mockMvc.post("/api/v1/videos/admin/workers") { header("Authorization", "Bearer $other"); contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isCreated() }; jsonPath("$.value.token") { isNotEmpty() } }.andReturn()
        val id = JsonPath.read<Int>(issued.response.contentAsString, "$.value.id")
        mockMvc.delete("/api/v1/videos/admin/workers/$id") { header("Authorization", "Bearer $other") }.andExpect { status { isNoContent() } }
    }

    private fun start(token: String, json: String): ResultActionsDsl =
        mockMvc.post("/api/v1/videos") { header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = json }

    private fun startUpload(token: String): String =
        start(token, """{"bytes":$UPLOAD_BYTES,"contentType":"video/mp4","purpose":"note","visibility":"PRIVATE"}""")
            .andExpect { status { isCreated() } }.andReturn().readString("$.value.id")

    /** 브라우저가 조각을 저장소로 올린 것까지 흉내 내고 마친다 */
    private fun finishUpload(token: String, id: String, bytes: Int) {
        mockMvc.post("/api/v1/videos/$id/parts") { header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = """{"numbers":[1]}""" }
            .andExpect { status { isOk() } }
        val uploadKey = jdbc.sql("select upload_key from videos where id = :id").param("id", id).query(String::class.java).single()
        storage.objects[uploadKey] = ByteArray(bytes)
        mockMvc.post("/api/v1/videos/$id/complete") { header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = """{"parts":[{"n":1,"etag":"e1"}]}""" }
            .andExpect { status { isOk() }; jsonPath("$.value.status") { value("QUEUED") } }
    }

    private fun worker(path: String, json: String): ResultActionsDsl =
        mockMvc.post("/api/internal/video$path") { header("Authorization", "Bearer $WORKER_TOKEN"); contentType = MediaType.APPLICATION_JSON; content = json }

    private companion object {
        const val UPLOAD_BYTES = 6_000_000
        const val WORKER_ID = "test-worker"

        /** 시험 설정(config/application.yml)의 `skeleton.video.worker-token-sha256` 가 이 문자열의 SHA-256 이다 */
        const val WORKER_TOKEN = "test-only-video-worker-token"
        const val HASH16 = "0123456789abcdef"
        val SHA256 = HASH16 + "0".repeat(48)
        const val POSTER_BYTES = 500
        const val PLAYLIST = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:3\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:3.0,\ns00001.m4s\n#EXT-X-ENDLIST\n"
    }
}
