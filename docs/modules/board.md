# board

게시판: 게시판(코드로 구분) · 글 · **대댓글이 되는 댓글 트리** · **종류를 설정으로 늘리는 반응**(좋아요/싫어요 → 공감 · 슬픔 …). 서비스 · 정책 · HTTP · 설정이 이 모듈이고,
저장은 어댑터 `board-jdbc`(PostgreSQL · MySQL)가 한다 — 이 모듈에는 메모리 구현이 없다. 설계 근거는 아래 "Decisions and rejected alternatives".

HTTP 는 `skeleton.board.http.base-path`(기본 `/api/v1/boards`)에 열린다 (서블릿 웹 앱 + Spring Security 가 있을 때. 호출자는 `Authentication.name` = 계정 id, 운영자는 `ROLE_<moderator-role>` 권한):

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `GET /config` | 반응 종류 · 모드 · 깊이 상한 · 길이 상한 · `canModerate` | 로그인 |
| `GET /` · `GET /{code}` · `POST /` | 게시판 목록(글 수 포함) · 하나 · 만들기 | 로그인 · 로그인 · 운영자 |
| `GET /{code}/posts?page&size&sort=latest\|reactions\|comments&q&reaction&status&mine` | 글 목록(페이지 envelope). 고정 글이 항상 먼저. `reaction=<코드>` 는 그 종류의 개수로 정렬. `q` 는 제목 · 본문 | 로그인 |
| `GET /{code}/posts/{id}` | 글 상세 (호출마다 조회수 +1). 숨김 · 삭제 · 임시저장은 작성자 · 운영자 외에는 404 | 로그인 |
| `POST` · `PATCH` · `DELETE /{code}/posts[/{id}]` | 쓰기(201 + Location, `Idempotency-Key`) · 고치기 · 소프트 삭제(204) | 로그인 · 작성자/운영자 · 작성자/운영자 |
| `PUT /{code}/posts/{id}/moderation` | `{status?: PUBLISHED\|HIDDEN\|DELETED, pinned?}` | 운영자 |
| `GET` · `POST /{code}/posts/{id}/comments` | 최상위 댓글 페이지 + 각 스레드의 **모든 자손**(평평하게, 작성 순) · 댓글/답글(`parentId`) 쓰기 | 로그인 |
| `PATCH` · `DELETE /…/comments/{cid}` · `PUT …/moderation` | 고치기(작성자) · 소프트 삭제(작성자/운영자) · `{status}` 숨김/복구/삭제(운영자) | |
| `PUT` · `DELETE /…/posts/{id}/reactions` · `…/comments/{cid}/reactions` | `{type}` 로 반응 · 떼기(`?type=` 없으면 내 반응 전부). 응답 `{counts, myReactions}` | 로그인 |

`status`(운영자) · `mine=true`(내 글 — 임시저장 포함) 목록 필터, 에러 코드는 `BOARD.*` ([에러 표](../errors.md)에 같은 모양): `NOT_FOUND` · `POST_NOT_FOUND` · `COMMENT_NOT_FOUND` 404 ·
`FORBIDDEN` 403 · `REACTION_TYPE_INVALID` · `CONTENT_INVALID` 400 · `COMMENT_TOO_DEEP` 422 · `POST_NOT_COMMENTABLE` · `CODE_TAKEN` 409 · `RATE_LIMITED` 429.

**계정 삭제**: `account` 가 계정을 지울 때(platform 의 `AccountErasureListener`) 글 · 댓글 작성자와 반응의 계정이 계정마다 하나인 톰스톤(`deleted:<해시>`)으로 바뀐다 — 행 · 카운터는 남고 응답에 `authorDeleted: true` 가 실려 화면이 "삭제된 사용자" 를 보인다.
앱이 자기 저장소를 두면 `BoardErasureRepository` 도 구현한다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:board"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `notification`, `idempotency` |
| 설정 접두사 | `skeleton.board` — [docs/config/modules/board.yml](../config/modules/board.yml) |
| 기본 동작 | 켜짐. 게시판은 `seed-boards` 로 심거나 운영자가 만든다 — 아무것도 심지 않으면 비어 있다. 반응은 LIKE · DISLIKE, SINGLE 모드. 알림 · 한도는 없는 것과 같다. |
| 부팅에 필요한 것 | 저장소 포트 네 개(`BoardRepository` · `PostRepository` · `CommentRepository` · `ReactionRepository`) — `board-jdbc` 가 내거나 앱이 직접 구현한다. 없으면 시작이 실패하고 메시지가 빠진 빈 이름을 적는다. |
| 교체 지점 | `BoardErasureRepository`, `BoardPolicy`, `BoardRateLimiter`, `BoardNotifier`, `BoardNotificationFormatter`, `BoardRepository`, `PostRepository`, `CommentRepository`, `ReactionRepository`, `BoardController`, `PostController`, `CommentController` |
| 마이그레이션 | 없음 (스키마는 `board-jdbc`) |
| 프론트 짝 | `@skeleton/board` |
| 테스트 | `modules/board/src/test`, `modules/board/src/noOptionalTest` (`notification` · `idempotency` 가 클래스패스에 없을 때) |

