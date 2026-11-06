package dev.sumin.skeleton.authorprofile.nooptional

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.authorprofile.T0
import dev.sumin.skeleton.authorprofiletest.AuthorProfileWebTestApplication
import dev.sumin.skeleton.authorprofiletest.FakeAuthorProfileStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `idempotency` 모듈이 클래스패스에 **없는** 앱 — 컨트롤러는 그대로 뜨고 Idempotency-Key 없이 만들어진다 (`@IdempotentOperation` 은 JVM 이 건너뛴다) */
@SpringBootTest(classes = [AuthorProfileWebTestApplication::class])
@AutoConfigureMockMvc
@Import(FakeAuthorProfileStore::class)
class NoOptionalIdempotencyTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var accounts: AccountRepository

    @Test
    fun `the idempotency module is really absent`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.sumin.skeleton.idempotency.IdempotentOperation") }
    }

    @Test
    fun `without storage a profile has no avatar keys and a PATCH carrying avatarKey is 400`() {
        val id = "acc_no_storage"
        check(accounts.insert(Account(id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "Mina", null, null, T0, T0, displayNameKey = "mina", displayTag = null), emptyList()))
        val auth = authentication(TestingAuthenticationToken(id, "n/a", "ROLE_USER"))
        val body = mvc.perform(post("/api/v1/author-profiles").with(auth).contentType(MediaType.APPLICATION_JSON).content("""{"name":"Owl"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.avatarKey").doesNotExist()).andExpect(jsonPath("$.value.avatar").doesNotExist())
            .andReturn().response.contentAsString
        val profileId = Regex("\"id\":\"(prf_[^\"]+)\"").find(body)!!.groupValues[1]
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/author-profiles/$profileId").with(auth).contentType(MediaType.APPLICATION_JSON).content("""{"avatarKey":"uploads/o/x/file.png"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `creating works without an Idempotency-Key`() {
        val id = "acc_no_optional"
        check(accounts.insert(Account(id, "$id@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "Mina", null, null, T0, T0, displayNameKey = "mina", displayTag = null), emptyList()))
        mvc.perform(
            post("/api/v1/author-profiles").with(authentication(TestingAuthenticationToken(id, "n/a", "ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"Night Owl"}"""),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.value.name").value("Night Owl"))
    }
}
