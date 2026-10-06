package dev.sumin.skeleton.idempotency

/**
 * `Idempotency-Key` 가 필수인 명령. [ignoredBodyFields] 는 **지문에 넣지 않을 JSON 본문 필드 이름**(어느 깊이든)이다 — 지문은 저장소(메모리 · Redis · DB)에 남으므로
 * 비밀번호 · 토큰 같은 값은 여기 적어 지문에서 뺀다 (무염 해시는 오프라인으로 맞춰 볼 수 있다). 대가: 같은 키로 비밀 값만 다르게 다시 보내면 같은 요청으로 본다 —
 * 키는 사용자 행동 하나당 새로 만든다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IdempotentOperation(val ignoredBodyFields: Array<String> = [])
