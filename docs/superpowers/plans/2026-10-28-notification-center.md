# 알림센터 보강 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 구현 · 리뷰는 역할(`developer` · `code-reviewer`)로 부른다.

**Goal:** 찍은 앱이 설정 몇 줄로 묶이는 알림 · 클릭 이동 · 종류별 끄기 · 안 읽은 수 전용 조회 · 보관 정리 · SSE 심장박동을 얻는다. 지금 발행 코드와 기본 동작은 그대로다.

**Architecture:** 묶기 · 이동은 `NotificationEvent` 의 두 칸(`collapseKey` · `link`)이 정하고, 저장소가 안 읽은 같은 열쇠 줄에 원자적으로 접는다(유니크 `(recipient_id, unread_collapse_key)`). broker 는 끈 사람을 빼고 저장한 뒤 구독자 전달을 커밋 뒤로 미룬다. 정리 · 심장박동 · 끌 수 있는 종류는 기본 꺼짐, 견본 앱이 켠다.

**Tech Stack:** Kotlin · Spring Boot · NamedParameterJdbcTemplate · Flyway(PostgreSQL · MySQL) · Testcontainers / React · TypeScript · TanStack Query · Storybook · Vitest.

**Spec:** `docs/superpowers/specs/2026-10-28-notification-center-design.md`. 계획과 스펙이 어긋나면 스펙이 이긴다 — 어긋남은 지휘에 알린다. 조각 키는 스펙 §11 표와 같다.

## 형 결정 (2026-10-28, 원문)

1. 묶음 열쇠: collapseKey + collapsedCount
2. 보관 정리: 기본 꺼짐, 켜면 max-age 기본 90일. 「스켈레톤 기본은 중립, 앱이 override」
3. 커밋 뒤 전달: 구독자 전달만 커밋 뒤, 저장은 호출자 트랜잭션 안
4. SSE 심장박동: 기본 꺼짐, 견본 앱이 15초로 켠다
5. link: 앱 안 상대 경로만, '/' 로 시작하고 '//' 는 금지
6. 수신 끄기: type 단위 + 패키지 NotificationPreferences 부품 + 견본 설정 화면
7. 비용을 줄이려고 SSE 심장박동은 N2 에 합친다 — 조각 3개(N1 → N2, 나란히 R1)

## Global Constraints

- 기준: kotlin `main`(`a1da77ca`), react `main`(`891f7ac`). 워크트리 · 브랜치 이름은 지휘가 정한다(예: 묶음 브랜치 `notification-center`, 조각 `notification-center/<키>`). **커밋은 조각 브랜치에만, 합치기 · push 는 지휘.** `Co-Authored-By` 를 붙이지 않는다.
- 시험은 **바뀐 범위만**(각 작업의 명령). 전체 스위트 · `kverify` · react 전체(`pnpm test` · `typecheck` · `lint` · `test:stories`) · e2e 는 지휘가 묶음 끝에 한 번. 같은 워크트리에서 Gradle 둘을 동시에 돌리지 않는다.
- 모듈 규칙: 빈은 AutoConfiguration `@Bean` + `@ConditionalOnMissingBean`, `@Component` 금지, 컨트롤러는 자동 설정이 등록. 새 `@ConfigurationProperties` 접두사는 `capabilities.json` 의 `config.prefixes` · `docs/config/modules/<m>.yml` · 모듈 문서 표에 같이 넣는다(`CapabilitiesCatalogTest` · `ModuleConfigSnippetsTest` 가 막는다). `perl scripts/build-capabilities.pl` 로 `llms.txt` · `docs/capabilities.md` 를 다시 만든다(손으로 고치지 않는다).
- 마이그레이션: `./gradlew newMigration -Pname=…` 로 만들고 `modules/notification-jdbc/src/main/resources/db/migration/{postgresql,mysql}/` 에 두 방언 짝, `perl scripts/migrations-lock.pl`. 잠긴 `V20260617010000__notification_inbox.sql` 은 고치지 않는다.
- 시각은 `Instant` + `SqlDialect.instantParam` / `readInstant`. 지금 시각은 `TimeProvider`(있으면) 또는 `Instant.now()` — 주변 코드 관례를 따른다. 프로젝트 모드는 프로젝트마다.
- 공개 레포 — 특정 서비스 · 도메인 · 이메일 흔적 금지(문서에는 「앞선 사용처」). KDoc 안에 `/**` 금지.
- react: 앱 코드는 날 요소 · 날값 금지(`@skeleton/ui` · 의미 토큰), 패키지는 `import.meta.env` 를 읽지 않고 다른 패키지 경로를 import 하지 않는다, 문구는 prop(기본 영어), SSR 안전, 눌리는 요소는 눌리게 보이게(테두리 · 채움 · 밑줄 링크).

