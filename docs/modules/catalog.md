# catalog

상품 · 가격 카탈로그 — 운영자가 **화면에서** 상품과 가격(월 · 연 · 단건 · 행사가 · 통화별)을 만들고, 랜딩 가격표 · 구독 화면 · 결제가 방문 나라에 맞는 통화의 그 표를 읽는다.
`catalog` 는 `payment` 의 계약 셋(`SubscriptionPlans` · `PaymentCatalog` · `ProviderPriceRefs`)을 **구현하는 모듈**이다 - `payment` 는 이 모듈을 모른다. 앱이 같은 타입의 빈을 두면 앱 것이 이긴다(코드로 플랜을 정하는 지금 방식은 그대로 쓸 수 있다). 저장은 `catalog-jdbc` 가 한다(메모리 구현 없음).

**표**: 상품(`catalog_products` — `kind` SUBSCRIPTION | ONE_TIME 은 못 바꾼다 · 구독 상품만 `level`(등급, 상품끼리 겹치지 않는다) · 인기 · 판매 중 · 정렬) ·
이름 · 설명(언어별 줄, 언어는 앱 것) · 가격(`catalog_prices`) · 결제사 가격 id 링크. 가격 코드가 **구독 줄의 `plan_code` · 단건의 `itemRef` · 결제사 링크의 열쇠**다(비우면 `<상품>-<주기>-<통화>` 로 만들어 준다).
금액은 통화 최소 단위(KRW 원 · USD 센트).

**가격은 고치지 않는다**(Stripe price 와 같다): 금액 · 통화 · 주기 · 정가는 만든 뒤 못 바꾼다. 바꾸려면 새 가격 + 옛 가격 판매 중지(되돌리지 않는다). 고칠 수 있는 것은 행사의 끝 시각(`PATCH`)과 결제사 링크뿐이다. 구독 줄은 이미 금액 · 주기를 박고 있으니 기존 구독자는 옛 가격 그대로이고
가격이 중지돼도 갱신 · 이름 · 해지 계산이 그대로다(`SubscriptionPlans.find` 는 판매가 끝난 가격도 돌려주되 `purchasable=false`).

**지금 살 수 있는 가격**: 열쇠 = (상품, 주기, 통화) - 열쇠마다 하나. 현재 가격 = 판매 중이고 지금이 판매 기간 안인 가격 가운데 **기간이 있는 것(행사)이 먼저**, 없으면 상시. 기간은 `[시작, 끝)` 열린 구간이다(시작 정각에 행사가, 끝 정각에 상시).
상시는 하나, 행사는 기간이 겹치지 않게(상품 줄을 잠그고 검사 → `409 CATALOG.PRICE_OVERLAP`, 동시에 둘을 만들어도 하나만). **현재 가격만 산다** - 행사 중에는 상시(정가) 가격으로 사는 길이 없다(`422 ITEM_NOT_PURCHASABLE`). 정가(`compareAtAmount`)와 기간을 단 새 가격 줄이 행사다 - 기간(`saleStartsAt` · `saleEndsAt`)이 있으면 정가가 필수이고 상시 가격에는 정가를 줄 수 없다(`PRICE_INVALID` + `errors[].field = compareAtAmount`). 끝만 주면 시작은 만드는 지금이다.
쿠폰은 행사가 위에 얹힌다(최소 금액 · 정률은 행사가 기준) - 쿠폰 범위는 가격 코드 또는 **상품 코드**로 걸 수 있어 가격을 바꿔도 쿠폰이 깨지지 않는다.

