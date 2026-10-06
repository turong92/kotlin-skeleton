# auth-social

소셜 로그인의 제공자 중립 계약(`OAuthProviderRegistry`, 계정 연결, 가입 정책)과 `/auth/social/*` 라우팅이다.
제공자(Google · Kakao · Naver)는 각자의 모듈이 등록한다. 제공자 모듈이 없으면 라우트는 있어도 쓸 수 있는 제공자가 없다.

`OAuthUserProfile.emailVerified` 는 제공자가 이메일을 **확인했다고** 알려 준 경우만 true 다 (Google `email_verified` · Kakao `is_email_verified`; Naver 는 알리지 않아 false). `account` 가 있으면 연결 표 · 가입 정책이 계정의 로그인 수단 표로 바뀌고,
확인된 이메일이 기존 계정과 겹쳐도 기본은 병합하지 않는다 ([계정 수명주기](../accounts.md)).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social"))` |
| 함께 오는 모듈 | `platform`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-social` — [docs/config/modules/auth-social.yml](../config/modules/auth-social.yml) |
| 기본 동작 | 켜짐. 환경변수 `<P>_AUTH_SOCIAL_PROVIDERS_<X>_CLIENT_ID` 같은 이름은 `AuthSocialEnvironmentAliasPostProcessor` 가 점 표기 프로퍼티로 옮겨 준다(Map 값이라 스프링 느슨한 바인딩만으로는 묶이지 않는다). 토큰 교환 거절은 제공자 `error` 코드와 함께 로그에 남는다. 제공자가 켜지기 전에는 로그인 가능한 제공자가 없다. |
| 부팅에 필요한 것 | 없음. |
| 교체 지점 | `OAuthProviderRegistry`, `OAuthAccountLinkRepository`, `OAuthAccountProvisioningPolicy`, `OAuthSocialLoginService` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
