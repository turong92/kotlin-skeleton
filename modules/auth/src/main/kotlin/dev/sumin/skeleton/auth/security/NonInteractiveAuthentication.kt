package dev.sumin.skeleton.auth.security

/**
 * 표지 - 이 [org.springframework.security.core.Authentication] 은 **화면 앞의 사람이 아니라 프로그램의 자격**이다(API 키 · 서비스 토큰 …).
 * 「사람에게만 거는 문」(예: 2단계 인증 등록 필수)이 이 표지를 읽어 건너뛴다 - 자격을 낸 모듈(`api-key`)과 문을 낸 모듈(`auth-mfa`)이 서로를 모르고도 만나게 하는 자리다.
 * [BearerTokenAuthenticator] 가 돌려주는 `Authentication` 이 구현한다. 사람의 세션 · JWT · 개발용 로그인은 구현하지 않는다.
 */
interface NonInteractiveAuthentication
