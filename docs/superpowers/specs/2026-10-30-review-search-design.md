# 리뷰 · 평점과 DB 전문 검색 — 설계 (초안)

> 2026-10-30. 요청: ① 아무 대상(상품 · 글 …)에 별점 1~5 · 본문 · 사진 리뷰, 구매 확인 배지, 평균 · 분포, 도움돼요, 신고 · 숨김 · 운영 화면, 리뷰 보상은 앱이 조립 ② 두 방언(PostgreSQL · MySQL)의 DB 전문 검색 모듈, 다른 모듈 데이터를 밀어 넣는 포트, 나중에 외부 엔진으로 바꿀 자리, 견본 앱의 노트 · 상품 검색.
> 원칙: 공개 스켈레톤 · 캡슐화된 조립식 모듈 · 모듈 기본값은 중립(앱 yml 이 덮어쓴다) · 업계 관행 · 상품은 `catalog` 표에 있다 · 개인 도메인 · 이메일 흔적 금지.
> 대상: kotlin-skeleton(새 `review` · `review-jdbc` · `search` · `search-jdbc`, 기존 `platform` · `catalog` · `apps/sample`) + react-skeleton(새 `@skeleton/reviews` · `@skeleton/search`, `apps/sample`).
> 이번에 읽은 것: `docs/modules/board.md`(반응 · 카운터 · 소프트 삭제 · LIKE 검색 · `BoardEventListener`) · `catalog.md` · `payment.md`(`PaymentOrderListener`) · `authz.md` · `reward.md` · `notification.md` · `storage.md` · `author-profile.md`, `common/upload/PublicUploads.kt` · `common/author/Author.kt` · `SqlDialect.kt` · `CampaignSignals.kt`, 견본 `CheerOrderListener` · `SampleCatalog` · `NoteRepository`(ILIKE), 선례 `2026-10-29-badges-design.md`.
> **리뷰(§A)와 검색(§B)은 서로를 모른다** — 조각 줄기도 따로다(§10).

## 0. 업계 관행 → 권장안

| 물음 | 업계 | 권장 |
|---|---|---|
| 한 사람 몇 개 | 아마존 · 구글 플레이 · 앱스토어: 대상당 하나(고쳐 쓴다). 쿠팡: 주문당 하나 | **계정 × 대상 하나**, 고치기 · 지우기 자유 |
| 쓰기 자격 | 쿠팡 · 스마트스토어 · 무신사: 구매자만. 아마존: 누구나 + 「Verified Purchase」 | 모듈은 둘 다 된다 — 기본 누구나(중립), `require-purchase` 로 구매자만 |
| 구매 확인 | 쓰는 순간 구매 기록과 맞춘다 | 앱이 **구매 신호**를 넣고(`ReviewPurchases.record`), 리뷰 줄의 `verified` 를 그때그때 맞춘다 |
| 평균 | 대부분 단순 평균 소수 한 자리 + 1~5 분포 | 대상별 집계 줄(개수 · 합 · 별점별 개수), 같은 트랜잭션의 원자적 갱신 |
| 도움돼요 | 아마존 「Helpful」 하나(싫어요 없음), 쿠팡 「도움돼요」 | 하나, 자기 리뷰 불가, 정렬 `helpful` |
| 신고 | 사유 고르기 + 운영 검토, 자동 가림은 서비스마다 | 사유 코드 + 운영 큐, 자동 가림은 기본 꺼짐(설정) |
| 판매자 답글 | 쿠팡 · 스마트스토어 · 앱스토어 · 플레이 모두 있음, 리뷰당 하나 | 결정 1 |
| 리뷰 보상 | 국내: 텍스트 · 포토 리뷰 적립 | 리뷰는 보상을 모른다 — 앱이 이벤트 고리에서 `CampaignSignals.report` |
| DB 한국어 검색 | MySQL: `FULLTEXT … WITH PARSER ngram`(기본 2글자). PostgreSQL: 형태소 사전이 기본 이미지에 없다(pg_bigm · mecab 은 확장 설치), `pg_trgm` 은 3글자 미만 질의에 색인을 못 쓴다 — 한국어 낱말은 2음절이 흔하다 | **두 방언 모두 2글자 조각(bigram)** — MySQL 은 ngram 파서, PostgreSQL 은 모듈이 만든 2글자 조각을 `tsvector('simple')` + GIN 에, 질의는 조각을 이어 붙인 구문(`<->`). 확장 설치 없음 |
| 색인 갱신 | 같은 DB 면 같은 트랜잭션, 바깥 엔진이면 아웃박스 + 일꾼 | 포트는 하나(호출자 트랜잭션 안에서 부른다), jdbc 구현은 같은 트랜잭션에 쓴다 |
| 하이라이트 | 엔진이 위치를 준다 | 서버가 원문에서 위치 `[시작, 끝)` 을 계산(HTML 없음, 두 방언 같은 결과) |

## 1. 범위

