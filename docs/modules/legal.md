# legal

약관 · 개인정보 처리방침 같은 **법적 문서**와 **동의 기록**: 종류(문자열 코드) · 판(시행일 · `DRAFT`/`REVIEWED`) · 원문 해시를 못 박는 장부 · `{{사실}}` 자리표시 · 더하기만 하는 동의 사건(주체 + 종류 + 판 + 해시 + 본 언어 + 출처 + IP/UA) ·
가입 동의 묶기 · 철회 · 재동의 필터 · 계정 삭제 때 익명화. 문서는 앱이 싣고(`skeleton.legal.location`), 없으면 모듈의 TEMPLATE 문서(ko/en)가 서되 stage · prod 에서는 DeployGuard 가 막는다.
저장은 어댑터 `legal-jdbc`(PostgreSQL · MySQL)가 한다 — 이 모듈에는 메모리 구현이 없다(동의 기록은 증거다). 모델 · 흐름 · 법적으로 누구의 일인가 · 위협 표: [docs/legal.md](../legal.md). 프런트용 HTTP 계약: [legal-http-contract.md](../legal-http-contract.md).

HTTP 는 `skeleton.legal.http.base-path`(기본 `/api/v1/legal`)에 열린다 (서블릿 웹 앱 + Spring Security 가 있을 때. 호출자는 `Authentication.name` = 계정 id, 운영자는 `ROLE_<admin-role>`):

| 메서드 · 경로 | 하는 일 | 누가 |
|---|---|---|
| `GET /documents` | 종류 · 언어마다 현재 판 (제목 · 해시 · 필수 여부 · 다음 판), 캐시 가능 | 누구나 |
| `GET /documents/{type}?version=&locale=` | 본문(마크다운, 사실 채움) + 메타. 시행된 옛 판도 읽힌다 | 누구나 |
| `GET /consents/me` | 무엇이 현재이고 내가 무엇에 동의했고 무엇이 모자라거나 낡았나 (`blocked`) | 로그인 |
| `POST /consents` | `{consents:[{type,version,locale?}]}` 에 동의 — 낡은 판은 `409`, 멱등 | 로그인 |
| `POST /consents/{type}/withdraw` | 선택 종류의 동의를 거둔다 (자기 사건으로 기록) | 로그인 |
| `GET /consents/me/history` | 내 동의 사건 (IP 없음) | 로그인 |
| `GET /admin/consents` · `GET /admin/documents` | 모든 주체의 사건(필터) · 매니페스트 상태 | 운영자 |

에러 코드는 `LEGAL.*` ([에러 표](../errors.md)에 같은 모양): `CONSENT_REQUIRED` 400 (가입) · `RECONSENT_REQUIRED` 403 (재동의 필터) · `VERSION_STALE` · `WITHDRAWAL_NOT_ALLOWED` 409 · `UNKNOWN_DOCUMENT` 400 · `DOCUMENT_NOT_FOUND` 404 · `RATE_LIMITED` 429.

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:legal"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | 없음 |
| 설정 접두사 | `skeleton.legal` — [docs/config/modules/legal.yml](../config/modules/legal.yml) |
| 기본 동작 | 켜짐. 필수 종류는 `terms` · `privacy`, 가입도 같다. 유예 0, 재동의 필터 꺼짐, IP · UA 를 남기고 365일 뒤 비운다, 계정 삭제는 익명화. `account` 의 가입이 `consents` 를 싣고 이 모듈이 있으면 검사 · 기록한다 (`SignUpConsentGate`) |
| 부팅에 필요한 것 | 저장소 포트 둘(`ConsentStore` · `LegalLedger`) — `legal-jdbc` 가 내거나 앱이 직접 구현한다. 없으면 시작이 실패하고 메시지가 빠진 빈 이름을 적는다. 문서: 앱의 `manifest.json` 또는 모듈의 TEMPLATE. 문서가 깨졌거나 발행된 판의 본문이 장부와 다르면 시작이 실패한다 |
| 교체 지점 | `ConsentStore`, `LegalLedger`, `LegalCatalog`, `LegalRules`, `ConsentService`, `SignUpConsentGate`, `LegalCallers`, `LegalDocumentController`, `ConsentController`, `LegalAdminController` |
| 마이그레이션 | 없음 (스키마는 `legal-jdbc`) |
| 프론트 짝 | 없음 (HTTP 계약: [legal-http-contract.md](../legal-http-contract.md)) |
| 테스트 | `modules/legal/src/test`, `modules/legal/src/noOptionalTest` (`spring-security-core` 가 클래스패스에 없을 때) |

## 앱이 바꾸는 곳

- 문서: `src/main/resources/legal/manifest.json` + `<종류>/<판>.<언어>.md` — 새 판은 `versions` 끝에 더한다. 발행된 판(`REVIEWED`)은 고치지 않는다.
- 필수 · 선택: `skeleton.legal.required` / `required-at-sign-up` (리스트가 기본값을 대체한다). 새 종류(`marketing` · `age` · `refund`)는 매니페스트에 적으면 생기고, 필수로 만들려면 `required` 에도 적는다.
- 사실: `skeleton.legal.facts.<키>` — 문서의 `{{키}}`.
- 가입 · 재동의: 가입 요청의 `consents`, `skeleton.legal.reconsent.enabled=true`. 자세한 흐름 · 경로별 차이는 [docs/legal.md](../legal.md).
- 결제 같은 곳의 동의: `ConsentService.record(subject, claims, ctx, "checkout", referenceId = 주문id)` · `requireCurrent(subject, 종류들, 주문id)`.

## Decisions and rejected alternatives

**모듈 둘.** `legal`(계약 · 서비스 · HTTP · 가드) + `legal-jdbc`(저장 · 트리거). board · alert 와 같다. 버린 것: *한 모듈*(두 DB 의 SQL · 트리거가 서비스와 섞이고 저장소를 못 바꾼다), *메모리 기본*(증거가 재시작에 사라지는 함정 — 어댑터가 없으면 시작을 실패시킨다).

**`account` 와 서로 모른다.** 가입 고리 · 삭제 고리 · 내보내기 고리는 `platform` 의 계약이다. 버린 것: *`compileOnly(account)` 다리*(타입을 쓰는 쪽이 없다 — 고리가 platform 에 있으면 둘 사이에 선이 필요 없다).

**주체 + 참조.** 동의는 페이지가 아니라 주체(`subjectType`, `subjectId`)에 묶고 선택 `referenceId`(주문)를 얹는다. (주체 · 종류 · 참조) 안의 순번이 유니크 키라서 동시 기록은 하나만 이기고 진 쪽은 다시 읽는다.

**더하기만 한다.** 동의 사건과 판 장부는 DB 트리거가 고치기 · 지우기를 막는다(예외: 계정 삭제 때의 익명화 · IP/UA 비우기). 철회는 `WITHDRAWN` 사건 — 동의 줄을 고치지 않는다.

**TEMPLATE 은 stage · prod 에서 막는다.** 의존성 한 줄로 서비스 구색이 서야 하지만 예시 문안으로 실사용자를 받으면 안 된다. 앱이 자기 문서로 바꾸거나 `acknowledge-template` 로 일부러 허락한다.

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md) · [스키마 관리](../schema-management.md)
