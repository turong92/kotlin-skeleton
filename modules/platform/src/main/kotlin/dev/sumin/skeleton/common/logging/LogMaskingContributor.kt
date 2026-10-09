package dev.sumin.skeleton.common.logging

/**
 * 모듈 · 앱이 [LogMasker] 에 **가림 규칙을 더하는** 자리 — 빈으로 내놓으면 `skeleton.redaction.output.patterns` 뒤에 붙는다(기본 규칙과 설정 규칙은 그대로).
 * 정규식은 [LogMasker] 의 앱 규칙과 같다: 일치한 부분 전체를 가리되 이름 있는 그룹 `value` 가 있으면 그 그룹만.
 * 모듈이 자기 경로의 비밀 조각(주소에 실린 토큰)을 가리는 데 쓴다 — 앱이 yml 에 규칙을 따로 넣지 않아도 된다.
 *
 * 규칙은 두 곳에 닿는다: logback 줄([LogMasker.mask], 기본 · 설정 규칙 뒤)과 **요청 로그의 경로**([LogMasker.maskRequestPath] — 경로에는 기여자의 규칙만, 512자로 자른 뒤).
 * 경로는 인증 · 한도보다 앞에서 아무 호출자에게서 오므로 **정규식은 입력 길이에 제곱으로 느려지지 않게** 쓴다(반복은 유한하게 · 앞에 고정 글자를 둔다).
 *
 * 빈이 하나도 없으면 동작은 이전과 같다(요청 로그의 경로는 그대로). 규칙은 [LogMasker] 를 만들 때 한 번 읽는다.
 */
fun interface LogMaskingContributor {
    fun patterns(): List<String>
}
