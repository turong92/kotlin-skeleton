# auth-social-google

Google OAuth 토큰 교환 · 프로필 조회 HTTP 클라이언트를 `auth-social` 의 제공자로 등록한다.
클라이언트 id · secret 만 있으면 된다. 별도 설정 접두사는 없고 `auth-social` 블록의 `providers` 아래에 둔다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-social-google"))` |
| 함께 오는 모듈 | `platform`, `auth-social`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | 없음 (설정은 `auth-social` 블록의 providers.google 아래) — [auth-social 블록](../config/modules/auth-social.yml) |
| 기본 동작 | 꺼짐. `skeleton.auth-social.providers.google.enabled=true` 로 켠다. |
| 부팅에 필요한 것 | 없음. 클라이언트 id · secret 은 켤 때 필요하다. |
| 교체 지점 | `GoogleOAuthProvider` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-social-google/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
