package dev.sumin.skeleton.time

import java.time.ZoneId
import java.util.Locale

/**
 * 현재 요청 사용자의 명시적 설정 (계정에 저장된 시간대/언어). SPI — 이 모듈은 계정을 모른다.
 * `libs/auth` 가 `AccountPrincipal` 로 구현해 빈으로 등록한다. 없으면 헤더 → 기본값.
 */
interface UserTimePreferences {
    fun zone(): ZoneId? = null
    fun locale(): Locale? = null
}