## 반응 종류를 늘리는 법

코드 · 스키마 변경 없이 설정만: `skeleton.board.reaction.types: [LIKE, EMPATHY, SAD]` (대문자 코드). 서버는 `GET /config` 의 `reactionTypes` 로 프론트에 알리고, 모르는 코드는 `400 BOARD.REACTION_TYPE_INVALID`.
설정에서 뺀 종류의 옛 반응은 DB 에 남지만 응답의 `counts` · `myReactions` 에는 나오지 않는다 (합계 `reaction_count` 에는 남는다). `mode: PER_TYPE` 으로 바꾸면 종류마다 하나씩 여러 종류를 함께 누를 수 있다 (역방향 전환은 SINGLE 이 다음 반응 때 그 사람의 다른 종류를 지운다).

## 앱이 바꾸는 곳

- 누가 무엇을 하는지: `BoardPolicy` (기본: 작성자 · `moderator-role`). 상태 규칙(숨겨진 글엔 댓글 불가 등)은 서비스에 있다.
- 첨부 키 검사: `BoardPolicy.canAttach` — 기본은 허용이다. 키 규칙은 앱의 저장소 설정이 안다 (`storage` 의 키는 `<접두사>/<계정 id>/…` 라 `startsWith` 한 줄로 막을 수 있다).
- 글 · 댓글 텍스트: **HTML 은 해석하지 않는다.** 제어문자(줄바꿈 · 탭 제외) · 글자 방향 덮어쓰기 문자를 지우고 공백을 다듬어 저장하며, 그릴 때 클라이언트가 이스케이프한다.
- 한도: `skeleton.board.rate-limit.enabled=true` 면 platform 의 `RateLimitStore`(redis-rate-limit 이 있으면 Redis)에 계정 · 동작별 고정 창. 한도 로직을 바꾸려면 `BoardRateLimiter`.
- 알림 문구: `BoardNotificationFormatter` (제목 · 본문). 보내는 곳 자체는 `BoardNotifier`.
- 검색은 제목 · 본문 `LIKE '%q%'`(와일드카드 이스케이프, 대소문자 무시)다. 데이터가 커지면 PostgreSQL `tsvector`/GIN 이나 MySQL `FULLTEXT`, 또는 외부 검색으로 `PostRepository.page` 만 바꾸면 된다.

## Decisions and rejected alternatives

**모듈 분리: `board` + `board-jdbc`.** `notification`/`notification-jdbc`, `storage`/`storage-s3` 와 같은 모양 — 계약 · 서비스 · HTTP 는 저장 방식을 모르고, 어댑터가 포트를 구현한다. 앱이 JPA · jOOQ 로 저장하고 싶으면 포트 네 개만 구현하면 된다.
버린 것: *한 모듈*(PostgreSQL · MySQL SQL 이 서비스와 섞이고, 저장소를 못 바꾼다), *메모리 기본 구현*(게시판은 영속이 본질이라 메모리 기본값은 "켰는데 재시작하면 사라짐" 이라는 함정이 된다. 알림과 달리 안전하게 퇴화할 수 있는 기능이 아니다 — 그래서 어댑터가 없으면 시작이 실패한다).
`board` 는 `board-jdbc` 에 의존하지 않으므로 `new-project.sh` 에는 `--modules board,board-jdbc` 라고 둘 다 적는다.

**댓글 깊이: 트리를 저장하고 상한은 설정.** `parent_id` + `root_id` + `depth`. 최상위 댓글만 페이지로 나누고 그 모든 자손은 `root_id IN (…)` 한 쿼리로 가져와 서버에서 평평하게 내려준다 (클라이언트가 `parentId`/`depth` 로 접는다) — 목록 쿼리 수가 댓글 수와 무관하다 (`JdbcQueryCountDbTest`).
`max-comment-depth` 기본 2 (댓글 → 대댓글 → 대대댓글), 넘으면 422. 버린 것: *깊이 1 로 고정*(대댓글의 대댓글을 못 단다), *무한 중첩 + 재귀 CTE*(UI 가 못 그리고 PostgreSQL / MySQL 문법이 갈린다), *materialized path*(이동이 없는 게시판에는 과하다). 부모를 지우면 자식은 그대로 — 아래 소프트 삭제.

