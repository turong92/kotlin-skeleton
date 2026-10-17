package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storagetest.StorageWebTestApplication
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 기본 설정(비회원 업로드 꺼짐)에서는 익명 발급이 지금과 같은 401 이다 */
@SpringBootTest(classes = [StorageWebTestApplication::class])
@AutoConfigureMockMvc
class StorageGuestOffWebTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `with the default settings an anonymous guest presign is a 401 and nothing is signed`() {
        mockMvc.perform(post("/api/v1/storage/guest/presign").contentType(MediaType.APPLICATION_JSON).content("""{"fileName":"a.png","contentType":"image/png","sizeBytes":10}"""))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("STORAGE.UNAUTHENTICATED"))
    }
}