| | 1차(끝에서 끝까지 한 벌) | 다음에 넓힐 것 |
|---|---|---|
| 리뷰 | 대상 `type`+`id` · 별점 · 본문 · 계정당 하나 · 고치기 · 지우기 · 평균/분포 · 정렬/거르기 · 도움돼요 · 구매 신호와 배지 · 구매자만 옵션 · 신고 · 숨김/되살리기 · 운영 큐 · 이벤트 고리 · 탈퇴 · (결정 1) 운영자 답글 · (결정 2) 사진 | 작성 프로필(`AuthorIdentities`) 고르기 · 마켓 판매자(대상 주인)의 답글 · 주문당 리뷰 · 「도움돼요」 대신 종류 반응 · 집계 다시 세기 도구 · 리뷰 검색 색인 · 사진 CDN 공개 영역 |
| 검색 | 플랫폼 포트(`SearchIndexer` · `SearchSource`) · 두 방언 색인 · 질의(쪽 · 종류 · 범위 · 정렬 · 하이라이트) · 주인 문서 가리기 · 재색인 작업 · 탈퇴 · `catalog` 상품 밀어 넣기 · 견본 노트 | `board` 글(지금 LIKE) · 리뷰 색인 · 언어별 문서 · 자동 완성 · 오타 교정 · 바깥 엔진 어댑터(`search-meilisearch` · `search-opensearch`) |

**비목표**: 형태소 분석 · 동의어 사전, 검색어 통계, 리뷰 AI 요약, 리뷰 신뢰도 점수(가중 평균), 리뷰 이미지 자동 검수, 리뷰 · 검색 전용 속도 제한(platform `skeleton.web.rate-limit` 를 쓴다).
**반목표**: 동시에 쓴 리뷰로 평균이 어긋남 · 같은 사람의 리뷰 둘 / 숨긴 리뷰 · 남의 노트가 응답 문자열에 섞임 / `review` 가 `payment` · `reward` · `campaign` · `catalog` 를 안다 / `search` 가 남의 표를 직접 읽는다 / 모듈을 안 붙인 앱의 동작이 바뀐다.

---

## A. 리뷰 · 평점 (`review` + `review-jdbc`)

### A2. 경계 · 확장 지점

```
review(-jdbc) ──▶ platform{AuthorDirectory, OperatorAccess, OperatorAuditLog, AccountErasureListener, PublicEndpointContributor, PrivateUploadAccess}
review ··compileOnly··▶ notification, idempotency      (board 와 같은 선택 연동, noOptionalTest)
```

`review` 는 상품 · 결제 · 보상을 모른다. 대상이 무엇인지 · 누가 샀는지 · 무엇을 줄지는 앱이 포트로 잇는다.

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `skeleton.review.target-types`(목록, 기본 비어 있음) + `ReviewTargets`(`existing(type, ids): Set<String>`) | `review` | 대상 일반화. 기본은 아무 대상도 없다(중립) — 앱이 `[product]` 처럼 적는다. 대상이 진짜 있는지는 앱이 안다(견본: `CatalogStore`). 빈이 없으면 id 모양만 본다 |
| `ReviewPurchases`(서버 API: `record(accountId, target, ref)` · `revoke(ref)`) | `review` | 「구매 신호를 앱이 준다」. 결제 리스너 · 외부 주문 · 수동 등 어디서든 같은 한 줄. `CampaignSignals` · `Referrals.qualify` 와 같은 모양 |
| `ReviewPolicy`(`canWrite` · `canAttach` · `canModerate` 기본 메서드) | `review` | board 의 `BoardPolicy` 관례. 사진 키 소유는 앱이 `StorageKeyOwnership` 으로 좁힌다 |
| `ReviewEventListener`(여러 개, **쓰기 트랜잭션 안**) | `review` | 보상 연결 자리. 보상 지급 건은 내구성이 필요해 커밋 뒤가 아니라 같은 트랜잭션(`PaymentOrderListener` 와 같은 규칙: 짧게, 던지면 쓰기도 실패) |
| `ReviewStats`(서버 API: `of(targets)` 일괄) | `review` | 앱이 자기 상품 목록 응답에 평균을 끼울 때(`AuthorDirectory` 일괄 관례) |
| `ReviewStore` | `review`, 구현 `review-jdbc` | 포트/어댑터 짝. 메모리 구현 없음(board 와 같은 이유 — 영속이 본질) |
| `ReviewNotifier`(기본: `notification` 이 있으면 받은편지함) | `review` | 숨김 · 답글을 작성자에게 알림 |
| `reviewPublicEndpointContributor` | `review` | 비로그인 읽기 — 배지 결정 1 의 관례(모듈 기본 꺼짐, 견본이 켬) |

### A3. 데이터 (두 방언, 시각 PG `timestamptz` · MySQL `datetime(6)` UTC, JSON 열 · 부분 인덱스 없음)

- `reviews(id pk 'rvw_', target_type varchar(32), target_id varchar(64), account_id, rating smallint 1~5, body varchar(2000), status PUBLISHED|HIDDEN|DELETED, verified boolean, helpful_count int, report_count int, reported_at null, reports_reviewed_at null, moderated_by null, moderation_reason varchar(200) null, photo_count smallint, created_at, updated_at, published_at, unique(target_type, target_id, account_id), index(target_type, target_id, status, published_at), index(account_id))`.
  지운 리뷰를 같은 대상에 다시 쓰면 **같은 줄이 되살아난다**(배지 보유 줄과 같은 꼴).
- `review_target_stats(target_type, target_id, review_count, rating_sum, r1, r2, r3, r4, r5, updated_at, pk(target_type, target_id))` — `PUBLISHED` 만 센다.
- `review_helpful(review_id, account_id, created_at, pk(review_id, account_id))`.
- `review_reports(review_id, account_id, reason varchar(32), detail varchar(500) null, created_at, pk(review_id, account_id))`.
- `review_purchases(id pk, account_id, target_type, target_id, ref varchar(200), created_at, revoked_at null, unique(ref), index(account_id, target_type, target_id))`.
- (결정 1) `review_replies(review_id pk, body varchar(2000), actor_id, created_at, updated_at)`. (결정 2) `review_photos(review_id, idx smallint, storage_key varchar(500), pk(review_id, idx))`.