**반응: 문자열 코드 + 유니크 키 하나로 두 모드.** 테이블 `(target_type, target_id, account_id, reaction_type)` 가 기본 키(= 유니크 키)다. 종류는 열거형 · 칼럼 하나씩이 아니라 `VARCHAR` 코드라 설정(`reaction.types`)만 늘리면 된다.
버린 것: *`liked`/`disliked` 불리언 칼럼*(종류를 늘릴 때마다 스키마 변경), *계정당 한 행 `(target, account)` 유니크*(PER_TYPE 이 불가능 — 모드를 바꾸려면 마이그레이션), *종류별 테이블*.
`SINGLE` 은 같은 트랜잭션에서 `delete other types` 후 삽입한다. **동시성**: 같은 계정이 LIKE · DISLIKE 를 동시에 누르면 서로의 삭제를 못 봐서 둘 다 들어간다 → 모든 반응 변경이 **대상 행(글 또는 댓글)을 `FOR UPDATE` 로 먼저 잠근다**. 그 행은 카운터 UPDATE 가 어차피 잠그므로 새 경합이 아니다.
증명: `JdbcConcurrencyDbTest` 가 PostgreSQL · MySQL 에서 40 스레드로 돌고, 잠금을 빼면 중복 키 · 교착으로 실패한다 (변이 시험으로 확인).

**카운터: 비정규화 합계 + 종류별은 묶어서 센다.** `comment_count`(PUBLISHED 댓글 수) · `reaction_count`(모든 종류의 합) · `view_count` 는 글 · 댓글 행에 두고 `set x = x + :delta` 원자적 UPDATE 로, 바꾸는 행과 같은 트랜잭션에서 맞춘다 (정렬 `sort=reactions|comments` 가 이 칼럼을 쓴다).
종류별 개수는 쪽마다 `GROUP BY` 쿼리 한 번(`(target_type, target_id, reaction_type)` 인덱스)으로 센다 — 항상 정확하고, 종류가 설정이라 카운터 행이 종류마다 필요 없다. 버린 것: *종류별 카운터 테이블 `board_reaction_counts`*(upsert 문법이 방언마다 다르고 종류를 지우거나 모드를 바꿀 때 재계산이 필요하다. 특정 종류로 정렬하는 `reaction=` 은 상관 서브쿼리라 느리지만 선택 기능이다 — 커지면 이 테이블이 업그레이드 경로다),
*읽을 때마다 `count(*)`*(글 목록 정렬이 풀스캔).
교착 방지 규칙: 행 잠금 순서는 글 → 댓글 하나뿐이고, 댓글 삽입은 삽입 *전에* 글 카운터를 올린다 (InnoDB 의 FK 검사가 부모에 공유 락을 걸어 삽입 후 UPDATE 는 락 업그레이드 교착이 난다).

**소프트 삭제: 행을 남기고 본문만 가린다.** 글은 `status=DELETED`, 댓글은 `DELETED`/`HIDDEN` — 목록에 그대로 있고 `body` 만 `null`(이유는 `status`). 스레드 모양이 유지되고 대댓글이 고아가 되지 않는다. 운영자 복구도 상태만 되돌린다. 버린 것: *하드 삭제*(대댓글 · 반응이 매달린다), *자식이 있을 때만 소프트*(규칙이 둘이 된다).

**알림 훅: 클래스패스에 있을 때만.** `notification` 은 `compileOnly` + `@ConditionalOnClass` 자동설정(`BoardNotificationAutoConfiguration`)이 `BoardNotifier` 를 등록한다. 내 글에 댓글이 달리거나 내 댓글에 답글이 달리면(자기 자신 제외) 한 건. 문구는 `BoardNotificationFormatter`, 알림 실패는 댓글 작성을 막지 않는다.
`noOptionalTest` 가 `notification` · `idempotency` 없이 부팅과 요청을 시험한다. 버린 것: *하드 의존*(알림이 필요 없는 앱에 브로커가 따라온다), *Spring 이벤트로 발행*(받는 쪽이 따로 있어야 하고 사용 예가 한 줄 더 는다).

**멱등 · 한도: 선택 통합.** 글 · 댓글 만들기에 `@IdempotentOperation` 을 단다 — `idempotency` 가 있으면 `Idempotency-Key` 가 **필수**가 된다 (그 모듈에 선택 모드가 없다). 없으면 애너테이션은 JVM 이 건너뛴다. 한도는 새 인터페이스 `BoardRateLimiter` — `redis-rate-limit` 에 의존하지 않고 platform 의 `RateLimitStore` 만 쓴다.

**검색: LIKE.** 와일드카드(`%` `_`)와 이스케이프(`!`)를 글자 그대로 찾도록 이스케이프한다 (`escape '!'` 는 두 DB 에서 같다). 풀텍스트는 업그레이드 경로 (위).

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md) · [스키마 관리](../schema-management.md)
