# search

DB 전문 검색 — 다른 모듈(`catalog` 상품 · 앱의 노트 …)이 **밀어 넣은 문서**를 한국어 2글자 조각으로 찾는다. 질의(쪽 · 종류 · 범위 · 정렬 · 하이라이트), 재색인 시작 · 종류별 현황(운영), 탈퇴 고리, 비로그인 읽기(앱이 열면).
저장 · 질의 SQL 은 어댑터 `search-jdbc` 가 한다(메모리 구현 없음). 이 모듈은 **`catalog` · `board` · `account` 어느 것도 모른다** — 그쪽이 `platform` 의 `SearchIndexer` 로 밀어 넣고 `SearchSource` 빈을 둘 뿐이다.

**색인 원칙(맨 위에 읽는다)**: 색인에 있는 문서 = 보여도 되는 문서. `ownerId` 가 없으면 로그인한 모두(앱이 열면 비로그인도)에게 제목 · 발췌가 보이고, 있으면 그 계정에게만 보인다. 가려야 하는 상태(판매 중지 · 숨김 · 지움)가 되면 **밀어 넣는 쪽이 `delete` 한다** — 이 모듈은 원본 상태를 모른다.

**밀어 넣기(platform 계약)**: `SearchDocument(type [a-z0-9._-]{1,32}, id ≤64, title ≤300, body, ownerId?, scope? ≤64, updatedAt)` · `SearchIndexer.upsert/delete` — **호출자 트랜잭션 안에서 부른다**(같은 DB 구현은 그 트랜잭션에 함께 쓴다: 커밋과 함께 보이고 롤백되면 없다). 부르는 쪽은 `ObjectProvider<SearchIndexer>.ifAvailable` 로 받는다 — 검색을 안 붙인 앱은 아무 일도 없다.
`upsert` 는 색인에 있는 것보다 `updatedAt` 이 옛것이면 무시한다. 본문은 앞 16,000자만 색인한다. `SearchSource(type, page(after, limit))` 는 재색인 · 나중에 붙인 앱의 채우기 · 바깥 엔진으로 옮길 때 처음 채우기가 쓴다(안정된 순서로 쪽을 내고, 색인에 있어야 하는 문서만 내놓는다).

**한국어 · 영어 (`SearchText`, 방언 · 엔진과 무관)**: 글자 · 숫자가 이어진 한 덩어리마다 겹치는 2글자 조각을 만든다. 질의는 NFKC · 소문자 · 공백과 구두점 · 따옴표 · `+` · `*` 로 덩어리를 나눈 뒤 **2글자 미만은 버리고**(국내 관행) 같은 것은 하나로, 최대 5개 — 모두 맞아야(AND) 하고 각 덩어리는 **부분 문자열 일치**다(`방끈` ⊂ `가방끈`). 다 버려지면 400 `SEARCH.QUERY_TOO_SHORT`.
형태소 분석 · 동의어는 없다(비목표). 두 방언의 순위 값은 다르므로 점수는 응답에 없고 순서만 나간다 — 제목 일치가 본문 일치보다 앞이다. 하이라이트 위치는 서버가 저장된 원문에서 계산한다: `[시작, 끝)` **UTF-16 위치**(자바스크립트 문자열과 같다), HTML 없음, 결합 문자는 끝에 붙여 센다.
알려진 차이: MySQL 의 기본 콜레이션은 악센트도 구별하지 않는다(`e` = `é`), PostgreSQL 은 구별한다. 밑줄 `_` 은 PostgreSQL 에서 낱말을 가른다.

**질의** `GET /api/v1/search?q&types=note,product&scope&sort=relevance|recent&page&size(≤50)`(로그인; 앱이 `allow-anonymous-read=true` 로 열면 비로그인도, 그때는 주인 없는 문서만): 쪽 envelope `{values: [{type, id, title, snippet, titleHighlights, snippetHighlights, updatedAt}], pagination}`.
`snippet` 은 첫 일치 앞뒤 약 120자(잘린 쪽에 `…`), 일치가 본문에 없으면 본문 머리. `*Highlights` 는 `[[시작, 끝)]` 쌍 목록(각각 제목 · 발췌 문자열 기준). **결과 창 상한은 1,000번째까지** — 넘으면 400 `SEARCH.PAGE_TOO_DEEP`, `totalElements` 도 1,000 에서 멈춘다. 남의 주인 문서는 결과 · 개수 어디에도 없다.
속도 제한은 검색 전용 한도를 만들지 않는다 — platform `skeleton.web.rate-limit`(`path-pattern: /api/v1/search`)을 따른다. 비로그인으로 여는 앱은 이것을 켜라.

**재색인(운영, `skeleton.search.admin.enabled=true` 일 때만 — 꺼져 있으면 404)**: 모든 호출에 `search.manage`(기본 역할 없음 — 전권 역할만, 익명 401 · 권한 없음 403).

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/admin/search/types` | `[{type, documents, rebuilding, lastRebuiltAt}]` — 색인에 있거나 `SearchSource` 가 있는 종류 |
| `POST /api/v1/admin/search/rebuild {type}` | 202. 소스 없는 종류 404 `SEARCH.SOURCE_NOT_FOUND`, 같은 종류가 이미 돌고 있으면 409 `SEARCH.REBUILD_RUNNING`. 운영 기록 `search.rebuilt` |

재색인은 `job-queue-jdbc` 작업 `search-rebuild`: 소스를 500개씩 읽어 덮어쓰고(원본이 진실 — 옛 시각이어도 쓴다) 다음 쪽을 다시 큐에 넣는다(쪽 색인 + 다음 작업은 한 트랜잭션). 끝에 그 종류에서 **시작 시각 전에 색인된 줄을 지운다**(사라진 원본 정리). 멱등. 진행 표시가 `skeleton.search.rebuild.stale-after`(기본 15분) 동안 없으면 멈춘 것으로 보고 새 재색인을 받는다.

**탈퇴**: `AccountErasureListener` 가 그 계정이 주인(`ownerId`)인 문서를 지운다. 주인 없는 문서는 그대로다.

**바깥 엔진으로 바꿀 때**: `SearchEngine` 이 두 번째 구현 자리다(`search-meilisearch` 같은 어댑터를 따로 만든다). `upsert/delete` 를 아웃박스 줄로 받아 일꾼이 보내면 부르는 쪽 코드는 그대로다. `SearchText`(질의 쪼개기 · 하이라이트)는 엔진과 무관해 그대로 쓴다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:search"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | Spring Security (경로를 등록할 때만 필요하다) |
| 설정 접두사 | `skeleton.search` — [docs/config/modules/search.yml](../config/modules/search.yml) |
| 기본 동작 | 켜짐. 검색 경로 켜짐(비로그인 읽기는 꺼짐 - 앱이 `allow-anonymous-read` 로 연다), 운영자 경로 꺼짐. 서비스 빈은 엔진이 있어야 선다. |
| 부팅에 필요한 것 | 엔진 포트 `SearchEngine` — `search-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `SearchEngine`, `SearchRebuilds`, `SearchReader`, `SearchAdmin`, `SearchController`, `SearchAdminController`, 공개 경로 `searchPublicEndpointContributor` |
| 마이그레이션 | 없음 (스키마는 `search-jdbc`) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/search/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