### A4. 흐름

**쓰기** `POST` — 대상 종류가 목록에 있고 `ReviewTargets` 가 있다고 답해야 한다(아니면 `404 REVIEW.TARGET_NOT_FOUND`). `require-purchase` 면 살아 있는 구매 줄이 있어야 한다(`403 REVIEW.PURCHASE_REQUIRED`). 같은 (대상, 계정)에 살아 있는 리뷰가 있으면 `409 REVIEW.ALREADY_WRITTEN`(동시에 둘 → 유니크가 하나로). 지운 줄은 되살린다, **운영자가 숨긴 뒤 지운 줄은 `409 REVIEW.LOCKED`**(숨김을 지우고 다시 쓰기로 피하지 못하게 — 되살리기는 운영자만). `verified` = 그 순간 살아 있는 구매 줄이 있는가.
**집계(동시성)** 리뷰 줄을 `for update` 로 잠근 뒤 → 집계 줄을 `SqlDialect.insertIgnore` 로 만들고 → `set review_count = review_count + :d, rating_sum = rating_sum + :s, r<n> = r<n> + :d` 원자적 UPDATE. 잠금 순서는 리뷰 → 집계 하나뿐(교착 없음). 별점 고치기는 옛 칸 −1 · 새 칸 +1, 숨김 · 지우기 −1, 되살리기 +1 — `PUBLISHED` 로 들어가고 나갈 때만. 평균 = 합 / 개수(읽을 때 소수 한 자리).
**구매 신호** `record` 는 호출자 트랜잭션에 합류해 구매 줄을 넣고(같은 `ref` 두 번 → 한 줄) 그 (계정, 대상)의 리뷰 `verified=true`. `revoke(ref)`(전액 환불) → `revoked_at`, 그 대상에 다른 살아 있는 구매가 없으면 `verified=false`. 모듈을 붙이기 전의 구매는 앱이 한 번 `record` 로 채운다(모듈 문서에 적는다).
**도움돼요** `PUT`/`DELETE` — 리뷰 줄 잠금 → 표 넣기/빼기 → `helpful_count ± 1`(board 반응과 같은 규칙). 자기 리뷰 `403 REVIEW.OWN_REVIEW`, 숨김 · 지움 404.
**신고** 계정당 한 번(두 번째는 같은 204), 자기 리뷰 403. `report_count + 1` · `reported_at`. 운영 큐 = `report_count > 0 ∧ (reports_reviewed_at 없음 ∨ reported_at > reports_reviewed_at)`. 숨김 · 「문제없음」이 `reports_reviewed_at` 을 찍는다. `report.auto-hide-at`(기본 0 = 꺼짐)이 N 이면 N 번째 신고에서 `HIDDEN`(사유 `AUTO_REPORTS`).
**숨김** 운영자만 · 사유 필수 → `HIDDEN` · 집계 −1 · 작성자에게 알림. 작성자는 자기 리뷰를 「숨겨짐 + 사유」로 보고 고칠 수 없다(지우기만).
**이벤트 고리** `ReviewEventListener.onPublished(review, firstTime)` · `onRemoved(review)` — 쓰기 트랜잭션 안. `firstTime` = 이 줄이 처음 공개됨(되살림은 거짓). `Reviews.countWritten(accountId)` = 그 계정의 리뷰 줄 수(줄은 지워지지 않으니 늘기만 한다 — 업적 수치로 쓴다).
**탈퇴** `AccountErasureListener`: board 와 같이 리뷰 · 도움돼요 · 신고의 계정을 계정마다 하나인 톰스톤으로(줄 · 집계는 남고 응답에 `authorDeleted: true`), 구매 줄은 지운다.

### A5. API (`skeleton.review.http.base-path` 기본 `/api/v1/reviews`, 붙이면 켜짐 · 운영은 `skeleton.review.admin.enabled` 기본 꺼짐 → 404)

| 경로 | 하는 일 | 누가 |
|---|---|---|
| `GET /config` | `targetTypes` · `bodyMinLength` · `bodyMaxLength` · `maxPhotos` · `reportReasons` · `canModerate` · `canReply` | 로그인 |
| `GET /targets/{type}/{id}/summary` | `{count, average\|null, distribution{1..5}, myReviewId\|null, canWrite, writeBlockedReason\|null(PURCHASE_REQUIRED · ALREADY_WRITTEN · LOCKED · SIGN_IN)}` | 읽기* |
| `GET /summaries?targets=product:a,product:b`(≤50) | `[{targetType, targetId, count, average}]` | 읽기* |
| `GET /targets/{type}/{id}/reviews?sort=recent\|helpful\|rating_high\|rating_low&rating&verified&photo&page&size` | 쪽 envelope. 남에게는 `PUBLISHED` 만 | 읽기* |
| `POST /targets/{type}/{id}/reviews` `{rating, body, photos?}` | 201 + Location, `@IdempotentOperation` | 로그인 |
| `PATCH` · `DELETE /{id}` | 고치기(별점 · 본문 · 사진) · 지우기(204) | 작성자 |
| `PUT` · `DELETE /{id}/helpful` | `{helpfulCount, helpful}` | 로그인 |
| `POST /{id}/reports` `{reason, detail?}` | 204 | 로그인 |
| `GET /me?cursor` | 내 리뷰(대상 · 상태 · 숨김 사유 포함) | 로그인 |
| `GET /{id}/photos/{index}` (결정 2) | `302` → 짧은 서명 주소(`PrivateUploadAccess`), `Cache-Control: private` | 그 리뷰를 볼 수 있는 사람 |
| `GET /api/v1/admin/reviews?queue=reported\|hidden\|all&targetType&cursor` | 운영 목록(신고 수 · 사유별 수 · 최근 신고 · 계정 이름은 `AccountLabels`, `account.read` 도 있을 때만) | `review.moderate` |
| `PUT /api/v1/admin/reviews/{id}/moderation` `{status: PUBLISHED\|HIDDEN, reason}` · `POST …/{id}/reports/dismiss` | 숨김 · 되살리기 · 문제없음 | `review.moderate` |
| `PUT` · `DELETE /api/v1/admin/reviews/{id}/reply` `{body}` (결정 1) | 리뷰당 답글 하나 | `review.reply` |

