package dev.sumin.skeleton.notification.web

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notificationtest.NotificationWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [NotificationWebTestApplication::class])
@AutoConfigureMockMvc
class NotificationInboxWebTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var inbox: NotificationInboxRepository

    private fun caller(accountId: String) = TestingAuthenticationToken(accountId, "n/a", "ROLE_USER")

    private fun saveFor(accountId: String, topic: String): String {
        val event = NotificationEvent(topic = topic, type = "t", title = "hello $topic")
        inbox.save(event, setOf(accountId))
        return event.id
    }

    @Test
    fun `lists only the caller's inbox as a page envelope`() {
        saveFor("acc_a", "list-a")
        saveFor("acc_b", "list-a")

        mockMvc.perform(get("/api/v1/notifications").param("topic", "list-a").principal(caller("acc_a")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(1)))
            .andExpect(jsonPath("$.values[0].recipientId").value("acc_a"))
            .andExpect(jsonPath("$.pagination.totalElements").value(1))
    }

    @Test
    fun `marks one read and then all read`() {
        val id = saveFor("acc_c", "read-c")
        saveFor("acc_c", "read-c")

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", id).principal(caller("acc_c")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.eventId").value(id))
        mockMvc.perform(patch("/api/v1/notifications/read-all").principal(caller("acc_c")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.updated").value(1))
        assertEquals(0L, inbox.findByRecipient("acc_c", dev.sumin.skeleton.notification.NotificationInboxQuery(unreadOnly = true)).totalElements)
    }

    @Test
    fun `an unknown or someone else's notification is 404`() {
        val id = saveFor("acc_d", "nf-d")

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", id).principal(caller("acc_other")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `rows carry link and collapsed count`() {
        val first = NotificationEvent(topic = "fold-e", type = "t", link = "/notes/1", collapseKey = "k")
        inbox.save(first, setOf("acc_e"))
        inbox.save(NotificationEvent(topic = "fold-e", type = "t", link = "/notes/1", collapseKey = "k"), setOf("acc_e"))
        saveFor("acc_e", "fold-e")

        mockMvc.perform(get("/api/v1/notifications").param("topic", "fold-e").principal(caller("acc_e")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.values", hasSize<Any>(2)))
            .andExpect(jsonPath("$.values[?(@.eventId == '${first.id}')].collapsedCount").value(2))
            .andExpect(jsonPath("$.values[?(@.eventId == '${first.id}')].link").value("/notes/1"))
            .andExpect(jsonPath("$.values[?(@.collapsedCount == 1)].link").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
    }

    @Test
    fun `unread-count is the caller's own unread number in the value envelope`() {
        val id = saveFor("acc_f", "uc-f")
        saveFor("acc_f", "uc-f")
        saveFor("acc_g", "uc-f")

        mockMvc.perform(get("/api/v1/notifications/unread-count").principal(caller("acc_f")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.count").value(2))
        inbox.markRead("acc_f", id)
        mockMvc.perform(get("/api/v1/notifications/unread-count").principal(caller("acc_f")))
            .andExpect(jsonPath("$.value.count").value(1))
        mockMvc.perform(get("/api/v1/notifications/unread-count").principal(caller("acc_nobody")))
            .andExpect(jsonPath("$.value.count").value(0))
    }

    @Test
    fun `unread-count without an authenticated caller is 401`() {
        mockMvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `without an authenticated caller it is 401`() {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized)
    }
}

@SpringBootTest(classes = [NotificationWebTestApplication::class], properties = ["skeleton.notification.inbox.enabled=false"])
@AutoConfigureMockMvc
class NotificationInboxWebDisabledTest {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `the endpoints are gone when disabled`() {
        mockMvc.perform(get("/api/v1/notifications").principal(TestingAuthenticationToken("acc", "x", "ROLE_USER")))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/notifications/unread-count").principal(TestingAuthenticationToken("acc", "x", "ROLE_USER")))
            .andExpect(status().isNotFound)
    }
}
