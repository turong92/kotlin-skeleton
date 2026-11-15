# request-routing

내 요청만 후보 · 로컬 서버로 보내는 머리표 라우팅 — 직원이 `baggage: route=<서비스>:<대상>` 를 실어 보내면, 서비스들이 그 표시를 이어 넘기고 허용 목록의 후보 · 로컬 주소로 그 요청(과 그 요청이 넣은 작업 · 메시지)만 보낸다. 나머지 호출은 원래 서버가 받는다. 핫픽스를 빌드 · 배포 없이 몇 초 단위로 고쳐 보는 고리다.
표시엔 **이름만** 싣고 이름 → 주소는 서버 설정의 허용 목록(`skeleton.routing.services`)이 정한다 — 표시로 아무 주소나 부를 수 없다. 로컬 대상(`local: true`)은 관리 API 로 연 창 안에서만 열린다(창을 여는 API · 저장소는 같은 모듈 · `request-routing-jdbc` 의 몫이다).
설계 · 작업 순서: `docs/superpowers/specs/2026-11-16-request-routing-design.md`.

**표시 모양**(`platform` 의 `RouteBaggage`): `baggage` 멤버 하나 `route=orders:alice|api:candidate`. 이름은 `^[a-z][a-z0-9-]{0,39}$`, 쌍은 8개 · 값은 256자까지, 같은 서비스를 두 번 쓸 수 없다. 요청 동안 값은 MDC 키 `route`.
**허용 목록 검사**: 기동 때 `RouteTargets` 가 이름 모양과 대상 `url`(http · https 원점만, 경로 없음)을 검사하고 어기면 기동이 실패한다 — 메시지엔 속성 이름만 있고 값은 없다.
**들어올 때**(서블릿 앱, 보안 필터 체인 바로 뒤의 `routeInboundFilterRegistration`): 요청의 `route` 를 믿고 · 검사하고 · MDC `route` 에 둔다. `inbound: edge`(기본)는 인증된 호출자가 `routing.use` 를 가질 때만 표시를 받고 **아니면 말없이 버린다**(손님의 표시는 응답 · 나가는 호출 어디에도 흔적이 없다). `inbound: internal` 은 앞 서비스가 보낸 표시를 믿는다 — 공개 주소가 없는 서비스만 쓴다. 받은 표시는 허용 목록으로 검사한다: 모양 오류 400 `ROUTING.MALFORMED`, 아는 서비스의 모르는 대상 400 `ROUTING.UNKNOWN_TARGET`, 로컬 대상인데 창이 닫힘 403 `ROUTING.LOCAL_CLOSED`, 모르는 서비스 이름은 뒤 서비스 몫이라 그대로 둔다. 로컬 대상을 받아들일 때마다 WARN 한 줄(행위자 id · 대상). Spring Security 가 없으면 아무도 직원이 아니다.
**나갈 때**(`routeOutboundCustomizer` → `ExternalHttpClientCustomizer`): `skeleton.routing.services` 에 적은 이름과 같은 `ExternalHttpClient` 클라이언트에만 필터를 단다 — 결제사 같은 외부 클라이언트에는 표시가 나가지 않는다. MDC `route` 가 있으면 `baggage` 로 싣고(이미 있는 baggage 는 `route` 멤버만 갈아 끼운다), 표시에 이 서비스 이름이 있으면 요청 주소의 **스킴 · 호스트 · 포트만** 대상 주소로 바꾸고(경로 · 쿼리 그대로) 대상의 `headers` 를 더한다. 로컬 대상은 **호출마다 창을 다시 본다** — 요청 도중 닫혔으면 원래 서버로 몰래 가지 않고 `ROUTING.LOCAL_CLOSED` 로 실패한다. 큐 일꾼 · Kafka 처리 경로에서도 `RouteBaggage.restore` 로 MDC 를 복원하면 같은 필터가 대상으로 보낸다. `.block()` 밖의 다른 스레드에서 구독하면 MDC 가 없어 표시가 빠진다(traceId 와 같은 한계).
**비동기**(선택 통합 — `job-queue-jdbc` · spring-kafka 가 클래스패스에 있을 때만): 큐 — `routingJobLanes`(`JobLanes`)가 믿은 표시의 이 서비스 대상을 잡의 차선으로 적고(손님 요청은 표시가 없어 기본 차선), 후보 인스턴스(`instance`)의 일꾼은 자기 차선의 잡만 집는다. 원래 서버는 차선 없는 잡과 `skeleton.job-queue.lane-grace`(기본 1h)가 지난 차선 잡을 집는다. 표시를 일꾼 로그 문맥으로 되살리려면 `skeleton.job-queue.propagated-mdc-keys: [traceId, route]`. Kafka — `routeKafkaContainerCustomizer`(`ContainerCustomizer`)가 리스너 컨테이너에 `RouteRecordInterceptor` 를 달아 원래 서버는 자기 서비스 몫으로 표시된 레코드를 건너뛰고, 후보는 자기 이름이 표시된 레코드만 받는다(소비 그룹 `<원래 그룹>-<instance>`, `auto.offset.reset=latest`). Boot 는 `ContainerCustomizer` 빈이 하나일 때만 컨테이너 팩토리에 달므로, 앱이 자기 `ContainerCustomizer` 를 두면 이 빈은 서지 않는다 — 그 꾸미개 안에서 `RouteKafkaContainerCustomizer(properties).configure(container)` 를 불러야 한다. 건너뛴 레코드는 그대로 커밋된다.
**입구 서비스가 대상일 때**(`service` 를 적은 서블릿 앱, `routeProxy`): 표시에 이 서비스 이름이 있고 그 대상이 `instance` 와 다르면 요청을 통째로 대상에 넘기고 응답을 읽는 대로 흘려 돌려준다(SSE 도 한 줄씩). `Authorization` 은 넘긴다(대상이 직원을 다시 확인한다). hop-by-hop · `Proxy-*` · `Host` · `Content-Length` 는 버리고 `baggage` 는 믿은 표시로 갈아 끼우고 `Via: 1.1 request-routing` 을 붙인다 — 이 표지가 이미 있는데 또 넘겨야 하면 508 `ROUTING.LOOP`, 대상이 안 닿으면 502 `ROUTING.TARGET_UNREACHABLE`. `proxy.timeout` 은 응답 머리까지만 센다. 로컬 대상은 창이 닫혀 있으면 넘기기 전에 403. WebSocket 은 넘기지 않는다.
**창 판정**: `LocalRouteWindow` — 저장소 빈(`LocalRouteWindowStore`)이 있으면 `cache-ttl` 동안 캐시해서 읽고, 없으면 창은 늘 닫혀 있다. 시각이 지나면 캐시 안에서도 닫힌 것으로 본다.

