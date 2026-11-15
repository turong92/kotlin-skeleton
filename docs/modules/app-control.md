# app-control

재배포 없이 끄고 켜는 세 가지 — **기능 스위치** · **점검 모드** · **모바일 최소 버전(강제 업데이트)**. 값은 DB 에 있고(어댑터 `app-control-jdbc`) 운영자 경로로 바꾼다. 클라이언트(`@skeleton/app-control` · `skeleton_app_control`)는 `GET /api/v1/app/config` 한 번으로 점검 · 보이는 스위치 · 플랫폼별 버전 정책을 읽는다. 계약: [app-control-http-contract](../app-control-http-contract.md).
작은 팀용 최소형이다 — 스위치는 **켜짐 / 꺼짐 둘뿐**이고(사용자 · 비율 단위 단계적 공개 없음, 필요해지면 `FeatureFlags` 뒤에 그 규칙을 얹는다), 키 · 값 설정 저장소가 아니다(`config-aws-ssm` 은 기동 때 한 번 읽는 비밀 · 설정이고 이쪽은 실행 중에 바뀌는 값이다).

**읽기(캐시)** 프로젝트 모드(`skeleton.projects`)에서는 값도 캐시도 프로젝트마다(표는 프로젝트 스키마마다, 캐시 키에 프로젝트가 붙는다)이고, 통과 경로는 상태를 읽지도 않아 프로젝트 없이 오는 헬스체크가 DB 를 건드리지 않는다. 값은 `cache-ttl`(기본 5초) 동안 메모리에 있고, 만료 뒤 첫 읽기가 표 전체를 **쿼리 한 번**으로 읽어 통째로 바꿔 끼운다 — 요청마다 DB 를 치지 않는다. 바꾼 인스턴스는 바로 보고, 다른 인스턴스는 늦어도 `cache-ttl` 안에 따라온다(여러 인스턴스가 곧 같은 값). DB 를 읽지 못하면 직전 값을 그대로 쓰고 다음 시도는 ttl 뒤다 — 한 번도 읽지 못했으면 중립(스위치 없음 · 점검 아님 · 버전 규칙 없음)이라 DB 장애가 점검을 켜거나 기능을 끄지 않는다. 읽을 수 없는 줄 하나(손으로 고친 JSON)는 건너뛰고 나머지는 읽는다.
**기능 스위치** 코드에서는 `featureFlags.isEnabled("new-checkout")` — 운영자가 만든 적 없는 키는 꺼짐(`default = true` 로 부르면 「평소엔 켜져 있고 문제 때 끄는」 기능). 컨트롤러는 `@RequireFeature("new-checkout")`(클래스 또는 메서드, 메서드가 우선) — 꺼져 있으면 경로가 없는 것처럼 `404 APP_CONTROL.FEATURE_DISABLED`. 스위치마다 `clientVisible` 이 있어 true 인 것만 클라이언트 설정에 실린다(기본 false — 서버 안의 스위치는 밖에 보이지 않는다). 키는 `[a-z][a-z0-9._-]{0,63}`.
**점검 모드** 켜면 통과 경로 밖의 모든 요청이 `503` + `Retry-After`(끝 예정이 있으면 남은 초, 없으면 `maintenance.retry-after` 5분) + `APP_CONTROL.MAINTENANCE`(`detail` = 운영자가 쓴 안내 문구, 없으면 영어 기본 문구)로 막힌다. 필터는 보안 체인 앞이라 로그인 전 요청도 막는다. 통과: `/health/**` · `/info` · `/actuator/health/**` · `/api/v1/auth/**`(운영자가 로그인해 끌 수 있게, 갱신이 막혀 로그아웃되지 않게) · `/api/v1/admin/**`(운영자 경로 — 호출자는 여전히 권한을 판정받는다) · 이 모듈의 설정 경로 · CORS preflight. 경로는 스프링이 핸들러를 고르는 방식 그대로 읽어(`RequestPaths`) 인코딩 우회가 안 된다. 점검 중 로그인까지 막으려면 `maintenance.pass-paths` 에서 `/api/v1/auth/**` 를 뺀다.
**플랫폼 버전** 플랫폼 이름(`android` · `ios` · `web` …)마다 `minVersion`(이보다 낮으면 강제 업데이트) · `recommendedVersion`(이보다 낮으면 닫을 수 있는 안내) · `storeUrl`(https). 버전은 `1`~`4`칸의 숫자(`1.10.2`)이고 **칸마다 숫자로 비교한다**(`1.10` > `1.9`, `1.2` = `1.2.0`) — 서버는 저장할 때 `recommended >= min` 을 지키는지만 보고, 비교해 막는 일은 클라이언트가 한다(서버가 낡은 앱 요청을 거절하지는 않는다).

