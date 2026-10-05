# auth-magic-link

**메일로 보낸 한 번 쓰는 링크**로 로그인한다 — 같은 계정 위에 얹히는 로그인 수단 하나(`magic_link`)다. 인증 · 재설정과 **같은 토큰 장치**(`OneTimeTokens`: 해시만 저장 · 한 번 · 용도 구분)를 쓰고,
증명이 곧 메일함 소유라서 같은 이메일의 기존 계정에 붙고 그 이메일은 확인된 것으로 친다. 링크를 처음 쓰는 이메일에 계정을 **만들지**는 앱이 `skeleton.auth-magic-link.sign-up=true` 로 정한다(기본 아니오).
요청은 계정 존재 여부와 무관하게 늘 202다. 스키마 · `account` 변경 없음.

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `POST /api/v1/auth/magic-link/request` | 링크 메일 (늘 202, 한도 넘으면 조용히 · IP 한도는 429) | 공개 |
| `POST /api/v1/auth/magic-link/redeem` | 링크 토큰 → 토큰 응답 (`ACCOUNT.TOKEN_INVALID` 410) | 공개 |

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:auth-magic-link"))` |
| 함께 오는 모듈 | `account`, `auth`, `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.auth-magic-link` — [docs/config/modules/auth-magic-link.yml](../config/modules/auth-magic-link.yml) |
| 기본 동작 | 켜짐. 링크 15분 · 한 번, 이미 있는 계정으로만(가입 닫힘). 메일은 `account` 의 메일 길(`notification-mail` 또는 로그)로 간다. |
| 부팅에 필요한 것 | `account` 의 부팅 조건과 같다 (메일 발송기 — 없으면 링크가 전달되지 않는다). |
| 교체 지점 | `MagicLinkService`, `MagicLinkController`, `magicLinkSignInMethod` |
| 마이그레이션 | 없음 |
| 프론트 짝 | `@skeleton/auth` |
| 테스트 | `modules/auth-magic-link/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
