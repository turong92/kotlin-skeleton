# 요청 라우팅(내 요청만 후보 · 로컬 서버로) 설계

날짜 2026-11-16. 대상: kotlin-skeleton main `2a068f66`. 이 문서는 설계만 담는다(코드는 고치지 않았다). 작업 목록: [plans/2026-11-16-request-routing.md](../plans/2026-11-16-request-routing.md).

형 결정(대화 요지): 요청 머리표(W3C `baggage`)로 **내 요청만** 바뀐 서버로 보낸다(Lyft · Signadot · mirrord 류). 머리표는 입구에서 손님 요청이면 지운다(직원만 표시 가능). 표시엔 이름만, 이름 → 주소는 서버 설정의 허용 목록. 운영 → 로컬(터널)은 깨진 유리: 기본 꺼짐 · 켤 때마다 시간 제한 · 감사 기록. **쓰기 권한은 준다**(읽기 전용 · 행 보안 없음). 후보에선 주기 작업이 같이 돌지 않게. 배포 레시피 · 서비스 메시 · 터널 도구 자체는 안 만든다.
추가 결정(2차): ① 로컬 창은 **관리 API + DB 표**(재시작 없이 켜고 끄기, 누가 · 왜 · 언제까지 기록, 최대 8시간 · 지나면 저절로 닫힘, 직원 권한, PostgreSQL · MySQL, 여러 인스턴스가 같은 값 — 짧은 캐시 허용). ② 비동기는 **후보 일꾼이 자기 표시가 붙은 작업 · 메시지만 집기**까지(작업 큐 집기 쿼리 · 마이그레이션, Kafka 별도 소비 그룹, 원래 일꾼은 표시 붙은 것을 안 집는다).

## 0. 왜

서버 여러 개(MSA)에서 하나만 고쳤을 때, 그 하나만 후보로 띄우거나 내 노트북에서 돌리고 **내 요청만** 그쪽으로 흘린다. 나머지 호출은 원래 서버가 받는다. 핫픽스 때 빌드 · 배포 없이 몇 초 단위로 고쳐 보고, 확인되면 그때 빌드 · 배포한다. 고친 코드가 HTTP 처리 안이든, 그 요청이 넣은 작업 · 메시지의 처리기 안이든 같은 방법으로 시험된다.

## 1. 범위

**1차**: 직원이 `baggage: route=orders:alice` 를 실어 보내면 → 입구가 직원인지 보고 표시를 받아들이고 → 모든 서비스가 공통 HTTP 클라이언트(`ExternalHttpClient`)로 부를 때 표시를 넘기고, 표시에 그 서비스 이름이 있으면 허용 목록의 주소로 바꿔 부른다 → 입구 서비스 자신이 대상이면 요청을 통째로 대상에 넘긴다(리버스 프록시) → 그 요청이 넣은 작업 · Kafka 메시지는 **대상 인스턴스의 일꾼만** 집는다 → 로컬 대상은 관리 API 로 연 창 안에서만 열린다 → 후보 · 로컬 인스턴스는 스케줄러 · 마이그레이션 없이, 일꾼은 「표시 붙은 것만」으로 뜬다.

**비목표**: 후보 리비전 배포 · 되돌리기 스크립트 · 서비스 메시(Istio · Envoy) · 터널 도구 구현(Cloudflare Tunnel 쓰는 법만 문서) · 읽기 전용 DB · 행 보안 · 일반 baggage 전파(우리 키 하나만) · OpenTelemetry 도입 · `ExternalHttpClient` 를 거치지 않는 호출 · WebSocket 프록시 · 관리 화면(프런트 짝 없음, API 만) · Kafka 배치 리스너.

**다음에 넓힐 것(추천 순)**: ① 서비스별 · 대상별 창(지금은 그 서버가 쓰는 DB 의 표 하나, 7절) ② 서비스 여럿의 창을 한 번에 여는 길(DB 를 나눈 서비스는 지금 서비스마다 연다, 7절) ③ Kafka 배치 리스너 ④ WebSocket 프록시.

## 2. 지금 레포에 있는 것

