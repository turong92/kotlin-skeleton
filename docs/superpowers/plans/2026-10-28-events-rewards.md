# 이벤트 · 보상(쿠폰 · 포인트) · 결제 면제 · 역할 위계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 구현 · 리뷰는 역할(`developer` · `code-reviewer`)로 부른다.

**Goal:** 찍은 앱이 모듈 몇 줄로 출석 · 친구초대 이벤트 → 쿠폰 · 포인트 지급 → 쿠폰으로 단건 · 구독 결제 할인, 운영자의 이벤트 · 쿠폰 · 포인트 · 무료 이용권 관리, 권한 하나로 결제 면제, 역할 위계를 얻는다.

**Architecture:** ① `campaign` 이 조건을 판정해 「지급 건」을 기록 · enqueue 하고 커밋에서 끝난다. ② `reward` 의 일꾼(`job-queue-jdbc`)이 지급 건마다 `RewardIssuer` 를 부른다. ③ `coupon` · `point` 가 지급기를 구현하고 단독으로도 돈다. 모듈 사이 계약(`RewardIssuer` · `DiscountSource` · `AccountFacts`)은 `platform` 에 둔다 — 서로 `compileOnly` 하지 않는다. `payment` 는 `DiscountSource` 빈들로 주문 · 구독 금액을 깎고(예약 → 확정 → 해제 · 복원), 최종가 0 은 결제사 없이 `provider = "free"`, 결제 면제는 `provider = "exempt"` 로 끝낸다. 역할 위계는 `RolePermissionSource.permissionsOf` 를 「물려받은 것까지 펼친 값」으로 바꿔 모든 판정이 고치지 않고 따른다.

**Tech Stack:** Kotlin · Spring Boot · Spring Data JDBC/JdbcClient · Flyway(PostgreSQL · MySQL) · Testcontainers · `job-queue-jdbc` / React · TypeScript · TanStack Query · Storybook · Vitest.

**Spec:** `docs/superpowers/specs/2026-10-28-events-rewards-design.md`(형 승인 2026-10-28, 결정 1~10). 계획과 스펙이 어긋나면 스펙이 이긴다 — 어긋남은 지휘에 알린다. 작업 키는 스펙 §13 표와 같다.

## Global Constraints

- 레포 · 기준: kotlin `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton-wt-rewards`(브랜치 `rewards`, 기준 `ec31a1fa`), react `/Users/sumin/IdeaProjects/sumin/homeserver/projects/react-skeleton-wt-rewards`(브랜치 `rewards`, 기준 `6a68306`). 나란히 도는 조각은 그 레포 안 `.claude/worktrees/rw-<키>`(브랜치 `rewards/<키>`, 시작점 `rewards`). **커밋은 조각 브랜치에만, 합치기 · push 는 지휘.** 커밋 메시지에 `Co-Authored-By` 를 붙이지 않는다.
- 시험은 **바뀐 범위만**(각 작업의 명령). 전체 스위트 · `kverify` · react 전체(`pnpm test` · `typecheck` · `lint` · `test:stories`) · e2e 는 지휘가 묶음 끝에 한 번. 같은 워크트리에서 Gradle 둘을 동시에 돌리지 않는다.
- 모듈 규칙: 빈은 AutoConfiguration `@Bean` + `@ConditionalOnMissingBean`, `@Component` 금지, 컨트롤러는 자동 설정이 등록. 새 모듈을 만드는 조각이 `settings.gradle.kts` · `docs/modules/<m>.md` · `docs/config/modules/<m>.yml` · `docs/modules/README.md` 행 · `capabilities.json` 항목 · workbench 의존 한 줄을 같이 더한다(가드가 그때 막는다). 모듈 간 시험 의존(`testImplementation(project(...))`) 금지.
- 마이그레이션: `./gradlew newMigration -Pname=…`, 두 방언 짝, `perl scripts/migrations-lock.pl`. 잠긴 파일은 고치지 않는다. `payment-jdbc` 는 JSON 열 · 부분 인덱스 없음. 다른 모듈의 JSON 은 `*_json text`.
- 시각은 `Instant`(`SqlDialect.instantParam` / `readInstant`), 날짜는 `LocalDate` + `date` 열, 시간대는 `<module>.zone` → `skeleton.time.default-zone` → `Asia/Seoul`. `ZoneId.systemDefault()` 금지. 프로젝트 모드는 프로젝트마다.
- 스켈레톤 중립: 운영 API `skeleton.<module>.admin.enabled=false`(→ 404), 견본 앱 yml 에서 켬. 초대자 보상 시점 · 포인트 유효기간에 기본값 없음. 이 모듈들을 안 붙인 앱의 결제 · 권한 동작은 그대로.
- 새 권한(기본 역할 없음 — 전권만): `campaign.manage` · `coupon.manage` · `point.manage` · `reward.operate` · `payment.exempt`. 오류 코드 접두사 `CAMPAIGN.` · `COUPON.` · `POINT.` · `REWARD.` · `PAYMENT.DISCOUNT_*` · `PAYMENT.NOTHING_TO_REFUND` · `AUTHZ.ROLE_CYCLE` · `AUTHZ.INCLUDE_UNKNOWN` · `AUTHZ.ROLE_INCLUDED`.
- 예약 결제사 id: `free`(쿠폰으로 0원) · `exempt`(결제 면제). 라우터가 고르지 않는다.
- 공개 레포 — 특정 서비스 · 도메인 · 이메일 흔적 금지(`example.com`). KDoc 안에 `/**` 금지.
- react: 날 요소 · 날값 금지(`@skeleton/ui` · 의미 토큰), 패키지는 `import.meta.env` 를 읽지 않고 서로를 import 하지 않는다(`@skeleton/payment` ↔ `@skeleton/rewards` 는 앱이 잇는다), 문구는 `labels` prop(ko/en), SSR 안전, 눌리는 요소는 테두리 · 채움.

## Review Focus

- **쿠폰 한 장 두 주문**: 같은 쿠폰으로 동시에 주문 둘 → 하나만 `RESERVED`, 다른 하나 `PAYMENT.DISCOUNT_UNAVAILABLE`. 만료로 풀린 쿠폰을 다른 주문이 쥔 뒤 첫 주문이 늦게 `PAID` → 두 주문 모두 그대로, 늦은 주문에 `DISCOUNT_CONFLICT` 표시(K6).
- **지급 중복**: 같은 지급 건 작업 두 개 동시 · 지급기가 던진 뒤 재시도 · DEAD 뒤 「다시 지급」 → 쿠폰 · 포인트 정확히 한 번(K2 · K3 · K4).
- **날짜 경계**: 서비스 시간대 23:59:59 와 00:00:00 출석은 다른 날, 같은 날 동시 두 요청은 한 줄. `zone` 을 서머타임 있는 곳(`America/New_York`)으로 두어도 하루 한 번(K5).
- **결제 면제 새는 곳**: 면제 주문이 순매출 · 결제 건수 · 퍼널 · 환불 · `first_purchase_only` 판정에 들어가지 않고, 역할 회수 뒤 다음 요청부터 결제창(K6).
- **구독 할인 회차**: 실패한 갱신의 재시도는 회차를 쓰지 않고 같은 금액, 플랜 변경 차액에는 할인 없음, `FULL_RESTART` 로 `periodSeq` 가 1 이 돼도 남은 회차는 이어진다(K7).

