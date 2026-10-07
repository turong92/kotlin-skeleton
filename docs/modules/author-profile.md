# author-profile

한 계정이 **여러 작성자 프로필**(닉네임)을 만들어 글 · 댓글마다 골라 쓰게 한다 — 두 프로필이 같은 사람이라는 사실이 다른 사용자에게 드러나지 않게. `account` 위에 얹힌 선택 모듈이다(`auth-magic-link` 와 같은 방식): 스키마 · `account` 코드 변경 없이 platform 의 고리 넷을 채운다.
설계 · 위협 모델 · 누출 경로 전수표: `docs/superpowers/specs/2026-10-07-author-profiles-design.md`.

- **프로필** = 공개 작성자 id(`prf_` + 무작위 128비트) + 이름 + 꼬리표. 이름은 `skeleton.account.display-name.*`(규칙 · 예약어 · 겹침 방식)를 그대로 따르고, 꼬리표는 프로필마다 독립 추첨이다. 계정당 살아 있는 프로필 수 상한 · 만들기 횟수 한도는 `skeleton.author-profile` 의 것이고 모두 계정 기준이다.
- **한도**: 개수 상한은 살아 있는 프로필 수(첫 프로필 포함)이고 상한 검사와 저장은 한 번에 원자적이다. **만들기 한도**는 표에서 센다(지운 것 포함, 실패한 시도는 세지 않는다 — 재시작 · 인스턴스 여럿에도 같다). **이름 바꾸기 한도**는 계정의 `display-name.change-limit`/`change-window` 숫자를 모든 프로필 합산으로 쓰되 계정 닉네임 변경과는 **따로** 센다(프로필 전용 카운터) — `AccountRateLimits` 를 쓰므로 인스턴스가 여럿이면 내구성 있는 `RateLimitStore`(예: `redis-rate-limit`)가 필요하다.
- **지우기**는 즉시이고 되돌릴 수 없다 — 글은 "삭제된 사용자"로 남고 다른 프로필로 옮기지 않는다. 남의 · 없는 · 지워진 프로필 id 는 모두 같은 `AUTHOR_PROFILE.NOT_FOUND` 이고, `AuthorIdentities.allows` 도 같은 false 다.
- **첫 프로필**: 계정이 처음 갖는 프로필. 계정 이름 · 꼬리표를 한 번 복사하고(이름이 없으면 이름 없는 프로필), `adopt-on-startup`(기본 켜짐)이 `AuthorAdoptionListener`(예: `board-jdbc`)가 가진 옛 글을 여기로 넘겨받는다 — 계정 id 커서로 끝까지 훑어 못 옮기는 계정이 뒤 계정을 굶기지 않고, 계정별 예외는 삼키며, 계정 id · 프로필 id 쌍을 로그에 남기지 않는다. 목록 조회가 부르는 `ensureFirstProfile` 도 같은 넘겨받기를 그 계정에 한 번 해 본다(실행기를 끈 앱).
- **첨부(`storage`)를 함께 쓰면** 기본 키(`<prefix>/<계정 id>/…`)가 공개 `attachments` 로 나가 두 프로필의 글을 잇는다 — `skeleton.storage.web.key-owner: OPAQUE` 로 바꾸고 비밀 `<P>_STORAGE_WEB_KEY_SECRET`(32자 이상, 배포 선언의 `secrets:`; 비면 기동 실패)을 준다. board 는 계정 id 가 든 새 첨부 키를 `400 BOARD.CONTENT_INVALID` 로 거절하는 안전망만 갖는다(`apps/sample` 이 이 선택을 켠다).
- 넘겨받기 한계: `adopted_at` 이 채워진 계정(옛 행이 없던 계정 포함)은 목록 조회가 다시 옮기지 않는다 — `adopt-on-startup=false` 인 앱이나 모듈을 끈 기간에 `author_ref` 없는 행이 **나중에** 생기면 다음 기동의 실행기(`adopt-on-startup=true`)가 옮기고, 그 전에는 `pending:` 으로 나간다. `adopt` 가 0 을 돌려주면 "옮길 것이 없었다"로 보고 끝낸 것으로 센다.
- 탈퇴(`AccountErasureListener`)는 그 계정의 프로필 행을 모두 지운다. 글 쪽 `author_ref` 는 건드리지 않는다.
- `AuthorDirectory` 는 `AccountAutoConfiguration` **보다 먼저** 등록된다 — `prf_` 가 아닌 id(아직 넘겨받지 않은 옛 행의 계정 id)는 `account` 의 기본 디렉터리에 그대로 넘긴다.
- 저장소 포트(`AuthorProfileRepository`)는 [author-profile-jdbc](author-profile-jdbc.md) 가 구현한다 (`author-profile` 은 어댑터에 의존하지 않으므로 `--modules author-profile,author-profile-jdbc` 라고 둘 다 적는다). **메모리 구현은 없다** — 어댑터가 없으면 시작이 실패한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:author-profile"))` |
| 함께 오는 모듈 | `platform`, `account`, `auth` |
| 컴파일 전용 | `idempotency` |
| 설정 접두사 | `skeleton.author-profile` — [docs/config/modules/author-profile.yml](../config/modules/author-profile.yml) |
| 기본 동작 | 켜짐. 계정당 프로필 5 개 · 30 일에 5 번 만들기, 기동할 때 옛 글 넘겨받기, HTTP `/api/v1/author-profiles`(로그인 필요), 관리자 연결 조회는 꺼짐(`admin.enabled`). 저장소 어댑터가 없으면 기동 실패. |
| 부팅에 필요한 것 | `AuthorProfileRepository` 구현(`author-profile-jdbc`)과 `account` 의 부팅 조건. |
| 교체 지점 | `AuthorProfileRepository`, `AuthorProfileService`, `AuthorIdentities`, `AuthorDirectory`, `authorProfileErasureListener`, `authorProfileAdoptionRunner`, `AuthorProfileController`, `AuthorProfileCallers`, `AuthorLinkLookup`, `AuthorProfileAdminController` |
| 마이그레이션 | 없음 (스키마는 `author-profile-jdbc`) |
| 프론트 짝 | `@skeleton/author-profile` |
| 테스트 | `modules/author-profile/src/test`, `modules/author-profile/src/noOptionalTest` (`idempotency` 가 클래스패스에 없을 때) |

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
**만들기(`POST`)는 `@IdempotentOperation`** 이다 — 앱이 `idempotency` 모듈을 끼우면 `Idempotency-Key` 헤더가 **필수**(없으면 `400 COMMON.IDEMPOTENCY_ERROR`)이고 같은 키의 두 번째 요청은 프로필을 또 만들지 않고 첫 응답을 재생한다(프론트가 네트워크 오류를 같은 키로 재시도하는 계약). 모듈이 없으면 애너테이션은 JVM 이 건너뛰고 키 없이 만들어진다(`idempotency` 는 `compileOnly`, `src/noOptionalTest` 가 증명). 이름 바꾸기 · 지우기는 멱등 표시가 없다. 프론트가 읽는 정본 계약 문서: `docs/author-profile-http-contract.md`(모든 엔드포인트의 요청/응답 JSON · 오류 코드 표 · `Idempotency-Key` · 첫 프로필 · board 쪽 변화 · 관리자 조회).