\* 읽기: `skeleton.review.http.allow-anonymous-read`(**모듈 기본 `false`**, 견본 `true`)면 이 셋의 `GET` 만 공개 경로.
- 리뷰 한 줄: `{id, rating, body, verified, helpfulCount, helpful, mine, authorName, authorTag, authorDeleted, edited, photos: [{index}], reply\|null, createdAt, updatedAt}` + 본인 · 운영자에게만 `status` · `moderationReason`. **계정 id 는 어떤 응답에도 없다**(리뷰끼리 · 게시판 프로필과 이어지지 않게). 이름은 `AuthorDirectory`(`AuthorContext("review", 대상 종류)`) 일괄 한 번.
- 권한 `PermissionDeclaration("review", [review.moderate, review.reply])` — `review.moderate` 는 board 처럼 `MODERATOR` 기본 포함, `review.reply` 는 전권만. 운영 기록: `review.hidden` · `review.restored` · `review.reports_dismissed` · `review.replied`.
- 오류 `REVIEW.*`: `TARGET_NOT_FOUND` · `NOT_FOUND` 404, `PURCHASE_REQUIRED` · `OWN_REVIEW` · `FORBIDDEN` 403, `ALREADY_WRITTEN` · `LOCKED` 409, `CONTENT_INVALID` 400(본문 길이 · 사진 수 · 키 거절). 본문은 board 처럼 HTML 을 해석하지 않고 제어문자만 지운다.

### A6. 화면 (react 새 패키지 `@skeleton/reviews`)

- `.`: `createReviewsApi` · 훅, `RatingStars`(표시, 「5점 만점에 4.3점」 라벨) · `RatingInput`(라디오 묶음, 키보드) · `ReviewSummary`(평균 + 분포 막대 — 막대를 누르면 그 별점으로 거름) · `ReviewList`(정렬 `Select` · 거르기 칩 · `Pagination` · 빈 상태) · `ReviewItem`(구매 인증 배지 · 「도움돼요 N」 단추 · `RowMenu` 신고 대화상자 · 숨겨짐 표시 · 답글 상자) · `ReviewForm`(별점 + `Textarea` + 사진 칸 `renderPhotos` prop — 올리기는 앱이 `@skeleton/storage` 로 잇는다, board 와 같이 패키지는 storage 를 모른다) · `writeBlockedReason` 문구(「구매한 분만 쓸 수 있어요」).
- `./admin`: `AdminReviewQueue`(신고 큐 표 · 사유 칩 · 숨김 대화상자 사유 필수 · 문제없음 · 되살리기) · `ReviewReplyEditor`. 단추는 `can(key)`.
- 문구 ko/en, 부품마다 스토리 + `play`, 화면은 Patterns(목록 · 폼 · 상세)에서 복사. 눌리는 것은 눌리게.

---

## B. 전문 검색 (`search` + `search-jdbc`)

### B2. 경계 · 확장 지점

```
platform common/search {SearchDocument, SearchIndexer, SearchSource}   ← catalog · 앱이 밀어 넣는다(search 를 모른다)
search ──▶ platform        search-jdbc ──▶ search, persistence-jdbc, job-queue-jdbc
```

| 확장 지점 | 자리 | 고른 이유 |
|---|---|---|
| `SearchIndexer`(`upsert(doc)` · `delete(type, id)`) — **호출자 트랜잭션 안에서 부른다** | `platform` | 다른 모듈(`catalog`, 나중에 `board` · `review`)이 `search` 에 의존하지 않고 밀어 넣는다(`RewardIssuer` 가 platform 에 있는 것과 같은 이유). 부르는 쪽은 `ObjectProvider.ifAvailable` — 검색을 안 붙인 앱은 아무 일도 없다 |
| `SearchSource`(`type` · `page(after, limit)`) | `platform` | 재색인 · 모듈을 나중에 붙인 앱의 채우기 · 바깥 엔진으로 옮길 때 처음 채우기 |
| `SearchEngine`(색인 쓰기 + 질의) | `search`, 구현 `search-jdbc` | **두 번째 구현이 계획에 있다**(바깥 엔진) — 레포 규칙상 둘째가 있을 때만 넓힌다. 바깥 엔진 구현은 `upsert` 를 아웃박스 줄로 받고 일꾼이 보낸다(포트 뜻 그대로) |
| `SearchText`(정규화 · 2글자 조각 · 질의어 쪼개기 · 하이라이트 위치) | `search` | 방언 · 엔진과 무관한 한 곳 — 바깥 엔진도 하이라이트를 같은 식으로 |
| `searchPublicEndpointContributor` | `search` | 비로그인 검색(기본 꺼짐) |