---

## 조각 사이 계약 (react 는 이것으로 가짜 API 를 먼저 만든다 — 바뀌면 kotlin 조각이 보고에 적고 지휘가 넘긴다)

### C-R 사용자 API (K3 · K4 · K5 가 연다, R1 이 쓴다)
```
GET  /api/v1/campaigns → values: [{ id, kind: "attendance"|"referral", name, startsAt, endsAt|null,
       attendance?: { checkedToday, total, streak, nextMilestone: { days, streak: bool }|null, today: "2026-10-28" } }]
POST /api/v1/campaigns/{id}/attendance → value: { alreadyChecked, total, streak, rewards: [{ type, label }] }   (끝난 · 꺼진 이벤트 409 CAMPAIGN.NOT_ACTIVE)
GET  /api/v1/campaigns/{id}/attendance?month=2026-10 → value: { days: ["2026-10-01", …] }
GET  /api/v1/referrals/me → value: { code, invited, rewarded }
POST /api/v1/referrals/claim {code} → 204 | 400 CAMPAIGN.REFERRAL_INVALID (자기 코드 · 맞초대 · 이미 · 새 계정 아님 · 없는 코드 — 이유는 detail.reason) | 409 CAMPAIGN.NOT_ACTIVE
GET  /api/v1/coupons?status=AVAILABLE|PAST → values: [{ id, name, status, expiresAt|null, stages: [{ type, value, maxDiscount|null, periods|null }], scope: { purposes, itemTypes, planCodes }, currency|null, minAmount|null }]
POST /api/v1/coupons/redeem {code} → 201 value: Coupon | 400 COUPON.CODE_INVALID | 409 COUPON.CODE_USED | 409 COUPON.CODE_EXHAUSTED
GET  /api/v1/points → value: { balance, expiringSoon: { amount, before } }
GET  /api/v1/points/history?cursor → values: [{ id, delta, kind, reason|null, expiresAt|null, createdAt }], cursor
```

### C-P 결제 API (K6 · K7, R2 가 쓴다 — 기존 응답에 칸을 **더하기만**)
```
POST /api/v1/payments/quote { itemType, itemRef, quantity, provider?, discounts: [{ type: "coupon", ref }] }
  → value: { originalAmount, amount, currency, discounts: [{ type, ref, label, amount }], settlement: "PROVIDER"|"FREE"|"EXEMPT" }
POST /api/v1/payments/orders  본문에 discounts(같은 꼴, 첫 판 최대 1개)
주문 응답 · 목록 · 상세(사용자 · 운영): + originalAmount|null, discountAmount, discounts[], settlement
  settlement FREE|EXEMPT 이면 status PAID · checkout null · refundableAmount 0
운영 주문 목록 필터: + settlement=EXEMPT
POST /api/v1/payments/subscriptions  본문에 discounts
구독 응답: + discount: { label, stages[], periodsUsed, nextAmount, remainingPeriods|null(영원) } | null
오류: 400 PAYMENT.DISCOUNT_NOT_APPLICABLE · 409 PAYMENT.DISCOUNT_UNAVAILABLE · 400 PAYMENT.DISCOUNT_NOT_SUPPORTED · 400 PAYMENT.DISCOUNT_BELOW_MINIMUM · 409 PAYMENT.NOTHING_TO_REFUND
```

### C-ADM 운영 API (K2~K5, R3 가 쓴다 — 스펙 §8 표 그대로, 본문)
```
campaign: { id, kind, name, enabled, startsAt, endsAt|null, config: { rewards: [{ type, params }], …종류별 } }
  attendance config: { daily: [Reward], milestones: [{ days, streak: bool, rewards: [Reward] }] }
  referral config:   { invitee: [Reward], inviter: [Reward], qualifyOn: "CLAIM"|"SIGNAL", claimWithinDays: 7, inviterCap: int|null }
coupon-definition: { id, name, status, stages[], currency|null, minAmount|null, scope, firstPurchaseOnly, validDays|null, validUntil|null, issueLimit|null, perAccountLimit, issuedCount }
point adjust: { delta, reason, expiresInDays|null } → 201 entry | 409 POINT.INSUFFICIENT
reward-grant: { id, accountId, type, label, status, sourceType, lastError|null, createdAt, issuedAt|null }
```

### C-A 역할 위계 (K9, R4 가 쓴다)
```
역할 응답: + includes: string[], effectivePermissions: string[]
POST /api/v1/admin/roles { name, description, permissions, includes? }   PUT …/{name} { description, permissions, includes? }  (PUT 에 includes 없으면 유지)
오류: 409 AUTHZ.ROLE_CYCLE · 400 AUTHZ.INCLUDE_UNKNOWN · 409 AUTHZ.ROLE_INCLUDED · 기존 ROLE_PROTECTED · ESCALATION · SELF_LOCKOUT
```

## 같이 만지는 파일과 주인

| 파일 | 주인 | 다른 조각 |
|---|---|---|
| `settings.gradle.kts` · `apps/workbench/build.gradle.kts` · `docs/modules/README.md` · `capabilities.json` | 모듈을 만드는 조각이 자기 줄만 | 지휘가 합친 뒤 `perl scripts/build-capabilities.pl` |
| `migrations.lock` | 마이그레이션을 더하는 조각 | 지휘가 합칠 때 다시 잠금 |
| `modules/payment/**` | K6, 그다음 K7(K6 위에서 시작) | — |
| `apps/sample/**` · CHANGELOG `[Unreleased]` | K8 · R3 | 다른 조각은 손대지 않는다 |
| react `packages/api-client/src/errorCodes.ts` | R1(`CAMPAIGN.` · `COUPON.` · `POINT.` · `REWARD.`) · R2(`PAYMENT.DISCOUNT_*`) · R4(`AUTHZ.*` 셋) | 자기 줄만 |

---

## 묶음 1 — 계약 · 허리 · 위계 (서로 기다리지 않는다)

### Task K1: 카카오페이 0원 1회차 확인 (kotlin, 소, 조사 — 코드 없음)

**Files:** Modify `docs/superpowers/specs/2026-10-28-events-rewards-design.md` §5 「첫 기간 무료」 한 줄(결과), `docs/modules/payment-kakaopay.md` 「알려진 제약」 한 줄.

- [ ] 카카오페이 정기결제 문서에서 1회차 `ready` 의 `total_amount = 0` 허용 여부 · SID 발급 여부를 찾는다. 문서가 모호하면 테스트 가맹점(`TCSUBSCRIP`)으로 `ready` 한 번(`modules/payment-kakaopay` 의 가짜가 아닌 실제 호출 — 키가 없으면 문서 근거만).
- [ ] 결과를 둘 중 하나로 적는다: 「된다 → K7 은 카카오페이도 0원 1회차로 등록」 / 「안 된다 → K7 은 첫 단계가 무료인 쿠폰을 카카오페이 구독에서 `DISCOUNT_NOT_SUPPORTED`」. 출처 URL 을 같이.
- [ ] 보고(지휘가 K7 에 넘긴다). 커밋: 문서 두 줄.