| 필요한 것 | 있는 것 | 쓰는 법 |
|---|---|---|
| 서버 간 공통 클라이언트 | `platform` `ExternalHttpClient`(WebClient). 클라이언트 이름마다 `ExternalHttpClientCustomizer.customize(name, builder)` | 고치지 않고 커스터마이저로 필터 하나 |
| 추적 | 자체 `TraceIdFilter`(W3C `traceparent` → MDC). OTel · micrometer tracing 없음 | 같은 방식(MDC)으로 표시를 든다 |
| 직원 판정 | `platform` `OperatorAccess.has(OperatorRef, permission)` + `PermissionDeclaration` | 새 권한 `routing.use` · `routing.window`(기본 역할 없음 → 전권 역할만) |
| 감사 | `platform` `OperatorAuditLog.record(OperatorAction)`(쓰기 트랜잭션 안, 사유 원문 금지) | 창 열기 · 닫기 한 줄씩. 사유는 창 표에만(`api-key` 회수 사유와 같다) |
| 관리 API 관례 | `authz`: 모듈은 API · 포트, 저장은 `-jdbc` 어댑터, 기본 꺼짐(404), 메모리 구현 없음 | 같은 모양으로 `request-routing` + `request-routing-jdbc` |
| break-glass | `auth` 의 것은 **비밀값으로 다른 계정 로그인** — 다른 개념이라 재사용 안 함 | 「로컬 창」이라 부른다 |
| 작업 큐 | `job-queue-jdbc`: `jobs` 표, `claim` · `claimOne` 이 `FOR UPDATE SKIP LOCKED`, 넣는 쪽 MDC 를 `log_context` 에 싣고 일꾼이 복원(`propagated-mdc-keys`), 정리(멈춘 잡 복구 · 처리기 없는 종류 DEAD · 보관 삭제)는 일꾼 고리 안 | 「차선(lane)」 열 하나와 집기 조건, 차선 일꾼은 정리를 안 한다 |
| 이벤트 | `event-kafka` 는 발행만(`KafkaEventMessageFactory` 가 MDC traceId 를 머리에). 소비자는 앱의 `@KafkaListener`(spring-kafka) | 발행에 `baggage` 한 줄, 소비는 컨테이너 꾸미개로 거르기 · 그룹 · 문맥 복원 |
| 배포 가드 | `DeployGuard`(problems → 기동 실패, warnings → 로그) | 후보 · 로컬 인스턴스 규칙 |
| 주기 작업 끄기 | `skeleton.scheduler.mode=external` 은 **실행 때 읽는다**(네이티브도 같은 이미지). `enabled` · `worker-enabled` 는 네이티브에서 빌드 때 굳는다 | 가드는 속성이 아니라 실제 빈 · 모드를 본다 |

## 3. 접근

- **A. 앱 안 모듈(이 설계)** — 공통 클라이언트에 필터, 입구에서 거르기, 큐 · Kafka 는 소비 쪽에서 차선 고르기. 인프라 변화 없음. 대가: `ExternalHttpClient` 를 안 거치는 호출은 안 따라간다.
- B. 서비스 메시 — 형이 도입 안 한다고 정했다. 메시도 큐 · 메시지 고르기는 못 한다.
- C. 입구 게이트웨이 하나만 — 안쪽 서비스를 바꿀 때 못 한다.

형 결정대로 A.

## 4. 설계

새 모듈 `request-routing`(설정 `skeleton.routing`, 패키지 `dev.sumin.skeleton.routing`, 기본 꺼짐) · `request-routing-jdbc`(창 표) · `platform` 표시 모양 `common/routing/RouteBaggage` · `event-kafka` 머리 한 줄 · `job-queue-jdbc` 차선.

**4.1 표시 모양** — `baggage` 멤버 하나: `route=<서비스>:<대상>|<서비스>:<대상>`. 이름 `^[a-z][a-z0-9-]{0,39}$`, 쌍 8개 · 값 256자까지, 같은 서비스 두 번 금지. 다른 baggage 멤버는 읽지도 옮기지도 않는다. 요청 동안 값은 MDC 키 `route`. `RouteBaggage` 가 파싱 · 문자열 · MDC 복원(`restore(value): AutoCloseable`)을 갖는다 — `event-kafka` 가 모듈 없이 쓰게 `platform` 에 둔다.

