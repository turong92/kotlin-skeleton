# review

리뷰 · 평점 — 아무 대상(`type` + `id`)에 별점 1~5와 본문을 쓴다. **계정 × 대상 하나**(고쳐 쓴다), 대상별 평균 · 별점 분포, 정렬 · 걸러 보기, 「도움돼요」, 구매 인증 배지, 비로그인 읽기(앱이 켠다).
**상품 · 결제 · 보상을 모른다** — `catalog` · `payment` · `reward` · `campaign` 에 의존하지 않는다. 대상이 진짜 있는지(`ReviewTargets`) · 누가 샀는지(`ReviewPurchases.record`) · 무엇을 줄지(`ReviewEventListener`)는 앱이 포트로 잇는다.
저장은 어댑터 `review-jdbc` 가 한다(메모리 구현 없음 — 영속이 본질이다). 신고 · 숨김 · 운영 큐 · 운영자 답글 · 사진도 이 모듈에 있다(아래 「운영」 「사진」).

**대상**: `skeleton.review.target-types`(기본 비어 있음 — 아무 대상도 없는 것이 중립이다)에 적은 종류만 받는다. 모르는 종류 · 모양이 틀린 id(1~64자)는 404 `REVIEW.TARGET_NOT_FOUND`. 앱이 `ReviewTargets` 빈을 두면 쓰기 때 대상이 진짜 있는지 묻는다(빈이 없으면 id 모양만 본다).
**쓰기** `POST {base}/targets/{type}/{id}/reviews {rating, body}` → 201 + `Location`(`Idempotency-Key` 필요 — `idempotency` 가 있을 때). `require-purchase=true` 면 살아 있는 구매 줄이 있어야 한다(403 `REVIEW.PURCHASE_REQUIRED`). 같은 계정이 같은 대상에 살아 있는 리뷰가 있으면 409 `REVIEW.ALREADY_WRITTEN`(동시에 둘 → 유니크가 하나로 가른다).
지운 리뷰를 다시 쓰면 **같은 줄이 되살아난다**(도움돼요 · 신고는 비운다). 운영자가 숨긴 뒤 지운 줄은 409 `REVIEW.LOCKED`(숨김을 지우고 다시 쓰기로 피하지 못하게). 본문은 HTML 을 해석하지 않고 제어문자 · 방향 덮어쓰기 문자만 지운다(400 `REVIEW.CONTENT_INVALID`).
**고치기 · 지우기** `PATCH`/`DELETE {base}/{id}` — 작성자만(남의 공개 리뷰 403 `REVIEW.FORBIDDEN`, 남의 숨김 · 지움 줄은 있는지도 알려 주지 않는 404). 숨겨진 리뷰는 지우기만 된다.
**집계**: 대상별 한 줄(개수 · 합 · 별점별 개수)을 같은 트랜잭션에서 `set n = n + :d` 원자적으로 바꾼다 — `PUBLISHED` 로 들어가고 나갈 때만(쓰기 +1 · 별점 고치기 옛 칸 −1 새 칸 +1 · 지우기 −1). 평균 = 합 / 개수, 소수 한 자리. 잠금 순서는 리뷰 줄 → 집계 줄 하나다.
**읽기**: `GET {base}/targets/{type}/{id}/summary`(`count · average · distribution{1..5} · myReviewId · canWrite · writeBlockedReason`) · `GET {base}/summaries?targets=product:a,product:b`(50개까지) · `GET {base}/targets/{type}/{id}/reviews?sort=recent|helpful|rating_high|rating_low&rating&verified&photo&page&size`(쪽 envelope).
남에게는 `PUBLISHED` 줄만 읽는다 — 저장소 질의가 그 줄만 가져온다(응답에서 걸러 내는 것이 아니다). 보는 사람 자신의 `HIDDEN` 줄만 더 읽고, 그 줄에는 `status` · `moderationReason` 이 실린다. **응답 어디에도 계정 id 가 없다** — 작성자는 `authorName` · `authorTag`(요청 하나에 한 번 `AuthorDirectory`, 컨텍스트 `("review", 대상 종류)`)뿐이고 탈퇴한 계정은 `authorDeleted: true`.
`GET {base}/me?cursor&size` 는 내 리뷰(대상 · 상태 · 숨김 사유 포함, 지운 줄 제외), `GET {base}/config` 는 `targetTypes · bodyMinLength · bodyMaxLength · maxPhotos · reportReasons · canModerate · canReply`(로그인; 뒤 둘은 운영 권한이 있을 때 true).
**도움돼요** `PUT`/`DELETE {base}/{id}/helpful` → `{helpfulCount, helpful}`. 리뷰 줄을 잠근 채 표를 넣고 빼고 `helpful_count ± 1` 한다(`board` 반응과 같은 규칙). 자기 리뷰 403 `REVIEW.OWN_REVIEW`, 숨김 · 지움 404.