### Task K2: `platform` 계약 + `AccountFacts` + `reward` / `reward-jdbc` (kotlin, 중)

**Files:**
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/reward/RewardContracts.kt`, `…/common/discount/DiscountContracts.kt` · `DiscountMath.kt`, `…/common/account/AccountFacts.kt`
- Modify `modules/account/…/AccountAutoConfiguration.kt`(`AccountFacts` 빈 — 저장소의 `created_at`)
- Create `modules/reward/`(`build.gradle.kts`: `api(platform)`, `compileOnly(notification)`, spring-security `compileOnly`), `…/reward/` — `RewardGrants` · `RewardGrantService` · `RewardWorker` · `RewardGrantStore`(포트) · `RewardTasks`(포트) · `RewardNotifier` · `NotificationRewardNotifier` · `RewardPermissions` · `web/RewardAdminController` · `RewardErrors` · 자동 설정 둘
- Create `modules/reward-jdbc/`(`api(reward)`, `implementation(job-queue-jdbc)`) — `JdbcRewardGrantStore` · `RewardJobHandler`(`reward.issue`) · `RewardDeadListener`(`JobDeadListener`) · 마이그레이션 `reward_grants`(스펙 §3.2)
- 모듈 문서 · 설정 문서 · 카탈로그 항목(짝 프런트 `@skeleton/rewards`)

**Interfaces — Produces:**
- `interface RewardIssuer { val type: String; fun validate(params: Map<String, String>); fun issue(grant: RewardGrantView): IssuedReward; fun describe(params: Map<String, String>, locale: Locale): RewardLabel }` — `issue` 는 호출자 트랜잭션 안, `(GRANT, grant.id)` 로 멱등. 영구 실패는 `RewardPermanentFailure`.
- `data class RewardGrantView(id, accountId, type, params: Map<String,String>, sourceType, sourceKey)` · `IssuedReward(resultRef: String)` · `RewardLabel(title, message, link: String?)`
- `enum DiscountStageType { FREE, PERCENT, FIXED }` · `data class DiscountStage(type, value: Long, maxDiscount: Long?, periods: Int?)` · `enum DiscountPurpose { ONE_TIME, SUBSCRIPTION }` · `data class DiscountTarget(accountId, itemType, itemRef, planCode: String?, amount: Long, currency: String, purpose, firstPurchase: Boolean)` · `DiscountClaim(kind, ref)` · `DiscountOffer(sourceRef, label, stages: List<DiscountStage>)`
- `interface DiscountSource { val kind: String; val order: Int; fun quote(claim, target): DiscountOffer; fun reserve(claim, target, holderRef: String): DiscountOffer; fun confirm(holderRef: String): DiscountConfirmOutcome; fun release(holderRef: String); fun restore(holderRef: String) }` · `enum DiscountConfirmOutcome { CONFIRMED, ALREADY, CONFLICT }` · `class DiscountRejectedException(val reason: DiscountRejection)` · `enum DiscountRejection { NOT_APPLICABLE, UNAVAILABLE }`
- `object DiscountMath { fun discount(amount: Long, stage: DiscountStage, currencyScale: Int): Long; fun stageAt(stages: List<DiscountStage>, index: Int): DiscountStage? }`
- `fun interface AccountFacts { fun createdAt(accountId: String): Instant? }`
- `interface RewardGrants { fun record(accountId: String, type: String, params: Map<String, String>, sourceType: String, sourceKey: String): String? }` — 호출자 트랜잭션 안, 이미 있으면 null.
- `interface RewardGrantStore { insertIfAbsent · lockPending(id) · markIssued(id, resultRef, at) · markFailed(id, error) · reopen(id) · cancel(id) · page(status, cursor) }` · `fun interface RewardTasks { fun enqueue(grantId: String) }` · `fun interface RewardNotifier { fun issued(grant: RewardGrantView, label: RewardLabel) }`

- [ ] **실패 시험 쓰기**
  - `modules/platform/src/test/…/common/discount/DiscountMathTest`: `PERCENT 30` on 9,999 → 2,999(버림); `maxDiscount 2,000` → 2,000; `FIXED 5,000` on 3,000 → 3,000(0 미만 없음); `FREE` → 전액; `stageAt([FREE×1, PERCENT50×3], 0..4)` → FREE, P, P, P, null; `[PERCENT10×null]` 의 1000번째 → PERCENT10.
  - `modules/reward/src/test/…/RewardWorkerTest`(저장소 · 큐 대역): 지급기가 두 번 던지고 세 번째 성공 → `issue` 성공 1회 · 상태 `ISSUED`; 이미 `ISSUED` 인 건 → 지급기 호출 0; 지급기 없음(타입 미등록) → `RewardPermanentFailure` → `FAILED`; 알림 대역이 던져도 `ISSUED` 유지; 알림 id = `"rwd_" + grantId`.
  - `modules/reward/src/noOptionalTest/…/RewardWithoutNotificationTest`: `notification` 없이 기동, `RewardNotifier` 는 아무것도 안 한다.
  - `modules/reward-jdbc/src/dbTest/…/RewardGrantStoreDbTest`: 같은 `(source_type, source_key)` 두 번 → 한 줄; 같은 건 작업 둘을 두 스레드로 → `issue` 1회; DEAD → `FAILED` → `reopen` → 다시 지급 1회.
  - `modules/reward/src/test/…/RewardAdminWebTest`: `reward.operate` 없으면 403, 익명 401, `admin.enabled=false` 404, `retry` 는 `FAILED` 만(그 밖 409 `REWARD.NOT_RETRYABLE`).
  - `modules/account/src/test/…/AccountFactsTest`: 있는 계정 → 가입 시각, 없는 계정 → null.
- [ ] 실행해 실패 확인(아래 명령).
- [ ] 구현.
- [ ] 통과:
```bash
./gradlew :modules:platform:test --tests '*DiscountMathTest' --tests '*ModuleRegistrationRulesTest' --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*ConfigPrefixDocumentationTest' --tests '*MigrationLockTest'
./gradlew :modules:reward:test :modules:reward:noOptionalTest :modules:reward-jdbc:postgresTest :modules:reward-jdbc:mysqlTest
./gradlew :modules:account:test --tests '*AccountFactsTest'
```
- [ ] 커밋 `feat(reward): grant ledger and worker, platform reward/discount contracts`. 보고에 위 시그니처 최종본.

### Task K9: 역할 위계 (kotlin, 중) — 독립

**Files:**
- Modify `modules/platform/…/common/authz/RolePermissionSource.kt`(`includesOf` · 펼친 `permissionsOf` · `CachingRolePermissionSource` 전체 무효화) · `AuthzProperties.kt`(`roleIncludes: Map<String, List<String>>`) · `AuthzAutoConfiguration.kt`(yml 검사) · Create `RoleClosure.kt`
- Modify `modules/authz/…/AuthzRoleStore.kt`(`StoredRole.includes` · `update(…, includes: Set<String>?)` · `lockHierarchy()` · `includedBy(name)`) · `RoleService.kt`(검사 · 운영 기록 detail) · `web/*`(DTO) · `AuthzErrors.kt`
- Modify `modules/authz-jdbc/…/JdbcAuthzRoleStore.kt` · `JdbcRolePermissionSource.kt` · `AuthzRoleSeeder.kt` + 마이그레이션 `authz_role_includes`
- Docs: `docs/modules/authz.md` · `docs/authz-http-contract.md` · `docs/config/modules/platform.yml`(`role-includes`)

**Interfaces — Produces (C-A):** `RolePermissionSource.includesOf(role: String): Set<String> = emptySet()`; `permissionsOf(role)` = 자기 + 물려받은 것 전부. `object RoleClosure { fun expand(roles: Set<String>, includesOf: (String) -> Set<String>, maxDepth: Int = 8): Set<String>; fun cycleOf(graph: Map<String, Set<String>>): List<String>? }`.

- [ ] **실패 시험 쓰기**
  - `modules/platform/src/test/…/authz/RoleClosureTest`: `STAFF→CS→VIEWER` 펼침 = 셋; `A→B→A` 의 `cycleOf` = `[A, B, A]`; 깊이 9 사슬은 8 까지만.
  - `…/authz/YmlRoleHierarchyTest`: `role-includes: {STAFF:[CS]}` 로 STAFF 토큰이 CS 권한 통과(`TokenOperatorAccess`); 순환 · 없는 역할 · 전권 포함 · 전권에 includes → 기동 실패 메시지에 역할 이름.
  - `modules/authz/src/test/…/RoleHierarchyServiceTest`(`FakeRoleStore`): 순환 409 `ROLE_CYCLE`; 없는 역할 400 `INCLUDE_UNKNOWN`; 전권 물려받기 409 `ROLE_PROTECTED`; `authz.roles` 만 가진 사람이 `payment.refund` 를 가진 역할을 includes 에 더하기 403 `ESCALATION`; 물려받던 `authz.roles` 를 잃는 변경 409 `SELF_LOCKOUT`; 물려받히는 역할 지우기 409 `ROLE_INCLUDED`; `PUT` 에 includes 없으면 유지; 성공 시 `OperatorAuditLog` detail `includesAdded` · `includesRemoved`.
  - `RoleServiceConcurrencyTest` 에 한 건: 두 스레드가 A→B, B→A 를 동시에 → 하나만 성공.
  - `modules/authz-jdbc/src/dbTest/…/RoleHierarchyDbTest`: 두 저장소 인스턴스(= 두 서버)로 A→B, B→A 동시 → 하나만(전권 줄 잠금); 씨앗 두 번 기동 → 한 번; `CS` 권한 변경 → 같은 인스턴스 STAFF 즉시, 다른 인스턴스 `cache-ttl` 뒤.
  - `modules/account/src/test/…`(기존 상승 막기 시험 파일) 한 건: STAFF(⊃ `account.manage` 가진 CS)를 가진 대상의 정지는 `account.manage` 만 가진 운영자에게 403.
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:platform:test --tests '*RoleClosureTest' --tests '*YmlRoleHierarchyTest' --tests '*Authz*' --tests '*MigrationLockTest' --tests '*ModuleDocumentationTest'
./gradlew :modules:authz:test :modules:authz-jdbc:postgresTest :modules:authz-jdbc:mysqlTest
./gradlew :modules:account:test --tests '*Escalation*'
```
- [ ] 커밋 `feat(authz): role hierarchy (includes) with cycle, escalation and lockout checks`.

### Task R1: `@skeleton/rewards` 사용자 부품 (react, 중) — C-R 만, K2 와 나란히

**Files:** Create `packages/rewards/`(`package.json` exports `.` · `./campaign` · `./coupon` · `./point` · `./admin`(R3 이 채움), `src/campaign/{AttendanceCard,InviteCard,useReferralCapture,campaignApi,queries}.ts(x)`, `src/coupon/{CouponWallet,RedeemCodeForm,CouponPicker,couponApi,stageSummary,queries}.ts(x)`, `src/point/{PointBalance,PointHistory,pointApi,queries}.ts(x)`, `labels.{ko,en}.ts`, 스토리 + `play`) · Modify `packages/api-client/src/errorCodes.ts` · `capabilities.json` · `docs/ui-catalog.md` · `tests/support/ssrFixtures.ts`.

**Interfaces — Produces:** `createRewardsApi(client, paths?)` · `useAttendance(campaignId)` · `useCheckIn()` · `useReferral()` · `useReferralCapture({ storage, onClaimed })`(URL `ref` → 30일 보관 → 로그인 뒤 claim 1회 → 지움) · `useCoupons(status)` · `useRedeemCoupon()` · `CouponPicker { coupons, selectedId, onSelect, preview?: QuotePreview }` · `stageSummary(stages, labels): string` · `usePoints()` · `usePointHistory()`.

- [ ] **실패 시험 쓰기**: `stageSummary.test.ts` — `[FREE×1, PERCENT50×3]` → 「첫 1회 무료, 이후 3회 50%」, `[PERCENT10×null]` → 「매번 10%」, en 문구; `useReferralCapture.test.ts` — `?ref=AB12CD34` 저장, 31일 뒤 버림, 로그인 뒤 claim 정확히 1회, 400 이어도 지움; `campaignApi.test.ts` · `couponApi.test.ts` · `pointApi.test.ts` — C-R 경로 · 본문; 스토리 `play`: 출석 단추 → 「오늘 출석함」 비활성, 코드 등록 오류 문구.
- [ ] 실패 확인 → 구현 → 통과:
```bash
pnpm --filter @skeleton/rewards test
pnpm --filter @skeleton/rewards typecheck
pnpm vitest run tests/ssr.safety.test.ts tests/stories.test.ts tests/capabilities.test.ts tests/skeleton.repo.test.ts
pnpm test:stories -- packages/rewards
```
- [ ] 커밋 `feat(rewards): attendance, invite, coupon wallet and point components`.

### Task R4: `AdminRoles` 위계 (react, 소) — C-A 만, K9 와 나란히

**Files:** Modify `packages/auth/src/admin/{rolesApi.ts,AdminRoles.tsx,AdminRoles.stories.tsx,rolesErrors.ts,rolesLabels.ts}` · `packages/api-client/src/errorCodes.ts`(AUTHZ 셋) · Create `packages/auth/src/admin/roleIncludes.ts`(`wouldCycle(roles, name, candidate): boolean` · `inheritedFrom(roles, name): Map<permission, role>`) · Modify `apps/sample/e2e/authzRoles.e2e.ts`(한 걸음).

- [ ] **실패 시험**: `roleIncludes.test.ts` — `wouldCycle` A→B 있을 때 B 에 A 후보 → true, 자기 자신 → true; `inheritedFrom` 이 사슬의 첫 출처를 돌려줌. `rolesErrors.test.ts` — 세 코드 문구. 스토리 `play`: 후보 비활성 이유 보임, 물려받은 권한 체크 · 잠금 · 「CS 에서 물려받음」, `PUT` 본문에 `includes`. e2e 한 걸음: STAFF ⊃ CS 를 만들고 STAFF 를 받은 user 가 결제 메뉴를 본다(K9 합친 뒤 지휘가 돌린다).
- [ ] 실패 확인 → 구현 → 통과:
```bash
pnpm --filter @skeleton/auth test -- src/admin
pnpm --filter @skeleton/auth typecheck
pnpm test:stories -- packages/auth/src/admin
```
- [ ] 커밋 `feat(auth/admin): role includes in the roles screen`.

---

## 묶음 2 — 보상 모듈 · 이벤트 · 단건 결제 (모두 K2 위, 서로 나란히)

### Task K3: `point` / `point-jdbc` (kotlin, 중) — 선행 K2

**Files:** Create `modules/point/`(`PointWallet` · `PointRewardIssuer` · `PointExpiryJob`(`scheduler` 관례) · `PointPermissions` · `PointErasure` · `web/PointController` · `web/PointAdminController` · 자동 설정), `modules/point-jdbc/`(저장소 + 마이그레이션 `point_balances` · `point_entries`, 스펙 §3.4) + 문서 · 카탈로그.

**Interfaces — Produces:** `interface PointWallet { fun balance(accountId: String): PointBalance; fun earn(accountId: String, amount: Long, expiresAt: Instant?, sourceType: String, sourceRef: String): PointEntry?; fun spend(accountId: String, amount: Long, sourceType: String, sourceRef: String): PointEntry; fun adjust(accountId: String, delta: Long, reason: String, expiresAt: Instant?, operatorId: String): PointEntry; fun expireDue(now: Instant): Int }` — `earn` 은 `(sourceType, sourceRef)` 로 멱등(null = 이미). `PointRewardIssuer.type = "point"`, params `amount` · `expiresInDays?`.

- [ ] **실패 시험** `modules/point-jdbc/src/dbTest/…/PointWalletDbTest`: 적립 100(10일 뒤 소멸) · 100(소멸 없음) 뒤 150 차감 → 소멸 임박 묶음부터(남은 0 · 50); 잔액 초과 차감 · 조정 → `POINT.INSUFFICIENT`, 잔액 불변; 같은 `(GRANT, g1)` 적립 두 번 → 한 줄; 동시 차감 둘(잔액 100, 각 80) → 하나만; `expireDue` 가 남은 양만 `EXPIRE` 줄로, 두 번 돌려도 한 번. `modules/point/src/test/…/PointAdminWebTest`: 권한 403 · 404 스위치 · 조정이 `OperatorAuditLog` `point.adjusted`.
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:point:test :modules:point-jdbc:postgresTest :modules:point-jdbc:mysqlTest
./gradlew :modules:platform:test --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*MigrationLockTest'
```
- [ ] 커밋 `feat(point): balances, FIFO-by-expiry ledger, reward issuer`.

### Task K4: `coupon` / `coupon-jdbc` (kotlin, 대) — 선행 K2

**Files:** Create `modules/coupon/`(`CouponDefinitions` · `CouponIssuance` · `CouponCodes` · `CouponRewardIssuer` · `CouponDiscountSource` · `CouponExpiry` · `CouponPermissions` · `CouponErasure` · `web/CouponController` · `web/CouponAdminController` · 자동 설정), `modules/coupon-jdbc/`(마이그레이션 스펙 §3.3 네 표) + 문서 · 카탈로그.

**Interfaces — Produces:** `CouponIssuance.issue(definitionId: String, accountId: String, sourceType: CouponSource, sourceRef: String): Coupon?`(정의 줄 잠금 · 한도 · 계정당 한도) · `CouponCodes.redeem(accountId: String, code: String): Coupon` · `CouponRewardIssuer.type = "coupon"`, params `definitionId` · `CouponDiscountSource.kind = "coupon"`, `order = 100` — `reserve` 는 `AVAILABLE → RESERVED(holder)`, `confirm` 은 `RESERVED(holder) → USED` · 내 것이 아니고 `AVAILABLE` 이면 잡아서 `USED` · 남이 쥐었으면 `CONFLICT`, `release`/`restore` 는 holder 가 맞을 때만 `AVAILABLE`(만료 지났으면 `EXPIRED`).

- [ ] **실패 시험**
  - `modules/coupon/src/test/…/CouponStagesValidationTest`: 단계 0개 · 6개 · `null` 이 마지막이 아님 · `PERCENT` 0 또는 101 · 통화 없는 `FIXED` → 400 `COUPON.DEFINITION_INVALID`.
  - `modules/coupon-jdbc/src/dbTest/…/CouponLifecycleDbTest`: 같은 쿠폰 `reserve` 동시 둘 → 하나만; `release` 후 다시 `reserve` 가능; holder 가 다른 `release` 는 무시; 만료 지난 쿠폰 `restore` → `EXPIRED`; 늦은 `confirm` 세 갈래(`CONFIRMED` · `ALREADY` · `CONFLICT`).
  - `…/CouponIssueDbTest`: `issue_limit 2` 에 동시 발급 셋 → 둘; 계정당 1 에 같은 계정 두 번 → 한 장; 같은 `(GRANT, g1)` → 한 장; 공용 코드 같은 계정 두 번 → `COUPON.CODE_USED`, 한도 다 차면 `CODE_EXHAUSTED`.
  - `modules/coupon/src/test/…/CouponDiscountSourceTest`: 범위 밖 itemType · 플랜, `min_amount` 미달, 통화 다른 `FIXED`, `firstPurchaseOnly` + `firstPurchase=false` → `NOT_APPLICABLE`; 남의 쿠폰 · 사용된 쿠폰 → `UNAVAILABLE`.
  - `…/CouponAdminWebTest`: 권한 · 스위치 · 「무료 이용권」 틀이 `[FREE×1]` · 발급이 `OperatorAuditLog` `coupon.issued`.
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:coupon:test :modules:coupon-jdbc:postgresTest :modules:coupon-jdbc:mysqlTest
./gradlew :modules:platform:test --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*MigrationLockTest'
```
- [ ] 커밋 `feat(coupon): stage-list definitions, wallet, codes, discount source`.

### Task K5: `campaign` / `campaign-jdbc` (kotlin, 대) — 선행 K2

**Files:** Create `modules/campaign/`(`CampaignKind` · `AttendanceKind` · `ReferralKind` · `Campaigns` · `AttendanceService` · `ReferralService` · `Referrals`(앱용) · `CampaignZone` · `CampaignPermissions` · `CampaignErasure` · `web/CampaignController` · `web/ReferralController` · `web/CampaignAdminController` · 자동 설정), `modules/campaign-jdbc/`(스펙 §3.1 네 표) + 문서 · 카탈로그.

**Interfaces — Consumes:** `RewardGrants.record` · `RewardIssuer.validate`(설정 저장 때, 빈 목록 `ObjectProvider<List<RewardIssuer>>` — 모르는 타입 400) · `AccountFacts`(없으면 새 계정 판정 생략).
**Produces:** `interface CampaignKind { val kind: String; fun validate(config: Map<String, Any?>, issuers: Map<String, RewardIssuer>) }` · `AttendanceService.checkIn(accountId: String, campaignId: String): CheckInResult` · `ReferralService.claim(accountId: String, code: String)` · `fun interface Referrals { fun qualify(inviteeId: String) }`. `source_key` 꼴은 스펙 §3.2.

- [ ] **실패 시험**
  - `modules/campaign-jdbc/src/dbTest/…/AttendanceDbTest`(`TimeProvider` 대역 · zone `Asia/Seoul`): 14:59:59Z 와 15:00:00Z 는 다른 날; 같은 순간 두 요청 → 한 줄 · 지급 건 1; 누적 3일 달성 보상 1회(4일째 없음); 연속 끊기면 연속 달성 다시 셈; zone `America/New_York` 서머타임 끝나는 날 하루 한 번; 꺼진 · 끝난 이벤트 409.
  - `…/ReferralDbTest`: 자기 코드 · 두 번째 claim · 맞초대 · 가입 8일 지난 계정(`AccountFacts` 대역) → `REFERRAL_INVALID` + 이유; `CLAIM` → 양쪽 지급 건, `SIGNAL` → 초대받은 쪽만, `qualify` 뒤 초대자 1건(두 번 불러도 1건); `inviterCap 2` 에 세 번째는 관계만 · 지급 없음.
  - `modules/campaign/src/test/…/CampaignTransactionBoundaryTest`: 지급기 빈 없이 출석 → 커밋 · 지급 건 `PENDING`(쿠폰 · 포인트 표를 안 만진다 — 그 모듈이 클래스패스에 없다).
  - `…/CampaignAdminWebTest`: 권한 · 스위치 · 모르는 보상 타입 400 · `enabled` 토글이 `OperatorAuditLog` `campaign.updated`.
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:campaign:test :modules:campaign-jdbc:postgresTest :modules:campaign-jdbc:mysqlTest
./gradlew :modules:platform:test --tests '*ModuleDocumentationTest' --tests '*CapabilitiesCatalogTest' --tests '*MigrationLockTest'
```
- [ ] 커밋 `feat(campaign): attendance and referral campaigns recording reward grants`.

### Task K6: `payment` 단건 할인 · 0원 · 결제 면제 (kotlin, 대) — 선행 K2

**Files:**
- Modify `modules/payment/…/PaymentOrders.kt`(`create` 의 할인 · 0원 · 면제 갈래, `quote`), `PaymentLedgerModel.kt`(`PaymentOrder.originalAmount` · `discountAmount`, `PaymentEventType.EXEMPTED`, `PaymentReviewReason.DISCOUNT_CONFLICT`), `PaymentLedgerStore.kt`(할인 줄 포트), `PaymentLedgerWriter.kt`(끝 상태 전이에서 `release`), `PaymentFulfillment.kt`(`confirm` 을 리스너 앞에), `RefundLines.kt`(`REFUNDED` 에서 `restore`), `PaymentRefunds.kt`(0원 · 면제 거절), `PaymentPermissions.kt`(`EXEMPT`), `web/PaymentOrderController.kt` · `web/PaymentRequests.kt` · `web/PaymentResponses.kt` · `web/PaymentAdminController.kt`(필터)
- Create `modules/payment/…/PaymentDiscounts.kt`(수단 모으기 · 순서 · `reserve`/`confirm`/`release`/`restore`) · `PaymentExemption.kt`
- Modify `modules/payment-jdbc/…`(`payment_orders` 두 칸 · `payment_order_discounts` 마이그레이션 · 저장소 · `PaymentDashboard.kt` 에 `provider <> 'exempt'`)
- 결제사 모듈의 최소 금액: `PaymentCheckoutGateway.minimumAmount(currency): Long?`(기본 null) — `payment-toss` · `-kakaopay` · `-naverpay` 값은 각 문서에서(출처를 KDoc 에)
- Docs: `docs/modules/payment.md` 「할인 · 0원 · 결제 면제」 절 · `docs/payment-http-contract.md`(C-P)

**Interfaces — Produces (C-P):** `CreateOrderCommand.discounts: List<DiscountClaim> = emptyList()` · `PaymentOrders.quote(command: QuoteCommand): OrderQuote` · `const val FREE_PROVIDER = "free"`, `EXEMPT_PROVIDER = "exempt"`(`PaymentOrders` companion) · `class PaymentExemption { fun isExempt(authentication: Authentication): Boolean; fun isExempt(accountId: String): Boolean }` · `PaymentOrder.settlement: PaymentSettlement`(파생: provider → `PROVIDER` · `FREE` · `EXEMPT`).

- [ ] **실패 시험**(`modules/payment/src/test/…`, 기존 `OrderCreateTest` 꼴의 대역 저장소 · 결제사)
  - `OrderDiscountTest`: 10,000 + 30% 쿠폰 → 결제사에 7,000, 줄 `amount 7000 · originalAmount 10000 · discountAmount 3000`, 할인 줄 `RESERVED`; 미리보기와 주문 금액 같음; 같은 `Idempotency-Key` 재전송 → 같은 줄, `reserve` 1회; 할인 수단 빈 없음 + `discounts` → `DISCOUNT_NOT_APPLICABLE`; 페이들 경로 → `DISCOUNT_NOT_SUPPORTED`; 0 < 최종가 < 최소 금액 → `DISCOUNT_BELOW_MINIMUM`.
  - `OrderDiscountLifecycleTest`: PAID → `confirm` 1회 · 리스너보다 먼저(리스너가 던지면 할인 줄도 되돌려지고 재시도에 1회); 만료 → `release`; 거절 → `release`; 만료 뒤 늦은 PAID + `CONFLICT` → 주문 `DISCOUNT_CONFLICT` 표시 · 리스너는 그대로 1회; 전액 환불 → `restore`; 부분 환불 → 없음; 지급 전 전액 환불 → `restore`.
  - `FreeOrderTest`: 100% 쿠폰 → 결제사 호출 0 · `provider free` · `PENDING→PAID` 이력 · 만료 작업 없음 · `onPaid` 1회 · 환불 요청 `NOTHING_TO_REFUND`.
  - `ExemptOrderTest`: `payment.exempt` 있는 호출자 → 결제사 0 · `provider exempt` · `originalAmount` = 카탈로그 가격 · `amount 0` · 할인 줄 없음 · `EXEMPTED` 이력(행위자 = 계정) · `onPaid` 1회 · 쿠폰을 같이 보내도 `reserve` 0 · 운영자 환불 · 사용자 환불 모두 `NOTHING_TO_REFUND`; 권한 회수 뒤 다음 주문은 결제창; `first_purchase_only` 판정에서 면제 주문은 「결제한 적」이 아니다; 전권(`*`)은 배정 없이 면제.
  - `modules/payment-jdbc/src/dbTest/…/OrderDiscountRowsDbTest`: 두 칸 · 할인 줄 왕복, 옛 줄은 `originalAmount null · discountAmount 0`. `…/DashboardRevenueTest` 에 한 건: 면제 주문은 순매출 · 건수 · 퍼널에 없음, 쿠폰 0원 주문은 건수 1 · 매출 0.
  - 기존 `PaymentOrderTransitionsTest` 불변(새 전이 없음).
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:payment:test --tests '*OrderDiscount*' --tests '*FreeOrderTest' --tests '*ExemptOrderTest' --tests '*OrderCreateTest' --tests '*FulfillmentTest' --tests '*RefundTest' --tests '*PaymentOrderTransitionsTest' --tests '*OrderReopenTest'
./gradlew :modules:payment-jdbc:postgresTest :modules:payment-jdbc:mysqlTest --tests '*OrderDiscountRows*' --tests '*DashboardRevenue*'
./gradlew :modules:payment-toss:test :modules:payment-kakaopay:test :modules:payment-naverpay:test --tests '*Minimum*'
./gradlew :modules:platform:test --tests '*MigrationLockTest' --tests '*ModuleDocumentationTest'
```
- [ ] 커밋 `feat(payment): order discounts, zero-amount and exempt settlement`.

### Task R2: `@skeleton/payment` 할인 표시 (react, 중) — 선행 R1(꼴만), C-P

**Files:** Modify `packages/payment/src/{types.ts,paymentApi.ts,ledgerTypes.ts,OrderSummary.tsx,PaymentResult.tsx,OrderList.tsx,MySubscriptionCard.tsx,labels.*.ts,errors.ts}` · `packages/payment/src/admin/{AdminOrderTable.tsx,AdminOrderPanel.tsx,types.ts}`(「결제 면제」 · 「무료(쿠폰)」 배지 · 필터 · 환불 단추 숨김) · Create `packages/payment/src/discount.ts`(`settlementOf(order)` · `discountLines(order, labels)`) · `packages/api-client/src/errorCodes.ts`(`PAYMENT.DISCOUNT_*` · `NOTHING_TO_REFUND`).

**Interfaces — Produces:** `CreateOrderInput.discounts?: { type: 'coupon'; ref: string }[]` · `PaymentApi.quote(input): Promise<OrderQuote>` · `useQuote(input, { enabled })` · `Settlement = 'PROVIDER' | 'FREE' | 'EXEMPT'`.

- [ ] **실패 시험**: `discount.test.ts` — 원가 · 할인 · 최종가 줄, `EXEMPT` 는 할인 줄 없이 「결제 면제」; `paymentApi.test.ts` — quote 본문 · `discounts`; `checkout.test.ts` 에 한 건 — `settlement FREE|EXEMPT` 응답이면 결제창을 열지 않고 결과로; `adminOrderTable.test.tsx` — 면제 줄 배지 · 환불 단추 없음; 스토리 `play`: `OrderSummary` 할인 줄, `MySubscriptionCard` 「다음 결제 3,450원(할인 2회 남음)」.
- [ ] 실패 확인 → 구현 → 통과:
```bash
pnpm --filter @skeleton/payment test
pnpm --filter @skeleton/payment typecheck
pnpm test:stories -- packages/payment
```
- [ ] 커밋 `feat(payment): discounts, free and exempt settlement in order views`.

---

## 묶음 3 — 구독 할인 · 운영 화면

### Task K7: `payment` 구독 할인 (kotlin, 중~대) — 선행 K6(같은 브랜치 위), K1(카카오페이 갈래)

**Files:** Modify `PaymentSubscriptions.kt`(`start(…, discounts)` · `prepareBilling` · `activate` · `activateByFirstCharge` 금액 · 0원 첫 기간), `SubscriptionLifecycle.kt`(`onOrderPaid` 에서 회차 · `end` 에서 할인 끝), `SubscriptionRenewTaskHandler.kt`(갱신 금액 · 0원 갱신), `SubscriptionPlanChanges.kt`(차액 할인 없음), `PaymentSubscriptionModel.kt`, `web/PaymentSubscriptionController.kt` · 응답 · Create `SubscriptionDiscounts.kt` · `payment-jdbc` 마이그레이션 `payment_subscription_discounts` · `_stages` + 저장소. 문서 `docs/modules/payment.md` 「구독 할인」.

**Interfaces — Produces:** `class SubscriptionDiscounts { fun attach(tx, subscription, offer: DiscountOffer, kind: String); fun amountFor(subscription: PaymentSubscription): PaymentAmount; fun onPaid(tx, subscription, order: PaymentOrder); fun end(tx, subscriptionId: String); fun view(subscriptionId): SubscriptionDiscountView? }` — 회차 = `periods_used`, 할인이 붙은 구독 주문(`INITIAL` · `RENEWAL`)이 PAID 될 때만 +1.

- [ ] **실패 시험**(`modules/payment/src/test/…`, 기존 `SubscriptionRenewalTest` 꼴)
  - `SubscriptionDiscountScheduleTest`: 9,900 플랜 + `[FREE×1, PERCENT50×3]` → 1~5번째 결제 0 · 4,950 · 4,950 · 4,950 · 9,900; 0원 기간은 결제사 호출 0 · 기간은 넘어감; 갱신 거절 뒤 재시도 금액 같음 · 회차 그대로; `[PERCENT10×null]` 은 열 번째도 10%.
  - `SubscriptionDiscountStartTest`: 토스 첫 기간 무료 → 빌링키 발급 · 승인 호출 0 · `ACTIVE`; `BillingPrepareRequest.amount` = 첫 결제액; 카카오페이는 K1 결과대로(된다: `RegisterWithChargeRequest.amount` 0 / 안 된다: `DISCOUNT_NOT_SUPPORTED`); 페이들 구독 → `DISCOUNT_NOT_SUPPORTED`; 범위에 `SUBSCRIPTION` 없는 쿠폰 → `NOT_APPLICABLE`.
  - `SubscriptionDiscountChangeTest`: 회차 2 쓴 뒤 플랜 변경 → 차액 주문에 할인 없음 · 회차 그대로 · 다음 갱신은 새 금액 × 남은 단계; `FULL_RESTART` 뒤에도 이어짐; 정액이 새 금액보다 크면 새 금액까지.
  - `SubscriptionDiscountEndTest`: 해지 예약 중 회차 보존 · `ENDED` 에서 할인 `ENDED`; 첫 주문 전액 환불(철회) → 쿠폰 `restore`; 갱신 기간 환불 → 회차 그대로; 중도 해지 환불 금액 = 그 주문 실제 결제액 기준(기존 `SubscriptionCancelNowTest` 에 할인 구독 한 건).
  - `modules/payment-jdbc/src/dbTest/…/SubscriptionDiscountRowsDbTest`: 단계 복사 왕복 · 정의를 바꿔도 복사본 불변.
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :modules:payment:test --tests '*SubscriptionDiscount*' --tests '*SubscriptionRenewalTest' --tests '*SubscriptionActivationTest' --tests '*SubscriptionPlanChangeTest' --tests '*SubscriptionCancelNowTest' --tests '*RegistrationSubscriptionTest'
./gradlew :modules:payment-jdbc:postgresTest :modules:payment-jdbc:mysqlTest --tests '*SubscriptionDiscountRows*'
./gradlew :modules:payment-kakaopay:test :modules:platform:test --tests '*MigrationLockTest'
```
- [ ] 커밋 `feat(payment): subscription discount schedules (free periods, repeating, forever)`.

### Task R3: `@skeleton/rewards/admin` + 견본 화면 + e2e (react, 대) — 선행 R1 · R2, C-ADM; e2e 만 K8 합친 뒤

**Files:** Create `packages/rewards/src/admin/{AdminCampaigns,AdminCouponDefinitions,StageEditor,AdminPoints,AdminRewardGrants,adminApi,labels}.ts(x)` + 스토리 · Modify `apps/sample/src/routes/routes.tsx`(`/rewards` · `/rewards/coupons` · `/admin/campaigns` · `/admin/coupons` · `/admin/points` · `/admin/reward-grants`, `RequirePermission`) · `apps/sample/src/layouts/*`(메뉴 · 알림 링크) · 응원 결제 화면에 `CouponPicker` 연결 · `main.tsx`(`useReferralCapture`) · i18n · Create `apps/sample/e2e/rewards.e2e.ts` · `capabilities.json` · `docs/ui-catalog.md` · CHANGELOG `[Unreleased]`(react 전체 블록).

- [ ] **실패 시험**: `StageEditor.test.tsx` — 단계 더하기 · 「영원」은 마지막만 · 6번째 단계 단추 비활성 · 「무료 이용권」 · 「n달 무료」 틀이 단계를 채움; `adminApi.test.ts` — C-ADM 경로; 스토리 `play`: 이벤트 켜고 끄기 스위치 · 포인트 조정 `INSUFFICIENT` 문구 · 실패 지급 건 「다시 지급」; `routes.test.tsx` 에 새 경로 권한 표.
- [ ] e2e(`rewards.e2e.ts`, 스펙 §10 의 1 · 2 · 3 · 6): 출석 → 알림 종 · 잔액; 쿠폰 골라 응원 결제(토스 가짜) → 결과 할인 줄 · 쿠폰 「사용함」; 무료 이용권 → 결제창 없이 완료; admin 이벤트 끄기 → 카드 사라짐 · `payment.exempt` 역할 → 「결제 면제」 완료 · 회수 → 결제창.
- [ ] 실패 확인 → 구현 → 통과:
```bash
pnpm --filter @skeleton/rewards --filter sample test
pnpm --filter @skeleton/rewards --filter sample typecheck
pnpm test:stories -- packages/rewards/src/admin
pnpm vitest run tests/stories.test.ts tests/capabilities.test.ts
```
- [ ] 커밋 `feat(rewards): admin screens and sample rewards flows`. e2e 는 K8 합친 뒤 지휘가 `pnpm e2e:sample -- rewards`.

---

## 묶음 4 — 조립 · 마무리

### Task K8: 견본 앱 조립 · 통합 시험 · 문서 (kotlin, 소~중) — 선행 K3~K7 · K9 합침

**Files:** Modify `apps/sample/build.gradle.kts`(여덟 모듈) · `apps/sample/src/main/resources/application.yml`(`admin.enabled` 넷 · `campaign.zone` · 씨앗은 local 프로필만) · Create `apps/sample/src/main/kotlin/…/sample/rewards/RewardsSeed.kt` · Modify `cheer/CheerOrderListener.kt`(`onPaid` 끝에 `referrals?.qualify(order.accountId)` — 결제 리스너 빈은 하나뿐이라 같은 리스너에서, 면제 주문은 부르지 않는다) · `docs/sample.md` · `docs/new-project-recipe.md`(조각 하나) · `scripts/new-project.sh` 조합 표 · `CHANGELOG.md` `[Unreleased]`(kotlin 전체 블록: 새 모듈 여덟, 결제 할인 · 0원 · 면제, 구독 할인, 위계, Breaking 없음 확인).

- [ ] **실패 시험** `apps/sample/src/test/…/rewards/RewardsFlowIntegrationTest`(진짜 PostgreSQL · 보안 체인 · 큐 일꾼 동기 실행): 출석 → 지급 건 → 포인트 10 · 받은편지함 1건; 초대 claim → 초대받은 쿠폰, 그 계정 첫 응원 결제 → 초대자 500포인트; 쿠폰 응원 결제(가짜 토스) → 쿠폰 `USED`, 만료 → `AVAILABLE`, 전액 환불 → `AVAILABLE`; 면제 역할 → 응원 줄 생김 · 주문 `exempt`. `MySqlMigrationUpgradeTest` · `MigrationUpgradeIntegrationTest` 통과.
- [ ] 모듈 단독 조합 셋을 `scripts/test-new-project.sh` 에 더한다(`12-coupon` = `--modules coupon,coupon-jdbc` · `13-point` = `point,point-jdbc` · `14-campaign` = `campaign,campaign-jdbc,reward,reward-jdbc`, 머리 주석과 `build_composition` 줄 — `10-authz` 꼴). 돌리는 것은 지휘의 `--full`.
- [ ] 실패 확인 → 구현 → 통과:
```bash
./gradlew :apps:sample:test --tests '*RewardsFlowIntegrationTest' --tests '*MigrationUpgrade*' --tests '*ApplicationTests*'
./gradlew :apps:api:test :apps:workbench:test --tests '*ApplicationTests*'
```
- [ ] 커밋 `feat(sample): rewards, coupons and exemption wired into the sample app`.

## 끝 검사 (지휘)

- kotlin: K2 → K9 → K3 → K4 → K5 → K6 → K7 → K8 순으로 `rewards` 에 합치고 `perl scripts/build-capabilities.pl` · `perl scripts/migrations-lock.pl --check` → `kverify`(`test-new-project.sh --full` 의 `12-coupon` · `13-point` · `14-campaign` 포함) 한 번.
- react: R1 → R4 → R2 → R3 합친 뒤 `pnpm test` · `typecheck` · `lint` · `test:stories` · `e2e:sample` 한 번.
- 리뷰: kotlin 끝 1회 · react 끝 1회(`code-reviewer`, Review Focus 위주, 시험 재실행 안 시킴). 확인(`qa`) 1회 — 스펙 §10 을 진짜 화면으로(화면당 캡처 2장까지).

## 예산

| 묶음 | 조각 | 동시 | 벽시계(대략) |
|---|---|---|---|
| 1 | K1 · K2 · K9 · R1 · R4 | 최대 3(K2 ∥ K9 ∥ R1, K1 · R4 는 짧아 그 뒤) | 4h |
| 2 | K3 · K4 · K5 · K6 · R2 | 3(kotlin 2 + react 1) | 7h |
| 3 | K7 · R3 | 2 | 4h |
| 4 | K8 · 끝 검사 · 리뷰 2 · QA 1 | 1 | 4h |

- developer 13회(K1 은 조사) + 리뷰 2 · QA 1. 동시 최대 3명.

## 정해야 할 것

없음 — 스펙 결정 1~10 으로 닫혔다.