**4.2 들어올 때** — 서블릿 필터, 보안 필터 체인 **뒤**. `inbound`:
- `edge`(기본): 인증된 호출자가 `routing.use` 를 가질 때만 표시를 받는다. 아니면 **말없이 버린다**.
- `internal`: 앞 서비스가 보낸 표시를 믿는다 — **공개 주소가 없는 서비스만**(홈서버 선언에 `domain` 없음 · Cloud Run `--ingress internal`). 업계 관행(입구에서 지우고 안쪽은 믿는다).
받아들인 표시는 이 서비스의 허용 목록으로 검사한다: 아는 서비스의 모르는 대상 → 400 `ROUTING.UNKNOWN_TARGET`, 로컬 대상인데 창이 닫힘 → 403 `ROUTING.LOCAL_CLOSED`, 모양이 틀림 → 400 `ROUTING.MALFORMED`. 모르는 서비스 이름은 뒤 서비스 몫이라 그대로 둔다. **실패로 막는 이유**: 직원이 「로컬로 갔겠지」 하고 옛 코드를 확인하는 사고가 조용한 원래 서버 대체보다 크다. 로컬 대상을 받아들일 때마다 WARN 한 줄(행위자 id · 대상, traceId 는 MDC).

**4.3 나갈 때** — `ExternalHttpClientCustomizer` 가 `services` 에 적힌 클라이언트 이름에만 필터를 단다: MDC 의 표시를 `baggage` 로 싣고(이미 있는 baggage 엔 `route` 멤버만 갈아 끼운다), 표시에 그 이름이 있으면 요청 주소의 **스킴 · 호스트 · 포트만** 대상 주소로 바꾸고(경로 · 쿼리 그대로) 대상의 `headers` 를 더한다. 로컬 대상은 이때 창을 다시 본다. 결제사 같은 외부 클라이언트에는 표시가 나가지 않는다.

**4.4 입구 서비스 자신이 대상일 때** — `service`(이 서비스 이름)에 대한 표시가 있고 그 대상이 `instance`(이 인스턴스가 무엇인지)와 다르면, 같은 필터가 요청을 통째로 대상에 넘기고 응답을 흘려 돌려준다(JDK `HttpClient`, 몸통 스트리밍 — SSE 도 흐른다). hop-by-hop 머리 · `Host` 는 버리고 `Authorization` 은 넘긴다(대상이 직원을 다시 확인한다). `Via: 1.1 request-routing` 을 붙이고, 그 표지가 이미 있는데 또 넘겨야 하면 508 `ROUTING.LOOP`. 대상이 안 닿으면 502 `ROUTING.TARGET_UNREACHABLE`.

**4.5 로컬 창(관리 API + 표)** — 창은 그 서버가 쓰는 DB 의 표 하나다(서비스별 · 대상별 창은 없다, 7절). 창이 열려 있는 동안만 이 서버가 `local: true` 대상으로 보낸다(들어올 때 · 나갈 때 · 프록시 모두).
- 표 `routing_local_windows`(`request-routing-jdbc`, PostgreSQL · MySQL): `id` · `opened_by` · `reason`(200자) · `opened_at` · `open_until` · `closed_by` · `closed_at`. 줄은 지우지 않는다(이력이 곧 기록). 열린 창 = `closed_at` 이 비고 `open_until` > 지금인 줄 중 가장 늦게 연 것.
- API(`skeleton.routing.admin.enabled`, 기본 꺼짐 → 404, 권한 `routing.window`): `GET /api/v1/admin/routing/local-window` → `{open, window}` · `POST` `{minutes: 1~480, reason}` → 열린 창을 닫고(`closed_by` = 연 사람) 새 창을 연다 · `DELETE` → 지금 닫는다(204, 열린 게 없어도 204).
- 시간 상한: `minutes` ≤ `local.max-window`(기본 8h). `max-window` 는 줄일 수만 있다 — 8h 를 넘기면 기동 실패. 시각이 지나면 다시 부르지 않아도 닫힌다.
- 감사: 같은 트랜잭션에서 `OperatorAuditLog` `routing.local-window.opened`(대상 = 창 id, detail `openUntil`) · `routing.local-window.closed`(대상 = 창 id). 사유는 표에만.
- 여러 인스턴스: 판정은 표를 읽고 `local.cache-ttl`(기본 5s) 동안 캐시한다. 연 · 닫은 인스턴스는 자기 캐시를 바로 비운다 — 다른 인스턴스는 늦어도 5초 뒤에 따른다.
- 저장소가 없으면(`-jdbc` 미조립) 창은 늘 닫혀 있다. API 를 켰는데 저장소가 없으면 기동 실패.

