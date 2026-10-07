# 작성자 프로필 — 한 계정의 여러 닉네임, 서로 이어지지 않게 — 설계 초안

- 날짜: 2026-10-07
- 요청: 주인 sumin (Ovation — 팬덤 서비스 — 의 첫 요구). 지휘 세션이 넘긴 설계점 8개를 레포에서 검증 · 반박한 결과다
- 기준 커밋: kotlin-skeleton `e76e8e7` · react-skeleton `49a5ae3` (둘 다 main)
- 상태: **승인됨(2026-10-07 주인 결정 반영)** — 맨 끝 「정해야 할 것」은 모두 결정됐다. 구현은 §13 단계 순서로 진행한다
- 경로 줄임: `B/` = `modules/board/src/main/kotlin/dev/sumin/skeleton/board/` · `BJ/` = `modules/board-jdbc/src/main/` · `A/` = `modules/account/src/main/kotlin/dev/sumin/skeleton/account/` · `P/` = `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/` · `R:` = react-skeleton 레포

## 1. 목적과 결정

한 사람이 여러 닉네임(프로필)을 갖고 **글 · 댓글마다 골라 쓴다.** 두 프로필이 같은 사람이라는 사실이 **다른 사용자에게 드러나지 않아야** 한다. 같은 이름의 서로 다른 사람은 꼬리표로 구분한다.

| 항목 | 결정 | 근거 |
|---|---|---|
| 프로필 고르기 | 글 · 댓글마다 자유 선택 | 주인: "선택지를 주고 글마다 선택하는게 맞는거같아. 그래야 자유를주지. 너무 한정되면 고객의 비밀을 못지켜주잖아. 민감할 수 있는 돌판이잖아." |
| 이름 겹침 | 막지 않고 구분만 한다 (꼬리표) | 주인: "겹치는걸 아예 막을 필요는 없을거같은데 누가 누군지 모르는것만 방지하면 될듯?" |
| `@아이디`(전체 유일 사용자명) | 넣지 않는다 | 주인: "안넣는게 맞는거같아 위 같은 이유로." |
| 요구의 본질 | **연결 차단** — 공개되는 어떤 값도 두 프로필을 잇지 않는다 | 위 세 결정에서 따라 나온다. §4 가 전수표다 |
| 남용 방지 | 계정 기준 유지 (반응 · 한도 · 정지) | 프로필을 바꿔 여러 번 누르거나 한도를 피하지 못하게. 계정 기준 장치는 본인만 관찰하므로 연결을 드러내지 않는다 (§4 5 · 8 · 17) |
| 스켈레톤 기본 | 꺼짐 = 모듈을 끼우지 않음. 그때 동작 · 응답은 지금과 같다 | 모듈 기본값은 중립, 조립식 |

## 2. 범위와 비목표

**범위**: platform SPI 둘, board 의 공개 작성자 id, 새 선택 모듈 `author-profile`(+ `-jdbc`), storage 키의 계정 id 제거, 프론트 패키지와 샘플 연결, 연결 누출을 잡는 자동 시험.

**비목표** (만들지 않는다)
- 아바타 · 소개글 · 프로필 공개 페이지 · "이 프로필의 글 모아 보기" — 표 구조만 막지 않는다.
- 사용자끼리의 차단 · 신고 · 글 수정 이력 · 반응한 사람 목록 · 게시판 실시간 이벤트 — **지금 스켈레톤에 없다**(§4). 나중에 만들 때 지킬 규칙만 적는다.
- "한 글 · 한 게시판 안에서는 처음 쓴 프로필로 고정" — 주인이 자유를 골랐다. 스키마는 나중에 설정 하나로 더할 수 있게 둔다 (§7.6).
- 돌판별 기본 프로필 등 Ovation 고유 규칙 — 훅만 남긴다 (§7.7).
- `account` 없이 자기 `AuthAccountRepository` 만 쓰는 앱 — 이 모듈은 `account` 위에 얹힌다.
- 운영자 · DB · 서버 로그로부터 숨기기, 문체 · 활동 시간대 같은 행동 상관 (§14).

## 3. 위협 모델

숨기는 것: **① 두 프로필이 같은 계정이라는 사실 ② 프로필 뒤의 계정 id · 이메일 · 계정 이름.** 숨기지 않는 것: 어느 프로필이 어느 글을 썼는지(그것이 기능이다).

| 누구에게서 | 볼 수 있는 것 | 다루는 방식 |
|---|---|---|
| 다른 로그인 사용자 | 게시판 응답 전체, 자기 받은편지함, 자기 요청의 오류 응답 | **주 대상.** §4 전수표 + §12 스캔 시험 |
| 게시판 운영자(`MODERATOR`) | 위 + 숨김 · 임시저장 글 | 다른 사용자와 같게 다룬다 — 연결을 보지 못한다 |
| 비로그인 · 검색엔진 (`allow-anonymous-read=true`, `B/BoardProperties.kt:62`) | 읽기 응답(같은 DTO) | 같은 조치로 덮인다. 호출자별 칸(`mine` · `myReactions`)이 공유 캐시에 남지 않는지는 §15 실측 |
| 같은 기기 · 화면 공유 · 어깨 너머 | 본인 화면 | 부분만: 고른 프로필 하나만 늘 보이고 목록은 눌러야 열린다, 로그아웃 때 기기 기억을 지운다. 본인 화면의 "내 글" 표시는 못 막는다(§14) |
| 관리자(`ADMIN`) · DB · 로그 | 전부 | **제외**(신뢰). 대신 연결 조회는 별도 엔드포인트 + 감사 기록, 평소 로그에는 (계정, 프로필) 쌍을 남기지 않는다 |
| 세션을 훔친 사람 | 피해자가 보는 전부 | 제외 — 계정 보안의 몫 |

## 4. 연결 누출 경로 전수표

"지금 상태"는 기준 커밋을 읽어 확인했다. 번호는 §12 시험과 §13 작업이 가리킨다.

