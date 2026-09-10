package dev.sumin.skeleton.time

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * "지금 이 요청은 어느 시간대·언어로 보여줘야 하는가". 어디서도 `ZoneId.systemDefault()` 를 쓰지 않는다.
 *
 * 결정 순서 (둘 다 동일):
 * 1. 사용자 명시 설정 — [UserTimePreferences] (계정에 저장된 값)
 * 2. 요청 헤더 — 시간대는 `X-Time-Zone`(IANA), 언어는 `Accept-Language`
 * 3. 기본값 — `skeleton.time.default-zone` / `default-locale`
 *
 * 요청 밖(배치, 테스트)에서는 1·2 가 없으니 기본값. 이벤트 시간대는 여기서 정하지 않는다 — 그건 데이터([ZonedMoment.zone]).
 */
class TimeContext(
    private val props: TimeContextProperties,
    private val preferences: ObjectProvider<UserTimePreferences>,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val defaultZone: ZoneId = ZoneId.of(props.defaultZone)
    val defaultLocale: Locale = Locale.forLanguageTag(props.defaultLocale)

    /** 서버 현재 시각 (platform [TimeProvider], DB 정밀도로 절삭됨) */
    fun now(): Instant = timeProvider.now()

    fun zone(): ZoneId =
        preferences.orderedStream().map { it.zone() }.filter { it != null }.findFirst().orElse(null)
            ?: headerZone()
            ?: defaultZone

    fun locale(): Locale =
        preferences.orderedStream().map { it.locale() }.filter { it != null }.findFirst().orElse(null)
            ?: requestLocale()
            ?: defaultLocale

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    private fun headerZone(): ZoneId? {
        val raw = currentRequest()?.getHeader(props.zoneHeader)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { ZoneId.of(raw) }.getOrElse {
            log.debug("Ignoring invalid {} header: {}", props.zoneHeader, raw)
            null
        }
    }

    private fun requestLocale(): Locale? {
        // 헤더가 실제로 있을 때만. 없으면 서블릿이 서버 기본 로케일을 지어내므로(en) 우리 기본값으로 넘긴다
        currentRequest()?.getHeader("Accept-Language")?.takeIf { it.isNotBlank() } ?: return null
        // Spring MVC 의 LocaleResolver(기본 AcceptHeaderLocaleResolver)가 Accept-Language 를 여기 넣어둔다
        return LocaleContextHolder.getLocaleContext()?.locale
    }
}
