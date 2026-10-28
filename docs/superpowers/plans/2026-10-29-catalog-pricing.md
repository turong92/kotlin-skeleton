# 상품 · 가격 카탈로그 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 구현 · 리뷰는 역할(`developer` · `code-reviewer`)로 부른다.

**Goal:** 찍은 앱이 `catalog` · `catalog-jdbc` 두 줄로 운영자 화면에서 상품 · 가격(월 · 연 · 단건 · 행사가 · 통화별)을 관리하고, 랜딩 가격표 · 구독 화면 · 결제가 방문 나라에 맞는 통화의 그 표를 읽는다(1차). 2차는 갈아타기(주기↑ 바로 · 내리기 예약).

**Architecture:** `catalog` 는 `payment` 의 계약(`SubscriptionPlans` · `PaymentCatalog` · 새 `ProviderPriceRefs`)을 구현하는 모듈이다 — `payment` 는 `catalog` 를 모른다. 가격 줄은 고치지 않고(새 줄 + 판매 중지), 가격 코드가 구독 줄의 `plan_code` · 단건 `itemRef` · 결제사 링크의 열쇠다. 「지금 살 수 있는 가격」은 (상품, 주기, 통화)마다 하나(행사 우선). 보이는 상품 · 가격의 원본은 DB 이고 결제사는 뒤에서 붙는다: 요청 통화는 나라 → 결제사를 정하는 `PaymentCountryRouting` 이 같이 정하고(KR → KRW · 토스, 그 밖 → USD · Paddle), Paddle 가격 id 는 그 통화 가격 줄에 붙인 링크 → 없으면 지금 yml 지도. Paddle 이 확정한 금액은 그대로 기록하고 DB 금액과 다르면 경고만.

**Tech Stack:** Kotlin · Spring Boot · JdbcClient · Flyway(PostgreSQL · MySQL) · Testcontainers / React · TypeScript · TanStack Query · Storybook · Vitest · Playwright.

**Spec:** `docs/superpowers/specs/2026-10-29-catalog-pricing-design.md`(형 결정 2026-10-12 · 2026-10-29 로 확정). 계획과 스펙이 어긋나면 스펙이 이긴다 — 어긋남은 지휘에 알린다. 작업 키는 스펙 §10 표와 같다.

## Global Constraints

- 레포 · 기준: kotlin `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton`(main `a1da77ca`), react `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton`(main `891f7ac`). 통합 브랜치 `catalog`(지휘가 main 에서 만든다). 조각은 그 레포 안 `.claude/worktrees/cat-<키>`(브랜치 `catalog/<키>`, 시작점 `catalog`). **커밋은 조각 브랜치에만, 합치기 · push 는 지휘.** 커밋 메시지에 `Co-Authored-By` 를 붙이지 않는다.
- 시험은 **바뀐 범위만**(각 작업의 명령). 전체 스위트 · `kverify` · react 전체(`pnpm test` · `typecheck` · `lint` · `test:stories`) · e2e 는 지휘가 묶음 끝에 한 번. 같은 워크트리에서 Gradle 둘을 동시에 돌리지 않는다.
- 모듈 규칙: 빈은 AutoConfiguration `@Bean` + `@ConditionalOnMissingBean`, `@Component` 금지, 컨트롤러는 자동 설정이 등록. 새 모듈을 만드는 조각이 `settings.gradle.kts` · `docs/modules/<m>.md` · `docs/config/modules/<m>.yml` · `docs/modules/README.md` 행 · `capabilities.json` 항목 · workbench 의존 한 줄을 같이 더한다. 모듈 간 시험 의존 금지.
- 마이그레이션: `./gradlew newMigration -Pname=…`, 두 방언 짝, `perl scripts/migrations-lock.pl`. 잠긴 파일은 고치지 않는다. `payment-jdbc` 는 JSON 열 · 부분 인덱스 없음 — `catalog-jdbc` 도 같은 규칙(이름은 자식 표).
- 시각은 `Instant`(`SqlDialect.instantParam` / `readInstant`), 「지금」은 `TimeProvider`. `ZoneId.systemDefault()` 금지. 프로젝트 모드는 기존 스키마 갈래를 그대로 탄다.
- 스켈레톤 중립: 공개 읽기 `skeleton.catalog.http.enabled=false` · 운영 `skeleton.catalog.admin.enabled=false`(→ 404), 견본 앱 yml 에서 켬. `catalog` 를 안 붙인 앱의 결제 동작은 그대로(새 칸은 모두 기본값).
- 새 권한 `catalog.manage`(기본 역할 없음 — 전권만). 오류 코드 `CATALOG.CODE_TAKEN` · `CATALOG.LEVEL_TAKEN` · `CATALOG.PRICE_OVERLAP` · `CATALOG.PRICE_INVALID` · `CATALOG.NOT_FOUND`.
- 공개 레포 — 특정 서비스 · 도메인 · 이메일 흔적 금지(`example.com`). KDoc 안에 `/**` 금지.
- react: 날 요소 · 날값 금지(`@skeleton/ui` · 의미 토큰), 패키지는 `import.meta.env` 를 읽지 않고 서로를 import 하지 않는다(`@skeleton/payment` → `@skeleton/marketing` 변환은 앱), 문구는 `labels` prop(ko/en), SSR 안전, 눌리는 요소는 테두리 · 채움.

## Review Focus

