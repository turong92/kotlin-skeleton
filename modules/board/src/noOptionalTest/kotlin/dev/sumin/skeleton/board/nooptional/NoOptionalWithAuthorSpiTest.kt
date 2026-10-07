package dev.sumin.skeleton.board.nooptional

import dev.sumin.skeleton.common.author.AuthorIdentities
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestConfiguration(proxyBeanMethods = false)
class FakeIdentitiesConfig {
    @Bean fun authorIdentities(): AuthorIdentities = AuthorIdentities { account, authorId, _ -> account == "acc_user" && authorId == "prf_u1" }
}

/** 가짜 [AuthorIdentities] 빈 하나로 켜진다 — 알림 · 멱등 모듈 없이도 (선택 통합과 겹치지 않는다) */
@SpringBootTest(classes = [NoOptionalApp::class])
@AutoConfigureMockMvc
@Import(MinimalRepositories::class, FakeIdentitiesConfig::class)
class NoOptionalWithAuthorSpiTest {
    @Autowired lateinit var mvc: MockMvc

    private val user = TestingAuthenticationToken("acc_user", "n/a", "ROLE_USER")

    @Test
    fun `an AuthorIdentities bean alone switches the author rules on`() {
        mvc.perform(get("/api/v1/boards/config").principal(user)).andExpect(jsonPath("$.value.authorRequired").value(true))
        mvc.perform(post("/api/v1/boards/general/posts").principal(user).contentType(MediaType.APPLICATION_JSON).content("""{"title":"t","body":"b"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOARD.AUTHOR_REQUIRED"))
        mvc.perform(post("/api/v1/boards/general/posts").principal(user).contentType(MediaType.APPLICATION_JSON).content("""{"title":"t","body":"b","authorId":"prf_other"}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("BOARD.AUTHOR_NOT_ALLOWED"))
        mvc.perform(post("/api/v1/boards/general/posts").principal(user).contentType(MediaType.APPLICATION_JSON).content("""{"title":"t","body":"b","authorId":"prf_u1"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.authorId").value("prf_u1")).andExpect(jsonPath("$.value.mine").value(true))
    }
}