## Review Focus

- **묶기 동시성 · 멱등**: 같은 열쇠 동시 두 발행 → 한 줄 `collapsedCount` 2(PG · MySQL 둘 다). 같은 이벤트 id 재발행 → 수 그대로. 읽은 뒤 → 새 줄. 묶인 줄의 `eventId` 는 처음 것이라 읽음 처리가 된다.
- **끄기가 전체 공지로 새지 않기**: 받는 사람을 모두 끈 알림이 `recipientIds` 빈 이벤트로 바뀌어 모두에게 가지 않는다. 목록에 없는 종류의 끄기 줄은 무시.
- **커밋 뒤 전달**: 롤백된 트랜잭션의 알림이 SSE 로 나가지 않는다. 트랜잭션 밖 발행은 지금처럼 바로.
- **기본값 그대로**: 새 설정을 안 건드린 앱의 저장 · 전달 · 응답(칸 추가 외) · 스레드 수가 같다.

---

## 조각 사이 계약

스펙 §5 표 그대로(HTTP · SSE · 패키지 표면). R1 은 이것으로 가짜 API(`packages/notifications/src/stories/fakeInbox.ts`, workbench 데모)를 먼저 맞춘다. kotlin 조각이 모양을 바꾸면 보고에 적고 지휘가 R1 에 넘긴다.

---

## N1 — 받은편지함: 묶기 · 링크 · 지우기 · 안 읽은 수 · 보관 정리 · 커밋 뒤 전달 (kotlin)

**Files:**
- Modify: `modules/notification/src/main/kotlin/dev/sumin/skeleton/notification/NotificationEvent.kt` — `link` · `collapseKey` + `init` 검사(스펙 §4.1)
- Modify: `.../notification/NotificationInbox.kt` — `NotificationInboxRecord.collapsedCount`, 계약 넷(§4.2, `countUnread` 만 기본 구현), 인메모리(`@Synchronized` 묶기 · 프로젝트 키) · Noop
- Modify: `.../notification/InMemoryNotificationBroker.kt` — 커밋 뒤 전달(§4.4)
- Create: `.../notification/NotificationInboxRetention.kt` · `NotificationRetentionProperties.kt`(`skeleton.notification.retention`)
- Modify: `.../notification/NotificationAutoConfiguration.kt` — retention 빈(`enabled=true` 일 때) + `SmartInitializingSingleton` 예약(`@Qualifier("skeletonTaskScheduler") ObjectProvider<TaskScheduler>`, 없으면 경고 — `BoardAutoConfiguration.boardPublicAttachmentSweep` 꼴), 프로젝트 모드 반복(`ObjectProvider<ProjectRegistry>`)
- Modify: `.../notification/web/NotificationInboxController.kt` — `GET /unread-count` → `NotificationUnreadCountResponse(count: Long)`, 줄 응답에 `link` · `collapsedCount`
- Modify: `modules/notification/build.gradle.kts` — `compileOnly("org.springframework:spring-tx")`, 시험용 `testImplementation`
- Modify: `modules/notification-jdbc/src/main/kotlin/dev/sumin/skeleton/notification/jdbc/JdbcNotificationInboxRepository.kt` — 새 칸 읽고 쓰기, 묶기 세 단계(§4.2), `markRead` · `markAllRead` 가 `unread_collapse_key = null`, 지우기 셋(`deleteOlderThan` 은 `select … limit 1000` → `delete … in (…)` 반복 — `JdbcJobRepository.deleteFinishedBefore` 꼴), `countUnread`
- Modify: `modules/persistence-jdbc/.../SqlDialect.kt` KDoc · `modules/db-postgresql/.../PostgresSqlDialect.kt` · `modules/db-mysql/.../MySqlSqlDialect.kt` — `insertIgnore(…, conflictColumns = emptyList())` = 아무 유니크 충돌(PG 대상 없는 `on conflict do nothing`, MySQL 은 `columns.first()` 로 noop)
- Create: `modules/notification-jdbc/src/main/resources/db/migration/{postgresql,mysql}/V<UTC>__notification_inbox_link_collapse.sql`(스펙 §6-1) + `migrations.lock`
- Modify: `docs/modules/notification.md` · `notification-jdbc.md` · `docs/config/modules/notification.yml` · `capabilities.json`(`skeleton.notification.retention`)