- **금액 출처**: 구독 시작 · 단건 주문 · 플랜 변경이 「지금 살 수 있는 가격」의 금액만 쓴다. 판매 중지 · 기간 지난 · 행사 중의 상시 가격 코드 → 422. 브라우저 금액은 어디서도 읽지 않는다(K1 · K2).
- **기존 구독자 불변**: 가격 중지 · 행사 끝 · 상품 판매 중지 · 등급 변경 뒤에도 그 가격의 구독은 갱신 금액 · 주기 · 이름 · 해지 계산이 그대로(K2).
- **행사 경계**: `sale_starts_at` 정각에 행사가, `sale_ends_at` 정각에 상시(끝은 열린 구간). 겹치는 행사 · 상시 둘 → `PRICE_OVERLAP`, 동시에 둘을 만들어도 하나만(상품 줄 잠금)(K2).
- **통화 라우팅**: KR 요청은 KRW 가격만, 그 밖은 USD 가격만 보이고 팔리며, 다른 통화 가격 코드를 보내도 결제사가 받지 않는 통화면 `ROUTING_FAILED`. 그 통화 가격이 없는 앱(코드 플랜)은 지금처럼 다 보인다. 브라우저가 통화를 고르는 길이 없다(K1 · K2).
- **Paddle**: 링크가 yml 보다 앞서고, 링크 없는 가격은 503 이며, 웹훅의 품목 대조가 링크로 통과한다. 같은 통화의 세전 금액이 다르면 경고 한 줄 · 지급은 그대로, 통화가 다르면 경고 없음(K1).
- **쿠폰 범위**: 상품 코드로 만든 범위가 그 상품의 모든 가격(월 · 연 · 행사)에 맞고, 가격 코드 범위도 지금처럼 맞는다(K1).

---

## 조각 사이 계약 (react 는 이것으로 가짜 API 를 먼저 만든다 — 바뀌면 kotlin 조각이 보고에 적고 지휘가 넘긴다)

### C-CAT 공개 읽기 (K2 가 연다, R1 이 쓴다)
```
GET /api/v1/catalog/products?kind=SUBSCRIPTION|ONE_TIME   (로그인 없이, Cache-Control: private, max-age=60 + Vary: CF-IPCountry[, X-Dev-Country], 꺼져 있으면 404)
→ value: { currency: "KRW"|"USD"|null,                       // 이 요청 통화(라우팅) - null 이면 상품마다 기본 통화 → 사전순 첫 통화
           products: [{ code, kind, level|null, highlighted, sortOrder,
             texts: { "<locale>": { name, description|null } },
             prices: [{ code, period|null, amount, currency, compareAtAmount|null, saleEndsAt|null }] }] }   // 판매 중 상품 · 그 통화의 현재 가격만, sortOrder 순
금액은 통화 최소 단위(KRW 원 · USD 센트)
```

### C-CADM 운영 (K2 가 연다, R2 가 쓴다 — 권한 catalog.manage, 꺼져 있으면 404 · 권한 없으면 403)
```
Product = { code, kind, level|null, highlighted, selling, sortOrder, texts, createdAt, updatedAt, prices: [Price] }   // 지난 가격까지, 최신 먼저
Price   = { code, period|null, amount, currency, compareAtAmount|null, saleStartsAt|null, saleEndsAt|null,
            selling, current: bool, stoppedAt|null, providerRefs: { "<provider>": "<externalId>" } }
GET    /api/v1/admin/catalog/products                         → values: [Product]
POST   /api/v1/admin/catalog/products { code, kind, level?, highlighted?, sortOrder?, texts } → 201 value: Product
PUT    /api/v1/admin/catalog/products/{code} { level?, highlighted?, selling?, sortOrder?, texts? } → value: Product   (kind 는 못 바꾼다)
POST   /api/v1/admin/catalog/products/{code}/prices { code?, period?, amount, currency, compareAtAmount?, saleStartsAt?, saleEndsAt? } → 201 value: Price
POST   /api/v1/admin/catalog/prices/{code}/stop              → value: Price
PATCH  /api/v1/admin/catalog/prices/{code} { saleEndsAt|null } → value: Price
PUT    /api/v1/admin/catalog/prices/{code}/provider-refs/{provider} { externalId } → value: Price
DELETE /api/v1/admin/catalog/prices/{code}/provider-refs/{provider} → value: Price
GET    /api/v1/admin/catalog/providers                        → values: ["paddle"]   // 가격 id 를 쓰는 켜진 결제사 - 링크 칸을 그릴지
오류: 409 CATALOG.CODE_TAKEN · 409 CATALOG.LEVEL_TAKEN · 409 CATALOG.PRICE_OVERLAP · 400 CATALOG.PRICE_INVALID · 404 CATALOG.NOT_FOUND
```

### C-PAY 결제 (K1 이 연다, R1 이 쓴다 — 칸을 **더하기만**)
```
GET /api/v1/payments/config             → + currency|null   (이 요청 통화)
GET /api/v1/payments/subscriptions/plans → 이 요청 통화의 플랜만(그 통화 플랜이 없으면 전부), plans[]: + productCode, regularAmount|null
GET /api/v1/payments/subscriptions/me    → plan 이름이 지난 가격이어도 나온다
구독 시작 · 단건 주문: 판매 중이 아닌 가격 코드 → 422 PAYMENT.ITEM_NOT_PURCHASABLE, 결제사가 받지 않는 통화 → 400 PAYMENT.ROUTING_FAILED(지금 그대로), Paddle 링크 없는 가격 → 503 PAYMENT.UNAVAILABLE
```

