# author-profile

한 계정이 **여러 작성자 프로필**(닉네임)을 만들어 글 · 댓글마다 골라 쓰게 한다 — 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않게. `account` 위에 얹힌 선택 모듈이다(`auth-magic-link` 와 같은 방식): 스키마 · `account` 코드 변경 없이 platform 의 고리 넷을 채운다.
설계 · 위협 모델 · 누출 경로 전수표: `docs/superpowers/specs/2026-10-07-author-profiles-design.md`.

- **프로필** = 공개 작성자 id(`prf_` + 무작위 128비트) + 이름 + 꼬리표. 이름은 `skeleton.account.display-name.*`(규칙 · 예약어 · 겹침 방식)를 그대로 따르고, 꼬리표는 프로필마다 독립 추첨이다. 계정당 살아 있는 프로필 수 상한 · 만들기 횟수 한도는 `skeleton.author-profile` 의 것이고 모두 계정 기준이다.
- **한도**: 개수 상한은 살아 있는 프로필 수(첫 프로필 포함)이고 상한 검사와 저장은 한 번에 원자적이다. **만들기 한도**는 표에서 센다(지운 것 포함, 실패한 시도는 세지 않는다 — 재시작 · 인스턴스 여럿에도 같다). **이름 바꾸기 한도**는 계정의 `display-name.change-limit`/`change-window` 숫자를 모든 프로필 합산으로 쓰되 계정 닉네임 변경과는 **따로** 센다(프로필 전용 카운터) — `AccountRateLimits` 를 쓰므로 인스턴스가 여럿이면 내구성 있는 `RateLimitStore`(예: `redis-rate-limit`)가 필요하다.
- **지우기**는 즉시이고 되돌릴 수 없다 — 글은 "삭제된 사용자"로 남고 다른 프로필로 옮기지 않는다. 남의 · 없는 · 지워진 프로필 id 는 모두 같은 `AUTHOR_PROFILE.NOT_FOUND` 이고, `AuthorIdentities.allows` 도 같은 false 다.
- **첫 프로필**: 계정이 처음 갖는 프로필. 계정 이름 · 꼬리표를 한 번 복사하고(이름이 없으면 이름 없는 프로필), `adopt-on-startup`(기본 켜짐)이 `AuthorAdoptionListener`(예: `board-jdbc`)가 가진 옛 글을 여기로 넘겨받는다 — 계정 id 커서로 끝까지 훑어 못 옮기는 계정이 뒤 계정을 굶기지 않고, 계정별 예외는 삼키며, 계정 id · 프로필 id 쌍을 로그에 남기지 않는다. 목록 조회가 부르는 `ensureFirstProfile` 도 같은 넘겨받기를 그 계정에 한 번 해 본다(실행기를 끈 앱).
- 탈퇴(`AccountErasureListener`)는 그 계정의 프로필 행을 모두 지운다. 글 쪽 `author_ref` 는 건드리지 않는다.
- `AuthorDirectory` 는 `AccountAutoConfiguration` **보다 먼저** 등록된다 — `prf_` 가 아닌 id(아직 넘겨받지 않은 옛 행의 계정 id)는 `account` 의 기본 디렉터리에 그대로 넘긴다.
- 저장소 포트(`AuthorProfileRepository`)는 `author-profile-jdbc` 가 구현한다. **메모리 구현은 없다** — 어댑터가 없으면 시작이 실패한다. HTTP(`/author-profiles`) · 관리자 연결 조회는 뒤따르는 조각이다(`http.*` · `admin.*` 키는 자리만 있다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:author-profile"))` |
| 함께 오는 모듈 | `platform`, `account`, `auth` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.author-profile` — [docs/config/modules/author-profile.yml](../config/modules/author-profile.yml) |
| 기본 동작 | 켜짐. 계정당 프로필 5 개 · 30 일에 5 번 만들기, 기동할 때 옛 글 넘겨받기. 저장소 어댑터가 없으면 기동 실패. |
| 부팅에 필요한 것 | `AuthorProfileRepository` 구현(`author-profile-jdbc`)과 `account` 의 부팅 조건. |
| 교체 지점 | `AuthorProfileRepository`, `AuthorProfileService`, `AuthorIdentities`, `AuthorDirectory`, `authorProfileErasureListener`, `authorProfileAdoptionRunner` |
| 마이그레이션 | 없음 |
| 프론트 짝 | 없음 (프론트 패키지 @skeleton/author-profile 은 뒤따르는 조각이다) |
| 테스트 | `modules/author-profile/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
