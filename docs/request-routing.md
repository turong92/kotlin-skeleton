# 요청 라우팅 — 내 요청만 후보 · 로컬 서버로

설계: [specs/2026-11-16-request-routing-design.md](superpowers/specs/2026-11-16-request-routing-design.md) · 모듈 쪽 한 장: [modules/request-routing.md](modules/request-routing.md) · 창 저장: [modules/request-routing-jdbc.md](modules/request-routing-jdbc.md)

## 1. 무엇이고 언제 쓰나

서버가 여럿(MSA)일 때 하나만 고쳤다면, 그 하나만 후보(새 리비전)로 띄우거나 내 노트북에서 돌리고 **내 요청만** 그쪽으로 보낸다. 나머지 호출과 다른 사람의 요청은 원래 서버가 그대로 받는다. 핫픽스에서 빌드 · 배포 없이 몇 초 단위로 고쳐 보고, 확인되면 그때 배포한다.

방법은 요청 머리 하나다 — W3C `baggage` 의 `route` 멤버:

```
baggage: route=orders:candidate
baggage: route=orders:alice|billing:candidate     # 서비스마다 대상 하나, 최대 8쌍
```

`<서비스>:<대상>`. **이름만** 싣는다. 이름이 어떤 주소인지는 서버 설정(허용 목록)에서만 정한다 — 표시에 주소를 넣으면(`orders:https://…`) 모양 오류다. 기본은 **꺼짐**이다(`skeleton.routing.enabled=false`) — 꺼져 있으면 필터 · 클라이언트 필터 · 가드가 하나도 없다.

고친 코드가 HTTP 처리 안이든, 그 요청이 넣은 작업 · Kafka 메시지의 처리기 안이든 같은 방법으로 시험된다(6절).

이 문서가 하지 않는 것: 후보 리비전 배포 · 되돌리기 스크립트(지금은 안 만든다), 서비스 메시, WebSocket 프록시, 터널 도구 자체(쓰는 법만), Kafka 배치 리스너.

## 2. 원래 서버 설정

```yaml
skeleton:
  routing:
    enabled: true
    service: orders              # 이 서비스 이름 — 프록시 · 큐 차선 · Kafka 거르기의 기준
    inbound: edge                # edge | internal
    services:                    # 표시를 넘길 서비스(= skeleton.http.clients 이름)와 대상 허용 목록
      orders:
        targets:
          candidate: { url: "https://candidate---orders-abc123-uc.a.run.app" }
      billing:
        targets:
          candidate: { url: "https://candidate---billing-abc123-uc.a.run.app" }
```

- **`inbound: edge`**(기본) — 인증된 호출자가 권한 `routing.use` 를 가질 때만 표시를 받는다. 그 밖(손님 · 일반 계정)의 표시는 **말없이 버린다** — 응답 · 나가는 호출 · 넣은 작업 어디에도 흔적이 없다. 권한은 기본 역할이 없어 전권 역할만 갖는다(필요하면 앱이 역할에 준다).
- **`inbound: internal`** — 앞 서비스가 보낸 표시를 그대로 믿는다. **공개 주소가 없는 서비스에만** 쓴다(홈서버 선언에 `domain` 없음 · Cloud Run `--ingress internal`). 공개 서비스에 쓰면 아무나 표시를 달 수 있다.
- 받아들인 표시는 허용 목록으로 검사한다. 아는 서비스의 모르는 대상은 400 `ROUTING.UNKNOWN_TARGET`, 로컬 대상인데 창이 닫혀 있으면 403 `ROUTING.LOCAL_CLOSED`, 모양이 틀리면 400 `ROUTING.MALFORMED`. 모르는 서비스 이름은 뒤 서비스 몫이라 그대로 둔다.
- 나가는 호출은 `ExternalHttpClient` 를 거칠 때만 따라간다. `services` 에 적은 이름과 같은 클라이언트에만 표시가 실린다 — 결제사 같은 외부 클라이언트에는 나가지 않는다. 그 클라이언트는 `skeleton.http.clients.<이름>.base-url` 로 원래 주소를 갖고 있어야 하고, 표시가 가리키면 주소의 스킴 · 호스트 · 포트만 대상 것으로 바뀐다(경로 · 쿼리 그대로).
- 입구 서비스가 대상인 경우(`service` 를 적은 서블릿 앱): 표시가 이 서비스의 다른 인스턴스를 가리키면 요청을 통째로 그 대상에 넘기고 응답을 읽는 대로 흘린다(SSE 도 한 줄씩). 이미 한 번 넘긴 요청이면 508 `ROUTING.LOOP`, 대상이 안 닿으면 502 `ROUTING.TARGET_UNREACHABLE`.