### C-CHG 2차 갈아타기 (K4 가 연다, R3 가 쓴다)
```
GET  …/subscriptions/{id}/change-preview?planCode → + timing: "NOW"|"NEXT_RENEWAL", effectiveAt
POST …/subscriptions/{id}/change { planCode, expectedCharge }  → 예약이면 200 + subscription.scheduledChange (expectedCharge 는 0)
DELETE …/subscriptions/{id}/scheduled-change → value: Subscription
구독 응답: + scheduledChange: { planCode, planName, amount, period, effectiveAt } | null
```

## 같이 만지는 파일과 주인

| 파일 | 주인 | 다른 조각 |
|---|---|---|
| `settings.gradle.kts` · `apps/workbench/build.gradle.kts` · `docs/modules/README.md` · `capabilities.json` | K2 | 지휘가 합친 뒤 `perl scripts/build-capabilities.pl` |
| `migrations.lock` | K2 · K4 | 지휘가 합칠 때 다시 잠금 |
| `modules/payment/**` · `modules/payment-paddle/**` · `modules/platform/**/discount` · `modules/coupon/**` | K1, 2차에 K4 | K2 는 읽기만 |
| `apps/sample/**` · CHANGELOG `[Unreleased]` | K3 · (react) R1 · R2 | R1 은 랜딩 · 구독 화면, R2 는 운영 라우트 · 메뉴 |
| react `packages/payment/src/index.ts` · `labels.*.ts` | R1, 그다음 R2(R1 위에서) | 자기 줄만 |
| react `packages/api-client/src/errorCodes.ts` | R2(`CATALOG.*`) | — |

---

## 1차 — 묶음 1 (서로 기다리지 않는다: K1 ∥ R1, R2 는 R1 첫 커밋 위)

### Task K1: `payment` · `platform` · `coupon` · `payment-paddle` 접점 (kotlin, 중~대)

**Files:**
- Modify `modules/payment/src/main/kotlin/dev/sumin/skeleton/payment/PaymentAppContracts.kt` — `SubscriptionPlan` 칸 셋, `PurchaseQuote.productCode`, 새 `ProviderPriceRefs`; KDoc: `find` 는 판매 중이 아닌 플랜도 돌려줄 수 있다
- Modify `PaymentSubscriptions.kt`(시작: `purchasable` 아니면 `PaymentItemNotPurchasableException`), `SubscriptionPlanChanges.kt`(대상 플랜 같은 검사), `SubscriptionCancellations.kt`(`monthlyPrice` = `regularAmount ?: amount`), `PaymentDiscounts.kt`(`targetFor` 에 `productCode`, `subscriptionTarget` 에 `plan.productCode`), `PaymentOrders.kt`(quote 의 `productCode` 를 할인 대상에), 자동 설정(`ProviderPriceRefs` 는 `ObjectProvider` → 없으면 `NONE`)
- Modify `PaymentProperties.kt` `Routing` — `providerCurrencies: Map<String, String>`(결제사 id → 통화, 검사: 세 글자 대문자) · `defaultCurrency: String?`; `web/PaymentCountryRouting.kt` — `currencyOf(route): String?`(스펙 §4.6 순서), `RequestRoute.currency`
- Modify `web/PaymentController.kt`(`/config` `+ currency`), `web/PaymentSubscriptionController.kt`(플랜 목록을 요청 통화로 거름 — 그 통화 플랜이 없으면 전부) · `web/PaymentResponses.kt` — 플랜 응답 두 칸, 내 구독 · 해지 응답의 이름은 `plans.find`
- Modify `PaymentPushedEvents.kt` — 단건 · 구독 첫 결제 반영 때 같은 통화인데 `total − tax` ≠ 우리 금액(주문의 원래 `amount` · 구독의 박힌 `amount`)이면 `WARN payment.price-drift order=… item=… expected=… paid=…`(지급 · 기록은 그대로)
- Modify `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/discount/DiscountContracts.kt` — `DiscountTarget.productCode: String? = null`(마지막 칸)
- Modify `modules/coupon/src/main/kotlin/dev/sumin/skeleton/coupon/CouponDiscountSource.kt` `applies` — `itemRefs` · `planCodes` 가 `productCode` 에도 맞는다
- Modify `modules/payment-paddle/…/PaddlePaymentProvider.kt` · `PaddlePaymentAutoConfiguration.kt` — `priceIdOf` = `refs.priceIdOf(providerId, itemRef) ?: properties.prices[itemRef]`, `pricePreview().prices` = yml + 링크(링크 우선), `createTransaction` 의 가격 없음 → `PaymentUnavailableException`
- Docs: `docs/modules/payment.md`(플랜 칸 · `ProviderPriceRefs`), `docs/modules/payment-paddle.md`(가격 id 찾는 순서 · 링크 바꾸기 주의), `docs/modules/coupon.md`(범위 = 가격 코드 또는 상품 코드), `docs/payment-http-contract.md`(C-PAY)

**Interfaces — Produces:**
```kotlin
data class SubscriptionPlan(
    val code: String, val name: String, val amount: PaymentAmount, val period: String, val level: Int,
    /** 같은 상품의 가격들이 같은 값 - 쿠폰 범위 · 화면 묶음. 코드 플랜은 코드 그대로 */
    val productCode: String = code,
    /** 지금 새로 살 수 있는가 - 판매 중지 · 기간 지난 가격은 false(기존 구독자가 찾을 때만 나온다) */
    val purchasable: Boolean = true,
    /** 정가(행사가의 비교가) - 연간 해지의 쓴 몫 · 화면 취소선. 없으면 amount 가 정가 */
    val regularAmount: Long? = null,
)
data class PurchaseQuote(val amount: PaymentAmount, val orderName: String, val productCode: String? = null)
interface ProviderPriceRefs {
    fun priceIdOf(provider: String, itemRef: String): String?
    fun all(provider: String): Map<String, String>
    companion object { val NONE: ProviderPriceRefs }
}
```
```kotlin
// PaymentCountryRouting
fun currencyOf(route: RequestRoute): String?   // provider-currencies[provider] → 결제사의 유일한 실효 통화 → routing.default-currency → null
```
**K1 첫 커밋은 위 계약만**(구현 없이 컴파일되는 상태) — K2 는 그 커밋에서 시작할 수 있다(지휘가 `catalog` 에 먼저 합친다).