**운영자 경로**(`skeleton.app-control.admin.enabled=true` 일 때만 — 꺼져 있으면 경로가 없어 404; 익명 401 · 권한 없음 403). 권한 `app-control.read`(보기) · `app-control.write`(바꾸기) — 기본 역할 없음, 전권(`ADMIN`)만(역할 화면에서 나눠 준다). 바꾼 것은 모두 같은 트랜잭션에서 `OperatorAuditLog` 에 남는다 — `app-control.flag.updated|deleted` · `app-control.maintenance.updated` · `app-control.platform.updated`(대상 = 키 · 플랫폼, 상세 = 켜짐 여부 · 버전만. 점검 문구 · 스위치 설명 같은 글은 싣지 않는다). 저장소에는 마지막으로 바꾼 사람 · 시각이 남는다.

| 메서드 · 경로 | 권한 | 하는 일 |
|---|---|---|
| `GET /` | read | `{flags:[{key,enabled,clientVisible,description,updatedBy,updatedAt}], maintenance:{enabled,message,endsAt,updatedBy,updatedAt}, platforms:[{platform,minVersion,recommendedVersion,storeUrl,updatedBy,updatedAt}]}` — 클라이언트에 안 보이는 스위치도 포함 |
| `PUT /flags/{key}` | write | `{enabled, clientVisible?, description?}` — 없으면 만들고 있으면 덮어쓴다 |
| `DELETE /flags/{key}` | write | 204 · 없으면 404 |
| `PUT /maintenance` | write | `{enabled, message?, endsAt?}` — 끝 예정은 미래(켤 때). 문구 500자 |
| `PUT /platforms/{platform}` | write | `{minVersion?, recommendedVersion?, storeUrl?}` — 하나는 있어야 한다 |
| `DELETE /platforms/{platform}` | write | 204 — 그 플랫폼의 규칙을 지운다 |

입력 오류는 `400 COMMON.VALIDATION_FAILED` + `errors[]`(칸 이름 · 코드). **점검을 끄는 법**이 막혀 있으면 안 된다 — 운영자 경로 · 로그인이 통과 경로라 점검 중에도 같은 화면으로 끈다. 운영자 화면이 없으면 `PUT /api/v1/admin/app-control/maintenance {"enabled":false}` 를 직접 부른다.

**켜는 법**: `app-control-jdbc` 를 얹고(마이그레이션 적용) `skeleton.app-control.enabled=true`, 클라이언트가 읽게 `config.enabled=true`, 운영자 경로는 `admin.enabled=true`. 켠 뒤 점검 필터가 항상 서 있다(점검 상태가 꺼져 있으면 그냥 지나간다). 배포 뒤 웹이 새로고침 안내를 받게 하려면 배포 스크립트가 `PUT /platforms/web {"recommendedVersion":"<새 버전>"}` 를 한 번 부른다(`@skeleton/app-control` 의 `useUpdateStatus`).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:app-control"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.app-control` — [docs/config/modules/app-control.yml](../config/modules/app-control.yml) |
| 기본 동작 | 꺼짐. `skeleton.app-control.enabled=true` 로 켜면 읽기 · 스위치 어노테이션 · 점검 필터가 선다. 클라이언트 설정 경로는 `config.enabled`, 운영자 경로는 `admin.enabled` 도 있어야 열린다(꺼져 있으면 404). |
| 부팅에 필요한 것 | 켰을 때: 저장소 `AppControlStore`(`app-control-jdbc` 가 낸다). 없으면 무엇을 얹을지 말하고 기동에 실패한다. 운영자 경로는 `OperatorAccess`(`platform`)가 판정하고 `OperatorAuditLog` 가 있으면 기록한다. |
| 교체 지점 | `AppControlStore`, `AppControl`, `FeatureFlags`, `AppConfigController`, `AppControlAdminController`, `appControlPermissionDeclaration` |
| 마이그레이션 | 없음 (스키마는 `app-control-jdbc`) |
| 프론트 짝 | `@skeleton/app-control` |
| 테스트 | `modules/app-control/src/test` |

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