**색인 원칙(모듈 문서 맨 위)**: 색인에 있는 문서 = 보여도 되는 문서. `ownerId` 가 없으면 로그인한 모두(켜면 비로그인도)에게 제목 · 발췌가 보인다. 가려야 하는 상태(판매 중지 · 숨김 · 지움)가 되면 밀어 넣는 쪽이 `delete` 한다.

### B3. 데이터 · 한국어

- `SearchDocument(type [a-z0-9._-]{1,32}, id ≤64, title ≤300, body, ownerId?, scope? ≤64, updatedAt)` — 본문은 앞 16,000자만 색인(PG `tsvector` 위치 상한 16,383).
- `search_documents(id pk, doc_type, doc_id, owner_id null, scope null, title, body, source_updated_at, indexed_at, unique(doc_type, doc_id), index(owner_id), index(doc_type, indexed_at))` — 제목 · 본문은 NFKC 정규화한 원문(표시 · 발췌용).
  - **PostgreSQL**: + `terms tsvector` = `setweight(to_tsvector('simple', :titleGrams), 'A') || setweight(to_tsvector('simple', :bodyGrams), 'B')`, GIN. `*Grams` 는 `SearchText` 가 소문자 · 글자 묶음(한글 · 한자 · 가나 / 라틴 · 숫자)마다 만든 2글자 조각을 띄어 쓴 문자열. 질의어 하나 = `phraseto_tsquery('simple', :termGrams)`(조각이 이어져야 맞는다 = 부분 문자열 일치), 여럿은 `&&`(모두 맞아야). 순위 `ts_rank_cd`. 확장 설치 없음.
  - **MySQL**: `FULLTEXT(title) WITH PARSER ngram` · `FULLTEXT(title, body) WITH PARSER ngram`, `MATCH … AGAINST('+"질의어1" +"질의어2"' IN BOOLEAN MODE)`, 순위 = 제목 점수 × 2 + 전체 점수. 마이그레이션이 색인을 만들기 전 `SET SESSION innodb_ft_enable_stopword = OFF`(ngram 은 불용어를 **포함한** 조각을 버려 영어 조각이 빠진다). 기동 때 `@@ngram_token_size = 2` 가 아니면 경고.
  - 두 방언의 순위 값은 다르다 — 응답에 점수를 싣지 않고 순서만.
- 질의어: NFKC · 공백으로 쪼개기 · 글자 · 숫자 밖은 지움(따옴표 · `+` · `*` 가 질의 문법에 섞이지 않는다) · 2글자 미만은 버림(국내 관행), 최대 5개 · 다 버려지면 `400 SEARCH.QUERY_TOO_SHORT`.
- **실측 결과(K4 첫 걸음, 2026-10-30, PostgreSQL 18.6 · MySQL 8.4.8, 두 방언 `dbTest`) — 스펙 방식이 그대로 된다.**
  - PostgreSQL: `to_tsvector('simple', '가방 방끈')` = `'가방':1 '방끈':2`, `phraseto_tsquery('simple', '가방 방끈')` = `'가방' <-> '방끈'`. 한글 · 라틴(`ab` `in` `to`) · 숫자 · 섞인(`b한` `é한` `1a`) 2글자 조각이 모두 한 낱말로 들어가고, 불용어도 없다(`simple`). `방끈` ⊂ `가방끈` 맞음, 이어지지 않은 조각(`가방 끈끈`) 안 맞음. 밑줄 `_` 은 낱말을 가른다.
    - **그래서 조각 묶음은 「글자 · 숫자가 이어진 한 덩어리」(스크립트로 나누지 않는다)** — 섞인 조각도 한 낱말이라 MySQL ngram(스크립트를 가리지 않는다)과 같은 모양이 된다. 위 「글자 묶음(한글 · 한자 · 가나 / 라틴 · 숫자)마다」는 이 줄로 대체.
    - **실측으로 드러난 함정**: 조각열을 그냥 띄어 붙이면 위치가 이어져 **서로 다른 낱말의 조각이 구문으로 맞는다**(`ab bc` 두 낱말이 질의 `abc` = `ab <-> bc` 에 맞음) — 제목 끝 · 본문 처음(`||` 이 위치를 이어 붙인다)도 같다. 그래서 덩어리 사이와 각 부분의 끝에 **절대 질의 조각이 될 수 없는 3글자 이상 표지 낱말**(`SearchText.BREAK`)을 끼운다 — 실측 `'ab BREAK bc'` vs `ab bc` = 안 맞음, vs `ab` = 맞음. 위치 상한(16,383)은 넘으면 잘릴 뿐 오류가 아니다(`'ab' × 17000` 실측).
  - MySQL: `innodb_ft_enable_stopword` 는 **세션 변수**고, Flyway 마이그레이션 파일 안의 `SET SESSION innodb_ft_enable_stopword = OFF;` 가 같은 연결의 뒤따르는 `create table … fulltext … with parser ngram` 에 붙는다(전역값 기본 `1`, `ngram_token_size = 2`). 같은 마이그레이션 안에서 SET 앞 표(대조군)와 뒤 표를 나란히 만들어 비교:
    - 대조군(불용어 켜짐) 질의 `in`=0 · `is`=0 · `to`=0 · `on`=0 · `or`=0 · `be`=0 — 영어 조각이 빠진다. **게다가 `end` 질의가 `and` 글에도 맞는다**(조각 `en` 이 불용어라 버려져 `nd` 만 남는다).
    - SET 뒤 표 `in`=1 · `is`=1 · `to`=1 · `on`=1 · `or`=1 · `be`=1 · `end`=1(`and` 글에 안 맞음) — 빠지지 않는다.
    - 뒷날 DBA 가 다른 세션에서 `ALTER TABLE … FORCE` · 색인 재생성을 하면 불용어가 다시 켜진다 — 모듈 문서에 적는다.