**Interfaces (다른 조각이 기댄다):**
```kotlin
data class NotificationEvent(…, val link: String? = null, val collapseKey: String? = null)
data class NotificationInboxRecord(val recipientId: String, val event: NotificationEvent, val readAt: Instant? = null, val collapsedCount: Int = 1)
interface NotificationInboxRepository {
    // save · findByRecipient · markRead · markAllRead 그대로 (save 는 collapseKey 면 접는다)
    fun countUnread(recipientId: String): Long = findByRecipient(recipientId, NotificationInboxQuery(size = 1, unreadOnly = true)).totalElements
    fun deleteOlderThan(cutoff: Instant): Int
    fun deleteByTopic(topic: String): Int
    fun deleteByRecipientAndTopic(recipientId: String, topic: String): Int
}
class NotificationInboxRetention(repository, properties, timeProvider) { fun run(now: Instant): Int }
```

- [ ] **Step 1: 시험 먼저** — `InMemoryNotificationInboxRepositoryTest`(묶기: 안 읽은 줄에 접힘 · 내용 새 것 · eventId 처음 것 · 수 2 / 읽은 뒤 새 줄 / 같은 id 재발행 수 그대로 / 프로젝트 분리; 지우기 셋 경계; `countUnread`), `InMemoryNotificationBrokerTest`(트랜잭션 동기화 켠 상태에서 롤백 → 구독자 0, 커밋 → 1, 밖 → 바로 — `TransactionSynchronizationManager.initSynchronization()` 으로 흉내), `NotificationEventTest`(`link` `//x` · `http://` · 1001자 거절, `collapseKey` 빈 글자 거절), `NotificationInboxWebTest`(`unread-count` 401 · 값, 줄에 `link` · `collapsedCount`), `NotificationInboxRetentionTest`(꺼짐이면 빈 없음, `run` 이 `now − max-age` 로 부름, 프로젝트마다).
- [ ] **Step 2: 실패 확인** — `./gradlew :modules:notification:test`
- [ ] **Step 3: notification 구현** → 같은 명령 통과
- [ ] **Step 4: DB 시험 먼저** — `src/dbTest/.../JdbcNotificationInboxRepositoryDbTest.kt` 에 묶기 넷(동시 두 스레드 → 한 줄 수 2: `CountDownLatch` 로 맞춰 각자 트랜잭션), 읽음 뒤 새 줄, `deleteOlderThan` 같은 시각 남김 · 2500줄 나눠 지움, 주제 지우기 둘, `countUnread`. 방언 `insertIgnore` 빈 목록 단위 시험(`PostgresSqlDialect` · `MySqlSqlDialect` 생성 SQL).
- [ ] **Step 5: 마이그레이션 + JDBC 구현** → `./gradlew newMigration -Pname=notification_inbox_link_collapse` 뒤 두 방언 작성, `perl scripts/migrations-lock.pl`
- [ ] **Step 6: 통과 확인** — `./gradlew :modules:notification-jdbc:postgresTest :modules:notification-jdbc:mysqlTest :modules:notification-jdbc:test :modules:db-postgresql:test :modules:db-mysql:test` (순서대로 한 명령)
- [ ] **Step 7: 문서 · 카탈로그** — 모듈 문서 표(설정 접두사 · 교체 지점), 설정 조각, `capabilities.json` 접두사, `perl scripts/build-capabilities.pl`, `./gradlew :modules:platform:test --tests '*CapabilitiesCatalogTest*' --tests '*ModuleConfigSnippetsTest*'`
- [ ] **Step 8: 커밋** — `feat(notification): collapse key, link, unread count, retention, after-commit delivery`