**4.6 작업 큐 — 차선** — `job-queue-jdbc` 에 열 `jobs.lane varchar(40) null`(새 마이그레이션 두 방언)과 포트 `JobLanes`(`enqueueLane()` · `workerLane()`, 기본 둘 다 null)를 둔다.
- 넣을 때: `lane = JobLanes.enqueueLane()`. `request-routing` 의 구현은 `표시.targetOf(service)` — 후보가 처리한 요청이 넣은 잡은 그 후보의 차선이 된다.
- 집을 때(`claim` · `claimOne`): 기본 일꾼(차선 null)은 `lane is null` 이거나 **`lane-grace`(기본 1h)보다 오래 밀린** 차선 잡만 집는다 — 후보가 사라져도 잡이 영영 남지 않게(`unhandled-type-grace` 와 같은 생각). 차선 일꾼(`workerLane()` = `instance`)은 `lane = 자기 차선` 만 집는다.
- 차선 일꾼은 정리(멈춘 잡 복구 · 처리기 없는 종류 DEAD · 보관 삭제)를 하지 않는다 — 운영 표 정리는 원래 일꾼 몫이다.
- 핸들러의 나가는 호출도 라우팅되게 앱 yml `propagated-mdc-keys: [traceId, route]`(모듈 기본은 안 바꾼다; 빠졌으면 가드가 경고).

**4.7 Kafka — 별도 소비 그룹** — 발행: `KafkaEventMessageFactory` 가 MDC 에 `route` 가 있으면 `baggage: route=…` 머리를 싣는다. 소비: `request-routing` 이 spring-kafka 가 있을 때 컨테이너 꾸미개(`ContainerCustomizer`) 하나로 앱의 `@KafkaListener` 컨테이너마다
- `RecordInterceptor` 를 단다: 레코드의 `baggage` 에서 `t = 표시.targetOf(service)`. 원래 인스턴스는 `t` 가 있으면 건너뛰고(null 반환), 후보 인스턴스는 `t == instance` 만 통과시킨다. 통과한 레코드는 처리 동안 MDC `route` 를 복원한다(나가는 호출이 라우팅된다).
- 후보 인스턴스면 소비 그룹을 `<원래 그룹>-<instance>` 로 바꾸고 `auto.offset.reset=latest` — 원래 그룹의 파티션을 뺏지 않고, 처음 뜰 때 옛 메시지를 다시 읽지 않는다.
- 원래 인스턴스가 건너뛴 레코드는 다시 오지 않는다 — 후보가 내려가 있으면 그 메시지는 후보 그룹이 다시 뜰 때 이어 읽는다(그룹 오프셋이 남아 있는 동안). 표시는 직원 요청에만 붙으므로 손님 메시지는 영향이 없다.

**4.8 후보 · 로컬 인스턴스 가드** — `instance` 가 비어 있지 않으면 `RequestRoutingDeployGuard` 가 기동을 막는다(환경과 상관없이):
- `service` 가 비어 있음 → 문제(차선 · 소비 거르기 · 프록시가 모두 이 이름으로 정해진다).
- `scheduler` 빈이 있고 `skeleton.scheduler.mode` ≠ `external` → 문제. 밖의 스케줄러는 원래 주소만 부르므로 후보에서는 아무것도 안 돈다.
- 큐 일꾼 빈이 있고 폴링 모드인데 `JobLanes.workerLane()` 이 null → 문제(원래 잡을 가로챈다). 이 모듈의 `JobLanes` 가 붙어 있으면 차선 일꾼으로 **켜진 채 뜬다**.
- Kafka 리스너가 있고 `spring.kafka.listener.auto-startup` ≠ `false` 인데 이 모듈의 컨테이너 꾸미개가 없음(앱이 바꿔 끼움) → 문제. 꾸미개가 있으면 별도 그룹 · 「표시 붙은 것만」으로 **켜진 채 뜬다**.
- `Flyway` 빈 있음 → 문제(로컬 가지의 새 마이그레이션이 운영 DB 에 먼저 들어가면 되돌릴 수 없다 — 마이그레이션은 배포가 한 번 돌린다). JVM 은 `spring.flyway.enabled=false`. 네이티브 후보 이미지는 1차 밖.
- 모듈의 정리 타이머(계정 · 세션 정리 등)는 막지 않는다 — 겹쳐 돌아도 안전하게 짜여 있다(`docs/modules/scheduler.md`).
늘: `local.max-window` > 8h → 문제 · `admin.enabled` 인데 창 저장소 없음 → 문제. 경고: 보호 환경에 로컬 대상이 있는데 창 저장소 없음(영영 못 연다) · 큐 일꾼이 있는데 `propagated-mdc-keys` 에 `route` 없음.

