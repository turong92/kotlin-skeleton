package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider

/** 오래 안 본 기기 줄을 지운다(`last_seen_at < now − stale-after`). 화면 · 앱이 열릴 때마다 `PUT` 으로 `last_seen_at` 을 갱신하므로 한 달 넘게 안 열린 기기가 대상이다 */
class PushStaleCleanup(
    private val devices: PushDeviceStore,
    private val properties: PushProperties.StaleCleanup,
    private val time: TimeProvider,
) {
    fun run(): Int = devices.deleteStale(time.now().minus(properties.staleAfter))
}
