package dev.sumin.skeleton.notification.web

import dev.sumin.skeleton.notificationtest.NotificationWebTestApplication
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [NotificationWebTestApplication::class],
    properties = ["skeleton.notification.preferences.types=NOTE_UPDATED,comment.created,reward.granted"],
)
@AutoConfigureMockMvc
class NotificationPreferencesWebTest {
    @Autowired lateinit var mockMvc: MockMvc

    private fun caller(accountId: String) = TestingAuthenticationToken(accountId, "n/a", "ROLE_USER")

    private fun mute(accountId: String, type: String, muted: Boolean) =
        mockMvc.perform(
            put("/api/v1/notifications/preferences/{type}", type).principal(caller(accountId))
                .contentType(MediaType.APPLICATION_JSON).content("""{"muted":$muted}"""),
        )

    @Test
    fun `lists every configured type in configured order, unmuted by default`() {
        mockMvc.perform(get("/api/v1/notifications/preferences").principal(caller("acc_pref_a")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.items.length()").value(3))
            .andExpect(jsonPath("$.value.items[0].type").value("NOTE_UPDATED"))
            .andExpect(jsonPath("$.value.items[1].type").value("comment.created"))
            .andExpect(jsonPath("$.value.items[2].type").value("reward.granted"))
            .andExpect(jsonPath("$.value.items[0].muted").value(false))
    }

    @Test
    fun `put mutes and unmutes one type for the caller only`() {
        mute("acc_pref_b", "comment.created", true)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.type").value("comment.created"))
            .andExpect(jsonPath("$.value.muted").value(true))

        mockMvc.perform(get("/api/v1/notifications/preferences").principal(caller("acc_pref_b")))
            .andExpect(jsonPath("$.value.items[0].muted").value(false))
            .andExpect(jsonPath("$.value.items[1].muted").value(true))
        mockMvc.perform(get("/api/v1/notifications/preferences").principal(caller("acc_pref_other")))
            .andExpect(jsonPath("$.value.items[1].muted").value(false))

        mute("acc_pref_b", "comment.created", false).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/notifications/preferences").principal(caller("acc_pref_b")))
            .andExpect(jsonPath("$.value.items[1].muted").value(false))
    }

    @Test
    fun `a type outside the list is 404 and stores nothing`() {
        mute("acc_pref_c", "SECURITY_ALERT", true).andExpect(status().isNotFound)
    }

    @Test
    fun `without an authenticated caller it is 401`() {
        mockMvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized)
        mockMvc.perform(
            put("/api/v1/notifications/preferences/NOTE_UPDATED").contentType(MediaType.APPLICATION_JSON).content("""{"muted":true}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a body without muted is 400`() {
        mockMvc.perform(
            put("/api/v1/notifications/preferences/NOTE_UPDATED").principal(caller("acc_pref_d"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isBadRequest)
    }
}

@SpringBootTest(classes = [NotificationWebTestApplication::class])
@AutoConfigureMockMvc
class NotificationPreferencesDefaultWebTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `with no configured types the list is empty and every put is 404`() {
        val caller = TestingAuthenticationToken("acc_pref_e", "n/a", "ROLE_USER")
        mockMvc.perform(get("/api/v1/notifications/preferences").principal(caller))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.items.length()").value(0))
        mockMvc.perform(
            put("/api/v1/notifications/preferences/NOTE_UPDATED").principal(caller).contentType(MediaType.APPLICATION_JSON).content("""{"muted":true}"""),
        ).andExpect(status().isNotFound)
    }
}

@SpringBootTest(classes = [NotificationWebTestApplication::class], properties = ["skeleton.notification.inbox.enabled=false"])
@AutoConfigureMockMvc
class NotificationPreferencesDisabledWebTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `the preferences endpoints go away together with the inbox`() {
        mockMvc.perform(get("/api/v1/notifications/preferences").principal(TestingAuthenticationToken("acc", "x", "ROLE_USER")))
            .andExpect(status().isNotFound)
    }
}