## N2 — 수신 끄기 · SSE 심장박동 · 보상 링크 · 견본 앱 · 문서 (kotlin, N1 뒤)

**Files (끄기):**
- Create: `modules/notification/src/main/kotlin/dev/sumin/skeleton/notification/NotificationMutes.kt` — `NotificationMuteRepository` + `InMemoryNotificationMuteRepository`(프로젝트 키), `NotificationPreferencesProperties`(`skeleton.notification.preferences.types: List<String> = emptyList()`)
- Modify: `InMemoryNotificationBroker.kt` — 끈 사람 빼기(스펙 §4.5 규칙 셋), `NotificationAutoConfiguration.kt` — 빈 · 인자
- Create: `.../notification/web/NotificationPreferencesController.kt`(또는 받은편지함 컨트롤러에 두 메서드 — 주변 크기 보고 정한다) — `GET /preferences` · `PUT /preferences/{type}`, 목록 밖 404(지금 `NotificationNotFoundException` 과 같은 방식), `NotificationInboxWebAutoConfiguration` 이 같은 스위치로 등록
- Create: `modules/notification-jdbc/.../JdbcNotificationMuteRepository.kt` + 자동 설정 빈, `V<UTC>__notification_mutes.sql` 두 방언 + lock
- Modify: `NotificationInboxErasure.kt` — `delete from notification_mutes where recipient_id = :a`

**Files (SSE 심장박동):**
- Modify: `modules/notification-sse/src/main/kotlin/dev/sumin/skeleton/notification/sse/NotificationSseProperties.kt` — `heartbeat: Duration? = null`(비움 = 꺼짐)
- Modify: `.../sse/NotificationSseService.kt` — 켜졌으면 `start()` 에서 데몬 `ScheduledExecutorService` 하나(이름 `notification-sse-heartbeat`), `open` 의 연결마다 `SseEmitter.event().comment("")`, 실패는 그 연결 `completeWithError`, `stop()` 에서 `shutdownNow`
- Modify: `docs/config/modules/notification-sse.yml` · `docs/modules/notification-sse.md`(Cloudflare 100초 · 짝 클라이언트 `idleTimeoutMs` 한 줄)

**Files (연결 · 문서):**
- Modify: `modules/reward/src/main/kotlin/dev/sumin/skeleton/reward/notification/RewardNotificationAutoConfiguration.kt` — `link = label.link`(payload 그대로)
- Modify: `apps/sample/.../notes/NoteNotifier.kt` — `link = "/notes/${note.id}"`(삭제 제외), `NOTE_UPDATED` 에 `collapseKey = "note-updated:${note.id}"`, 파일 맨 위 계약 주석 갱신
- Modify: `apps/sample/src/main/resources/application.yml` — 스펙 §7 견본 값(retention 켬 90d · preferences 넷 · `sse.heartbeat: 15s`)
- Modify: `docs/modules/notification.md` · `notification-jdbc.md`(끄기 · 커밋 뒤 전달 한 줄 · 「알림 실패가 업무를 실패시키면 안 되면 커밋 뒤에 부른다」), `docs/config/modules/notification.yml`, `capabilities.json`(`skeleton.notification.preferences`), `CHANGELOG.md`(Unreleased — 묶음 한 절, **Breaking**: `NotificationInboxRepository` 를 직접 구현한 앱은 지우기 셋을 구현해야 한다), `docs/superpowers/specs/2026-10-28-events-rewards-design.md` §4.6 의 빈틈 ⑤ ⑥ ⑦ 줄에 「알림센터 보강에서 해결」 꼬리
- Modify: `apps/sample/.../upgrade/MigrationUpgradeIntegrationTest.kt` · `MySqlMigrationUpgradeTest.kt` — 옛 줄이 `collapsedCount = 1` · `link = null` 로 읽히는 단정 한 줄(이미 받은편지함을 읽는 곳에)