| # | 경로 | 지금 상태 (파일:줄) | 조치 |
|---|---|---|---|
| 1 | 글 · 댓글 응답의 `authorId` | 계정 id 그대로 — `B/web/BoardDtos.kt:32,54,85` | 공개 값은 `author_ref`(프로필 id). 계정 id 는 비공개 열에만 (§7.2) |
| 2 | `authorDeleted` | 공개 `authorId` 문자열에서 계산 — `BoardDtos.kt:45,70,96` | 비공개 `author_id` 의 톰스톤 여부 또는 카드의 `deleted` 로 (§7.2) |
| 3 | 탈퇴 톰스톤 | `deleted:` + `sha256("tombstone/"+계정 id)` 앞 16자 — `P/erasure/Erasure.kt:40-41`. **소금이 없어 계정 id 를 아는 누구나 계산**하고, 계정의 모든 글 · 댓글이 **같은 값**이 된다 — `BJ/kotlin/…/JdbcRepositories.kt:373-378` | 탈퇴 때 `author_ref` 는 **건드리지 않는다** — 무작위 프로필 id 가 곧 프로필마다 다른 흔적이다. 톰스톤은 비공개 `author_id` 에만 (§7.5) |
| 4 | 댓글 알림 payload | `"authorId" to comment.authorId`(계정 id)가 **받는 사람의 편지함**에 — `B/notification/BoardNotificationAutoConfiguration.kt:56`, 그대로 응답 `modules/notification/…/web/NotificationInboxController.kt:38` | 같은 공개 id 규칙 (§7.2) |
| 5 | 알림 수신자 · 자기 글 알림 생략 | 계정끼리 비교 — `B/CommentService.kt:100-101` | **계정 기준 유지.** 프로필 A 로 쓴 글에 B 로 답해도 자기에게 알림이 안 간다 — 본인만 안다. 시험으로 고정 |
| 6 | 켜기 전에 쌓인 편지함 payload | 남의 편지함에 `acc_…` 가 남아 있다 | 수용 — 그 값은 켜기 전에 이미 공개였고 첫 프로필과만 이어진다 (§11) |
| 7 | **첨부 키** (지휘 세션 목록에 없던 것) | `<prefix>/<계정 id>/<uuid>/<파일>` — `modules/storage/…/web/StorageController.kt:198-201`. 글 상세의 `attachments` 로 공개 — `BoardDtos.kt:59` | storage 에 계정 id 없는 키 방식 + board 의 막기 (§7.8). **안 하면 첨부가 있는 글끼리 그대로 이어진다** |
| 8 | 반응 | 행은 계정 키 — `BJ/resources/db/migration/postgresql/V20261005142218__board.sql:58-65`. 응답은 개수와 본인 것뿐 — `B/ReactionSupport.kt:14` | 변경 없음. 프로필 B 로 누른 글에 A 로 또 못 누르는 것은 본인만 본다. **반응한 사람 목록은 만들지 않는다**(반응에는 프로필이 없다) |
| 9 | "내 글" 판정 | 서버: `mine=true` 목록은 계정 기준 — `B/PostService.kt:88-92`. 프론트: `accountId === authorId` — `R:packages/board/src/CommentItem.tsx:34`, `R:apps/sample/src/routes/BoardPostPage.tsx:46,80` | 켜면 프론트 비교가 **항상 거짓**이 되어 수정 · 삭제 단추가 사라진다 → 응답에 `mine` 을 싣는다(본인 응답에만 참, §8) |
| 10 | 수정으로 작성자 바꾸기 | 길이 없다 — `B/PostService.kt:58-72` | **불변**으로 못 박는다. 임시저장으로 돌린 뒤 바꾸는 것도 안 된다(PUBLISHED ↔ DRAFT 왕복이 열려 있다 — 같은 글 번호의 작성자가 A → B 로 보인다) |
| 11 | 서버가 고르는 기본 프로필 | (새 위험) | 켜지면 쓰기 요청에 `authorId` **필수.** 생략 시 조용히 다른 이름으로 올라가는 사고를 없앤다 |
| 12 | "기본 프로필" 바꾸기 | (새 위험) | 서버에 "기본" 개념을 두지 않는다. 옛 글이 "지금의 기본"을 따라가면 기본을 바꾸는 순간 두 프로필이 이어진다 → 옛 글은 넘겨받을 때 행에 박는다 (§7.4) |
| 13 | 꼬리표 | 무작위 추첨 — `A/DisplayNames.kt:205-209` | 프로필마다 독립 추첨. 계정 · 형제 프로필에서 파생하지 않는다(첫 프로필이 계정 꼬리표를 물려받는 것만 예외 — 같은 공개 인물의 연속) |
| 14 | 식별자 모양 | `acc_` + 무작위 128비트 — `A/AccountCore.kt:192` | `prf_` 도 같은 방식. **UUIDv7 · ULID · 순번 금지**(시각 · 순서가 박힌다) |
| 15 | 프로필 생성 시각 | 공개 카드는 이름 · 꼬리표뿐 — `P/author/Author.kt:28` | 유지. 시각은 본인 · 관리자 응답에만 |
| 16 | 닉네임 변경 한도 | 계정당 — `A/ProfileService.kt:56-62` | 계정 기준 유지(모든 프로필 합산) — 본인만 본다 |
| 17 | 쓰기 한도 | 계정당 — `B/BoardRateLimiter.kt:21-27` | 유지 |
| 18 | 정지 표시 | 이름 그대로, 표시 없음 — `A/AccountAuthorDirectory.kt:11-12` | 유지 — 공개 표시를 달면 모든 프로필에 동시에 붙어 이어진다 |
| 19 | 탈퇴 시각 | (새 위험) 계정이 지워지면 모든 프로필이 **같은 순간** "삭제된 사용자"가 된다 | 막지 않는다 — §14 잔여 위험. 프로필을 따로따로 먼저 지울 수 있다 |
| 20 | 오류 응답의 차이 | (새 위험) | 남의 프로필 id · 없는 id · 지워진 id 로 쓰기 → **같은 403, 같은 문구** |
| 21 | 감사 · 서버 로그 | 이벤트 detail 이 로그 한 줄로 — `A/events/AccountEvents.kt:62` | 계정 이벤트 detail 에 프로필 id 를 싣지 않는다. 연결 조회 감사에는 누가 · 어느 계정을 봤는지만 |
| 22 | 관리자 화면 | 계정 닉네임만 — `A/web/AdminAccountController.kt:118` | 연결 조회는 별도 엔드포인트(기본 꺼짐) + 감사 (§7.9) |
| 23 | 기능 끄기 | (새 위험) 모듈을 빼면 계정 id 로 되돌아갈 수 있다 | `author_ref` 가 있는 행은 모듈이 없어도 `author_ref` 를 낸다 (§11) |
| 24 | 기기 기억 | (새 위험) "이 게시판에서 마지막으로 쓴 프로필" | id 만, 계정별 키, 로그아웃 때 삭제 (§10) |
| 25 | 사용자 차단 | **없다** — `account_blocks` 는 운영자 삭제 뒤의 재가입 차단이다 (`modules/account-jdbc/…/V20261005223426__accounts.sql:72-83`) | 만들 때의 규칙: 차단은 **프로필 단위.** 계정 단위로 가리면 "A 를 차단했더니 B 도 사라졌다"가 그대로 판별기가 된다. 회피는 신고 → 관리자 → 계정 정지로 |
| 26 | 신고 · 수정 이력 · 반응자 목록 · 게시판 실시간 | 없다 (알림 SSE · WebSocket 만 — 4 와 같은 payload) | 만들 때 공개 id 만 쓴다 |
| 27 | 꼬리표 판정 기준(프론트) | `authorId` 로 묶는다 — `R:packages/board/src/authorDisplay.ts:74`, `authorScopeContext.ts:15` | 코드 변경 없음 — 값이 프로필 id 가 되면 "같은 이름 · 다른 프로필"에 꼬리표가 붙는다(같은 사람의 두 프로필도 남처럼 보인다) |

## 5. 구조 — 모듈 경계와 SPI

