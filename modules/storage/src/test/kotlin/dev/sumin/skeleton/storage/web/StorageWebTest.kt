package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storagetest.FakePresignedStorage
import dev.sumin.skeleton.storagetest.StorageWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [StorageWebTestApplication::class],
    properties = [
        "skeleton.storage.validation.max-size-bytes=1000",
        "skeleton.storage.validation.allowed-content-types=image/*,text/plain",
    ],
)
@AutoConfigureMockMvc
class StorageWebTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var storage: FakePresignedStorage

    private fun caller(id: String) = TestingAuthenticationToken(id, "n/a", "ROLE_USER")

    private fun postJson(path: String, body: String, accountId: String = "acc_a") =
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).principal(caller(accountId)))

    @Test
    fun `presign picks the key under the caller's prefix and signs the declared type and size`() {
        postJson("/api/v1/storage/presign", """{"fileName":"my cat.png","contentType":"image/png","sizeBytes":500}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.method").value("PUT"))
            .andExpect(jsonPath("$.value.key").value(org.hamcrest.Matchers.matchesPattern("uploads/acc_a/[0-9a-f-]{36}/my_cat\\.png")))
            .andExpect(jsonPath("$.value.url").value(org.hamcrest.Matchers.startsWith("http://s3.test/uploads/acc_a/")))

        val signed = storage.uploads.last()
        assertEquals("image/png", signed.contentType)
        assertEquals(500L, signed.contentLength)
    }

    @Test
    fun `presign rejects a file the validator refuses with the rejection reasons in data`() {
        postJson("/api/v1/storage/presign", """{"fileName":"big.png","contentType":"image/png","sizeBytes":5000}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STORAGE.FILE_REJECTED"))
            .andExpect(jsonPath("$.data.errors[0].code").value("SIZE_TOO_LARGE"))
    }

    @Test
    fun `validate answers valid or the list of errors without presigning`() {
        val before = storage.uploads.size
        postJson("/api/v1/storage/validate", """{"fileName":"a.exe","contentType":"application/x-msdownload","sizeBytes":10}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.valid").value(false))
            .andExpect(jsonPath("$.value.errors[0].code").value("UNSUPPORTED_CONTENT_TYPE"))
        postJson("/api/v1/storage/validate", """{"fileName":"a.txt","contentType":"text/plain","sizeBytes":10}""")
            .andExpect(jsonPath("$.value.valid").value(true))
            .andExpect(jsonPath("$.value.errors").isEmpty)
        assertEquals(before, storage.uploads.size)
    }

    @Test
    fun `presign-download works for the caller's own key and is 404 for someone else's`() {
        postJson("/api/v1/storage/presign-download", """{"key":"uploads/acc_a/x/cat.png"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.method").value("GET"))
            .andExpect(jsonPath("$.value.url").value(org.hamcrest.Matchers.endsWith("?down")))
        postJson("/api/v1/storage/presign-download", """{"key":"uploads/acc_b/x/cat.png"}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("STORAGE.OBJECT_NOT_FOUND"))
        postJson("/api/v1/storage/presign-download", """{"key":"uploads/acc_a/../acc_b/x"}""")
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `multipart start part complete abort follow the contract and check ownership`() {
        postJson("/api/v1/storage/multipart/start", """{"fileName":"v.txt","contentType":"text/plain","sizeBytes":900}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.uploadId").value("upload-1"))
            .andExpect(jsonPath("$.value.key").value(org.hamcrest.Matchers.startsWith("uploads/acc_a/")))
        postJson("/api/v1/storage/multipart/part", """{"key":"uploads/acc_a/z/v.txt","uploadId":"upload-1","partNumber":2,"contentLength":5}""")
            .andExpect(jsonPath("$.value.partNumber").value(2))
            .andExpect(jsonPath("$.value.url").value(org.hamcrest.Matchers.endsWith("part=2")))
        postJson("/api/v1/storage/multipart/complete", """{"key":"uploads/acc_a/z/v.txt","uploadId":"upload-1","parts":[{"partNumber":1,"eTag":"e1"}]}""")
            .andExpect(jsonPath("$.value.eTag").value("etag-all"))
        postJson("/api/v1/storage/multipart/abort", """{"key":"uploads/acc_a/z/v.txt","uploadId":"upload-1"}""")
            .andExpect(status().isOk)
        assertTrue(storage.aborted.any { it.uploadId == "upload-1" })

        postJson("/api/v1/storage/multipart/abort", """{"key":"uploads/acc_b/z/v.txt","uploadId":"upload-1"}""")
            .andExpect(status().isNotFound)
    }

    @Test
    fun `without an authenticated caller it is 401`() {
        mockMvc.perform(post("/api/v1/storage/presign").contentType(MediaType.APPLICATION_JSON).content("""{"fileName":"a.txt","contentType":"text/plain","sizeBytes":1}"""))
            .andExpect(status().isUnauthorized)
    }
}

@SpringBootTest(classes = [StorageWebTestApplication::class], properties = ["skeleton.storage.web.enabled=false"])
@AutoConfigureMockMvc
class StorageWebDisabledTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `the endpoints are gone when disabled`() {
        mockMvc.perform(post("/api/v1/storage/presign").contentType(MediaType.APPLICATION_JSON).content("{}").principal(TestingAuthenticationToken("a", "x", "ROLE_USER")))
            .andExpect(status().isNotFound)
    }
}