- [ ] **Step 1: 끄기 시험 먼저** — `InMemoryNotificationBrokerTest`(목록 밖 종류 끄기 무시, 일부 끔 → 저장 · 전달 남은 사람만, 모두 끔 → 저장 0 · 전달 0, 전체 공지 그대로, 목록 비면 끄기 저장소를 부르지 않음), `NotificationPreferencesWebTest`(401, 목록 순서, PUT 켜고 끄기, 목록 밖 404, `inbox.enabled=false` 면 없음), `NoteNotifier` 단위(링크 · 열쇠), 보상 `RewardWorkerTest` 또는 알림기 단위(`link` 실림)
- [ ] **Step 2: 끄기 구현** → `./gradlew :modules:notification:test :modules:reward:test`
- [ ] **Step 3: DB** — `JdbcNotificationMuteRepositoryDbTest`(끄기 · 다시 켜기 · `mutedAmong` 여러 사람), `NotificationInboxErasureDbTest` 에 끄기 줄 지움 → `./gradlew :modules:notification-jdbc:postgresTest :modules:notification-jdbc:mysqlTest`
- [ ] **Step 4: 심장박동 시험 먼저** — `NotificationSseServiceTest`(꺼짐 → 스레드 없음 · 주석 없음, 켜짐 → 짧은 간격(50ms)으로 주석 도착, 보내기 실패 연결은 `open` 에서 빠짐, `stop` 뒤 실행기 종료), `NotificationSseAutoConfigurationTest`(바인딩) → `./gradlew :modules:notification-sse:test` 실패 확인 → 구현 → 통과
- [ ] **Step 5: 견본 앱** — yml · `NoteNotifier`, `./gradlew :apps:sample:test --tests '*MigrationUpgrade*' --tests '*Note*'`(MySQL 업그레이드 시험 이름은 그 클래스로)
- [ ] **Step 6: 문서 · 카탈로그 · CHANGELOG** → `perl scripts/build-capabilities.pl`, `./gradlew :modules:platform:test --tests '*CapabilitiesCatalogTest*' --tests '*ModuleConfigSnippetsTest*'`
- [ ] **Step 7: 커밋** — 의도마다 나눈다: `feat(notification): per-type mute` · `feat(notification-sse): optional heartbeat comments` · `feat(sample): notification links, collapse, mute and heartbeat wiring`(보상 link 포함)

## R1 — `@skeleton/notifications` 1.2.0 + 견본 앱 (react, 나란히)