```
platform   AuthorDirectory (있음, 카드에 deleted 추가) · AuthorIdentities (새) · AuthorAdoptionListener (새)
   ▲                 ▲                                ▲
 board ── 공개 id 규칙 · author_ref           author-profile ── 프로필 · 고르기 검증 · 카드 · HTTP
   ▲                                              ▲        │ implementation
 board-jdbc ── 새 V 파일                 author-profile-jdbc   └─▶ account (닉네임 규칙 · 계정 이름 · 한도 · 관리자 역할)
```

board 는 `author-profile` 도 `account` 도 모른다. 빈이 없으면 지금 동작이다.

### 5.1 platform (`P/author/`)

```kotlin
/** 화면에 보일 작성자. deleted: 지워진 프로필 (기본 false — 기존 구현은 그대로 컴파일된다) */
data class AuthorCard(val name: String?, val tag: String? = null, val deleted: Boolean = false)

/**
 * 이 계정이 이 공개 작성자 id 로 써도 되는가 — 글을 쓰는 모듈이 쓰기 직전에 묻는다.
 * 빈이 없으면 공개 작성자 id 는 계정 id 다 (이 기능 이전의 동작). 구현은 없는 id · 남의 id · 지워진 id 를 구별하지 않고 false.
 */
fun interface AuthorIdentities {
    fun allows(accountId: String, authorId: String, context: AuthorContext): Boolean
}

/** 공개 작성자 id 없이 저장된 옛 행을 계정의 첫 프로필로 옮긴다 — 글을 들고 있는 모듈이 구현한다 (AccountErasureListener 와 같은 방식). 멱등 */
interface AuthorAdoptionListener {
    val name: String
    /** 아직 옮기지 않은 행을 가진 계정 id (지워진 계정 제외) */
    fun pendingAccounts(limit: Int): List<String>
    fun adopt(accountId: String, authorId: String): Int
}
```

- `AuthorDirectory.resolve` 는 **그대로**다. board 가 넘기는 id 가 계정 id 에서 공개 id 로 바뀔 뿐이다.
- "이 호출자가 쓸 수 있는 작성 신원 목록" SPI 는 **두지 않는다** — board 는 목록이 필요 없다. 목록은 프론트가 `author-profile` 의 HTTP 에서 직접 받는다.

### 5.2 새 모듈 `author-profile` (+ `author-profile-jdbc`)

- `author-profile`: 프로필 모델 · 서비스 · 저장소 포트 · HTTP · 설정(`skeleton.author-profile`). 등록하는 빈: `AuthorIdentities`, `AuthorDirectory`(`AccountAutoConfiguration` **보다 먼저** 등록 — 그쪽은 `@ConditionalOnMissingBean(AuthorDirectory::class)`, `A/AccountAutoConfiguration.kt:314-316`), `AccountErasureListener`, 넘겨받기 실행기. 모두 `@ConditionalOnMissingBean`.
- `account` 에 `implementation` 의존 — 쓰는 타입: `DisplayNames` · `DisplayNameRules`(이름 규칙 · 꼬리표), `AccountRepository.namesOf` · `displayTagsOf`, `AccountRateLimits`, `AccountCallers.requireAdmin`, `AccountEventPublisher`. `auth-magic-link` → `account` 와 같은 모양이다.
- `author-profile-jdbc`: 포트의 PostgreSQL · MySQL 구현 + 마이그레이션. 메모리 구현은 없다(board 와 같은 이유 — 재시작에 신원이 사라지면 안 된다). 어댑터가 없으면 기동 실패.
- 프로필 디렉터리는 `prf_` 가 아닌 id(아직 넘겨받지 않은 옛 행의 계정 id)는 `AccountAuthorDirectory` 에 그대로 넘긴다.
- `account` 에 생기는 변경은 하나: `AccountEventType` 에 `AUTHOR_LINK_VIEWED` 추가.

### 5.3 버린 대안

| 대안 | 버린 이유 |
|---|---|
| `account` 안의 기능(`skeleton.account.profiles.*`, 표는 `account-jdbc`) | 스타터(`apps/api`)까지 포함해 **모든 앱**이 표와 코드를 갖는다. `account` 는 이미 가장 큰 모듈이고(`AccountAutoConfiguration` 368줄) FINAL-5 계약이 또 자란다. 꺼짐을 "모듈 없음"으로 표현하는 쪽이 조립식 원칙에 맞다. (장점이던 "이름 정책 한 곳"은 새 모듈이 `account` 의 `DisplayNames` 를 그대로 써서 유지한다) |
| 앱이 `AuthorDirectory` 빈만 바꾸기 (지금 문서가 안내하는 "돌판마다 다른 닉네임") | 이름만 바뀌고 응답의 `authorId` 가 계정 id 로 남는다 — 요구의 본질(연결 차단)을 못 한다 |
| `author_id` 를 프로필 id 로 바꾸고 계정 열을 새로 추가 | 기존 행 전부를 고쳐 써야 하고, 권한 · "내 글" · 알림 수신자 · 탈퇴 익명화가 모두 `author_id = 계정` 에 기대고 있다(`B/BoardPolicy.kt:39-56`). 열을 **더하는** 쪽이 동결된 마이그레이션 · 업그레이드 시험에 안전하다 |
| 옛 글을 읽을 때마다 "계정의 기본 프로필"로 풀기 | §4 12 — 기본을 바꾸면 연결이 드러난다 |

## 6. 데이터 모델 (두 방언, 새 V 파일만)

**`board-jdbc`** — `V<UTC>__board_author_ref.sql` (`./gradlew newMigration -Pname=board_author_ref -Pmodule=modules/board-jdbc`, 잠금은 `perl scripts/migrations-lock.pl`)

```sql
alter table board_posts    add column if not exists author_ref varchar(64);   -- 공개 작성자 id. NULL = 이 기능 이전의 행 (공개 id = author_id). MySQL 판은 `if not exists` 없이 (8.4 에 없다)
alter table board_comments add column if not exists author_ref varchar(64);
create index if not exists idx_board_posts_author_ref    on board_posts (author_ref, author_id);     -- 넘겨받기: (author_ref is null → author_id) 목록 · (author_ref is null and author_id = 계정)
create index if not exists idx_board_comments_author_ref on board_comments (author_ref, author_id);  -- 댓글 표에는 author_id 인덱스가 없다 — 열 하나짜리로는 켠 직후(전부 NULL)에 못 쓴다
```

`author_id`(계정 id, 탈퇴 뒤에는 톰스톤)의 뜻은 그대로다. `board_reactions` 는 바뀌지 않는다. 열이 NULL 허용이라 `MigrationUpgradeIntegrationTest` 의 기준선 데이터 위에서 그대로 돈다.

**`author-profile-jdbc`** — `V<UTC>__author_profiles.sql` (PostgreSQL. MySQL 은 `datetime(6)`, `name_key` 는 `utf8mb4_bin` — `accounts` 의 MySQL 판과 같은 규칙)