## 3. 후보 인스턴스 설정

후보 = 같은 코드의 새 리비전을 **트래픽 없이** 띄운 것. Cloud Run 이면 `--no-traffic --tag candidate` 로 올리면 태그 주소(`https://candidate---<서비스>-<해시>.a.run.app`)가 생기고, 그 주소를 위 허용 목록의 `url` 로 적는다. 후보 인스턴스는 환경변수 · 설정으로:

```yaml
skeleton:
  routing:
    enabled: true
    service: orders
    instance: candidate          # 이 인스턴스가 대상이면 그 이름. 비면 원래 서버
  scheduler:
    mode: external               # 밖의 스케줄러는 원래 주소만 부른다 — 후보에서 타이머가 같이 돌지 않게
spring:
  flyway:
    enabled: false               # 후보 가지의 새 마이그레이션이 운영 DB 에 먼저 들어가지 않게
```

작업 큐 일꾼과 Kafka 소비자는 **켠 채로** 뜬다(6절 — 후보는 자기 표시가 붙은 것만 집는다). 배포 스크립트는 없다 — 후보를 올리는 명령은 쓰는 쪽의 배포 방법대로 한다.

기동 가드(`requestRoutingDeployGuard`, 환경과 상관없이)가 위를 어기면 기동을 막는다: `service` 가 비어 있음 · 스케줄러 `mode` ≠ `external` · 차선 없는 큐 일꾼(폴링) · 라우팅 꾸미개 없는 Kafka 리스너(`spring.kafka.listener.auto-startup`) · Flyway 켜짐. 메시지에는 속성 이름만 나온다.

## 4. 로컬 고리 — 내 노트북으로

대상을 `local: true` 로 적으면 **관리 API 로 연 창 안에서만** 닿는다. 노트북을 공개 주소로 닿게 하려면 터널이 필요하다 — 권장은 **Cloudflare 고정 이름 터널 + Access 서비스 토큰**이다. 터널은 이 레포가 만들지 않는다(Cloudflare 문서대로 만든다). 서비스 토큰의 id · secret 을 대상 `headers` 에 실으면 나가는 호출마다 붙는다:

```yaml
skeleton:
  routing:
    services:
      orders:
        targets:
          alice:
            url: "https://alice-orders.example.com"
            local: true
            headers:
              CF-Access-Client-Id: "${ORDERS_ALICE_ACCESS_ID}"
              CF-Access-Client-Secret: "${ORDERS_ALICE_ACCESS_SECRET}"
```

노트북에서는 같은 코드를 `skeleton.routing.instance=alice` · `service=orders` 로 띄운다(3절과 같은 규칙).

**알고 켠다.** 로컬 인스턴스는 운영 DB · 비밀(JWT 서명 비밀 포함)을 쓰고 **쓰기가 실제로 일어난다**(읽기 전용도 행 보안도 없다). 노트북에 운영 비밀이 있게 되는 것을 받아들인 설계다. 그래서 환경 `skeleton.env=prod` 에서 이 인스턴스를 띄우는 것도 의식적으로 한다. 공개 터널에는 반드시 Access(서비스 토큰)를 걸어 누구나 노트북에 닿지 않게 한다.

## 5. 로컬 창 — 켜고 끄기

창은 깨진 유리다: 기본 꺼짐 · 켤 때마다 시간 제한 · 감사 기록.