**결제 계약 구현**:
- `CatalogSubscriptionPlans`(`SubscriptionPlans`): `list()` = 판매 중 구독 상품의 현재 가격들(모든 통화 - 요청 통화로 거르는 것은 결제 쪽, `GET /payments/subscriptions/plans`), 플랜 한 줄 = 가격 하나, 플랜 코드 = 가격 코드. 플랜 이름은 요청 언어(`Accept-Language`, 플랜 목록 · 내 구독 · 해지 · 변경 미리보기 응답) → `skeleton.catalog.default-locale` → 첫 언어 순으로 고른다(`SubscriptionPlan.names` 에 상품의 모든 언어 이름이 실린다). 갱신 알림처럼 **서버가 만드는 이름은 받는 사람의 언어를 모르니 기본 언어 이름 그대로**다.
- `CatalogPaymentCatalog`(`PaymentCatalog`, 공개 클래스): `itemType` = `skeleton.catalog.item-type`(기본 `product`), `itemRef` = 단건 가격 코드, 수량 1, 금액 = 현재 가격, 결제창 이름 = 기본 언어 상품 이름. `PaymentCatalog` 빈은 하나뿐이라 앱이 코드 상품(응원하기)을 따로 두면 자기 빈에서 `itemType` 으로 갈라 이 클래스를 부른다.
- `CatalogProviderPriceRefs`(`ProviderPriceRefs`): 운영자가 Paddle 대시보드에서 만든 `pri_…` 를 **그 통화의 가격 줄**에 붙인다. Paddle 모듈이 이 링크를 먼저 보고 없으면 자기 설정 지도를 본다. 링크 없는 가격을 Paddle 로 사면 `503 UNAVAILABLE`.
  링크를 결제창이 열린 동안 바꾸면 그 결제는 품목 대조에서 「확인 필요」로 간다.

**통화**: 화면에 보이는 상품 · 가격의 원본은 우리 DB 이고 결제사는 뒤에서 붙는다. 이 요청의 통화는 결제의 나라 라우팅이 정한다(`PaymentCountryRouting.currencyOf` - `payment.routing.provider-currencies` · `default-currency`, 예: KR → 토스 → KRW, 그 밖 → Paddle → USD). 공개 읽기는 그 통화의 현재 가격만 낸다 -
통화를 모르거나 그 통화 가격이 하나도 없으면 상품마다 사전순 첫 통화다(`/payments/subscriptions/plans` 도 같은 규칙). 브라우저가 통화를 고르는 길은 없다.

**공개 읽기**(`skeleton.catalog.http.enabled=true` 일 때만, 로그인 없이, 꺼져 있으면 404): `GET /api/v1/catalog/products?kind=SUBSCRIPTION|ONE_TIME` →
`{ currency|null, products: [{ code, kind, level|null, highlighted, sortOrder, texts: { <locale>: { name, description|null } }, prices: [{ code, period|null, amount, currency, compareAtAmount|null, saleEndsAt|null }] }] }`.
통화가 나라마다 달라지므로 `Cache-Control: private, max-age=60` + `Vary: CF-IPCountry`(로컬 나라 스위치가 켜져 있으면 `X-Dev-Country` 도).

**운영자 경로**(`skeleton.catalog.admin.enabled=true` 일 때만, 꺼져 있으면 404) - 모든 호출에 `catalog.manage`(기본 역할 없음 - 전권 역할만, 익명 401 · 권한 없음 403), 모든 쓰기가 `OperatorAuditLog` 에 한 줄:

| 메서드 · 경로 | 하는 일 |
|---|---|
| `GET /api/v1/admin/catalog/products` | 상품 + 가격 전부(모든 통화 · 중지된 것 · 지난 가격, 최신 먼저). `Price.current` = 지금 살 수 있다 |
| `POST /api/v1/admin/catalog/products` `{code, kind, level?, highlighted?, sortOrder?, texts}` | 상품 만들기 → 201 |
| `PUT /api/v1/admin/catalog/products/{code}` `{level?, highlighted?, selling?, sortOrder?, texts?}` | 고치기(코드 · 종류는 못 바꾼다) |
| `POST /api/v1/admin/catalog/products/{code}/prices` `{code?, period?, amount, currency, compareAtAmount?, saleStartsAt?, saleEndsAt?, replaces?}` | 가격 추가 → 201. `replaces`(같은 상품의 옛 가격 코드)를 주면 같은 잠금 안에서 옛 가격 판매 중지 + 새 가격 추가가 한 번에(가격 교체). 행사가는 상시 가격을 대신할 수 없다(행사가 끝나면 현재 가격이 없어진다 - `PRICE_INVALID`, field `replaces`) |
| `POST /api/v1/admin/catalog/prices/{code}/stop` | 판매 중지(되돌리지 않는다, 두 번 눌러도 같다) |
| `PATCH /api/v1/admin/catalog/prices/{code}` `{saleEndsAt: <시각 \| null>}` | 행사 끝 시각 바꾸기(키가 없으면 400) |
| `PUT` · `DELETE /api/v1/admin/catalog/prices/{code}/provider-refs/{provider}` `{externalId}` | 결제사 가격 id 링크(`PUT` 은 `GET …/providers` 에 있는 결제사만) |
| `GET /api/v1/admin/catalog/providers` | 가격 id 를 쓰는 켜진 결제사(링크 칸을 그릴지) |