```sql
create table if not exists author_profiles (
    id         varchar(40)  primary key,        -- prf_ + 32자 16진수 (SecureRandom 16바이트)
    account_id varchar(40)  not null,           -- 주인. FK 없음 (다른 모듈의 표)
    primary_of varchar(40)  unique,             -- 계정의 첫 프로필이면 계정 id, 아니면 NULL — 계정당 하나 (부분 인덱스 없이 두 방언 공통. NULL 은 서로 겹치지 않는다)
    name       varchar(60),                     -- NULL: 지워진 프로필 · 이름 없는 계정의 옛 글을 넘겨받은 첫 프로필
    name_key   varchar(255),
    tag        char(4),                         -- accounts.display_tag 와 같은 규칙 (NONE: NULL · UNIQUE: '0000' · TAGGED: '0001'..'9999')
    status     varchar(16)  not null,           -- ACTIVE | DELETED
    adopted_at timestamptz,                     -- 첫 프로필만: 옛 글을 넘겨받은 시각
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    deleted_at timestamptz,
    constraint uq_author_profiles_name unique (name_key, tag)
);
create index if not exists idx_author_profiles_account on author_profiles (account_id, status);
```

## 7. 동작 규칙

### 7.1 쓰기 (board)

| `AuthorIdentities` 빈 | 요청의 `authorId` | 결과 |
|---|---|---|
| 없음 | 없음 | 지금과 같다 — `author_ref = NULL` |
| 없음 | 있음 | `403 BOARD.AUTHOR_NOT_ALLOWED` |
| 있음 | 없음 | `400 BOARD.AUTHOR_REQUIRED` |
| 있음 | 있음 | `allows(계정, authorId, AuthorContext("board", 게시판 코드))` 가 참이면 `author_ref = authorId`, 거짓이면 `403 BOARD.AUTHOR_NOT_ALLOWED` (이유를 구별하지 않는다) |

- `author_id` 는 언제나 호출자 계정. 권한 · `mine=true` 목록 · 알림 수신자 · 한도 · 반응은 지금 코드 그대로 `author_id` 를 본다.
- 수정(`PATCH`)에는 `authorId` 칸이 없다. 운영자가 고쳐도 `author_ref` 는 바뀌지 않는다.
- 첨부: `AuthorIdentities` 가 있을 때 첨부 키에 호출자 계정 id 가 들어 있으면 `400 BOARD.CONTENT_INVALID` (§7.8) 수정(PATCH)에서는 글이 이미 가진 키는 검사하지 않는다(켜기 전 글 호환) — 새로 붙는 키만 본다.

### 7.2 읽기 (board) — 공개 id 한 곳

```
공개 id = author_ref
          ?: author_id            (AuthorIdentities 없음)
          ?: "pending:<행 종류><행 id>"   (AuthorIdentities 있음 + 아직 넘겨받지 않은 행 — 계정 id 를 내지 않는다)
```

- 이 규칙은 `BoardAuthors` 한 곳에만 있고 응답 DTO · 알림 payload · `AuthorNames` 가 모두 이것을 쓴다. `AuthorIdentities` 가 있으면 `author_id` 는 **어떤 응답에도 나가지 않는다.**
- 이름: `AuthorNames.of(게시판, 공개 id들)` — 요청 하나에 한 번이라는 지금 규칙 그대로. `deleted:` · `pending:` 은 묻지 않는다.
- `authorDeleted = author_id 가 톰스톤 || 카드.deleted`.
- `mine = 호출자 != null && 호출자 계정 == author_id`.

### 7.3 프로필 수명 (`author-profile`)

- **만들기**: 이름은 `account` 의 닉네임 규칙 · 예약어 · 방식(`skeleton.account.display-name.*`)을 그대로 따른다 — 앱에 닉네임 정책은 하나다. 꼬리표는 프로필 표 안에서 독립 추첨하고, 같은 키의 계정 꼬리표도 피한다(아직 안 넘겨받은 옛 작성자와 `이름#꼬리표` 가 겹치지 않게). 살아 있는 프로필 수 상한 `max-per-account`, 만들기 횟수 한도 `create-limit`/`create-window`(지우고 다시 만드는 것을 막는다) — 둘 다 계정 기준.
- **이름 바꾸기**: 과거 글에 바로 반영된다(읽을 때 조회 — 계정 닉네임과 같은 방식). 키가 같으면 꼬리표 유지, 다르면 새로 추첨. 한도는 계정의 `display-name.change-limit`/`change-window` 숫자를 쓰되 모든 프로필 합산.
- **지우기**: 즉시, 되돌릴 수 없다. 행은 남는다(`status=DELETED`, 이름 · 키 · 꼬리표 NULL → 꼬리표가 풀린다). 그 프로필의 글 · 댓글은 그대로 남고 `authorDeleted: true` · 이름 없음으로 보인다. **다른 프로필로 옮기지 않는다.** 주인은 계속 고치고 지울 수 있다(`mine` 은 계정 기준). board 의 행은 건드리지 않는다 — 디렉터리가 `deleted` 카드를 돌려줄 뿐이다.
- **첫 프로필**: 계정이 처음 갖는 프로필(`primary_of`). 옛 글을 넘겨받는 것 말고는 다른 프로필과 같다 — 지울 수 있고, 지워도 자리는 남아 두 번 넘겨받지 않는다. 서버에 "기본 프로필"은 없다.
- 계정 이름(`accounts.display_name`)은 남는다 — 본인 · 관리자 · 메일 인사말용 사적 라벨이 되고 공개되지 않는다. 첫 프로필은 만들어질 때 계정 이름 · 꼬리표를 **한 번 복사**하고(꼬리표가 겹치면 새로 추첨) 이후 따로 간다. `GET`/`PATCH /account/me` 는 그대로다.

### 7.4 넘겨받기 (이미 글이 있는 DB 에서 켤 때)

1. 기동 실행기(`adopt-on-startup`, 기본 켜짐): 모든 `AuthorAdoptionListener.pendingAccounts` 를 돌며 계정마다 첫 프로필을 보장하고(없으면 계정 이름 복사, 이름 없는 계정은 이름 없는 첫 프로필) `adopt(계정, 프로필 id)` 를 부른 뒤 `adopted_at` 을 채운다.
2. board 의 구현: `update board_posts|board_comments set author_ref = :ref where author_id = :account and author_ref is null`.
3. 사용자가 `GET /author-profiles` 를 부를 때도 같은 보장을 한 번 한다(실행기를 끈 앱 · 실행 사이의 틈).
4. 멱등 — 첫 프로필은 `primary_of` 유니크 + `SqlDialect.insertIgnore`, `adopt` 는 `author_ref is null` 조건. 인스턴스 여럿이 동시에 돌아도 같다.
5. 실행이 끝나기 전의 요청은 §7.2 의 `pending:` 로 나간다(이름 없음, 계정 id 없음).

### 7.5 계정 탈퇴 · 정지