- [ ] **실패 시험**
  - `modules/payment/src/test/…/SubscriptionPlanPurchasableTest`: `purchasable=false` 플랜으로 시작 → `ITEM_NOT_PURCHASABLE`, 결제사 호출 0; 그 플랜의 기존 구독 갱신 · 내 구독 이름은 그대로; 플랜 변경 대상이 `purchasable=false` → 422.
  - `…/CancelMonthlyRegularPriceTest`(기존 해지 시험 꼴): 연간 구독, 같은 등급 월 플랜이 `amount 2900 · regularAmount 3900` → 쓴 몫이 3,900 × 시작한 개월.
  - `…/web/PaymentHttpVariantsTest` 또는 구독 컨트롤러 시험에 한 건: 플랜 응답에 `productCode` · `regularAmount`.
  - `modules/coupon/src/test/…/CouponDiscountSourceTest`: 범위 `planCodes=[basic]` 이 `planCode=basic-yearly · productCode=basic` 에 맞고, `itemRefs=[sticker]` 가 `itemRef=sticker-2026 · productCode=sticker` 에 맞으며, 다른 상품엔 안 맞는다. 기존 시험 불변.
  - `…/web/PaymentCountryRoutingTest`(기존 꼴): `provider-currencies.paddle=USD` 면 US → USD, KR → 토스 → KRW(통화 하나), 통화 여럿 · 설정 없음 → `default-currency` → null, 결제사를 못 정함 → `default-currency`; `/config` 의 `currency`; 플랜 목록이 KRW · USD 섞인 대역에서 나라마다 한 통화만, 한 통화뿐인 대역은 전부.
  - `…/PushedPriceDriftTest`(기존 Paddle 반영 시험 꼴): 같은 통화 · 세전 금액 다름 → 경고 한 줄(로그 잡기) · 주문 `PAID` · 지급 작업 그대로; 같으면 경고 없음; 통화 다름 → 경고 없음; 갱신 결제는 비교하지 않음.
  - `modules/payment-paddle/src/test/…/PaddleGatewayTest`: 링크 `basic → pri_db` 와 yml `basic → pri_yml` → `pri_db`; 링크 없고 yml 만 → yml; 둘 다 없음 → 거래 만들기 `PaymentUnavailableException`; `pricePreview().prices` 가 둘을 합친다(링크 우선).
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:payment:test --tests '*SubscriptionPlanPurchasable*' --tests '*CancelMonthlyRegularPrice*' --tests '*PaymentHttpVariantsTest' --tests '*Subscription*' --tests '*OrderDiscountTest' --tests '*CountryRouting*' --tests '*PushedPriceDrift*' --tests '*PushedEvents*'
./gradlew :modules:coupon:test --tests '*CouponDiscountSourceTest'
./gradlew :modules:payment-paddle:test
./gradlew :modules:platform:test --tests '*ModuleDocumentationTest'
```
- [ ] 커밋 ① `feat(payment): plan product code, purchasable flag, regular amount, provider price refs and request currency contract` ② `feat(payment): catalog-ready plans - request currency from routing, purchasable check, regular price for used share, coupon scope by product, Paddle price refs first, price drift warning`.

### Task R1: 카탈로그 읽기 · 가격표 · 구독 화면 (react, 중) — C-CAT · C-PAY 만

**Files:**
- Create `packages/payment/src/catalogTypes.ts` · `catalogApi.ts`(`createCatalogApi({ client, basePath })` — `products(kind?)` → `{ currency, products }`) · `money.ts`(최소 단위 ↔ 기본 단위, `Intl.NumberFormat().resolvedOptions().maximumFractionDigits` 로 통화 자릿수) · `catalogQueries.ts`(`paymentKeys.catalogProducts(kind)`, `useCatalogProducts`) + 각 `.test.ts`
- Modify `packages/payment/src/subscriptionTypes.ts`(`SubscriptionPlan.productCode?` · `regularAmount?`), `SubscriptionPlanList.tsx`(+ `.module.css` · `.stories.tsx` · 시험): 같은 `productCode` 를 한 카드로 묶고, 그 상품에 둘 이상의 주기가 있으면 주기 토글, `regularAmount > amount` 면 취소선. 금액은 서버가 준 플랜 금액이다(Paddle 경로도 — 미리보기 금액을 쓰지 않는다). `productCode` 가 없으면(옛 서버) 지금처럼 플랜당 한 카드. `index.ts` 내보내기, `labels.ko/en`(주기 토글 · 「○월 ○일까지」)
- Modify `packages/marketing/src/pricing/pricing.ts` · `PricingTable.tsx`(+ 시험 · 스토리): `price: { monthly: number | null; yearly: number | null } | null`, `compareAt?: { monthly?: number; yearly?: number }`, `saleEndsAt?: { monthly?: string; yearly?: string }`; 토글은 연 가격이 있는 요금제가 하나라도 있을 때만, `savingsPercent` 는 둘 다 있는 요금제만, 그 주기 가격이 없는 요금제는 「이 주기 없음」 대신 카드를 흐리게 하지 않고 숨긴다(내 판단 — 화면 문구가 덜 든다), 취소선 + 기간 라벨(`labels.saleUntil(date)`)
- Modify `apps/sample/src/landing/pricingData.ts` → `catalogPricing.ts`(카탈로그 → `PricingPlan[]`, 앞에 사전의 무료 카드, 기능 목록은 사전 `landing.pricing.features.<productCode>`) + 시험, `routes/LandingPage.tsx`(훅 · 읽는 중 · 실패 = 가격표 자리 숨김), `routes/SubscribePage.tsx`(묶음 목록 · 이름은 서버 이름 — 코드별 번역 사전 줄은 지운다 · `usePaddleAmounts` 를 떼고 DB 금액, Paddle 안내 문구 `payment.policy.paddle.*` 는 그대로), `i18n/ko.ts` · `en.ts`. `CheerPage` 의 Paddle 미리보기는 그대로(코드 상품).
- 통화: 랜딩 · 구독 화면 모두 응답의 `currency`(서버가 나라로 정함), 서식만 화면 언어의 로케일. 언어로 통화를 고르던 `pricingFor` 는 지운다.

- [ ] **실패 시험**: `catalogApi.test.ts`(경로 · `kind` 쿼리 · 응답 펼치기), `pricing.test.ts`(연 없음 → 토글 없음, 절약 % 는 둘 다 있는 것만, 행사 취소선 값), `PricingTable.test.tsx`(토글 숨김 · 취소선 · 기간 라벨), `subscriptionUi.test.tsx`(묶음 · 토글 · 옛 서버 모양), `money.test.ts`(KRW 0자리 · USD 2자리), `apps/sample/src/landing/catalogPricing.test.ts`(무료 카드 앞 · 인기 · 연 총액 · USD 센트 → 달러 · 통화는 응답 것), `SubscribePage.test.tsx` 갱신.
- [ ] 통과:
```bash
pnpm --filter @skeleton/payment test -- catalog money subscriptionUi SubscriptionPlanList
pnpm --filter @skeleton/marketing test -- pricing PricingTable
pnpm --filter sample test -- catalogPricing SubscribePage LandingPage
pnpm --filter @skeleton/payment --filter @skeleton/marketing --filter sample typecheck
```
- [ ] 커밋 ① `feat(payment): catalog read API and hooks` ② `feat(marketing): pricing table with optional intervals and sale prices` ③ `feat(sample): landing and subscribe page read the catalog`.

### Task R2: 운영 카탈로그 화면 (react, 중~대) — C-CADM 만, R1 커밋 ① 위에서

**Files:**
- Create `packages/payment/src/admin/catalogAdminTypes.ts` · `catalogAdminApi.ts` · `catalogAdminQueries.ts`(목록 · 만들기 · 고치기 · 가격 추가 · 중지 · 끝 시각 · 링크 · 결제사 목록, 성공 시 목록 무효화) + 시험
- Create `AdminCatalogTable.tsx`(상품 · 종류 · 등급 · 판매 중 · 현재 가격 요약), `AdminProductEditor.tsx`(코드 · 종류는 만들 때만, 등급 · 인기 · 판매 중 · 정렬 · 언어별 이름/설명 — 언어 목록은 prop), `AdminPriceList.tsx`(통화별로 묶어 현재 · 예정 · 지난 가격, 결제사 링크 칸은 `providers` 가 있을 때만), `AddPriceDialog.tsx`(주기 고르기는 구독 상품만 — `P1M` · `P1Y` + 직접 입력, 금액(통화 자릿수로 입력 → 최소 단위로 보냄), 통화(KRW · USD + 직접 입력), 「행사가」 켜면 정가 · 시작 · 끝), `StopPriceDialog.tsx`(확인: 「기존 구독자는 이 가격 그대로」 문구) — 각 `.module.css` · `.stories.tsx` · 시험, `admin/index.ts`, `labels.admin.ko/en`
- Modify `packages/api-client/src/errorCodes.ts`(`CATALOG.*` 다섯), 오류 문장
- Create `apps/sample/src/routes/AdminCatalogPage.tsx` · `AdminCatalogProductPage.tsx`, 라우트 · 운영 메뉴 「상품 · 가격」(`catalog.manage` 없으면 메뉴 숨김 — 기존 운영 메뉴 꼴)
- Create `apps/sample/e2e/catalog.e2e.ts`(K3 합친 뒤에만 돈다): 운영자가 standard KRW 연 행사가(정가 69,000 → 59,000, 끝 = 내일) 추가 → 랜딩(KR) 연 토글에 취소선 → 로그인 사용자가 구독 화면에서 연 행사가로 시작(토스 테스트 · 기존 구독 e2e 단계 재사용) → 운영자가 그 가격 중지 → 랜딩은 정가, 내 구독은 행사가 그대로. `X-Dev-Country: US` 로 랜딩 · 구독 화면이 USD 가격(DB 금액)만 보인다(Paddle 결제창은 열지 않는다)

- [ ] **실패 시험**: API 경로 · 본문, 표 · 편집기 · 대화상자(필수 칸 잠금, 행사가 켜면 정가 > 금액 검사, 중지 확인), 메뉴 권한, 오류 코드 문장(ko/en).
- [ ] 통과:
```bash
pnpm --filter @skeleton/payment test -- admin/catalog AdminCatalog AddPriceDialog StopPriceDialog AdminProductEditor
pnpm --filter @skeleton/api-client test -- errorCodes
pnpm --filter sample test -- AdminCatalog routes RootLayout
pnpm --filter @skeleton/payment --filter sample typecheck
```
- [ ] 커밋 ① `feat(payment-admin): catalog operator screens` ② `feat(sample): catalog admin route, menu and e2e`.

## 1차 — 묶음 2 (K1 계약 커밋 위)

### Task K2: `catalog` · `catalog-jdbc` (kotlin, 대) — 선행 K1 커밋 ①

**Files:**
- Create `modules/catalog/build.gradle.kts`(`api(project(":modules:payment"))`, spring-security `compileOnly`), `modules/catalog/src/main/kotlin/dev/sumin/skeleton/catalog/`:
  - `CatalogModels.kt` — `CatalogProduct` · `CatalogProductText` · `CatalogPrice` · `ProductKind`
  - `CatalogStore.kt` — 포트(읽기 · 상품 줄 잠금 · 넣기 · 판매 중지 · 끝 시각 · 링크)
  - `CurrentPrices.kt` — 순수 함수: `current(prices, now): List<CatalogPrice>`(열쇠마다 행사 우선) · `overlaps(existing, candidate)`
  - `CatalogAdmin.kt` — 검증(코드 형식 64자, 구독은 주기 · 등급 필수, 단건은 주기 없음, `compareAt > amount`, `starts < ends`, 겹침) + 상품 줄 잠금 안에서 쓰기 + `OperatorAuditLog`
  - `CatalogSubscriptionPlans.kt`(`SubscriptionPlans`: `list` = 판매 중 구독 상품의 현재 가격, `find` = 모든 가격 + `purchasable` · `regularAmount` · `productCode` · 이름 = 기본 언어), `CatalogPaymentCatalog.kt`(공개 클래스 — `itemType` 확인 · 수량 1 · 현재 가격 · `productCode`), `CatalogProviderPriceRefs.kt`
  - `CatalogProperties.kt`(`skeleton.catalog`: `default-locale`(기본 `en`), `item-type`(기본 `product`), `http.enabled`, `admin.enabled`), `CatalogPermissions.kt`(`catalog.manage`), `CatalogErrors.kt`
  - `CatalogAutoConfiguration.kt` — `@AutoConfiguration(before = [PaymentSubscriptionAutoConfiguration, PaymentLedgerAutoConfiguration 등 플랜 · 카탈로그 빈을 찾는 결제 자동 설정])`, 빈 셋 `@ConditionalOnMissingBean`(앱이 `PaymentCatalog` 를 두면 카탈로그 단건은 앱이 `CatalogPaymentCatalog` 로 잇는다)
  - `web/CatalogController.kt`(C-CAT, 익명 허용, 통화 = `PaymentCountryRouting.currencyOf(routing.of(request))`, 캐시 머리 · `Vary`) · `web/CatalogAdminController.kt`(C-CADM, 운영 결제사 목록은 `PaymentProviderRouter.providerIds(PaymentPushGateway::class)`) · `web/CatalogResponses.kt` · `web/CatalogWebAutoConfiguration.kt`
  - `META-INF/spring/…AutoConfiguration.imports`
- Create `modules/catalog-jdbc/`(`api(catalog)`, `persistence-jdbc`): `JdbcCatalogStore.kt` · `CatalogJdbcAutoConfiguration.kt` · 마이그레이션 `V<UTC>__catalog.sql`(postgresql · mysql — 스펙 §3 네 표, 유니크: 상품 코드 · 등급 · 가격 코드 · (결제사, 외부 id))
- Docs: `docs/modules/catalog.md`(「프론트 짝」 `@skeleton/payment` · `@skeleton/payment/admin`, 코드 플랜에서 옮기는 법: 월 가격 코드를 옛 플랜 코드로), `docs/modules/catalog-jdbc.md`, `docs/config/modules/catalog.yml`, `docs/modules/README.md` 행, `capabilities.json` 항목(+ `perl scripts/build-capabilities.pl`), `settings.gradle.kts`, workbench 의존 한 줄

- [ ] **실패 시험**
  - `modules/catalog/src/test/…/CurrentPricesTest`: 상시만 / 행사 시작 1초 전 · 정각 · 끝 1초 전 · 정각 / 판매 중지 제외 / 열쇠(주기 · 통화)가 다르면 따로 — KRW 행사가 USD 현재 가격을 바꾸지 않는다.
  - `…/CatalogAdminTest`(대역 저장소): 코드 · 등급 중복 · 상시 둘 · 겹치는 행사 → 각 오류, 끝 시각 고치기가 겹침을 만들면 거절, 단건에 주기 → `PRICE_INVALID`, 금액을 바꾸는 길이 없다(API 표면), 감사 기록 한 줄씩.
  - `…/CatalogSubscriptionPlansTest`: `list` 는 현재 가격만 · 판매 중지 상품 제외 · 단건 제외; `find` 는 지난 가격도(`purchasable=false`); `regularAmount` = 정가; 이름 = 기본 언어 → 없으면 첫 언어.
  - `…/CatalogPaymentCatalogTest`: 다른 `itemType` · 수량 2 · 지난 가격 → `PaymentItemNotPurchasableException`; 결제창 이름 · `productCode`.
  - `…/web/CatalogControllerTest` · `CatalogAdminControllerTest`: 꺼짐 404, 익명 공개 읽기 200 + 캐시 머리 · `Vary`, 나라 KR → KRW 가격만 · US → USD 가격만 · 통화를 못 정함 → 기본 통화 → 사전순 첫 통화, 운영 익명 401 · 권한 없음 403, C-CAT · C-CADM 본문 모양.
  - `modules/catalog-jdbc/src/dbTest/…/JdbcCatalogStoreTest`: 왕복 · 유니크 · 상품 줄 잠금 아래 동시 행사 추가 둘 → 하나만.
  - `…/CatalogAutoConfigurationTest`: 결제 구독이 켜진 조립에서 앱 `SubscriptionPlans` 빈이 없어도 기동(카탈로그 빈), 앱 빈이 있으면 앱 것.
- [ ] 통과:
```bash
./gradlew :modules:catalog:test
./gradlew :modules:catalog-jdbc:postgresTest :modules:catalog-jdbc:mysqlTest
./gradlew :modules:platform:test --tests '*CapabilitiesCatalogTest' --tests '*ModuleRegistrationRulesTest' --tests '*ModuleDocumentationTest' --tests '*MigrationLockTest'
```
- [ ] 커밋 `feat(catalog): products and prices with sale windows, payment contracts, public and operator APIs`.

## 1차 — 묶음 3

### Task K3: 견본 앱 조립 (kotlin, 소~중) — 선행 K1 · K2 합침

**Files:**
- Modify `apps/sample/build.gradle.kts`(`catalog-jdbc` 한 줄), `subscription/SubscriptionConfiguration.kt`(`samplePlans` 삭제 · `renewalGuard` 는 그대로)
- Create `apps/sample/src/main/kotlin/dev/sumin/skeleton/app/sample/catalog/CatalogSeed.kt`(모든 프로필, 없는 코드만: 상품 basic · standard · pro, KRW 월 가격 코드 `basic` · `standard` · `pro` · 연 `basic-yearly` …, USD `basic-usd` · `basic-yearly-usd` …(스펙 §8 금액), 주기 `skeleton.sample.subscription.period` / 새 `yearly-period`, ko/en 이름) · `SampleCatalog.kt`(`PaymentCatalog` = `itemType` 이 `cheer` 면 `CheerCatalog`, 아니면 `CatalogPaymentCatalog`)
- Modify `cheer/CheerCatalog.kt`(`@Component` 를 떼고 `SampleCatalog` 가 쓴다 — 견본 앱은 모듈이 아니라 `@Component` 가 허용되지만 `PaymentCatalog` 빈이 둘이 되지 않게)
- Modify `application.yml`(`skeleton.catalog.http.enabled` · `admin.enabled` · `default-locale: ko`, `skeleton.payment.routing.provider-currencies.paddle: USD`, `payment-paddle.prices` 키를 USD 코드로 — `basic-usd: ${PADDLE_PRICE_BASIC:}` …, 연 가격 환경변수 셋 `PADDLE_PRICE_*_YEARLY` 추가), `application-local.yml`(`yearly-period: PT36M`), `docs/deploy.md` 의 Paddle 환경변수 표
- Test: `apps/sample/src/test/…/catalog/CatalogJourneyIntegrationTest`(씨앗 → 공개 읽기 KR = KRW 여섯 가격 · US(`X-Dev-Country` 스위치 켠 시험 설정) = USD 여섯 가격 → KRW 연 가격으로 구독 시작(가짜 결제사) → 운영자 행사가 추가 · 중지 → 기존 구독 이름 · 금액 그대로; USD 가격을 KR 요청으로 시작 → `ROUTING_FAILED`), 업그레이드 시험(`MigrationUpgradeIntegrationTest` 꼴)에 옛 `basic` 구독 줄이 그대로 찾아지는 한 건
- Docs: CHANGELOG `[Unreleased]`(새 모듈 · `SubscriptionPlan` 칸 · 요청 통화 · Paddle 가격 id 순서 · 견본 `PADDLE_PRICE_*` 가 USD 가격 코드로 옮김 · 쿠폰 범위), `docs/new-project-recipe.md` 의 결제 예에 카탈로그 한 줄

- [ ] 통과:
```bash
./gradlew :apps:sample:test --tests '*CatalogJourney*' --tests '*Subscription*' --tests '*Cheer*' --tests '*MigrationUpgrade*'
```
- [ ] 커밋 `feat(sample): catalog-backed plans in KRW and USD with yearly prices, routing currency, seed and cheer alongside`.

## 1차 끝 검사 (지휘)

- kotlin: K1 → K2 → K3 순으로 `catalog` 에 합치고 `perl scripts/build-capabilities.pl` · `perl scripts/migrations-lock.pl --check` → `kverify` 한 번(`test-new-project.sh --full` 에 `catalog` 조합이 있으면 포함 — 없으면 K2 가 `--modules payment,payment-jdbc,catalog,catalog-jdbc` 조합 한 줄을 더한다).
- react: R1 → R2 합친 뒤 `pnpm test` · `typecheck` · `lint` · `test:stories` · `e2e:sample` 한 번.
- 리뷰: kotlin 1 · react 1(`code-reviewer`, Review Focus 위주, 시험 재실행 안 시킴). 확인(`qa`) 1 — 운영자 행사가 → 랜딩(KR · US) → 연 구독 → 중지(화면당 캡처 2장까지).

---

## 2차 (1차가 main 에 들어간 뒤, 스펙 §5)

### Task K4: 갈아타기 넓히기 (kotlin, 대)

**Files:**
- Create `modules/payment/…/PlanChangeRules.kt` — `enum PlanChangeKind { IMMEDIATE_SAME_PERIOD, IMMEDIATE_NEW_PERIOD, SCHEDULED, NOT_ALLOWED }`, `classify(current: SubscriptionPlan, target: SubscriptionPlan, subscription): PlanChangeKind`(같은 통화만, 주기 길이 비교 = `SubscriptionPeriod.after(기준, 1)`)
- Modify `SubscriptionPlanChanges.kt` — `isUpgrade` 를 `classify` 로, 주기↑ 과금 = `target − 남은 몫`(남은 몫 = 이번 기간 낸 돈의 `Proration.remaining` 합, 0 미만 없음, `minCharge` 규칙 그대로), 예약 · 예약 취소(`cancelScheduled`), 올리기가 예약을 지운다
- Extract `CurrentPeriodPayments`(지금 `SubscriptionCancellations` 안의 「이번 기간 낸 돈」 계산) — 해지와 주기↑가 같이 쓴다
- Modify `PaymentSubscriptionModel.kt`(`pendingPeriod` · `scheduledPlanCode` · `scheduledAmount` · `scheduledPeriod`), `SubscriptionLifecycle.onOrderPaid`(`pendingPeriod` 가 있으면 주기를 바꾸고 새 기준 시각 · 기간 번호 1), `SubscriptionRenewTaskHandler.step`(기한이 된 `ACTIVE` · `PAST_DUE` 에 예약이 있으면 같은 트랜잭션에서 플랜 · 금액 · 주기를 바꾼 뒤(주기가 바뀌면 기준 시각 = 옛 기간 끝, 기간 번호 0) 과금), `SubscriptionCancellations`(해지 예약 · 중도 해지가 예약을 지운다)
- Modify `web/PaymentSubscriptionController.kt` · `web/PaymentResponses.kt`(C-CHG), `docs/payment-http-contract.md`, `docs/modules/payment.md`
- Create `modules/payment-jdbc/…/db/migration/{postgresql,mysql}/V<UTC>__payment_subscriptions_plan_schedule.sql`, Modify `PaymentSubscriptionRows.kt`, `modules/payment/src/testFixtures/…/InMemoryPaymentLedgerStore`

- [ ] **실패 시험**: `PlanChangeRulesTest`(표 여섯 줄 + 통화 다름 · PT3M/PT36M), `PlanChangeNewPeriodTest`(월 9,900 · 15일 남음 → 연 99,000 − 남은 몫, 결제 후 주기 `P1Y` · 오늘부터, 거절이면 그대로, 쿠폰으로 반값 낸 달은 남은 몫도 반), `ScheduledPlanChangeTest`(내리기 → 이번 기간 그대로 · 갱신 때 새 금액 과금 · 연→월은 기준 시각 = 옛 기간 끝 · 실패한 갱신 재시도는 새 금액 · 예약 취소 · 올리기 · 해지 예약이 예약을 지움 · Paddle 구독은 거절), 기존 `SubscriptionPlanChanges*` · `Renew*` · `Cancel*` 불변, `payment-jdbc` dbTest 왕복.
- [ ] 통과:
```bash
./gradlew :modules:payment:test --tests '*PlanChange*' --tests '*ScheduledPlanChange*' --tests '*Renew*' --tests '*Cancel*'
./gradlew :modules:payment-jdbc:postgresTest :modules:payment-jdbc:mysqlTest --tests '*Subscription*'
./gradlew :modules:platform:test --tests '*MigrationLockTest'
```
- [ ] 커밋 `feat(payment): longer-period upgrades credit the unused part, downgrades wait for the renewal`.

### Task R3: 변경 창 · 예약 표시 (react, 중) — C-CHG 만

**Files:** Modify `packages/payment/src/ChangePlanDialog.tsx`(다른 플랜 모두 — 「바로」/「다음 결제일부터 ○월 ○일」 묶음, 예약이면 결제 금액 대신 시작일), `MySubscriptionCard.tsx`(예약 줄 + 「예약 취소」 확인), `subscriptionApi.ts` · `subscriptionQueries.ts`(`cancelScheduledChange`), 타입 · 라벨 · 스토리 · 시험, `apps/sample/src/routes/SubscriptionPage.tsx`.
- [ ] 통과: `pnpm --filter @skeleton/payment test -- ChangePlanDialog MySubscriptionCard subscriptionApi` · `pnpm --filter sample test -- SubscriptionPage` · typecheck.
- [ ] 커밋 `feat(payment): plan change dialog schedules downgrades, my subscription shows and cancels the schedule`.

2차 끝: 지휘가 합친 뒤 kotlin `kverify` · react 전체 · e2e 한 번, 리뷰 1(kotlin · react 함께), 확인 1(월→연 · 내리기 예약 · 예약 취소, 로컬 PT3M 주기로 갱신까지).

## 예산

| 묶음 | 조각 | 동시 | 벽시계(대략) |
|---|---|---|---|
| 1차-1 | K1 · R1 · R2 | 3 | 2h(K1 은 1.5h — 계약 커밋은 15분 안에) |
| 1차-2 | K2 | 1(react 가 남아 있으면 같이) | 3h(K1 계약 커밋 뒤 바로 시작하면 1차-1 과 겹친다) |
| 1차-3 | K3 · 끝 검사 · 리뷰 2 · QA 1 | 1 | 2h |
| 2차 | K4 · R3 · 끝 검사 · 리뷰 1 · QA 1 | 2 | 3h |

- 1차: developer 5회 + 리뷰 2 · QA 1, 약 5~6시간(K2 를 K1 계약 커밋에서 바로 시작할 때 — 통화별 가격이 1차로 와서 K1 · K3 · R1 이 조금씩 커졌다). 2차: developer 2회 + 리뷰 1 · QA 1, 약 3시간. 동시 최대 3명.
- QA 캡처는 화면당 2장까지, 리뷰는 한 번(지적 반영 뒤 재리뷰는 지휘가 필요할 때만).

## 정해야 할 것

없음 — 스펙의 형 결정(2026-10-12 · 2026-10-29)으로 닫혔다.
