# captcha-turnstile

Cloudflare Turnstile 토큰을 서버에서 검증하는 `TurnstileVerifier` 를 준다.
외부 호출은 `platform` 의 `ExternalHttpClient` 를 쓴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:captcha-turnstile"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.captcha-turnstile` — [docs/config/modules/captcha-turnstile.yml](../config/modules/captcha-turnstile.yml) |
| 기본 동작 | 꺼짐. `enabled=true` 와 `secret-key` 로 켠다. |
| 부팅에 필요한 것 | 없음. 켤 때 Turnstile secret 과 인터넷이 필요하다. |
| 교체 지점 | `TurnstileVerifier` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/captcha-turnstile` |
| 테스트 | `modules/captcha-turnstile/src/test` |

자세히: [Turnstile 상세](../captcha-turnstile.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