### B4. 흐름

- **밀어 넣기**: 쓰는 쪽이 자기 트랜잭션 안에서 `upsert` · `delete`. jdbc 는 같은 트랜잭션에서 줄을 쓴다(`unique` 위로 덮어쓰기, `source_updated_at` 이 더 옛것이면 무시) → 커밋과 함께 보인다. 바깥 엔진으로 바꿔도 부르는 쪽 코드는 그대로.
- **`catalog`**: 상품 만들기 · 고치기 · 판매 여부 바꾸기에서 `product` 문서(`title` = 기본 언어 이름, `body` = 모든 언어 이름 · 설명, `scope` = `kind`, 주인 없음, 판매 중지면 `delete`) + `SearchSource("product")`. 견본 씨앗도 `CatalogAdmin` 을 지나므로 저절로 들어간다.
- **재색인**: `POST /api/v1/admin/search/rebuild {type}` → 작업 `search-rebuild`(job-queue-jdbc)가 `SearchSource.page` 를 커서로 500개씩 넣고 다음 쪽을 다시 큐에 넣는다, 끝에 그 종류에서 시작 시각 전에 색인된 줄을 지운다(사라진 것 정리). 같은 종류가 이미 돌고 있으면 409. 멱등.
- **질의**: 호출자 → `owner_id is null or owner_id = :caller`(비로그인은 `is null` 만) ∧ 종류 ∧ 범위 → 순위 또는 최신순. 쪽마다 하이라이트를 `SearchText` 로 계산: 제목 전체 + 본문 발췌(첫 일치 앞뒤 약 120자) 와 각각의 일치 위치 `[[시작, 끝)]`(UTF-16 위치 — JS 문자열과 같다).
- **탈퇴**: `owner_id` 가 그 계정인 줄을 지운다.

### B5. API (`skeleton.search.http.base-path` 기본 `/api/v1/search`, 붙이면 켜짐 · 운영 `skeleton.search.admin.enabled` 기본 꺼짐)

| 경로 | 하는 일 | 누가 |
|---|---|---|
| `GET /?q&types=note,product&scope&sort=relevance\|recent&page&size(≤50)` | 쪽 envelope `{type, id, title, snippet, titleHighlights, snippetHighlights, updatedAt}`. 결과 창 상한 1,000번째까지(`400 SEARCH.PAGE_TOO_DEEP`) | 로그인(`allow-anonymous-read` 기본 꺼짐) |
| `GET /api/v1/admin/search/types` | `[{type, documents, rebuilding, lastRebuiltAt}]` | `search.manage`(전권만) |
| `POST /api/v1/admin/search/rebuild` `{type}` | 202 · 소스 없는 종류 404 | `search.manage` |

### B6. 화면 (react 새 패키지 `@skeleton/search`)

`createSearchApi` · `useSearch`(입력 늦춤 · 이전 쪽 유지), `SearchBox`(`Input` 검색 + 제출 단추), `SearchResults`(종류 칩 거르기 · `Pagination` · 빈 · 로딩 · 오류 · 「2글자 이상」 안내), `Highlight`(위치를 `<mark>` 로 — 문자열은 그대로 이스케이프). 종류 이름과 링크는 앱이 `describe(type) → {label, href(id)}` prop 으로 준다(패키지는 노트 · 상품을 모른다). 운영 재색인 단추는 다음 판(1차는 HTTP 만).

---

## 7. 견본 앱 조립 (kotlin `apps/sample` + react `apps/sample`)

**리뷰**: `review-jdbc` 조립, yml `target-types: [product]` · `allow-anonymous-read: true` · `require-purchase: true`(결정 3) · `body-min-length: 10` · (결정 2) `max-photos: 5`. `ReviewTargets` = `CatalogStore` 로 상품 코드 확인. 구매 신호: 앱의 `PaymentOrderListener`(지금 `CheerOrderListener` 하나뿐 — 응원 · 리뷰 둘을 부르는 앱 리스너로 정리)가 구독 · 단건 주문의 가격 코드 → 상품 코드로 `ReviewPurchases.record(…, "order:<id>")`, 전액 환불이면 `revoke`. 로컬 씨앗: `acc_user` 에 `product:basic` 구매 줄(결제 키 없는 로컬에서도 써 볼 수 있게, local 프로필만). 보상: `ReviewEventListener` → `CampaignSignals.report(계정, "reviews.written", countWritten)` + 씨앗 업적 「첫 리뷰」 단계 1 → 100포인트. react: 공개 상품 화면 `/products/:code`(카탈로그 공개 읽기 + `ReviewSummary` · `ReviewList` · `ReviewForm`), 랜딩 가격표 카드에서 링크, 운영 메뉴 `/admin/reviews`.
**검색**: `search-jdbc` 조립(+ `catalog` 상품이 들어온다). `NoteService` 가 만들기 · 고치기 · 지우기에서 `note` 문서(주인 = 작성자)를 밀어 넣고 `SearchSource("note")`. react: 머리말 검색창 + `/search?q=` 화면, `describe`: `note` → `/notes/:id`, `product` → `/products/:code`.