**4.9 설정**

```yaml
skeleton:
  routing:
    enabled: false              # 모듈 스위치
    service: ""                 # 이 서비스 이름 — 프록시 · 큐 차선 · Kafka 거르기의 기준
    instance: ""                # 이 인스턴스가 대상이면 그 이름(candidate · alice). 비면 원래 서버
    inbound: edge               # edge | internal
    services:                   # 표시를 넘길 내부 서비스(= skeleton.http.clients 이름)와 대상 허용 목록
      orders:
        targets:
          candidate: { url: "https://candidate---orders-abc.a.run.app" }
          alice: { url: "https://alice-orders.example.com", local: true, headers: { CF-Access-Client-Id: "${ORDERS_ALICE_ACCESS_ID}", CF-Access-Client-Secret: "${ORDERS_ALICE_ACCESS_SECRET}" } }
    admin:
      enabled: false            # 로컬 창 관리 API (/api/v1/admin/routing/local-window)
    local:
      max-window: 8h            # 창 하나의 최대 길이 — 줄일 수만 있다
      cache-ttl: 5s             # 창 판정 캐시
    proxy:
      timeout: 30s              # 대상이 응답 머리를 줄 때까지(콜드스타트 여유)
  job-queue:
    lane-grace: 1h              # (job-queue-jdbc) 차선 잡이 이보다 오래 밀리면 기본 일꾼이 집는다
```
대상 `url` 은 원점만(경로 없음, http · https) — 아니면 기동 실패. 8h 는 Azure PIM 의 기본 최대 활성 시간과 같다.

**4.10 확장 지점**(고른 이유)
- `LocalRouteWindowStore`(창 저장 포트) — 형 결정이 DB 표이고, `authz` 처럼 저장은 어댑터(`-jdbc`)가 낸다. 두 번째 구현(Redis 등)은 앱이 둔다.
- `LocalRouteWindow`(판정 — 기본은 저장소 + 짧은 캐시) — 저장소 없이 「늘 닫힘」이 두 번째 구현이다.
- `JobLanes`(`job-queue-jdbc`) — 큐가 라우팅을 모르게 하는 자리. 기본(차선 없음)과 `request-routing` 구현, 둘이 실제로 있다.
- 나머지는 이미 있는 자리를 쓴다: `OperatorAccess` · `OperatorAuditLog` · `JobContextPropagator` · spring-kafka `ContainerCustomizer`. 허용 목록은 형 결정대로 설정이라 인터페이스로 빼지 않는다. 필터 · 컨트롤러 등록 빈은 관례대로 `@ConditionalOnMissingBean`.

## 5. 안전장치

| 막을 것 | 막는 것 | 시험 |
|---|---|---|
| 손님이 표시를 실어 다른 서버로 감 | `edge` 는 `routing.use` 없으면 버림 | 익명 · 일반 계정 → 표시 없이 원래 주소, 큐 차선도 null |
| 표시로 아무 주소나 부름(SSRF) | 표시엔 이름만, 주소는 허용 목록 | 목록에 없는 대상 → 400, 주소 문자열을 실어도 안 감 |
| 운영 → 로컬이 상시 열림 | 창 없으면 막힘, 최대 8h, 지나면 저절로 닫힘, 닫기 API | 닫힘 → 403 · 시각 지남 → 403 · 481분 → 400 · 닫은 뒤 캐시 시간 안에 다른 인스턴스도 403 |
| 누가 · 왜 · 언제까지 열었는지 모름 | 창 표 줄(지우지 않음) + `OperatorAuditLog` 열기 · 닫기 + 사용마다 WARN | 표 줄 · 감사 한 줄 · 로그 |
| 창을 아무나 엶 | 권한 `routing.window`, API 기본 꺼짐(404) | 권한 없음 403 · 꺼짐 404 · 익명 401 |
| 후보가 원래 잡 · 메시지를 가로챔 | 차선 집기 · 별도 그룹 + 거르기, 4.8 가드 | 후보 일꾼이 차선 없는 잡을 안 집음 · 후보 소비자가 남의 레코드 건너뜀 |
| 원래 일꾼이 후보 몫을 처리함 | 기본 일꾼은 `lane is null`(유예 뒤만 예외) · 원래 소비자는 자기 서비스 표시 레코드 건너뜀 | 시험 |
| 후보가 사라져 잡이 영영 남음 | `lane-grace` 뒤 기본 일꾼이 집음 | 시험 |
| 후보가 운영 표를 정리함 | 차선 일꾼은 정리 안 함 | 멈춘 잡 · 처리기 없는 종류가 차선 일꾼 고리에서 안 바뀜 |
| 로컬 가지의 마이그레이션이 운영 DB 에 | 4.8 가드 | Flyway 빈 + instance → 기동 실패 |
| 공개 터널로 누구나 로컬 서버에 닿음 | 문서: Cloudflare Access 서비스 토큰 + 대상 `headers` | 문서 |
| 외부 업체에 내부 이름이 샘 | `services` 에 적힌 클라이언트에만 baggage | 다른 클라이언트 호출에 baggage 없음 |