**신고** `POST {base}/{id}/reports {reason, detail?}` → 204. 사유 코드 `SPAM · ABUSE · PRIVACY · OFF_TOPIC · OTHER`, 계정당 한 번(두 번째도 같은 204), 자기 리뷰 403 `REVIEW.OWN_REVIEW`, 공개된 줄만(아니면 404). `skeleton.review.report.auto-hide-at=N`(기본 0 = 꺼짐)이면 N 번째 신고에서 자동으로 숨긴다(사유 `AUTO_REPORTS`).
**운영(앱이 열면)** `skeleton.review.admin.enabled=true`(기본 꺼짐 → 경로가 없어 404)일 때 `{admin base}/reviews`(기본 `/api/v1/admin/reviews`). 매 호출이 권한을 판정한다(익명 401 · 권한 없음 403): `review.moderate`(`MODERATOR` 기본 포함) — 큐 · 숨김 · 되살리기 · 문제없음, `review.reply`(전권 역할만) — 답글. 앱의 `ReviewPolicy.canModerate` 가 한 번 더 거절할 수 있다.
- `GET ?queue=reported|hidden|all&targetType&cursor&size` — 운영 목록(커서). 한 줄 `{id, targetType, targetId, rating, body, status, moderationReason, reportCount, reportReasons{사유: 수}, lastReportedAt, accountLabel|null, reply|null, createdAt}`. `reported` 는 **공개 중이고 확인 안 한 신고**가 있는 줄(숨기거나 「문제없음」을 누르면 빠지고, 새 신고가 들어오면 다시 오른다). `accountLabel`(이메일, 없으면 null)은 운영자가 `account.read` 도 가질 때만 — 계정 id 는 나가지 않는다.
- `PUT /{id}/moderation {status: HIDDEN|PUBLISHED, reason}` → 204 — 숨김은 사유 필수(집계 −1, 이벤트 `onRemoved`, 작성자에게 알림), 되살리기는 집계 +1 · 숨김 기록 삭제. **운영자가 숨긴 뒤 작성자가 지운 줄도 여기서 되살리면 다시 쓸 수 있다**(되살리기는 운영자만). `POST /{id}/reports/dismiss` → 204 (문제없음). `PUT`/`DELETE /{id}/reply {body}` → 204 (리뷰당 하나, 공개 줄에만).
- 운영 기록 `review.hidden` · `review.restored` · `review.reports_dismissed` · `review.replied`(대상은 리뷰 id 만). 숨긴 리뷰는 작성자 본인에게 「숨겨짐 + 사유」로 보이고(고칠 수 없고 지우기만), 남에게는 본문 · 사유가 어떤 응답에도 없다. 답글은 공개 줄의 `reply: {body, createdAt, updatedAt}`.
- 알림(`ReviewNotifier`, 커밋 뒤, 실패해도 운영 처리는 남는다): 기본은 아무것도 안 한다 — `notification` 이 있으면 받은편지함(주제 `reviews`, `skeleton.review.notify.enabled=false` 로 끈다).
**사진** `skeleton.review.max-photos=N`(기본 0)이면 쓰기 · 고치기에 `photos: [저장 키…]`(N 개까지)를 받는다. `PATCH` 는 `photos` 가 **없으면 그대로, 있으면 그 목록으로 전체 교체**(빈 목록 = 모두 지움). 올리기는 앱이 `storage` 로 한다(`ReviewPolicy.canAttach` 로 키 소유를 좁힌다). **키에 작성자 계정 id 가 들어 있으면 거절**한다(서명 주소로 새 나간다).
응답의 `photos` 는 `[{index}]` 이고 **저장 키(`key`)는 작성자 본인 줄에만** 실린다(고칠 때 기존 사진을 그대로 다시 보내려고). 보기: `GET {base}/{id}/photos/{index}`(로그인) → `302` 로 짧은 서명 주소(`PrivateUploadAccess`), `Cache-Control: private`. 그 리뷰를 볼 수 있는 사람(공개 줄은 누구나, 숨긴 줄은 작성자 · 운영자)만 — 숨기는 순간 바로 안 보인다. 못 보면 404.
**구매 신호(앱이 준다)**: `ReviewPurchases.record(accountId, ReviewTarget(type, id), ref)` — 결제 리스너 · 외부 주문 · 수동 어디서든 같은 한 줄. 호출자 트랜잭션에 합류하고, 같은 `ref` 두 번은 한 줄이며, 그 (계정, 대상)의 리뷰는 `verified=true` 가 된다. 리뷰를 쓰는 순간에도 살아 있는 구매 줄이 있으면 `verified` 다.
`revoke(ref)`(전액 환불)는 구매를 취소하고, 그 대상에 다른 살아 있는 구매가 없으면 `verified=false` 로 내린다. **모듈을 붙이기 전의 구매는 앱이 한 번 `record` 로 채운다.**
**이벤트 고리**: 앱이 `ReviewEventListener` 빈을 두면(여러 개) 공개 `onPublished(review, firstTime)`(`firstTime` = 이 줄이 처음 공개됨, 되살림은 false)와 `onRemoved(review)`(작성자가 지움)가 **쓰기 트랜잭션 안에서** 불린다 — 짧게 하고, **던지면 쓰기도 실패한다**(`PaymentOrderListener` 와 같은 규칙; 보상 지급 건은 내구성이 필요해서다). 리뷰는 보상을 모른다 — 앱이 `CampaignSignals.report` 같은 곳에 잇는다.
`Reviews.countWritten(accountId)` 는 그 계정의 리뷰 줄 수(줄은 지워지지 않아 늘기만 한다 — 업적 수치로 쓴다). `ReviewStats.of(targets)` 는 앱이 자기 상품 목록 응답에 평균을 끼울 때 쓰는 일괄 조회다.

