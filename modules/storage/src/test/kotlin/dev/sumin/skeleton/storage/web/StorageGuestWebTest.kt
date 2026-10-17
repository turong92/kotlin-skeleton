package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storage.StorageKeyScheme
import dev.sumin.skeleton.storage.StorageProperties
import dev.sumin.skeleton.storagetest.FakePresignedStorage
import dev.sumin.skeleton.storagetest.StorageWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hamcrest.Matchers.matchesPattern
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
        "skeleton.storage.web.guest.enabled=true",
        "skeleton.storage.web.guest.secret=guest-secret-for-tests-0123456789abcdef",
        "skeleton.storage.web.guest.max-size-bytes=2000",
        "skeleton.storage.web.guest.rate-limit.capacity=1000",
    ],
)
@AutoConfigureMockMvc
class StorageGuestWebTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var storage: FakePresignedStorage
    @Autowired lateinit var properties: StorageProperties
    private val keys get() = StorageKeyScheme(properties.web)

    private fun guestPost(body: String) = mockMvc.perform(post("/api/v1/storage/guest/presign").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun png(size: Int = 500) = """{"fileName":"cat.PNG","contentType":"image/png","sizeBytes":$size}"""

    @Test
    fun `an anonymous caller gets a signed image upload under the guest prefix, the extension comes from the content type`() {
        guestPost("""{"fileName":"photo.jpeg","contentType":"image/jpeg","sizeBytes":900}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.key").value(matchesPattern("uploads/g/[0-9a-f-]{36}\\.[0-9a-f]{32}/file\\.jpg")))
        val signed = storage.uploads.last()
        assertEquals("image/jpeg", signed.contentType)
        assertEquals(900L, signed.contentLength)
        assertTrue(keys.isGuestKey(signed.key.value))
    }

    @Test
    fun `svg and other non-image types, a size over the limit and an extension that does not match the type are refused`() {
        guestPost("""{"fileName":"x.svg","contentType":"image/svg+xml","sizeBytes":100}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STORAGE.FILE_REJECTED")).andExpect(jsonPath("$.data.errors[0].code").value("UNSUPPORTED_CONTENT_TYPE"))
        guestPost("""{"fileName":"x.pdf","contentType":"application/pdf","sizeBytes":100}""").andExpect(status().isBadRequest)
        guestPost(png(2001)).andExpect(status().isBadRequest).andExpect(jsonPath("$.data.errors[0].code").value("SIZE_TOO_LARGE"))
        guestPost(png(0)).andExpect(status().isBadRequest)
        guestPost("""{"fileName":"cat.svg","contentType":"image/png","sizeBytes":100}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.data.errors[0].code").value("EXTENSION_CONTENT_TYPE_MISMATCH"))
    }

    @Test
    fun `only the guest presign is open - the other routes still need a signed-in caller`() {
        mockMvc.perform(post("/api/v1/storage/presign").contentType(MediaType.APPLICATION_JSON).content(png())).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/v1/storage/presign-download").contentType(MediaType.APPLICATION_JSON).content("""{"key":"uploads/g/x"}""")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an anonymous authentication token is not an account on the member routes`() {
        val anonymous = org.springframework.security.authentication.AnonymousAuthenticationToken("k", "anonymousUser", listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        mockMvc.perform(post("/api/v1/storage/presign").contentType(MediaType.APPLICATION_JSON).content(png()).principal(anonymous)).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/v1/storage/presign").contentType(MediaType.APPLICATION_JSON).content(png()).principal(TestingAuthenticationToken("acc_a", "n/a", "ROLE_USER"))).andExpect(status().isOk)
    }
}