## 관리자 연결 조회 (`skeleton.author-profile.admin.enabled`, 기본 꺼짐)

프로필 ↔ 계정 연결을 **관리자만** 볼 수 있는 별도 엔드포인트다(설계 §7.9). 기본 꺼짐 — 켜지 않으면 컨트롤러 빈도 경로도 없다(사용자용 `http.enabled` 와 따로 켜고 끈다). 서블릿 웹 앱 + Spring Security 에서만 등록된다.

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/admin/author-profiles/{id}` | 그 프로필의 계정과 그 계정의 프로필들 |
| `GET /api/v1/admin/author-profiles?accountId=` | 계정의 프로필들 (`accountId` 가 없으면 `400`) |

- 응답 `{ value: { accountId, profiles: [{ id, name, tag, status, createdAt, updatedAt, deletedAt }] } }` — 만든 순, **지워진 것 포함**(`status` `DELETED`: `name` · `tag` 는 null, `deletedAt` 있음). 계정 이메일은 싣지 않는다 — 필요하면 `GET /api/v1/admin/accounts/{id}`(account 의 관리자 조회).
- **권한**: `AccountCallers.requireAdmin` — `skeleton.account.admin.role`(기본 `ADMIN`)만, 매 호출 저장소의 계정 역할 · 상태를 다시 읽는다(토큰의 역할은 힌트). `MODERATOR`(또는 `skeleton.board.moderator-role`) · 일반 사용자 · 정지된 관리자 `403`, 익명 `401`. 게시판 운영자는 연결을 보지 못한다(글 숨김 · 삭제는 연결 없이 되고, 계정 제재는 관리자의 일).
- **감사**: 성공한 호출마다 `AccountEventType.AUTHOR_LINK_VIEWED` 를 낸다 — 대상 계정 id, `detail = { by: <관리자 계정 id> }`. **detail 에 프로필 id 가 없고 로그 한 줄에도 없다**(기본 로거는 `account=<대상> detail={by=…}` 만 찍는다). 이 이벤트는 계정 이벤트 한 종류가 늘어난 것이 `account` 의 유일한 변경이다 — `account_audit.type` 은 `varchar(40)` 문자열이라(제약 · enum 열 없음) 마이그레이션은 없다.
  - **전제**: 조회 기록이 남으려면 로그 리스너 밖의 이벤트 길이 있어야 한다 — `skeleton.account.audit.enabled=true`(+ `account-jdbc`, `account_audit` 표) 또는 앱의 `AccountEventListener`. 없으면 기록은 로그 한 줄뿐이라 stage · prod 에서 배포 가드(`AuthorProfileAdminDeployGuard`, 이름 `author-profile`)가 **경고**한다(기동은 막지 않는다).
  - **기록 실패**: 듣는 쪽이 던지면 발행자가 warn 로그만 남기고 삼킨다 — **조회는 그대로 나간다**(기록 실패로 조회를 실패시키는 것은 하지 않았다; 주인 결정 항목).
  - **한계 ①**: 대상 계정이 나중에 지워지면(`account-jdbc` 의 scrub) 그 계정의 감사 행에서 `by`(누가 봤는지)가 사라진다 — 조회 사실만 남는다.
  - **한계 ②**: 로그 한 줄에는 쌍이 없지만, 같은 `traceId` 로 요청 줄(경로의 프로필 id)과 감사 줄(대상 계정)을 이으면 **조회한 프로필 하나와 대상 계정**이 이어진다 — 로그는 신뢰 영역이다(스펙 §3, §14).
  - **교체**: `AuthorLinkLookup` 을 교체하는 앱은 감사 발행의 책임도 함께 가져간다 — 교체본이 `AUTHOR_LINK_VIEWED` 를 내지 않으면 조회가 기록 없이 나간다.
- **없는 대상**: 없는 프로필 id · 없는 계정 id 는 `404 AUTHOR_PROFILE.NOT_FOUND`(관리자에게는 구별해도 되는 선택 — 이 코드가 둘 다 쓰인다) 이고 **이벤트를 남기지 않는다** — 헛짚은 조회가 감사 표에 존재 여부의 흔적을 남기지 않게. 계정은 있는데 프로필이 없으면 `200` + 빈 목록이고 감사는 남는다.
- 요청 경로의 프로필 id 는 일반 요청 로그(`RequestLoggingFilter`)에 남는다 — 그 줄에 대상 계정 id 는 없다(위 한계 ②).

## 탈퇴 · 넘겨받기 · 연결 차단 요약

- **탈퇴**: 계정이 지워질 때 `AccountErasureListener` 고리가 그 계정의 프로필 행을 모두 **지운다**(형제 관계가 `author_profiles` 표에는 남지 않는다 — board 의 `author_ref` 는 프로필마다 다른 id 로 남는다). 글 쪽(`board`)의 `author_ref` 는 건드리지 않아 프로필마다 다른 id 가 남고 `authorDeleted` 는 글의 `author_id` 톰스톤에서 나온다.
- **넘겨받기**: 이미 글이 있는 DB 에서 켜면 기동 실행기(`adopt-on-startup`)가 `AuthorAdoptionListener`(예: `board-jdbc`)가 가진 옛 행을 계정의 첫 프로필로 옮긴다. 실행기를 끈 앱·실행 사이의 틈은 `GET /`(목록 조회)가 그 계정에 한 번 더 해 본다. 옮기기 전의 행은 `board` 가 `pending:…` 로 내보낸다(이름 없음, 계정 id 없음).
- **연결 차단**: 프로필 id(`prf_` + 무작위 128비트)는 계정 id 에서 파생하지 않고, 꼬리표는 프로필마다 독립 추첨이며, 응답 · 알림 · 로그에 계정 id 와 프로필 id 의 쌍이 실리지 않는다. 지우기는 글을 다른 프로필로 옮기지 않는다. 단 `local`/`dev` 프로필의 DevLogin · RequestLogging 로그는 (계정, 경로의 프로필 id)를 함께 찍는다 — 운영에서는 아니다.

## 알려진 한계

- 만들기 한도(`create-limit`)를 넘었을 때의 `Retry-After` / `data.retryAfterSeconds` 는 **창 전체 길이**(`create-window`, 기본 30 일 = `2592000`)다 — 표에서 센 횟수라 "가장 오래된 만들기가 창 밖으로 나가는 시각"까지 정확히 계산하지 않는다. 프론트는 정확한 대기 시간이 아니라 "한동안"으로 보인다.
- 이름 바꾸기 한도는 계정의 `display-name.change-limit`/`change-window` **숫자만** 쓰고 카운터는 계정 닉네임 변경과 **따로** 센다(프로필 전용, 모든 프로필 합산). 카운터가 `AccountRateLimits`(= `RateLimitStore`)에 있으므로 인스턴스가 여럿이거나 재시작에도 지켜야 하면 내구성 있는 `RateLimitStore`(예: `redis-rate-limit`)가 필요하다 — 기본 메모리 저장소는 인스턴스마다 · 재시작마다 센다.
- **ACTIVE 가 아닌 계정**(정지 · 삭제 유예 · 지우는 중): 새 프로필 줄을 만들지 않는다 — `POST` 는 `404 AUTHOR_PROFILE.NOT_FOUND`. `GET /` 은 **이미 프로필이 있으면 읽을 수 있다**(정지 계정도 자기 목록을 본다)지만 옛 글을 옮기지는 않고, 프로필이 한 번도 없던 계정이 정지된 뒤 부르면 첫 프로필을 새로 만들 수 없어 같은 `404` 다.

## 누출 스캔 시험과 검증 기록

**연결 누출 스캔**(스펙 §12 1~7)은 `apps/sample` 의 `AuthorLinkLeakIntegrationTest` 다 — 진짜 PostgreSQL · 보안 체인에 board · account · author-profile · notification-jdbc · storage 가 다 끼워진 앱(`allow-anonymous-read=true`)에서 이 클래스만 돌린다: `./gradlew :apps:sample:test --tests '*AuthorLinkLeakIntegrationTest'`. 시드는 `apps/sample/src/test/resources/leak-seeds.yml`, 코드는 `apps/sample/src/test/kotlin/.../leak/`.
**이 스캔을 쓰는 앱**(작성자 프로필을 켜는 자기 앱): 이 폴더와 `leak-seeds.yml` 을 **자기 앱의 시험으로 옮기고**, 앱이 더한 표 · 읽기 경로를 추가한다 — `AuthorLinkColumns` 의 공개 / 비공개 표에 새 표의 열을, `LeakSweep` 에 새 읽기 경로를 적는다. 스켈레톤의 스캔은 샘플 앱의 경로만 안다.

- 계정 X 가 프로필 A(첫 프로필) · B · C 를 만들고, A 로 글 · 임시저장 글, B 로 글(둘 다 OPAQUE 첨부), Y 의 글에 A 로 댓글 · B 로 자기 댓글에 답글 · B 로 Y 의 댓글에 답글 · C 로 댓글, 반응 몇 개. **계정 이름은 첫 프로필이 복사해 간 뒤에 바꾼다**(금지 값인 현재 계정 이름이 A 의 공개 이름과 달라야 스캔이 둘을 구별한다). 켜기 전의 **옛 행**(`author_ref` 없는 글)도 SQL 로 하나 심는다.
- 독자 셋이 **각자** 읽고 따로 스캔한다 — Y(일반 사용자), Z(비로그인), 운영자(`MODERATOR`: 숨김 · 임시저장 글, 글 · 댓글 숨김 / 복구 응답). 경로: 게시판 · config · 글 목록(정렬 · 반응 정렬 · 검색 · 쪽 · `mine` · `status`) · 상세 · 댓글 스레드(정렬 셋) · 오류 응답 · 반응 PUT/DELETE 응답 · Y 의 받은편지함 · Y 가 SSE 로 받을 이벤트(구독자로 받아 같은 JSON) · X 의 프로필 id 로 `PATCH`/`DELETE`/`GET`/글 쓰기를 찔러 본 응답(없는 id · 모양이 틀린 id · `pending:` 꼴과 함께 상태 · 본문 · 헤더가 하나로 같다) · 값 찾기(`q=<X 의 계정 id>` · `q=<X 의 이메일>` 은 0건, OpenAPI 문서 `/api/v1/docs` 본문도 같은 스캔). 본문과 헤더를 모아 **금지 값**(X 의 계정 id · 이메일 · `AccountTombstone.of` · 현재 계정 이름; 대소문자 · URL 인코딩 무시)이 없음을, 그리고 A · B · C · 옛 행의 조각(응답 객체를 `authorId` 로 묶어 잎 값을 뽑는다)이 **값으로** 겹치지 않음을 단정한다. 허용 목록은 게시판 코드 · 글 상태 · 반응 종류 · 알림 종류 · 두 조각이 놓인 같은 글(Y 의 것)의 제목 · 알림 제목뿐이고, 숫자는 **`종류.칸` 별**로만 허용한다(댓글의 `postId` = Y 의 글, `id` · `rootId` · `parentId` = 스레드 뿌리 댓글 둘; 개수 · 깊이 칸은 늘 허용; null · 불리언은 비교하지 않는다). 첨부 키는 `/` 마디를 다시 `.` 로 쪼개 `uuid` 와 `mac` 을 각각 비교한다(고정 접두사와 `file[.확장자]` 만 공통 허용).
- **스캔이 값을 찾는다는 증명이 먼저다**: `LeakScanTest`(합성 응답에 심은 값 · 겹치는 값 · 한 마디만 같은 첨부 키를 **찾고** 허용 값은 넘긴다)와 통합 시험 1번(X 가 `/account/me` 를 읽으면 같은 스캔 함수가 계정 id · 이메일 · 계정 이름을 잡고, 비공개 열 `board_posts.author_id` 에서 계정 id 를, 탈퇴 뒤 톰스톤을 잡는다), 2번(허용 목록을 **비우면** 같은 진짜 응답에서 짝 스캔이 `general` · `PUBLISHED` · 글 번호를 공통 값으로 찾는다). 매 단계 · 독자마다 A · B 의 조각 · 알림 · 첨부 키 · C 와 옛 행의 조각이 실제로 스캔에 들어갔는지도 단정한다(빈 스캔이 통과로 보이지 않게).
- **상태를 바꿔 반복**: 기준선 → B 프로필 삭제 → 관리자 정지 → 정지 해제 · 본인의 탈퇴 요청(**유예 중**: 프로필은 그대로 살아 있다) → 유예를 지나 지우기(`purgeDue`) → 둘째 계정 V 의 **관리자 지우기 경로**(정지 → `POST /admin/accounts/{id}/erase`, 두 프로필의 글이 서로 다른 `authorId` · `authorDeleted: true` · 이름 없음. Y · Z · 운영자의 읽기 + Y 의 받은편지함 · SSE · 프로브 + 공개 열 전체에 V 의 값이 없고 V1 · V2 의 조각 · `author_ref` 행이 값으로 겹치지 않음). 각 단계에서 `authorId` 는 늘 프로필 id(옛 행은 `pending:p<번호>`)이고 A · B 가 서로 다르며(`deleted:` 값이 어떤 응답에도 없다), `authorDeleted` · `authorName` 이 맞게 나온다(삭제한 프로필 · 탈퇴 뒤 `true` · `null`, 정지 · 유예는 아무 표시 없음, 옛 행은 탈퇴 뒤에도 `pending` 이고 `authorDeleted` 만 `true`).
- **DB 열 분류(§12 7)**: `board*` · `notification*` · `author_profile*` 의 모든 열을 `information_schema` 에서 읽어 `AuthorLinkColumns` 의 공개 / 비공개 표와 맞춘다(분류되지 않은 열 · 표에만 있는 열이 있으면 실패). PostgreSQL 은 위 통합 시험의 컨텍스트에서(공개 열 — `author_ref` · 남의 편지함 payload · 탈퇴 전의 `author_profiles` 행 포함 — 의 값에서 금지 값 · 짝 스캔을 단계마다 다시), MySQL 은 `AuthorLinkColumnsMySqlTest`(Flyway 로 `db/migration/mysql` 만 끝까지 적용한 MySQL 컨테이너의 열 목록).
- 조립: 작성자 빈을 끼우지 않은 board(`modules/board` 의 `NoOptionalGoldenKeysTest`)의 응답 키가 작성자 프로필 이전(`e76e8e7`)과 같고 덧붙은 것이 `mine`(글 요약 · 상세 · 댓글) · `authorRequired`(config)뿐임을 키 집합으로 고정한다. 모듈을 끼우지 않은 `apps/api` 의 시험은 한 줄도 바뀌지 않는다.

### 훑지 않은 경로 (정직하게)

실제 SSE 소켓(구독자로 받은 같은 이벤트 JSON 으로 대신한다) · `POST /storage/presign-download` 와 멀티파트 응답 · 액추에이터(`/health` 만 열려 있다) · 로그 · 브라우저 안의 상태 · 운영자 / DB 직접 접근 · MySQL 의 **값**(열 목록만 본다) · 통계적 상관(문체 · 시각 분포 · 글 길이). 값 찾기는 OpenAPI 문서와 `q=<계정 id | 이메일>` 검색 두 줄만 넣었다.

### 변이 확인 (손으로, 커밋하지 않고 원복)

기준 `9c585d4`(a · b · c, 2026-10-08)와 그 위의 B7 리뷰 반영(d, 같은 날).

| # | 변이 (한 줄) | 결과 |
|---|---|---|
| (a) | `board/web/BoardDtos.kt` — 글 요약 · 상세 · 댓글 `toResponse` 의 `publicAuthorId` 자리에 계정 id(`item.authorId` / `post.authorId` / `authorId`) | `AuthorLinkLeakIntegrationTest` 의 기준선 · B 삭제 · 정지 · 탈퇴 단계가 **"forbidden values in responses"** 로 실패(앞 셋은 `account id`, 탈퇴 뒤는 `tombstone`). 열 분류 시험과 1번(양성 대조)은 통과 |
| (b) | `board-jdbc` `JdbcBoardErasureRepository.anonymizeAuthor` — `set author_id = :t` 를 `set author_id = :t, author_ref = :t` 로(글 · 댓글) | **탈퇴 단계만** 같은 단정으로 실패(`tombstone` 값이 Y · Z 의 읽기 응답에 나옴). 기준선 · B 삭제 · 정지 단계는 통과 |
| (c) | `board/web/BoardDtos.kt` — `BoardConfigResponse` 에 `val sneaky: Boolean = false` 추가 | `NoOptionalGoldenKeysTest` 실패(`e76e8e7` 키 + `authorRequired` 와 다른 집합) |
| (d) | `JdbcBoardErasureRepository.anonymizeAuthor` — 글 · 댓글 갱신에 `updated_at = timestamptz '2026-01-01 00:00:00+00'` 를 덧붙임(탈퇴가 모든 행의 `updated_at` 을 같은 시각으로) | **짝 스캔만** 잡는다 — 탈퇴 단계가 `values shared by prf_… and prf_…: [string 2026-01-01T00:00:00Z]` 로 실패하고, 금지 값 단정은 통과한 뒤였다. 나머지 단계는 통과 |

네 변이 모두 원복했고(`git checkout`), 원복 뒤 같은 시험이 통과한다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