**로컬 창 관리 API**(`skeleton.routing.admin.enabled=true` + 창 저장소 빈 + Spring Security 일 때만, 꺼져 있으면 404 — `routing.window` 권한): `GET /api/v1/admin/routing/local-window`(열렸나 · 누가 · 언제까지), `POST`(`{"minutes":1~480,"reason":"1~200자"}` — `local.max-window` 보다 길면 400 `COMMON.VALIDATION_FAILED`, 열려 있던 창은 닫힌다), `DELETE`(204, 열린 게 없어도). 저장과 감사(`routing.local-window.opened` · `.closed`, 사유는 싣지 않는다)는 한 트랜잭션이고, 연 · 닫은 인스턴스는 판정 캐시를 바로 비운다 — 다른 인스턴스는 `cache-ttl` 안에 따른다. 창 표는 「그 서버가 쓰는 DB 의 표」일 뿐이라 서버들이 DB 하나를 같이 쓰면 한 번 열면 전부 적용되고, DB 를 나눴으면 서버마다 따로 연다.
**배포 가드**(`requestRoutingDeployGuard`, 환경과 상관없이): `instance` 를 적은 인스턴스는 `service` 가 비었거나 · 스케줄러가 `skeleton.scheduler.mode` ≠ `external` 이거나 · 큐 일꾼이 차선 없이 폴링하거나(`skeleton.job-queue.mode`) · Kafka 리스너가 있는데 라우팅 꾸미개가 없거나(`spring.kafka.listener.auto-startup`) · Flyway 가 켜져 있으면(`spring.flyway.enabled`) 기동이 실패한다. 어느 쪽이든 `local.max-window` 가 8h 를 넘거나 `admin.enabled` 인데 창 저장소 빈이 없어도 실패한다. 경고: 보호 환경에 로컬 대상이 있는데 창 저장소가 없을 때, 큐 일꾼이 있는데 `skeleton.job-queue.propagated-mdc-keys` 에 `route` 가 없을 때. 메시지에는 속성 이름만 있다.
**오류 코드**: `ROUTING.MALFORMED` 400 · `ROUTING.UNKNOWN_TARGET` 400 · `ROUTING.LOCAL_CLOSED` 403 · `ROUTING.LOOP` 508 · `ROUTING.TARGET_UNREACHABLE` 502.
**권한**(기본 역할 없음 → 전권만): `routing.use`(표시 쓰기) · `routing.window`(창 열고 닫기).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:request-routing"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `job-queue-jdbc` (그리고 외부 라이브러리 spring-kafka) |
| 설정 접두사 | `skeleton.routing` — [docs/config/modules/request-routing.yml](../config/modules/request-routing.yml) |
| 기본 동작 | 꺼짐. `skeleton.routing.enabled=true` 일 때만 허용 목록 · 창 판정 · 권한 선언 · 들어오는 요청 필터가 선다. |
| 부팅에 필요한 것 | `TimeProvider` · `OperatorAccess` 빈(platform). 창 API 는 `OperatorAuditLog` · `TransactionOperations` 도. 창을 열려면 `LocalRouteWindowStore` 구현(`request-routing-jdbc`). 대상 `url` 이 원점이 아니면 기동 실패. |
| 교체 지점 | `RouteTargets`, `RouteProxy`, `LocalWindowService`, `LocalWindowAdminController`, `LocalRouteWindow`, `LocalRouteWindowStore`, `routeInboundFilterRegistration`, `routeOutboundCustomizer`, `requestRoutingPermissionDeclaration` |
| 마이그레이션 | 없음 (`request-routing-jdbc` 가 가진다) |
| 프론트 짝 | 없음 |
| 테스트 | `modules/request-routing/src/test` |

자세히: [쓰는 법 — 후보 · 로컬 서버로 보내기](../request-routing.md)

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