오류: `409 CATALOG.CODE_TAKEN`(상품 · 가격 코드, 이미 다른 가격에 걸린 결제사 가격 id) · `409 LEVEL_TAKEN` · `409 PRICE_OVERLAP` · `400 PRICE_INVALID`(금액 0 이하 · 통화 · 주기 · 정가 ≤ 금액 · 시작 ≥ 끝 · 이미 지난 끝 · 중지된 가격을 고침) · `404 NOT_FOUND`. 입력이 틀리면 `errors[{field, code, message}]` 로 어느 칸인지 알린다(`field` 는 요청 본문 이름, 가격은 `CATALOG.PRICE_INVALID`, 상품 쪽(코드 모양 · 종류 · 등급 · 언어 이름)은 `400 COMMON.VALIDATION_FAILED`).

**검색 연동**: 검색(`search-jdbc`)을 붙인 앱이면 상품을 만들 때 · 고칠 때(이름 · 설명 · 판매 여부) `product` 문서가 같은 트랜잭션 안에서 색인에 들어간다 - 제목은 `skeleton.catalog.default-locale` 의 이름(없으면 첫 언어), 본문은 모든 언어의 이름 · 설명, 범위(`scope`)는 상품 종류(`SUBSCRIPTION` · `ONE_TIME`), 문서 id 는 상품 코드, 주인은 없다. 판매 중지(`selling=false`)가 되면 문서를 지우고 다시 켜면 다시 넣는다(가격 변경은 문서에 영향이 없다). `catalog` 는 `search` 에 의존하지 않는다 - platform 의 `SearchIndexer` 를 `ObjectProvider` 로 부르므로 검색을 안 붙인 앱은 동작이 그대로다. 검색을 나중에 붙인 앱은 `POST /api/v1/admin/search/rebuild {"type":"product"}`(운영 경로를 켠 앱) 로 처음 채운다 - `SearchSource("product")` 빈(`CatalogSearchSource`)이 판매 중인 상품만 코드 순으로 내놓는다.

**코드 플랜에서 옮기는 법**: 옛 `SubscriptionPlans` 빈의 월 가격 코드를 그대로 가격 코드로 만든다(예: `basic` · `standard` · `pro`) - 기존 구독 줄의 `plan_code` · 쿠폰 범위가 그대로 맞는다. 연 가격은 새 코드(`basic-yearly`), 달러 가격은 `basic-usd` 처럼 통화별 줄을 더하고, 옛 플랜 빈은 지운다.
단건(응원하기 같은 코드 상품)은 앱이 `PaymentCatalog` 빈을 두고 `itemType` 이 표 상품이면 `CatalogPaymentCatalog` 를 부른다.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:catalog"))` |
| 함께 오는 모듈 | `payment`, `platform`, `crypto` |
| 컴파일 전용 | 없음 (Spring Security 는 경로를 등록할 때만 필요하다) |
| 설정 접두사 | `skeleton.catalog` — [docs/config/modules/catalog.yml](../config/modules/catalog.yml) |
| 기본 동작 | 켜짐. 공개 읽기 꺼짐, 운영자 경로 꺼짐. 결제 계약 구현은 저장소가 있으면 빈으로 서고 앱이 같은 타입의 빈을 두면 앱 것이 이긴다. |
| 부팅에 필요한 것 | 저장소 포트 `CatalogStore` — `catalog-jdbc` 가 낸다. 없으면 서비스 빈이 켜지지 않는다. |
| 교체 지점 | `CatalogStore`, `CatalogAdmin`, `CatalogReader`, `CatalogSearchSource`, 결제 계약 `SubscriptionPlans` · `PaymentCatalog` · `ProviderPriceRefs`, `CatalogController`, `CatalogAdminController` |
| 마이그레이션 | 없음 (스키마는 `catalog-jdbc`) |
| 프론트 짝 | `@skeleton/payment` |
| 테스트 | `modules/catalog/src/test` |

자세히: [payment](payment.md) · `coupon` 모듈 문서(붙였을 때)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
