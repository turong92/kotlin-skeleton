package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storage.StorageKeyScheme
import dev.sumin.skeleton.storage.StorageProperties
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
        "skeleton.storage.web.guest.enabled=true",
        "skeleton.storage.web.guest.secret=guest-secret-for-tests-0123456789abcdef",
        "skeleton.storage.web.guest.rate-limit.capacity=1000",
    ],
)
@AutoConfigureMockMvc
class StorageCompleteWebTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var storage: FakePresignedStorage
    @Autowired lateinit var properties: StorageProperties
    private val keys get() = StorageKeyScheme(properties.web)
    private val html = "<html><script>alert(1)</script></html>".toByteArray()

    private fun complete(path: String, key: String, accountId: String? = null) =
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""{"key":"$key"}""").apply { accountId?.let { principal(TestingAuthenticationToken(it, "n/a", "ROLE_USER")) } })

    @Test
    fun `completing someone else's key, or a guest key with a forged mac, is a 404 that reads and deletes nothing`() {
        val others = keys.issue("acc_b", "b.png")
        storage.objects[others] = html to "image/png"
        val forged = "uploads/g/${java.util.UUID.randomUUID()}.${"0".repeat(32)}/file.png"
        storage.objects[forged] = html to "image/png"
        val reads = storage.reads.size
        val deletes = storage.deleted.size

        complete("/api/v1/storage/complete", others, "acc_a").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("STORAGE.OBJECT_NOT_FOUND"))
        complete("/api/v1/storage/guest/complete", forged).andExpect(status().isNotFound)
        complete("/api/v1/storage/guest/complete", others).andExpect(status().isNotFound)

        assertEquals(reads, storage.reads.size, "no byte of a key the caller does not own is read")
        assertEquals(deletes, storage.deleted.size, "and none is deleted")
        assertTrue(others in storage.objects && forged in storage.objects)
    }

    @Test
    fun `a refused completion answers 400 with the reason and deletes the caller's one object, for members and guests`() {
        val mine = keys.issue("acc_a", "a.png")
        val guest = keys.issueGuest("image/png")
        val keep = keys.issue("acc_a", "keep.png")
        listOf(mine, guest, keep).forEach { storage.objects[it] = html to "image/png" }

        complete("/api/v1/storage/complete", mine, "acc_a").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STORAGE.FILE_REJECTED")).andExpect(jsonPath("$.data.errors[0].code").value("CONTENT_MISMATCH"))
        complete("/api/v1/storage/guest/complete", guest).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("STORAGE.FILE_REJECTED"))

        assertTrue(mine !in storage.objects && guest !in storage.objects)
        assertTrue(keep in storage.objects, "an object that was not completed is untouched")
    }
}
