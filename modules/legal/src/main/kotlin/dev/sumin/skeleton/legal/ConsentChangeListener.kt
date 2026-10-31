package dev.sumin.skeleton.legal

/**
 * 동의 사건이 **저장된 뒤** 불린다 - 동의 · 철회 · 재확인이 어느 경로(`legal` HTTP · 가입 · 앱 코드)로 와도 같은 자리에서 통지를 보내려는 고리.
 * 빈이 없으면 아무 일도 없다. 던지는 리스너는 삼킨다(증거는 이미 저장됐고, 다음 리스너도 불린다). 트랜잭션 안에서 불릴 수 있으므로(가입) 무거운 일은 하지 않는다.
 * [event] 는 방금 저장한 사건이다 - 저장소가 붙이는 `id` 는 읽지 않아 0 이다.
 */
fun interface ConsentChangeListener {
    fun onChange(event: ConsentEvent)
}

/** 이 `source` 로 온 동의는 이미 현재 판에 동의한 상태에서도 사건을 더한다 - 유효 기간을 새로 시작하는 재확인 */
const val RECONFIRM_SOURCE = "reconfirm"