**e2e 여정**
- 리뷰(`reviews.e2e.ts`): ① user 가 가짜 결제사로 베이직 구독 → `/products/basic` 에서 5점 · 본문 작성 → 요약 「1개 · 5.0」 · 「구매 인증」 → 받은편지함 「100포인트」 ② 구매 없는 user2 는 쓰기 대신 「구매한 분만 쓸 수 있어요」, user 의 리뷰에 도움돼요(1) · 신고(스팸) ③ admin `/admin/reviews` 신고 큐에서 답글(결정 1) → 숨김(사유) → user 알림 · 상품 화면 0개, user 는 「숨겨짐」 ④ 로그아웃 상태로 상품 화면의 리뷰가 보인다(숨기기 전 단계에서 확인).
- 검색(`search.e2e.ts`): ① user 노트 「여름 가방끈 수선 메모」 → `방끈` 검색 → 결과 · 강조 ② `프로` → 상품 「프로」 ③ user2 의 `방끈` → 없음 ④ user 노트 지우기 → 없음 ⑤ 한 글자 → 안내.

## 8. 검증 (깨지면 사고 나는 것만)

**kotlin 리뷰** — 두 방언 `dbTest`: 40 스레드가 한 대상에 쓰기 · 고치기 · 지우기 · 숨기기 → 집계 = `PUBLISHED` 줄로 다시 센 값 · 같은 계정 동시 두 번 → 한 줄 + 409 · 도움돼요 동시 토글 = 표 줄 수 · 구매 신호가 리뷰 전/후 · `revoke` 뒤 다른 구매가 남은 경우. 서비스 시험: `require-purchase` 403 · 숨긴 뒤 지우고 다시 쓰기 409 `LOCKED` · 남에게 숨김 · 지움 리뷰의 본문 · 사유 문자열이 응답 JSON 에 없다 · 응답 어디에도 계정 id 문자열이 없다 · 비로그인 기본 401 / 켜면 200 · 운영 꺼짐 404 · 권한 없음 403 · 이벤트 고리가 던지면 쓰기도 실패 · `noOptionalTest`(`notification` · `idempotency` 없이).
**kotlin 검색** — 두 방언 `dbTest`: 2글자 한글 부분 일치(`방끈` ⊂ `가방끈`) · 대소문자 · 전각/반각 · 두 질의어 AND · 따옴표 · `+` · `*` · `'` 가 섞인 질의가 깨지지 않는다 · 제목 일치가 본문 일치보다 앞 · 남의 주인 문서가 결과 · 개수에 없다 · `delete` 뒤 없음 · 롤백된 트랜잭션의 `upsert` 는 없음 · 재색인이 사라진 문서를 지운다 · 16,000자 넘는 본문. 하이라이트 위치 단위 시험(이모지 · 결합 문자 포함).
**공통**: `MigrationLockTest` · `CapabilitiesCatalogTest` · `ModuleDocumentationTest`, 견본 통합 시험(구매 신호 → 배지, 노트 → 검색).
**react**: `RatingInput` 키보드 · 라벨, `ReviewItem` 숨겨짐 · 도움돼요 · 신고 대화상자, `Highlight` 가 `<script>` 같은 문자열을 글자로 그린다, api 경로 기록 시험, `capabilities` 검사, 스토리 `play`.

## 9. 내가 정한 것 (관행 · 선례로 정함 — 다르면 말해 줘)

1. 계정 × 대상 하나, 지운 줄은 되살림, 숨긴 뒤 지운 리뷰는 다시 못 씀(운영자 되살리기만).
2. 리뷰 응답에 계정 id · 프로필 id 없음, 이름은 `AuthorDirectory`. 작성 프로필 고르기는 다음 판.
3. 구매 확인 = 앱이 넣는 신호(`record`/`revoke`), 배지는 리뷰 줄 칸으로 그때그때 맞춘다(환불하면 내린다).
4. 평균은 단순 평균 소수 한 자리, 숨김 · 지움은 집계에서 뺀다. 집계는 비정규화 줄 + 원자적 UPDATE(board 카운터 관례).
5. 도움돼요 하나(싫어요 없음), 신고 사유 코드 `SPAM · ABUSE · PRIVACY · OFF_TOPIC · OTHER`, 자동 가림 기본 꺼짐.
6. 리뷰 · 검색 공개 읽기는 모듈 기본 꺼짐 — 리뷰는 견본이 켠다(배지 결정 1 관례), 검색은 견본도 로그인만.
7. 탈퇴는 board 처럼 톰스톤(리뷰 · 집계는 남는다).
8. 보상은 `ReviewEventListener`(트랜잭션 안) → 앱이 `CampaignSignals.report` — `review` 는 보상 모듈을 모른다(배지 결정 2 의 조립 원칙).
9. 검색은 두 방언 모두 2글자 조각, 확장 설치 없음, 질의어 2글자 이상 · AND, 점수는 응답에 없음, 결과 창 1,000.
10. 색인 포트는 platform, 동기(호출자 트랜잭션), 재색인은 job-queue 작업. 상품은 `catalog` 가 직접 밀어 넣는다(작은 `catalog` 변경), 언어별 문서는 다음 판.

## 10. 작업 계획