**Files:**
- Modify: `packages/notifications/src/types.ts` — 스펙 §5 타입
- Modify: `src/notificationsApi.ts` — `unreadCount` · `preferences` · `setPreference`(`PUT {base}/preferences/{encodeURIComponent(type)}`, 본문 `{ muted }`)
- Modify: `src/queries.ts` — `unreadCountQuery` → `api.unreadCount()` 의 `count`, `notificationKeys.preferences()`, `preferencesQuery`
- Modify: `src/hooks.ts` — `usePreferences` · `useSetPreference`(성공 시 `setQueryData`)
- Modify: `src/inboxCache.ts` — `parseNotificationEvent` 가 `link` · `collapseKey` 읽기, `ingest` 의 묶음 분기(수 무효화)
- Modify: `src/NotificationList.tsx` · `.module.css` — `describe` · `onOpen` · `collapsedLabel`(링크는 `<a>` + 밑줄, 묶음 수는 `Badge`)
- Modify: `src/NotificationBell.tsx` — 같은 props 를 목록에 넘기고 `onOpen` 때 읽음(안 읽었으면) + 닫기
- Create: `src/NotificationPreferences.tsx`(+ 스토리) — `SwitchRow` 목록, 로딩 `Spinner`, 빈 목록이면 `EmptyState`
- Modify: `src/index.ts` · `src/stories/fakeInbox.ts`(새 메서드 · 링크 · 묶음 줄) · `NotificationList.stories.tsx` · `NotificationBell.stories.tsx` · `README.md`(공개 표면 표 · 「안 읽은 수는 전용 엔드포인트」로 고침) · `package.json` 1.2.0
- Modify: `apps/workbench/src/modules/demos/NotificationsDemo.tsx` — 가짜/실 API 새 메서드에 맞춤(타입 오류 없게)
- Modify: `apps/sample/src/layouts/RootLayout.tsx` — 종 `onOpen={(item) => item.link && navigate(item.link)}`, `apps/sample/src/notifications/useLiveNotifications.ts` — `idleTimeoutMs: 45_000`, `apps/sample/src/routes/SettingsPage.tsx` — 「알림」 구역에 `NotificationPreferences`(라벨 ko/en: 노트 수정 · 댓글 · 답글 · 보상), `apps/sample/src/i18n/{ko,en}.ts`
- Modify: `capabilities.json`(notifications 항목 요약 · 키워드에 끄기 · 링크), `CHANGELOG.md`

- [ ] **Step 1: 시험 먼저** — `notificationsApi.test.ts`(세 경로 · 메서드 · 본문 · 인코딩), `queries.test.ts`(`unreadCountQuery` 가 `unreadCount` 를 부름), `inboxCache.test.ts`(`collapseKey` 있는 이벤트 → 수 +1 아님 · 무효화, 없으면 지금처럼 +1, `link` 파싱), `ui.test.tsx`(링크 줄 `<a href>` · `onOpen` 없으면 링크 아님 · `describe` 가 제목을 바꿈 · 수 2 배지 · `NotificationPreferences` 마크업), 견본 `SettingsPage.test.tsx` 에 알림 구역 한 줄
- [ ] **Step 2: 실패 확인** — `pnpm --filter @skeleton/notifications test`
- [ ] **Step 3: 구현** → `pnpm --filter @skeleton/notifications test && pnpm --filter @skeleton/notifications typecheck`
- [ ] **Step 4: 견본 · workbench** → `pnpm --filter sample test -- SettingsPage RootLayout` · `pnpm --filter sample typecheck` · `pnpm --filter workbench typecheck`(패키지 이름은 각 `package.json` 의 `name` 으로)
- [ ] **Step 5: 카탈로그 · CHANGELOG** — 레포의 카탈로그 생성 명령(README · CLAUDE.md 의 것)으로 다시 만들기
- [ ] **Step 6: 커밋** — `feat(notifications): unread-count endpoint, links, collapsed rows, per-type mute`

## 지휘 — 묶음 끝 (한 번)

- [ ] N1 → N2 순서로 합치고 R1 을 합친다. kotlin 전체 스위트 · `kverify` 한 번, react `pnpm test` · `typecheck` · `lint` · `test:stories` 한 번.
- [ ] 리뷰 둘(kotlin · react) — Review Focus 네 줄을 넘긴다. 시험 재실행은 시키지 않는다.
- [ ] qa — 스펙 §9 e2e(견본 앱 두 레포 함께): 노트 두 번 수정 → 배지 1 · 수 2 → 줄 클릭 이동 · 읽음 → 끄기 → 수정해도 배지 그대로 → 켜기, `curl -N` SSE 15초 안 `:` 줄, `unread-count` 와 배지 일치. 캡처는 배지 · 목록 · 설정 세 장.
