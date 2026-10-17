package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storagetest.StorageWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [StorageWebTestApplication::class],
    properties = ["skeleton.storage.web.guest.enabled=true", "skeleton.storage.web.guest.secret=guest-secret-for-tests-0123456789abcdef", "skeleton.storage.web.guest.rate-limit.capacity=3"],
)
@AutoConfigureMockMvc
class StorageGuestRateLimitWebTest {
    @Autowired lateinit var mockMvc: MockMvc

    private fun issue() = mockMvc.perform(post("/api/v1/storage/guest/presign").contentType(MediaType.APPLICATION_JSON).content("""{"fileName":"a.png","contentType":"image/png","sizeBytes":10}"""))

    @Test
    fun `the request after the limit from one address is a 429`() {
        assertEquals(List(3) { 200 }, (1..3).map { issue().andReturn().response.status })
        issue().andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("STORAGE.RATE_LIMITED"))
    }
}
