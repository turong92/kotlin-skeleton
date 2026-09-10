package dev.sumin.skeleton.time

import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountryTimeZonesTest {
    @Test
    fun `시간대가 하나인 나라는 자동 확정`() {
        assertEquals(ZoneId.of("Asia/Seoul"), CountryTimeZones.defaultZoneOf("KR"))
        assertEquals(ZoneId.of("Asia/Tokyo"), CountryTimeZones.defaultZoneOf("jp"))
        assertTrue(CountryTimeZones.isSingleZone("KR"))
    }

    @Test
    fun `여러 개인 나라는 대표값 + 선택지`() {
        assertEquals(ZoneId.of("America/New_York"), CountryTimeZones.defaultZoneOf("US"))
        assertEquals(ZoneId.of("America/Sao_Paulo"), CountryTimeZones.defaultZoneOf("BR"))
        assertEquals(ZoneId.of("Australia/Sydney"), CountryTimeZones.defaultZoneOf("AU"))
        assertTrue(CountryTimeZones.zonesOf("US").size > 5)
        assertTrue(ZoneId.of("America/Los_Angeles") in CountryTimeZones.zonesOf("US"))
    }

    @Test
    fun `모르는 국가는 빈 목록, 표는 200개국 이상`() {
        assertTrue(CountryTimeZones.zonesOf("XX").isEmpty())
        assertTrue(CountryTimeZones.countries.size > 200)
    }
}
