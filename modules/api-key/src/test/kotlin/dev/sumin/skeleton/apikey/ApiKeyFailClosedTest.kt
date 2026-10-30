package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.apikeytest.ApiKeyTestApplication
import dev.sumin.skeleton.apikeytest.ApiKeyTestBeans
import java.time.Instant
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 문(접근 인터셉터)이 빠진 조립에서는 키가 아예 해석되지 않는다 - 살아 있는 키도 어느 경로에서든 401 (조용한 fail-open 이 없다) */
@SpringBootTest(
    classes = [ApiKeyTestApplication::class],
    properties = ["spring.autoconfigure.exclude=dev.sumin.skeleton.apikey.web.ApiKeyWebAutoConfiguration"],
)
@AutoConfigureMockMvc
@Import(ApiKeyTestBeans::class)
class ApiKeyFailClosedTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeApiKeyStore

    @Test
    fun `without the web auto-configuration a live key is not honoured anywhere`() {
        val token = ApiKeyTokens("apk").generate()
        store.put(
            ApiKey(
                "key_live", ApiKeyOwnerTypes.ACCOUNT, "acc_x", "acc_x", "ci", token.hash, token.hint, setOf("notes.read"),
                null, null, null, null, null, null, Instant.now(),
            ),
        )

        mvc.perform(get("/api/v1/account/me").header("Authorization", "Bearer ${token.token}")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/probe/read").header("Authorization", "Bearer ${token.token}")).andExpect(status().isUnauthorized)
    }
}
