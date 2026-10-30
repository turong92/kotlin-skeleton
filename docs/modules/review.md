# review

리뷰 · 평점 — 아무 대상(`type` + `id`)에 별점 1~5와 본문을 쓴다. **계정 × 대상 하나**(고쳐 쓴다), 대상별 평균 · 별점 분포, 정렬 · 걸러 보기, 「도움돼요」, 구매 인증 배지, 비로그인 읽기(앱이 켠다).
**상품 · 결제 · 보상을 모른다** — `catalog` · `payment` · `reward` · `campaign` 에 의존하지 않는다. 대상이 진짜 있는지(`ReviewTargets`) · 누가 샀는지(`ReviewPurchases.record`) · 무엇을 줄지(`ReviewEventListener`)는 앱이 포트로 잇는다.
저장은 어댑터 `review-jdbc` 가 한다(메모리 구현 없음 — 영속이 본질이다). 신고 · 숨김 · 운영 큐 · 운영자 답글 · 사진은 같은 모듈의 다음 조각이 채운다(표는 이미 있다).

**대상**: `skeleton.review.target-types`(기본 비어 있음 — 아무 대상도 없는 것이 중립이다)에 적은 종류만 받는다. 모르는 종류 · 모양이 틀린 id(1~64자)는 404 `REVIEW.TARGET_NOT_FOUND`. 앱이 `ReviewTargets` 빈을 두면 쓰기 때 대상이 진짜 있는지 묻는다(빈이 없으면 id 모양만 본다).
**쓰기** `POST {base}/targets/{type}/{id}/reviews {rating, body}` → 201 + `Location`(`Idempotency-Key` 필요 — `idempotency` 가 있을 때). `require-purchase=true` 면 살아 있는 구매 줄이 있어야 한다(403 `REVIEW.PURCHASE_REQUIRED`). 같은 계정이 같은 대상에 살아 있는 리뷰가 있으면 409 `REVIEW.ALREADY_WRITTEN`(동시에 둘 → 유니크가 하나로 가른다).
지운 리뷰를 다시 쓰면 **같은 줄이 되살아난다**(도움돼요 · 신고는 비운다). 운영자가 숨긴 뒤 지운 줄은 409 `REVIEW.LOCKED`(숨김을 지우고 다시 쓰기로 피하지 못하게). 본문은 HTML 을 해석하지 않고 제어문자 · 방향 덮어쓰기 문자만 지운다(400 `REVIEW.CONTENT_INVALID`).
**고치기 · 지우기** `PATCH`/`DELETE {base}/{id}` — 작성자만(남의 공개 리뷰 403 `REVIEW.FORBIDDEN`, 남의 숨김 · 지움 줄은 있는지도 알려 주지 않는 404). 숨겨진 리뷰는 지우기만 된다.
**집계**: 대상별 한 줄(개수 · 합 · 별점별 개수)을 같은 트랜잭션에서 `set n = n + :d` 원자적으로 바꾼다 — `PUBLISHED` 로 들어가고 나갈 때만(쓰기 +1 · 별점 고치기 옛 칸 −1 새 칸 +1 · 지우기 −1). 평균 = 합 / 개수, 소수 한 자리. 잠금 순서는 리뷰 줄 → 집계 줄 하나다.
**읽기**: `GET {base}/targets/{type}/{id}/summary`(`count · average · distribution{1..5} · myReviewId · canWrite · writeBlockedReason`) · `GET {base}/summaries?targets=product:a,product:b`(50개까지) · `GET {base}/targets/{type}/{id}/reviews?sort=recent|helpful|rating_high|rating_low&rating&verified&photo&page&size`(쪽 envelope).
남에게는 `PUBLISHED` 줄만 읽는다 — 저장소 질의가 그 줄만 가져온다(응답에서 걸러 내는 것이 아니다). 보는 사람 자신의 `HIDDEN` 줄만 더 읽고, 그 줄에는 `status` · `moderationReason` 이 실린다. **응답 어디에도 계정 id 가 없다** — 작성자는 `authorName` · `authorTag`(요청 하나에 한 번 `AuthorDirectory`, 컨텍스트 `("review", 대상 종류)`)뿐이고 탈퇴한 계정은 `authorDeleted: true`.
`GET {base}/me?cursor&size` 는 내 리뷰(대상 · 상태 · 숨김 사유 포함, 지운 줄 제외), `GET {base}/config` 는 `targetTypes · bodyMinLength · bodyMaxLength · maxPhotos · reportReasons · canModerate · canReply`(로그인).
**도움돼요** `PUT`/`DELETE {base}/{id}/helpful` → `{helpfulCount, helpful}`. 리뷰 줄을 잠근 채 표를 넣고 빼고 `helpful_count ± 1` 한다(`board` 반응과 같은 규칙). 자기 리뷰 403 `REVIEW.OWN_REVIEW`, 숨김 · 지움 404.

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
| 기본 동작 | 켜짐. 사용자 경로 켜짐(대상 종류는 앱이 적어야 쓸 수 있다, 비로그인 읽기는 꺼짐 — 앱이 `allow-anonymous-read` 로 연다), 구매자만 쓰기 꺼짐. |
| 부팅에 필요한 것 | 저장소 포트 `ReviewStore` — `review-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `ReviewStore`, `ReviewTargets`, `ReviewPolicy`, `ReviewEventListener`, `ReviewPurchases`, `ReviewStats`, `Reviews`, `ReviewService`, `ReviewReader`, `ReviewController`, 공개 경로 `reviewPublicEndpointContributor`, 탈퇴 고리 `reviewAccountErasureListener` |
| 마이그레이션 | 없음 (스키마는 `review-jdbc`) |
| 프론트 짝 | `@skeleton/reviews` |
| 테스트 | `modules/review/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