- 탈퇴(지우기): `author-profile` 의 고리가 그 계정의 프로필 행을 **지운다**(형제 관계가 DB 에도 남지 않는다). board 의 고리는 지금 SQL 그대로 `author_id` 와 반응의 계정만 톰스톤으로 바꾼다 — `author_ref` 는 손대지 않으므로 프로필마다 다른 id 가 남고, `authorDeleted` 는 `author_id` 에서 나온다. 고리 순서에 기대지 않는다.
- 유예 중(`DELETED`) · 정지(`SUSPENDED`): 프로필 카드는 그대로, 공개 표시 없음. 정지된 계정은 지금처럼 로그인 · 쓰기가 막힌다.

### 7.6 자작(같은 스레드에서 프로필을 바꿔 답하기)

허용한다(주인의 결정). 다른 사용자에게는 서로 다른 두 사람으로 보인다. 관리자는 §7.9 로 본다. "처음 쓴 프로필로 고정"은 만들지 않는다 — 필요해지면 행마다 `(author_id, author_ref)` 가 있어 board 설정 하나로 더할 수 있다(스키마 변경 없음).

### 7.7 앱(Ovation)이 끼어드는 자리

- `AuthorIdentities` 빈 교체 · 감싸기: `AuthorContext.scope` = 게시판 코드 → "이 프로필은 이 돌판에서만" 같은 제한.
- `AuthorDirectory` 빈 교체: 지금과 같다.
- 프론트 선택기의 `initialAuthorId(boardCode)` prop: 돌판별 기본 프로필.
- 프로필에 붙는 앱 고유 정보(돌판 연결 · 아바타)는 앱의 표가 `prf_` id 를 키로 갖는다.

### 7.8 첨부 키 (`storage`)

- `storage` 웹 설정에 `key-owner: ACCOUNT_ID`(기본, 지금 동작) | `OPAQUE` 를 더한다. `OPAQUE` 의 키: `<prefix>/o/<uuid>.<mac>/<파일>`, `mac = HMAC-SHA256(서버 비밀, 계정 id + "\n" + uuid)` 앞 32자. 소유 확인은 다시 계산해 비교한다 — DB 가 필요 없고, 업로드마다 값이 달라 **같은 프로필의 첨부끼리도 이어지지 않는다.** `ACCOUNT_ID` 꼴의 옛 키는 어느 방식에서나 지금처럼 확인된다(유효한 `ObjectKey` 가 아니거나 접두사 뒤가 비면 아니다. `<prefix>/o/` 로 시작하는 키는 `OPAQUE` 에서 mac 으로만 판정한다).
- **스펙 이탈(B4 수정): `OPAQUE` 키에서는 클라이언트 파일 이름을 버리고 알려진 확장자(내장 목록, 소문자 비교)만 남긴다**(`file[.<ext>]`, 목록에 없으면 `file` — `kim.minsu` · `example.com` 처럼 이름의 일부가 확장자 자리에 올 수 있다). 이유: 파일 이름이 두 프로필의 첨부를 잇는 값이 된다(같은 이름 · 같은 카메라 파일 번호). 원래 이름이 필요하면 앱이 별도 열에 저장한다. 소유 확인은 꼬리가 `file[.<소문자 영숫자 1~8자>]` 일 때만 인정한다. `ACCOUNT_ID` 방식은 지금과 같다.
- 앱의 `BoardPolicy.canAttach` 가 접두사 비교 대신 쓸 `StorageKeyOwnership.owns(accountId, key)` 빈을 함께 낸다.
- board 쪽 안전망은 §7.1 의 막기(storage 를 모른 채 "키에 내 계정 id 가 있나"만 본다).
- 버린 것: 키에 프로필 id 를 넣기 — 올린 뒤 프로필을 바꿔 쓰면 A 의 키가 B 의 글에 실린다.

### 7.9 관리자의 연결 조회 (`skeleton.author-profile.admin.enabled`, 기본 꺼짐)

- `GET /api/v1/admin/author-profiles/{id}` → 그 프로필의 계정 id 와 같은 계정의 프로필들(지워진 것 포함, 시각 포함).
- `GET /api/v1/admin/author-profiles?accountId=` → 계정의 프로필들.
- `AccountCallers.requireAdmin`(매 호출 저장소의 역할을 다시 읽는다). 호출마다 `AUTHOR_LINK_VIEWED`(대상 계정, `by` = 관리자) — detail 에 프로필 id 는 싣지 않는다.
- 게시판 운영자(`MODERATOR`)는 보지 못한다. 글 숨김 · 삭제는 연결 없이 되고, 계정 제재는 관리자의 일이다.

## 8. HTTP 계약 변화

**모듈이 없을 때**: 경로 · 필드 · 오류가 지금과 같다. 덧붙는 것만 둘 — 글 · 댓글 응답의 `mine: boolean`, `GET /boards/config` 의 `authorRequired: false`.

**모듈이 있을 때** (board)

| 어디 | 변화 |
|---|---|
| 글 요약 · 상세 · 댓글 응답 | `authorId` 의 **값**이 프로필 id(`prf_…`). 키 이름 · `authorName` · `authorTag` · `authorDeleted` 는 그대로. `mine` 추가 |
| `GET /boards/config` | `authorRequired: true` |
| `POST …/posts` · `POST …/comments` | 본문에 `authorId`(필수). `400 BOARD.AUTHOR_REQUIRED` · `403 BOARD.AUTHOR_NOT_ALLOWED` |
| 댓글 알림 payload | `authorId` = 프로필 id |

