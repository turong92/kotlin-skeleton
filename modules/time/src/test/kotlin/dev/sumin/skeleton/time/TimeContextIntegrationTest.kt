package dev.sumin.skeleton.time

import dev.sumin.skeleton.timetest.TimeTestApplication
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@SpringBootTest(classes = [TimeTestApplication::class], properties = ["skeleton.time.default-zone=Asia/Seoul", "skeleton.time.default-locale=ko-KR"])
@AutoConfigureMockMvc
@Import(TimeContextIntegrationTest.FixedClock::class)
class TimeContextIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider.fixed(Instant.parse("2026-09-10T00:00:00Z"))
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `아무 정보 없으면 기본값, JSON 은 Instant=Z, LocalDate=날짜만, ZonedMoment=local+zone+at`() {
        mockMvc.perform(get("/probe"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.now").value("2026-09-10T00:00:00Z"))
            .andExpect(jsonPath("$.zone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.locale").value("ko-KR"))
            .andExpect(jsonPath("$.birthday").value("1998-03-05"))
            .andExpect(jsonPath("$.deadline.local").value("2026-10-05T21:00:00"))
            .andExpect(jsonPath("$.deadline.zone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.deadline.at").value("2026-10-05T12:00:00Z"))
            .andExpect(jsonPath("$.deadline.zoneId").doesNotExist())
    }

    @Test
    fun `X-Time-Zone 과 Accept-Language 헤더를 따른다, 잘못된 시간대는 무시`() {
        mockMvc.perform(get("/probe").header("X-Time-Zone", "America/Sao_Paulo").header("Accept-Language", "pt-BR"))
            .andExpect(jsonPath("$.zone").value("America/Sao_Paulo"))
            .andExpect(jsonPath("$.locale").value("pt-BR"))

        mockMvc.perform(get("/probe").header("X-Time-Zone", "Mars/Olympus"))
            .andExpect(jsonPath("$.zone").value("Asia/Seoul"))
    }

    @Test
    fun `dual 은 이벤트 시간대와 보는 사람 시간대를 같이, 같은 오프셋이면 viewer 없음`() {
        mockMvc.perform(get("/dual").header("X-Time-Zone", "America/Sao_Paulo").header("Accept-Language", "en-US"))
            .andExpect(jsonPath("$.event").value(org.hamcrest.Matchers.containsString("GMT+9")))
            .andExpect(jsonPath("$.viewer").value(org.hamcrest.Matchers.containsString("GMT-3")))
            .andExpect(jsonPath("$.viewer").value(org.hamcrest.Matchers.containsString("9:00")))

        mockMvc.perform(get("/dual").header("X-Time-Zone", "Asia/Tokyo"))
            .andExpect(jsonPath("$.viewer").doesNotExist())
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Prefs {
        @Bean
        fun accountPrefs() = object : UserTimePreferences {
            override fun zone() = ZoneId.of("Europe/Berlin")
            override fun locale(): Locale = Locale.GERMANY
        }
    }

    @Nested
    @SpringBootTest(classes = [TimeTestApplication::class])
    @AutoConfigureMockMvc
    @Import(Prefs::class)
    inner class WithPreferences {
        @Autowired
        lateinit var mockMvc: MockMvc

        @Test
        fun `계정 설정이 헤더보다 우선한다`() {
            mockMvc.perform(get("/probe").header("X-Time-Zone", "America/Sao_Paulo").header("Accept-Language", "pt-BR"))
                .andExpect(jsonPath("$.zone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.locale").value("de-DE"))
        }
    }
}
