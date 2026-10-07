# author-profile

한 계정이 **여러 작성자 프로필**(닉네임)을 만들어 글 · 댓글마다 골라 쓰게 한다 — 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않게. `account` 위에 얹힌 선택 모듈이다(`auth-magic-link` 와 같은 방식): 스키마 · `account` 코드 변경 없이 platform 의 고리 넷을 채운다.
설계 · 위협 모델 · 누출 경로 전수표: `docs/superpowers/specs/2026-10-07-author-profiles-design.md`.

- **프로필** = 공개 작성자 id(`prf_` + 무작위 128비트) + 이름 + 꼬리표. 이름은 `skeleton.account.display-name.*`(규칙 · 예약어 · 겹침 방식)를 그대로 따르고, 꼬리표는 프로필마다 독립 추첨이다. 계정당 살아 있는 프로필 수 상한 · 만들기 횟수 한도는 `skeleton.author-profile` 의 것이고 모두 계정 기준이다.
- **한도**: 개수 상한은 살아 있는 프로필 수(첫 프로필 포함)이고 상한 검사와 저장은 한 번에 원자적이다. **만들기 한도**는 표에서 센다(지운 것 포함, 실패한 시도는 세지 않는다 — 재시작 · 인스턴스 여럿에도 같다). **이름 바꾸기 한도**는 계정의 `display-name.change-limit`/`change-window` 숫자를 모든 프로필 합산으로 쓰되 계정 닉네임 변경과는 **따로** 센다(프로필 전용 카운터) — `AccountRateLimits` 를 쓰므로 인스턴스가 여럿이면 내구성 있는 `RateLimitStore`(예: `redis-rate-limit`)가 필요하다.
- **지우기**는 즉시이고 되돌릴 수 없다 — 글은 "삭제된 사용자"로 남고 다른 프로필로 옮기지 않는다. 남의 · 없는 · 지워진 프로필 id 는 모두 같은 `AUTHOR_PROFILE.NOT_FOUND` 이고, `AuthorIdentities.allows` 도 같은 false 다.
- **첫 프로필**: 계정이 처음 갖는 프로필. 계정 이름 · 꼬리표를 한 번 복사하고(이름이 없으면 이름 없는 프로필), `adopt-on-startup`(기본 켜짐)이 `AuthorAdoptionListener`(예: `board-jdbc`)가 가진 옛 글을 여기로 넘겨받는다 — 계정 id 커서로 끝까지 훑어 못 옮기는 계정이 뒤 계정을 굶기지 않고, 계정별 예외는 삼키며, 계정 id · 프로필 id 쌍을 로그에 남기지 않는다. 목록 조회가 부르는 `ensureFirstProfile` 도 같은 넘겨받기를 그 계정에 한 번 해 본다(실행기를 끈 앱).
- 탈퇴(`AccountErasureListener`)는 그 계정의 프로필 행을 모두 지운다. 글 쪽 `author_ref` 는 건드리지 않는다.
- `AuthorDirectory` 는 `AccountAutoConfiguration` **보다 먼저** 등록된다 — `prf_` 가 아닌 id(아직 넘겨받지 않은 옛 행의 계정 id)는 `account` 의 기본 디렉터리에 그대로 넘긴다.
- 저장소 포트(`AuthorProfileRepository`)는 [author-profile-jdbc](author-profile-jdbc.md) 가 구현한다 (`author-profile` 은 어댑터에 의존하지 않으므로 `--modules author-profile,author-profile-jdbc` 라고 둘 다 적는다). **메모리 구현은 없다** — 어댑터가 없으면 시작이 실패한다. 관리자 연결 조회는 뒤따르는 조각이다(`admin.enabled` 키는 자리만 있고 동작하지 않는다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:author-profile"))` |
| 함께 오는 모듈 | `platform`, `account`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.author-profile` — [docs/config/modules/author-profile.yml](../config/modules/author-profile.yml) |
| 기본 동작 | 켜짐. 계정당 프로필 5 개 · 30 일에 5 번 만들기, 기동할 때 옛 글 넘겨받기, HTTP `/api/v1/author-profiles`(로그인 필요). 저장소 어댑터가 없으면 기동 실패. |
| 부팅에 필요한 것 | `AuthorProfileRepository` 구현(`author-profile-jdbc`)과 `account` 의 부팅 조건. |
| 교체 지점 | `AuthorProfileRepository`, `AuthorProfileService`, `AuthorIdentities`, `AuthorDirectory`, `authorProfileErasureListener`, `authorProfileAdoptionRunner`, `AuthorProfileController`, `AuthorProfileCallers` |
| 마이그레이션 | 없음 (스키마는 `author-profile-jdbc`) |
| 프론트 짝 | `@skeleton/author-profile` |
| 테스트 | `modules/author-profile/src/test` |

## HTTP

`skeleton.author-profile.http.base-path`(기본 `/api/v1/author-profiles`)에 열린다 — 서블릿 웹 앱이고 Spring Security 가 있을 때. `skeleton.author-profile.http.enabled=false` 면 경로가 없다(서비스는 그대로).
**전부 로그인이 필요하다**(공개 경로를 내지 않는다). 호출자는 `Authentication.name`(auth: 계정 id)이고, 응답 어디에도 계정 id 가 없다. 응답 모양은 다른 모듈과 같다(`{ value }` · `{ values }`, 오류는 `ApiError`).

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /config` | `{ maxPerAccount, nameMaxLength }` |
| `GET /` | 내 살아 있는 프로필 `[{ id, name, tag, createdAt }]`(만든 순). 먼저 첫 프로필을 보장하고 그 계정의 옛 글을 넘겨받는다. `name` 은 이름 없는 첫 프로필이면 `null`, `tag` 는 꼬리표 방식이 `NONE`(또는 `UNIQUE` 의 고정 꼬리표)면 `null` |
| `POST /` `{ name }` | `201` + 프로필 하나(`Location` 포함) |
| `PATCH /{id}` `{ name }` | `200` + 프로필 하나 (과거 글에 바로 반영) |
| `DELETE /{id}` | `204` (즉시, 되돌릴 수 없다) |

오류: `400` 필드 `name`(코드는 닉네임과 같다: `Required` · `Size` · `Pattern` · `Reserved`, `COMMON.VALIDATION_FAILED`) · `409 AUTHOR_PROFILE.NAME_TAKEN` · `409 AUTHOR_PROFILE.LIMIT_REACHED` · `429 AUTHOR_PROFILE.RATE_LIMITED`(`Retry-After` 헤더와 `data.retryAfterSeconds` — 계정의 `RATE_LIMITED` 와 같은 모양) ·
남의 · 없는 · 지워진 · 모양이 틀린 id 는 모두 **같은 `404 AUTHOR_PROFILE.NOT_FOUND`**(상태 · 문구 · 본문 · 헤더가 같다 — 존재 여부를 알려 주지 않는다. 남의 id 에 잘못된 이름을 보내도 `400` 이 아니라 같은 `404`).
`Idempotency-Key` 는 이 모듈이 따로 다루지 않는다 — 앱이 `idempotency` 모듈을 끼우면 그 필터가 처리한다. 프론트가 읽는 정본 계약 문서(`docs/author-profile-http-contract.md`)는 뒤따른다.

## 탈퇴 · 넘겨받기 · 연결 차단 요약

- **탈퇴**: 계정이 지워질 때 `AccountErasureListener` 고리가 그 계정의 프로필 행을 모두 **지운다**(형제 관계가 DB 에도 남지 않는다). 글 쪽(`board`)의 `author_ref` 는 건드리지 않아 프로필마다 다른 id 가 남고 `authorDeleted` 는 글의 `author_id` 톰스톤에서 나온다.
- **넘겨받기**: 이미 글이 있는 DB 에서 켜면 기동 실행기(`adopt-on-startup`)가 `AuthorAdoptionListener`(예: `board-jdbc`)가 가진 옛 행을 계정의 첫 프로필로 옮긴다. 실행기를 끈 앱·실행 사이의 틈은 `GET /`(목록 조회)가 그 계정에 한 번 더 해 본다. 옮기기 전의 행은 `board` 가 `pending:…` 로 내보낸다(이름 없음, 계정 id 없음).
- **연결 차단**: 프로필 id(`prf_` + 무작위 128비트)는 계정 id 에서 파생하지 않고, 꼬리표는 프로필마다 독립 추첨이며, 응답 · 알림 · 로그에 계정 id 와 프로필 id 의 쌍이 실리지 않는다. 지우기는 글을 다른 프로필로 옮기지 않는다.

## 알려진 한계

- 만들기 한도(`create-limit`)를 넘었을 때의 `Retry-After` / `data.retryAfterSeconds` 는 **창 전체 길이**(`create-window`, 기본 30 일 = `2592000`)다 — 표에서 센 횟수라 "가장 오래된 만들기가 창 밖으로 나가는 시각"까지 정확히 계산하지 않는다. 프론트는 정확한 대기 시간이 아니라 "한동안"으로 보인다.
- 이름 바꾸기 한도는 계정의 `display-name.change-limit`/`change-window` **숫자만** 쓰고 카운터는 계정 닉네임 변경과 **따로** 센다(프로필 전용, 모든 프로필 합산). 카운터가 `AccountRateLimits`(= `RateLimitStore`)에 있으므로 인스턴스가 여럿이거나 재시작에도 지켜야 하면 내구성 있는 `RateLimitStore`(예: `redis-rate-limit`)가 필요하다 — 기본 메모리 저장소는 인스턴스마다 · 재시작마다 센다.
- 관리자 연결 조회(`admin.enabled`)는 아직 동작하지 않는다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
