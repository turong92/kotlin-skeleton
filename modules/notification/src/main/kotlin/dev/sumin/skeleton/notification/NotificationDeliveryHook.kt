package dev.sumin.skeleton.notification

import org.slf4j.LoggerFactory

/** 채널이 기기마다 끝낸 푸시 하나의 결과. 알림함 저장은 채널이 아니므로 여기에 없다 */
enum class DeliveryOutcome {
    /** 전달 수단이 받았다 */
    DELIVERED,

    /** 보내지 못했다(기기 사라짐 · 재시도를 다 씀 · 영구 실패) */
    FAILED,

    /** 보내지 않기로 했다(기기 없음 · 주인 바뀜 · 만료 · 끔 · 광고 동의 없음 · 멈춘 공지) */
    DROPPED,
}

/**
 * 채널의 전달 과정을 듣는 고리 - 대량 발송처럼 「이 알림이 어디까지 갔나」를 세는 쪽이 빈으로 올린다(여럿 가능). 모든 메서드는 기본이 아무 일도 안 한다.
 * 받는 사람 id 는 싣지 않는다(통계만). 호출자([NotificationDeliveryHooks])가 던진 예외를 삼키므로 구현이 던져도 전달은 멈추지 않는다.
 */
interface NotificationDeliveryHook {
    /** 보내기 직전에 묻는다 - true 면 이 알림은 더 보내지 않는다(큐에서 기다리던 것 포함). 같은 질문이 잦으니 구현이 싸야 한다 */
    fun halted(eventId: String): Boolean = false

    /** 채널이 [count] 개의 전달 작업을 넣었다 */
    fun onQueued(eventId: String, channel: String, count: Int) {}

    fun onOutcome(eventId: String, channel: String, outcome: DeliveryOutcome) {}

    /** 받는 사람이 그 알림을 눌러 연 **첫** 한 번(같은 사람의 두 번째부터는 부르지 않는다) */
    fun onOpened(eventId: String, channel: String) {}
}

/** 빈으로 올라온 [NotificationDeliveryHook] 들을 한 번에 부른다 - 고리가 던지면 로그만 남기고 삼킨다(`halted` 는 던지면 false). 목록은 부를 때 읽는다(빈 순환을 피한다) */
class NotificationDeliveryHooks(private val hooks: () -> List<NotificationDeliveryHook>) {
    constructor(vararg hooks: NotificationDeliveryHook) : this({ hooks.toList() })

    private val log = LoggerFactory.getLogger(javaClass)

    fun halted(eventId: String): Boolean = hooks().any { hook -> safely(false) { hook.halted(eventId) } }

    fun onQueued(eventId: String, channel: String, count: Int) = hooks().forEach { hook -> safely(Unit) { hook.onQueued(eventId, channel, count) } }

    fun onOutcome(eventId: String, channel: String, outcome: DeliveryOutcome) = hooks().forEach { hook -> safely(Unit) { hook.onOutcome(eventId, channel, outcome) } }

    fun onOpened(eventId: String, channel: String) = hooks().forEach { hook -> safely(Unit) { hook.onOpened(eventId, channel) } }

    private fun <T> safely(fallback: T, call: () -> T): T =
        try {
            call()
        } catch (e: Exception) {
            log.warn("notification delivery hook failed exception={}", e.javaClass.simpleName)
            fallback
        }
}

/** 붙어 있는 채널 하나의 이름표 - 채널 모듈(푸시 …)이 빈으로 올리면 공지 화면 같은 곳이 고를 칸 목록을 얻는다. [key] 는 [NotificationEvent.channels] 가 쓰는 이름 */
data class NotificationChannelDeclaration(val key: String)
