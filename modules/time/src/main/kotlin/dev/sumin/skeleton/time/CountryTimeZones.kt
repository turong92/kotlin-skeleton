package dev.sumin.skeleton.time

import org.slf4j.LoggerFactory
import java.time.ZoneId

/**
 * 국가(ISO 3166-1 alpha-2) → IANA 시간대. "아티스트 국가를 고르면 시간대가 자동으로" 용.
 *
 * - 시간대가 하나인 나라(KR, JP …)는 [defaultZoneOf] 로 확정
 * - 여러 개인 나라(US, BR, AU …)는 [defaultZoneOf] 가 대표값(수도/최대 도시)이고 [zonesOf] 로 선택지를 준다
 * - **결과는 저장해라.** 국가만 저장하고 매번 계산하면 이 표를 고쳤을 때 기존 데이터의 시각이 움직인다
 *
 * 데이터는 tzdata `zone.tab` 에서 `scripts/gen-country-zones.py` 로 생성한 `country-zones.tsv`.
 * 현재 JDK 의 tzdb 가 모르는 zone 은 로드 시 건너뛴다 (JDK tzdata 가 시스템보다 오래된 경우).
 */
object CountryTimeZones {
    private val log = LoggerFactory.getLogger(javaClass)

    private val table: Map<String, List<ZoneId>> by lazy { load() }

    fun zonesOf(countryCode: String): List<ZoneId> = table[countryCode.uppercase()] ?: emptyList()

    fun defaultZoneOf(countryCode: String): ZoneId? = zonesOf(countryCode).firstOrNull()

    fun isSingleZone(countryCode: String): Boolean = zonesOf(countryCode).size == 1

    val countries: Set<String> get() = table.keys

    private fun load(): Map<String, List<ZoneId>> {
        val stream = javaClass.getResourceAsStream("/dev/sumin/skeleton/time/country-zones.tsv")
            ?: error("country-zones.tsv missing from classpath")
        val known = ZoneId.getAvailableZoneIds()
        val skipped = mutableListOf<String>()
        val map = stream.bufferedReader().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (cc, _, all) = line.split('\t')
                cc to all.split(',').filter { z -> (z in known).also { ok -> if (!ok) skipped += z } }.map(ZoneId::of)
            }
        if (skipped.isNotEmpty()) log.warn("country-zones: {} zone(s) unknown to this JDK's tzdata, skipped: {}", skipped.size, skipped)
        return map
    }
}