```yaml
skeleton:
  routing:
    admin:
      enabled: true              # 창 관리 API (기본 꺼짐 → 404). 창 저장소(request-routing-jdbc)도 필요하다
    local:
      max-window: 8h             # 창 하나의 최대 길이 — 줄일 수만 있다
      cache-ttl: 5s              # 창 판정 캐시
```

```bash
# 열기: 1~480분, 사유 필수. 열려 있던 창은 닫히고 새로 열린다
curl -sS -X POST https://orders.example.com/api/v1/admin/routing/local-window \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"minutes": 60, "reason": "hotfix check"}'
# 상태
curl -sS https://orders.example.com/api/v1/admin/routing/local-window -H "Authorization: Bearer $TOKEN"
# 닫기 (열린 게 없어도 204)
curl -sS -X DELETE https://orders.example.com/api/v1/admin/routing/local-window -H "Authorization: Bearer $TOKEN"
```

- 권한은 `routing.window`(기본 역할 없음). 열기 · 닫기는 감사에 한 줄씩 남는다(`routing.local-window.opened` · `closed`, `OperatorAuditLog` 빈이 있을 때). **사유는 창 표에만 있고 감사 기록에는 싣지 않는다.**
- 시각이 지나면 다시 부르지 않아도 닫힌다. 요청 도중 닫히면 나가는 호출이 다시 확인해 403 으로 실패한다 — 원래 서버로 몰래 가지 않는다. 다른 인스턴스는 `cache-ttl` 안에 따라 닫는다.
- **창은 「그 서버가 쓰는 DB 의 표」일 뿐이다.** 서버들(서비스 · 인스턴스)이 DB 하나를 같이 쓰면(초기에 흔하다) 한 번 열면 **전부에 적용**된다. 서비스마다 DB 가 따로면 서비스마다 열어야 한다 — 로컬 대상을 부르는 서비스 목록을 돌며 연다:

```bash
for svc in orders billing; do
  curl -sS -X POST "https://$svc.example.com/api/v1/admin/routing/local-window" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"minutes": 60, "reason": "hotfix check"}'
done
```

서비스별 · 대상별 창은 아직 없다.

## 6. 작업 큐 · Kafka

요청이 넣은 작업 · 메시지도 같은 대상이 처리한다. 둘 다 선택 통합이다 — `job-queue-jdbc` · spring-kafka 가 있을 때만 붙는다.

**작업 큐** (`job-queue-jdbc`)

- 표시에 이 서비스의 대상이 있으면 그 이름이 잡의 **차선**(`jobs.lane`)으로 적힌다. 손님 요청은 표시가 없어 기본 차선이다.
- 후보 인스턴스의 일꾼은 **자기 차선의 잡만** 집고, 공유 정리 일(멈춘 잡 복구 · 처리기 없는 종류 정리 · 보관 삭제)은 하지 않는다. 원래 서버의 일꾼은 차선 없는 잡만 집는다.
- 후보가 내려간 채 잡이 쌓이면 `skeleton.job-queue.lane-grace`(기본 `1h`)가 지난 차선 잡을 원래 일꾼이 집는다.
- 잡을 돌리는 동안 표시를 되살려 핸들러의 나가는 호출도 같은 대상으로 가게 하려면 `skeleton.job-queue.propagated-mdc-keys: [traceId, route]` 로 앱이 정한다(모듈 기본은 바꾸지 않는다 — 빠졌으면 가드가 경고한다).
- 새 열은 null 허용이라 옛 줄 · 옛 버전 일꾼과 함께 돈다(배포 중 몇 분은 옛 일꾼이 차선 잡을 집을 수 있다). `schema.sql` 모드는 자기 `jobs` 정의에 `lane` 열을 더한다([job-queue-jdbc](job-queue-jdbc.md)).

**Kafka** (spring-kafka)

