package dev.sumin.skeleton.notification.advertising

import java.time.Instant

/**
 * 「재확인 안내를 이 동의 주기에 이미 보냈다」 표시 - 광고 동의를 2년마다 다시 확인받는 안내를 동의 주기당 한 번만 보내려는 기록(채널과 무관).
 * 구현은 `notification-jdbc`(푸시를 쓰는 앱은 `notification-push-jdbc` 의 `PushAdvertisingNoticeStore` 가 이 계약을 겸한다).
 * [agreedAt] 은 그 주기를 연 동의 사건의 시각이다(재확인하면 새 주기 = 새 표시).
 */
interface AdvertisingNoticeStore {
    fun isNoticed(accountId: String, type: String, agreedAt: Instant): Boolean

    /** 이미 있으면 아무 일도 없다 */
    fun markNoticed(accountId: String, type: String, agreedAt: Instant, at: Instant)
}