쓰기는 막지 않는다(형 결정). 로컬 인스턴스는 운영 DB · 비밀(JWT 비밀 포함)을 쓴다 — 노트북에 운영 비밀이 있게 되는 것을 받아들인 설계다(문서에 명시).

## 6. 검증

- 모듈 시험(MockMvc + 보안 체인 + JDK `HttpServer` 두 개를 원래 · 후보로): 5절 시험 행, 프록시 스트리밍 · 508 · 502.
- 창: API(권한 · 상한 · 닫기 · 감사) 시험, `request-routing-jdbc` 저장소를 PostgreSQL · MySQL 두 묶음(`postgresTest` · `mysqlTest`)으로, 캐시(다른 인스턴스 = 다른 판정 객체가 TTL 뒤 따라옴).
- 큐: `job-queue-jdbc` 두 방언 묶음에 차선 집기 · 유예 · 차선 일꾼 정리 안 함. 옛 버전 호환(새 열은 null 허용 — expand/contract CI 통과).
- Kafka: 꾸미개 단위 시험(레코드 거르기 · MDC · 그룹 이름 · 오프셋 설정). 실제 브로커 시험은 하지 않는다(레포에 Kafka 컨테이너 시험이 없다).
- 플랫폼: `RouteBaggage` 파싱 · `event-kafka` 머리. 문서 · 카탈로그 가드, `migrations.lock`, workbench 설정 조각 시험.
- 손으로 한 번(QA): `apps/workbench` 두 개(원래 · `instance=candidate`)로 직원 → 후보, 일반 → 원래, 창 열고 닫기.

## 7. 남는 한계(문서에 적는다)

- 창은 **그 서버가 쓰는 DB 의 표**일 뿐이다. 서버들이 DB 하나를 같이 쓰면(초기에 흔하다) 한 번 열면 전부에 적용되고, DB 를 나눴으면 서버마다 따로 연다 — 문서에 서비스 목록을 돌며 `POST` 하는 예를 둔다. 서비스별 · 대상별 창은 없다.
- `ExternalHttpClient` 를 거치지 않는 호출은 안 따라간다. `.block()` 밖의 다른 스레드에서 구독하면 MDC 가 없어 표시가 빠진다(지금의 traceId 와 같은 한계).
- 로컬 인스턴스가 부르는 아래 서비스에 노트북이 닿아야 한다(홈서버 내부 앱은 Tailscale, Cloud Run 내부 전용은 못 닿음).
- Cloud Run 서비스 간 ID 토큰은 대상이 태그 주소여도 aud 를 서비스 원래 주소로 둔다(공식 문서 "custom audiences").
- 앱이 자기 `ContainerCustomizer` 를 이미 두면 Kafka 꾸미개와 겹친다 — 가드가 후보에서 잡고, 원래 인스턴스는 문서대로 앱 꾸미개 안에서 이 꾸미개를 부른다.
- 배포 중 옛 버전 일꾼은 차선을 모르므로 차선 잡을 집을 수 있다(배포 몇 분 동안만).
- 유예 뒤 원래 일꾼이 거둔 차선 잡이 `route` 를 되살리면 나가는 호출이 내려간 후보로 가 실패할 수 있다(직원 시험 잡만).
- Kafka 거르기는 레코드 리스너만 닿는다: 후보의 배치 리스너 · 꾸미개가 실제 컨테이너에 안 달린 경우(앱 팩토리)는 시작 직전 확인에서 기동 실패로 막는다.

## 정해야 할 것

없음 — 2차 결정으로 모두 정했다.