**`author-profile`** (`skeleton.author-profile.http.base-path`, 기본 `/api/v1/author-profiles`. 로그인 필요, 호출자는 `Authentication.name`)

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /config` | `{ maxPerAccount, nameMaxLength }` |
| `GET /` | 내 살아 있는 프로필 `[{ id, name, tag, createdAt }]` |
| `POST /` `{ name }` | `201`. `400` 필드 `name`(코드는 닉네임과 같다: `Required` · `Size` · `Pattern` · `Reserved`) · `409 AUTHOR_PROFILE.NAME_TAKEN` · `409 AUTHOR_PROFILE.LIMIT_REACHED` · `429 AUTHOR_PROFILE.RATE_LIMITED` |
| `PATCH /{id}` `{ name }` | `200`. 같은 오류 |
| `DELETE /{id}` | `204` |
| 남의 · 없는 · 지워진 id | 모두 같은 `404 AUTHOR_PROFILE.NOT_FOUND` |

계약 문서는 `docs/author-profile-http-contract.md` 로 따로 쓰고 `docs/modules/board.md` · `docs/accounts.md` 에서 가리킨다. `account-http-contract.md`(FINAL-5)는 바뀌지 않는다.

## 9. 설정

`skeleton.author-profile` — `docs/config/modules/author-profile.yml`

| 키 | 기본 | 뜻 |
|---|---|---|
| `max-per-account` | `5` | 계정당 살아 있는 프로필 수 |
| `create-limit` · `create-window` | `5` · `30d` | 계정당 창 안의 만들기 횟수 (`0` = 한도 없음) |
| `adopt-on-startup` | `true` | 기동할 때 옛 글 넘겨받기 |
| `http.enabled` · `http.base-path` | `true` · `/api/v1/author-profiles` | |
| `admin.enabled` | `false` | 연결 조회 엔드포인트 |

이름 규칙 · 겹침 방식 · 예약어 · 이름 변경 한도는 `skeleton.account.display-name.*` 를 따른다. board 에는 새 키가 없다(빈의 유무가 스위치). storage 는 `key-owner` 와 비밀 하나.

## 10. 프론트 (react-skeleton)

- **`@skeleton/board`** — 다른 패키지를 모른 채: 타입에 `mine?` · `BoardConfig.authorRequired?` · 입력의 `authorId?`. 소유 판정은 `mine ?? (authorId === currentUserId)`(옛 서버 호환). `PostEditor` · `CommentForm` · `BoardComments` 에 선택기 자리(`authorPicker` 렌더 prop: 값 · 바꾸기 · 이 스레드에서 내가 쓴 id) — `authorRequired` 인데 값이 없으면 칸 오류로 막는다. `lastOwnAuthorId(comments)`: `mine` 인 댓글에서 이 스레드에 마지막으로 쓴 id. `AuthorScope` · `collidingNames` 는 코드 변경 없이 주석만("계정" → "작성자 id").
- **`@skeleton/author-profile`** (새 패키지, 백엔드 짝 `author-profile` · `/author-profiles`): `createAuthorProfileApi` · 훅 · `AuthorPicker` · `AuthorProfileManager`(목록 · 추가 · 이름 바꾸기 · 삭제 — 삭제는 `ConfirmDialog`) · `createAuthorMemory(storage)`. 문구는 prop, 스토리 + `play`, README, `capabilities.json`, `tests/support/ssrFixtures.ts`, `docs/ui-catalog.md`.
- **실수 방지**
  - 접힌 선택기는 **지금 고른 이름#꼬리표 하나**를 제출 단추 옆에 늘 보인다. 목록은 눌러야 열린다(화면 공유 때 다른 프로필 이름이 깔려 있지 않게).
  - 처음 값: 이 스레드에서 쓴 적이 있으면 그 프로필 → 없으면 이 기기가 기억하는 이 게시판의 마지막 프로필 → 없으면 **비어 있음**(골라야 보낼 수 있다).
  - 이 스레드에서 쓴 프로필과 **다른 것으로 바꿀 때만** 한 줄 확인("이 글에서는 ○○(으)로 쓴 적이 있어요"). 늘 떠 있는 경고는 두지 않는다 — 그 자체가 노출이다.
- **기기 기억**: `localStorage`, 키에 계정 id, 값은 프로필 id 만, 로그아웃 때 지운다.
- **`apps/sample`**: 설정 화면의 닉네임 자리에 프로필 관리 구역, 게시판 글쓰기 · 댓글에 선택기, `NicknameGate`(닉네임 없으면 쓰기 전에 묻기)는 "쓸 수 있는 프로필이 없으면 만들기"로 바뀐다. `ko.ts` · `en.ts`. e2e 한 줄기 추가.

## 11. 기존 데이터 이전 · 끄기

- **켜기**: §7.4. 공개 id 가 `acc_…` → `prf_…` 로 바뀐다(id 를 저장해 둔 클라이언트는 다시 읽는다). 이름은 그대로, 꼬리표도 겹치지 않는 한 그대로다.
- **켜기 전부터 글을 쓴 사람의 첫 프로필은 옛 계정 id 와 이어져 있다** — 그 글들은 계정 id 로 공개돼 있었기 때문에 막을 수 없다. 켠 뒤에 만든 프로필은 이어지지 않는다. 비밀이 필요한 활동은 새 프로필로 하라는 안내가 앱의 몫이다. 첫 배포부터 켜는 앱에는 이 문제가 없다.
- 켜기 전의 편지함 payload · 첨부 키에는 계정 id 가 남는다(같은 이유로 수용).
- **끄기(모듈 빼기)는 지원하는 방향이 아니다.** 빼면: `author_ref` 가 있는 행은 계속 `author_ref` 를 내고 이름이 사라진다(연결은 드러나지 않는다 — §4 23). 새 글은 다시 계정 id 로 나간다.

## 12. 테스트 전략

**연결 누출 스캔** — 진짜 PostgreSQL + 보안 체인, `board` · `account` · `author-profile` · `notification-jdbc` · `storage` 를 다 끼운 앱에서:

1. 계정 X 가 프로필 A · B 를 만든다. 관찰자 계정 Y, 비로그인 Z.
2. X 가 A 로 글 · B 로 글, Y 의 글에 A 로 댓글 · B 로 답글, 첨부가 있는 글을 각각, 반응 몇 개.
3. Y 와 Z 로 **읽을 수 있는 모든 경로**를 훑는다: 게시판 목록 · 글 목록(정렬 · 검색 · 반응 정렬 전부) · 글 상세 · 댓글 스레드 · config · Y 의 받은편지함 · X 의 프로필 경로 찔러 보기. 응답 본문과 헤더를 모은다.
4. **금지 값**: X 의 계정 id · 이메일 · 계정 톰스톤(`AccountTombstone.of` — `author_ref` 없는 옛 행의 톰스톤도 `pending:` 으로 나가 응답에 없다) · 계정 이름이 어디에도 없다.
5. **짝 스캔**: A 의 글 · 댓글 조각과 B 의 조각에서 모든 잎 값을 뽑아 교집합을 낸다. 허용 목록(게시판 코드 · 상태 · 개수 · 반응 종류 · 불리언) 밖의 공통 값이 있으면 실패 — "두 글을 잇는 값"을 이름이 아니라 **값으로** 찾는다.
6. 같은 스캔을 상태를 바꿔 반복: B 삭제 뒤 → X 정지 뒤 → X 지우기 뒤(A · B 글의 `authorId` 가 서로 다르고 공통 `deleted:` 값이 없다).
7. **DB 열 분류**: `board_*` · `notification_*` · `author_profiles` 의 모든 열을 `information_schema` 에서 읽어 "공개 / 비공개" 표와 맞춘다. 분류되지 않은 열이 생기면 실패(새 열이 조용히 계정 id 를 담지 못하게). 공개 열(`author_ref`, 남의 편지함 payload)에서 4 · 5 를 다시 본다. 두 방언.
8. 변이 확인(한 번, 손으로): DTO 가 `author_id` 를 내도록 바꾸면 4 가, 톰스톤을 `author_ref` 에 쓰도록 바꾸면 6 이 실패하는지.

**그 밖**

| 대상 | 무엇 |
|---|---|
| board 단위 | §7.1 표 네 줄 · 수정으로 `author_ref` 불변 · `AuthorIdentities` 가 있으면 어떤 뷰에도 `author_id` 가 없다(`pending:`) · `mine` · 알림 payload · 자기 알림 생략은 계정 기준 · 계정 id 가 든 첨부 키 거절 |
| board `noOptionalTest` | 새 SPI 빈 없이 지금과 같은 응답 |
| board-jdbc `dbTest` (PG · MySQL) | `author_ref` 왕복 · `pendingAccounts` · `adopt` 멱등 · 탈퇴 뒤 `author_ref` 그대로 |
| author-profile 단위 | 상한 · 만들기 한도 · 이름 규칙 · 꼬리표 유지/재추첨 · 지운 뒤 카드 `deleted` · `allows` 가 남의 · 없는 · 지워진 id 에 같은 false |
| author-profile-jdbc `dbTest` (PG · MySQL) | `(name_key, tag)` · `primary_of` 유니크 경합(두 방언, 동시 요청) · 탈퇴 고리 멱등 |
| 조립 | 기준선 데이터 → 최신 마이그레이션 → 기동 → 옛 글이 첫 프로필 이름으로 보인다. 모듈 없이 조립한 앱의 기존 시험은 한 줄도 바뀌지 않는다 |
| storage | `OPAQUE` 키 발급 · 소유 확인 · 남의 키 404 · 옛 꼴 키 호환 |
| 가드 | `CapabilitiesCatalogTest` · `ModuleDocumentationTest` · `ModuleRegistrationRulesTest` · `MigrationLockTest` · `RepositoryMigrationsTest` |
| 프론트 | 선택기 필수 · 처음 값 세 단계 · 바꿀 때 확인 · 로그아웃 때 기억 삭제 · 계정 id 가 DOM 에 없다 · e2e: 두 프로필로 쓴 뒤 다른 사용자 화면에서 두 작성자가 남처럼 보인다 |

## 13. 단계별 작업 목록

크기: S = 파일 5개 안팎 · M = 10~20 · L = 20 이상. 각 단계는 앞 단계만 있으면 따로 검증된다.

**백엔드**

| # | 작업 | 크기 | 끝났다는 증거 |
|---|---|---|---|
| B1 | platform: `AuthorCard.deleted`, `AuthorIdentities`, `AuthorAdoptionListener` | S | platform 시험 · 기존 모듈이 그대로 컴파일 |
| B2 | board-jdbc: 새 V 파일(두 방언) + 잠금, 모델 · 저장소가 `author_ref` 를 읽고 쓴다(값은 아직 늘 NULL), 넘겨받기 포트 구현 | M | board-jdbc `postgresTest` · `mysqlTest`, 샘플 업그레이드 시험, `MigrationLockTest` |
| B3 | board: 공개 id 한 곳(§7.2), `mine`, `authorRequired`, 쓰기 규칙(§7.1), 알림 payload, 첨부 막기, 오류 코드 둘, `docs/modules/board.md` · `docs/errors.md` | M | board 시험 + `noOptionalTest`(가짜 SPI 빈으로 켜짐 · 빈 없이 꺼짐) |
| B4 | storage: `key-owner=OPAQUE`, `StorageKeyOwnership`, 문서 | M | storage 시험 |
| B5 | `author-profile` + `-jdbc`: 표 · 서비스 · 디렉터리 · `AuthorIdentities` · 탈퇴 고리 · 넘겨받기 실행기 · HTTP · 설정 yml · 모듈 문서 2장 · `capabilities.json` · `new-project.sh` 조각 · `apps/workbench` 조립 | L | 모듈 시험 + 두 방언 `dbTest` + 카탈로그 · 문서 가드 |
| B6 | 관리자 연결 조회 + `AUTHOR_LINK_VIEWED` | S | 웹 시험(역할 · 감사 이벤트 · detail 에 프로필 id 없음) |
| B7 | 누출 스캔 시험(§12 1~8) + 조립 시험 + `docs/author-profile-http-contract.md` + `docs/accounts.md` 절 + CHANGELOG(이식 안내 형식) | M | 스캔 통과 + 변이 확인 기록 |
| B8 | `apps/sample` 에 켜기(「정해야 할 것」 9) — 시드 · 기존 통합 시험 갱신 | M | 샘플 통합 시험 |

B4 는 B1~B3 과 나란히 할 수 있다. B5 는 B1 만 있으면 시작된다.

**프론트**

| # | 작업 | 크기 | 끝났다는 증거 |
|---|---|---|---|
| F1 | `@skeleton/board`: 타입 · `mine` 소유 판정 · 선택기 자리 · `lastOwnAuthorId` · 스토리 · README | M | 패키지 시험 + `test:stories` (가짜 서버로 켜짐 · 꺼짐) |
| F2 | `@skeleton/author-profile`: API · 훅 · `AuthorPicker` · `AuthorProfileManager` · 기기 기억 · 스토리 · README · 카탈로그 | L | 패키지 시험 + `test:stories` + `tests/capabilities*.test.ts` · `ssr.safety` |
| F3 | `apps/sample` 연결(설정 구역 · 게시판 · 쓰기 전 확인 · 문구 · 로그아웃 정리) + e2e | M | `pnpm e2e:sample`(B8 뒤) |
| F4 | 관리자 화면의 "프로필로 계정 찾기"(선택) | S | 스토리 + 시험 |

F1 은 B3 의 계약만 정해지면 가짜 서버로 먼저 간다.

## 14. 남는 위험 (이 설계가 막지 못하는 것)

- 문체 · 활동 시간대 · 관심사 같은 행동 상관.
- 계정 탈퇴 · 정지 때 여러 프로필이 같은 때에 사라지거나 조용해진다(§4 19).
- 켜기 전부터 쓰던 사람의 첫 프로필 ↔ 옛 계정 id (§11).
- `OPAQUE` 키에서 원래 파일 이름은 사라진다(§7.8) — 앱이 보여 줄 이름이 필요하면 따로 저장하는데, 그 이름이 공개되면 거기서 다시 이어진다.
- 첨부 파일 **내용**(사진의 EXIF · 위치 · 기기 정보) — 업로드 때 지우는 것은 앱의 몫.
- 본인 화면: 여러 프로필의 글에 한꺼번에 뜨는 수정 단추, 한 편지함에 모이는 알림.
- 사용자가 스스로 두 프로필에 같은 이름을 붙이는 것.

## 15. 구현 전에 실측할 것 (읽기만으로는 확정하지 못한 것)

- 게시판 `GET` 응답의 `Cache-Control` — 익명 읽기를 켠 앱에서 `mine` · `myReactions` 가 든 응답이 Cloudflare 등 공유 캐시에 남지 않는지.
  - 실측(B3): `apps/sample` 에 `skeleton.board.http.allow-anonymous-read=true` 를 켜고 보안 체인을 거쳐 `GET /boards/config` · `GET /boards/general/posts` 를 익명과 Bearer 로 불렀다 — 네 응답 모두 `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` · `Pragma: no-cache` · `Expires: 0`(Spring Security 기본 헤더. board 는 아무 헤더도 달지 않고 `Vary` 도 없다). 공유 캐시에 남지 않으므로 코드는 바꾸지 않았다. 단 앱이 자기 `SecurityFilterChain` 으로 `headers.cacheControl` 을 끄면 이 보장이 사라진다 — 그때는 앱이 책임진다.
- `author_ref is null` 조건이 PostgreSQL · MySQL 양쪽에서 새 인덱스를 타는지(실행 계획).
  - 실측(1): board_posts 2만 행(1% 가 NULL, `analyze` 뒤)에서 `author_ref is null` 의 `select distinct author_id … limit` · `count(*)` 는 PostgreSQL 18 이 단일 열 인덱스를 Bitmap Index Scan 으로(`enable_seqscan` 기본값 그대로), MySQL 이 `type=ref` 로 탄다.
  - 실측(2, 최종 인덱스 `(author_ref, author_id)`): 글 · 댓글 각 2만 행, 전부 NULL, 계정 300개. 댓글 `update … where author_id = :a and author_ref is null` 은 PostgreSQL 이 `idx_board_comments_author_ref` Bitmap Index Scan(`Index Cond: author_ref IS NULL AND author_id = …`), MySQL 이 `type=range` 로 같은 인덱스를 탄다. 글 `update` 는 MySQL 이 새 인덱스(range)를, PostgreSQL 이 기존 `idx_board_posts_author`(Filter: author_ref IS NULL)를 고른다. 전부 NULL 일 때 `pendingAccounts`(두 표 union)는 PostgreSQL 이 두 표를 Seq Scan 한다 — 전 행이 대상이라 맞는 계획이다. MySQL 은 두 표를 새 인덱스(covering, `Using index`)로 읽는다. 전부 NULL 이 아닌 보통 상태(1% NULL)는 실측 1 의 **단일 열 인덱스로** 쟀고, 최종 인덱스 `(author_ref, author_id)` 로는 다시 재지 않았다.
- `author-profile` 의 `AuthorDirectory` 가 `account` 의 기본 구현보다 먼저 등록되는지(`@AutoConfiguration(before = …)`) — 컨텍스트 러너로.
  - 실측(B5a): `ApplicationContextRunner` 에 실제 `AccountAutoConfiguration` 과 `AuthorProfileAutoConfiguration`(저장소만 가짜)을 `AutoConfigurations.of(…)` 로 **두 나열 순서 모두** 올리면 `AuthorDirectory` 빈은 하나이고 `AuthorProfileDirectory` 다. 변이 확인: `@AutoConfiguration(before = [AccountAutoConfiguration::class])` 에서 `before` 를 지우고 같은 시험을 돌리면 `AccountAuthorDirectory` 가 이겨서 시험 둘이 실패한다 — `before` 가 필요하고 충분하다(`AuthorProfileAutoConfigurationTest`). `prf_` 가 아닌 id 는 이 디렉터리가 `AccountAuthorDirectory` 에 그대로 넘긴다(같은 시험이 확인).
- `PATCH` 본문의 모르는 칸(`authorId`)이 조용히 무시되는지, 거절되는지(지금 Jackson 설정).
  - 실측(B3): 조용히 무시된다 — `PATCH …/posts/{id}` 에 `{"title":"edited","authorId":"prf_u2"}` 를 보내면 `200`, 제목은 바뀌고 `authorId` 는 그대로다(`BoardAuthorIdentitiesWebTest`). 거절하지 않으므로 수정 요청에 `authorId` 를 실어 보내는 낡은 클라이언트도 깨지지 않고, 작성자는 바뀌지 않는다.
- 앱(샘플)의 `BoardPolicy.canAttach` 가 지금 접두사 비교를 하는지 — 한다면 B4 에서 함께 바꾼다.
  - 실측(B3, B4 수정 1회차에서 정정): `BoardPolicy.canAttach` 는 샘플이 바꾸지 않아 `DefaultBoardPolicy` 가 모든 키를 허용한다. 그러나 샘플 Notes 의 `@OwnAttachmentKey` → `OwnedKeys.isOwnedBy`(`apps/sample/…/notes/OwnAttachmentKey.kt:34-43`)가 접두사 비교(`<key-prefix>/<계정 id>/` + `..` 거절)를 하고, `docs/sample.md:37` 도 그렇게 안내한다. 이번 커밋(B4)에서는 샘플 코드를 바꾸지 않았다 — B8 에서 `StorageKeyOwnership.owns` 로 바꾼다.

## 정해야 할 것 — 결정됨 (2026-10-07 주인)

주인 원문은 §1. 아래는 항목별 확정 선택지다. 표시: **결정됨** = 주인 확정.

### 가. 되돌리기 어려운 것

1. **어디에 두나** — 결정됨: 새 선택 모듈 `author-profile` + `-jdbc`, `account` 에 의존 (추천안 확정)
2. **모듈 · 표 · 경로 이름** — 결정됨: `author-profile` / `author_profiles` / `/author-profiles` / `prf_` (추천안 확정)
3. **공개 작성자 id 의 응답 키** — 결정됨: 기존 `authorId` 키에 값만 프로필 id (추천안 확정)
4. **글 · 댓글을 쓸 때 프로필 지정** — 결정됨: 필수, 없으면 400 (추천안 확정)
5. **첨부 키의 계정 id** — 결정됨: **이번 범위에 같이 고친다** — storage `OPAQUE` 키 + board 가 계정 id 가 든 키를 막는다 (추천안 확정; 단계는 §13 의 첨부 단계)
6. **프로필 ↔ 계정 연결을 누가 보나** — 결정됨: **`ADMIN` 만**, 별도 조회 + 조회 감사 기록. `MODERATOR` 는 조회 불가 (추천안 확정)
7. **Ovation 은 언제 켜나** — 결정됨: 첫 배포부터 켠다. **스켈레톤 밖** — Ovation 쪽 일이라 스켈레톤 문서에는 권고(켜기 전에 쓴 글은 계정 id 로 공개된 적이 있어 첫 프로필과 영영 이어진다)로만 적는다
8. **탈퇴 때 모든 프로필이 같은 순간에 "삭제된 사용자"** — 결정됨: 수용하고 문서에 적는다 (추천안 확정)

### 나. 추천대로 진행

9. **`apps/sample` 에 켜나** — 결정됨: 켠다 (추천안 확정)
10. **개수 상한 · 만들기 한도 기본값** — 결정됨: **5개, 30일에 5번** (추천안 확정)
11. **프로필 삭제** — 결정됨: **즉시 삭제**, 되돌릴 수 없음, 글은 **"삭제된 사용자"로 남긴다**. 다른 프로필로 이전하지 않는다 (추천안 확정)
12. **한 글 · 한 게시판 안 프로필 고정 옵션** — 결정됨: 지금은 만들지 않는다
13. **마지막에 쓴 프로필 기억** — 결정됨: 기기 로컬 + 스레드 안에서는 내가 쓴 댓글에서 유추
14. **같은 스레드에서 다른 프로필로 쓸 때** — 결정됨: 바꾸는 순간에만 한 줄 확인
15. **`mine` 칸** — 결정됨: 모듈이 없을 때도 싣는다
16. **이름 정책** — 결정됨: `skeleton.account.display-name.*` 를 그대로 따른다
17. **연결 조회 감사 기록** — 결정됨: `AccountEventType` 에 한 종류(`AUTHOR_LINK_VIEWED`) 추가
18. **정지된 계정의 공개 표시** — 결정됨: 없음