- 발행: 표시가 있으면 이벤트 머리 `baggage` 에 실린다.
- 소비: 리스너 컨테이너마다 라우팅 인터셉터가 붙는다. 원래 서버는 자기 서비스 몫으로 표시된 레코드를 **건너뛰고**(그대로 커밋된다), 후보는 자기 이름이 표시된 레코드만 받는다. 후보는 소비 그룹이 `<원래 그룹>-<instance>` 이고 `auto.offset.reset=latest` 라서 원래 그룹을 가로채지 않고 옛 메시지를 되감아 읽지 않는다.
- Spring Boot 는 `ContainerCustomizer` 빈이 **하나일 때만** 컨테이너 팩토리에 단다. 앱이 자기 `ContainerCustomizer` 를 이미 두면 이 모듈의 꾸미개 빈은 서지 않는다 — 앱의 꾸미개 안에서 `RouteKafkaContainerCustomizer(properties).configure(container)` 를 불러야 한다. 후보 인스턴스에서 꾸미개가 없으면 가드가 기동을 막는다.

## 7. 한계

- **창은 그 서버가 쓰는 DB 의 표다**(5절): DB 를 같이 쓰면 한 번에 전부, 나눴으면 각각. 서비스별 · 대상별 창은 없다.
- `ExternalHttpClient` 를 거치지 않는 호출은 따라가지 않는다. `.block()` 밖의 다른 스레드에서 구독하면 MDC 가 없어 표시가 빠진다(traceId 와 같은 한계).
- 로컬 인스턴스가 부르는 아래 서비스에 노트북이 닿아야 한다(홈서버 내부 앱은 Tailscale, Cloud Run 내부 전용은 못 닿는다).
- Cloud Run 서비스 간 ID 토큰의 대상(aud)은 태그 주소여도 서비스 원래 주소다(custom audiences 설정이 필요할 수 있다).
- 후보 가지의 새 마이그레이션은 후보가 돌리지 않는다(Flyway 꺼짐) — 마이그레이션은 정식 배포가 한 번 돌린다. 네이티브 후보 이미지는 다루지 않는다.
- 외부에서 받은 `baggage` 머리를 앱이 Kafka 메시지 머리로 그대로 옮기지 않는다. 발행은 믿은 표시(MDC `route`)가 없으면 `route` 멤버를 지우지만, 앱이 직접 만든 머리까지 이 모듈이 검증하지는 않는다.
- 배포 중 옛 버전 일꾼은 차선을 모르므로 차선 잡을 집을 수 있다.

## 8. 확인 절차

시험이 보장하는 것(`modules/request-routing` 시험): 직원 요청의 `route` 가 대상으로 넘어가고, 손님 요청은 원래 서버에만 가고 흔적이 없고, 닫힌 창에서 로컬 대상은 403 이고 연 뒤 통과하고, 창 닫기가 다른 인스턴스에 `cache-ttl` 안에 따라가고, 후보가 스케줄러를 로컬로 두면 기동 실패 메시지에 `skeleton.scheduler.mode` 가 나온다.

실제 서버 둘로 직접 보려면(이 절차는 자동으로 돌리지 않았다): 같은 앱을 두 번 띄운다 — 원래(`enabled=true`, `service=orders`, `admin.enabled=true`)와 후보(같은 설정 + `instance=candidate`, `scheduler.mode=external`, `spring.flyway.enabled=false`, 다른 포트). 원래 서버의 `services.orders.targets.candidate.url` 을 후보 포트로 둔다.

1. 전권 계정 + `baggage: route=orders:candidate` 로 아무 API 를 부른다 → 후보 로그에 그 요청이 나타나고(traceId 같음) 원래 로그엔 없다.
2. 일반 계정 + 같은 머리 → 원래 로그에만 나타난다.
3. 대상을 `local: true` 로 바꾸고 창이 닫힌 채 호출 → 403 `ROUTING.LOCAL_CLOSED`. `POST local-window` 뒤 → 통과. `DELETE` 뒤 `cache-ttl` 안에 다시 403.
4. 후보를 스케줄러 `mode` 기본값으로 띄우면 기동이 실패하고 메시지에 `skeleton.scheduler.mode` 가 나온다.