| # | 레포 | 조각 | 선행 | 크기 · 예상 |
|---|---|---|---|---|
| K1 | kotlin | `review` · `review-jdbc` 핵심: 대상 종류 · `ReviewTargets` · 쓰기/고치기/지우기 · 집계 · 목록/요약/일괄 요약 · 도움돼요 · `ReviewPurchases` · `require-purchase` · 이벤트 고리 · 비로그인 읽기 · 탈퇴 · 두 방언 dbTest · 모듈 문서 · capabilities | — | 대 · 2.5시간 |
| K2 | kotlin | `review` 운영: 신고 · 큐 · 숨김/되살리기/문제없음 · 자동 가림 · 권한 · 운영 기록 · `ReviewNotifier` · (결정 1) 답글 · (결정 2) 사진 칸 · 서명 주소 경로 | K1 계약 커밋 | 중 · 1.5시간 |
| K3 | kotlin | 견본 리뷰 조립: yml · `ReviewTargets` · 앱 결제 리스너의 구매 신호 · 로컬 씨앗 · 업적 「첫 리뷰」 · 통합 시험 · CHANGELOG | K1 · K2 | 소~중 · 1시간 |
| K4 | kotlin | `platform` `common/search` 포트 + `search` · `search-jdbc`: 실측 → 두 방언 색인 · 질의 · `SearchText` · HTTP · 재색인 작업 · 탈퇴 · dbTest · 모듈 문서 · capabilities | — | 대 · 2.5시간 |
| K5 | kotlin | `catalog` 상품 밀어 넣기 + `SearchSource` · 견본 노트 색인 · 견본 조립 · 통합 시험 · CHANGELOG | K4 계약 커밋 | 중 · 1시간 |
| R1 | react | `@skeleton/reviews` 사용자 부품 · api · 훅 · 스토리 | §A5 계약 | 중~대 · 2시간 |
| R2 | react | `@skeleton/reviews/admin` + 견본 `/products/:code` · 가격표 링크 · 운영 메뉴 · `reviews.e2e.ts` | R1 · (e2e) K3 | 중 · 1.5시간 |
| R3 | react | `@skeleton/search` + 견본 검색창 · `/search` · `search.e2e.ts`(상품 결과는 글자까지만 — 상품 화면은 R2) | §B5 계약 · (e2e) K5 | 중 · 1.5시간 |

- 줄기: **리뷰** K1 ∥ R1 → K2 ∥ R2(화면) → K3 → e2e / **검색** K4 ∥ R3 → K5 → e2e. 두 줄기는 처음부터 나란히 돈다.
- 겹치는 파일: K3 · K5 가 `apps/sample` 의 `build.gradle.kts` · `application.yml` · `capabilities.json` · CHANGELOG 를 같이 고친다 — 둘을 나란히 돌리면 지휘 세션이 합칠 때 손으로 맞춘다(아니면 K5 를 K3 뒤로). R2 · R3 도 견본 라우트 · 메뉴가 겹친다.
- developer 8번, 묶음(리뷰 kotlin · 리뷰 react · 검색 kotlin+react)마다 리뷰 1 + 확인 1, 전체 스위트는 묶음 끝에 지휘 세션이 한 번. 나란히 돌리면 약 5~6시간, 한 줄로 돌리면 약 13시간.
- 결정 1 · 2 를 「다음 판」으로 고르면 K2 가 소(1시간)로, R2 의 답글 · 사진 칸이 빠진다.

## 정해야 할 것

1. **판매자 답글을 1차에 넣을까**
   - **운영자 답글, 리뷰당 하나를 1차에 (추천)** — 쿠팡 · 스마트스토어 · 앱스토어 · 플레이 모두 있는 관행이고, 표 하나 · 권한 하나 · 화면 칸 하나라 K2 에 소로 얹힌다.
   - 다음 판으로 미룬다 — 1차가 조금 작아진다.
   - 마켓 판매자(대상 주인)도 답한다 — 대상 → 주인을 묻는 포트가 더 필요하다. 지금 견본은 판매자가 하나라 쓸 곳이 없다.
2. **리뷰 사진을 1차에 넣을까, 어떻게 보여 줄까**
   - **넣는다 — 저장 키만 싣고, 볼 때 서버가 짧은 서명 주소로 넘긴다(`302`) (추천)** — 숨기는 순간 바로 안 보이고, board 의 공개 영역(복사 · 큐 · 대조)이 필요 없다. 대신 그림마다 서버를 한 번 거치고 CDN 캐시가 없다.
   - 다음 판으로 미룬다 — 포토 리뷰가 국내 관행이라 빠지면 견본이 덜 그럴듯하다.
   - board 의 공개 영역 방식(CDN 복사 · 아웃박스 · 전체 대조)을 그대로 — 가장 빠르게 보이지만 조각 하나가 대로 늘어난다.
3. **견본 앱에서 누가 리뷰를 쓸 수 있나** (모듈은 둘 다 된다, 견본의 선택과 e2e 가 달라진다)
   - **산 사람만(`require-purchase: true`) (추천)** — 국내 커머스 관행(쿠팡 · 스마트스토어 · 무신사). e2e 가 가짜 결제사로 먼저 산다.
   - 누구나 쓰고 산 사람에게만 「구매 인증」 배지 — 아마존식. e2e 가 더 짧다.

## 결정됨 (2026-10-30)

형이 따로 고르지 않아 지휘 세션이 추천안으로 정했다(끝 보고에서 형에게 알린다). 이 절이 본문과 다르면 이 절이 이긴다.

1. 답글 — 운영자 답글 리뷰당 하나, 1차에 넣는다.
2. 사진 — 넣는다. 저장 키만 싣고, 볼 때 서버가 짧은 서명 주소로 302.
3. 견본 앱 — 산 사람만 리뷰를 쓴다(e2e 는 가짜 결제사로 먼저 산다).
