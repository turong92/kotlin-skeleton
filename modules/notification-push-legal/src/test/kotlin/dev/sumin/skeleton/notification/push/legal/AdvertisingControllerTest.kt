package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import dev.sumin.skeleton.notification.legal.LegalAdvertisingConsents
import dev.sumin.skeleton.notification.push.legal.web.AdvertisingController
import java.time.Duration
import kotlin.test.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AdvertisingControllerTest {
    private val kit = LegalKit()
    private val properties = AdvertisingProperties(types = listOf("promo.tips"), fallbackZone = "Asia/Seoul", senderName = "Example Co.")
    private val consents = LegalAdvertisingConsents(kit.service, properties)
    private val controller = AdvertisingController(consents, AdvertisingGate(properties, null, null, kit.clock), properties, kit.clock)
    private val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    private val caller = TestingAuthenticationToken("acc_a", "n/a", "ROLE_USER")

    @Test
    fun `an agreed caller sees the state, expiry and the night window the contract promises`() {
        kit.agree("acc_a")

        mockMvc.perform(get("/api/v1/push/advertising").principal(caller))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.advertisingTypes[0]").value("promo.tips"))
            .andExpect(jsonPath("$.value.senderName").value("Example Co."))
            .andExpect(jsonPath("$.value.consent.type").value("marketing"))
            .andExpect(jsonPath("$.value.consent.state").value("AGREED"))
            .andExpect(jsonPath("$.value.consent.agreedAt").value("2026-10-07T00:00:00Z"))
            .andExpect(jsonPath("$.value.consent.expiresAt").value("2028-10-06T00:00:00Z"))
            .andExpect(jsonPath("$.value.consent.reconfirmDue").value(false))
            .andExpect(jsonPath("$.value.consent.effective").value(true))
            .andExpect(jsonPath("$.value.night.type").value("marketing-night"))
            .andExpect(jsonPath("$.value.night.state").value("NONE"))
            .andExpect(jsonPath("$.value.night.agreedAt").doesNotExist())
            .andExpect(jsonPath("$.value.night.effective").value(false))
            .andExpect(jsonPath("$.value.nightWindow.start").value("21:00"))
            .andExpect(jsonPath("$.value.nightWindow.end").value("08:00"))
            .andExpect(jsonPath("$.value.nightWindow.zone").value("Asia/Seoul"))
    }

    @Test
    fun `an expired caller sees EXPIRED and nothing effective`() {
        kit.agree("acc_a")
        kit.agree("acc_a", "marketing-night")
        kit.clock.now = kit.clock.now.plus(Duration.ofDays(731))

        mockMvc.perform(get("/api/v1/push/advertising").principal(caller))
            .andExpect(jsonPath("$.value.consent.state").value("EXPIRED"))
            .andExpect(jsonPath("$.value.consent.effective").value(false))
            .andExpect(jsonPath("$.value.night.state").value("EXPIRED"))
            .andExpect(jsonPath("$.value.night.effective").value(false))
    }
}