**비로그인 읽기(앱이 열면)**: `skeleton.review.http.allow-anonymous-read=true`(**모듈 기본은 `false` — 로그인 필요, 401**; `badge` · `board` 와 같다)면 요약 · 일괄 요약 · 목록의 `GET` 셋만 공개 경로가 된다(`reviewPublicEndpointContributor`). `config` · `me` · 쓰기 · 도움돼요는 항상 로그인이 필요하다. 비로그인 요약은 `canWrite=false`, `writeBlockedReason=SIGN_IN`. 열면서 platform `skeleton.web.rate-limit` 도 켠다(리뷰 전용 한도는 없다).
**탈퇴**: `AccountErasureListener` 가 그 계정의 리뷰 · 도움돼요 · 신고의 계정을 계정마다 하나인 톰스톤으로 바꾼다(줄 · 집계는 남는다). 구매 줄은 지운다.
**알려진 한계**: 같은 (계정, 대상)의 구매 신호와 첫 리뷰가 **같은 밀리초에** 서로 커밋 전이면 `verified` 가 한 번 놓칠 수 있다(트랜잭션끼리 서로의 미커밋 줄을 못 본다) — 다음 `record`/`revoke` 가 맞춘다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:review"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `notification` (숨김 · 답글 알림 — 클래스패스에 있을 때만 켜진다), `idempotency` (쓰기의 멱등 표시), Spring Security (경로를 등록할 때만 필요하다) |
| 설정 접두사 | `skeleton.review` — [docs/config/modules/review.yml](../config/modules/review.yml) |
| 기본 동작 | 켜짐. 사용자 경로 켜짐(대상 종류는 앱이 적어야 쓸 수 있다, 비로그인 읽기는 꺼짐 — 앱이 `allow-anonymous-read` 로 연다), 구매자만 쓰기 꺼짐, 운영자 경로 꺼짐, 자동 가림 꺼짐, 사진 꺼짐. |
| 부팅에 필요한 것 | 저장소 포트 `ReviewStore` — `review-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `ReviewStore`, `ReviewTargets`, `ReviewPolicy`, `ReviewEventListener`, `ReviewNotifier`, `ReviewModerationService`, `ReviewPurchases`, `ReviewStats`, `Reviews`, `ReviewService`, `ReviewReader`, `ReviewController`, `ReviewAdminController`, 권한 `reviewPermissionDeclaration`, 공개 경로 `reviewPublicEndpointContributor`, 탈퇴 고리 `reviewAccountErasureListener` |
| 마이그레이션 | 없음 (스키마는 `review-jdbc`) |
| 프론트 짝 | `@skeleton/reviews` |
| 테스트 | `modules/review/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
