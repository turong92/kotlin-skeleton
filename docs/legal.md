# 약관 · 개인정보 처리방침 · 동의 기록 (`legal`)

> 목표: 도메인만 바꿔 켜도 서비스 구색이 갖춰진다 — 이용약관 · 개인정보 처리방침이 서고, 가입할 때 동의가 **기록**되고, 새 판이 시행되면 다시 동의를 받는다.
> 모듈 한 줄(`legal-jdbc`)로 오고, 문서는 앱이 싣는다. HTTP 계약(프런트용): [legal-http-contract.md](legal-http-contract.md). 모듈 한 장 요약: [modules/legal.md](modules/legal.md) · [modules/legal-jdbc.md](modules/legal-jdbc.md).

**이 문서와 모듈은 법률 자문이 아니다.** 모듈이 하는 일은 *증거를 정직하게 남기는 기계장치*다 — 누가 · 언제 · 어느 판(본문 해시)을 · 어느 언어로 보고 · 어디서 동의했는지. 문서의 내용이 법에 맞는지는 앱(주인)과 법률 검토의 몫이다.

## 1. 모델

| 개념 | 뜻 |
|---|---|
| 종류 (type) | 문자열 코드. 기본 `terms` · `privacy`, 앱이 `marketing` · `age` · `refund` … 를 더한다. 종류 목록은 **매니페스트**가 정한다 |
| 판 (version) | 문서 하나의 한 번의 발행. 시행일(`effectiveFrom`) · 상태(`DRAFT` / `REVIEWED`) · 언어별 원문을 가진다. 순서는 시행일이 정한다 (이름은 비교하지 않는다) |
| 현재 판 | 시행일이 지난 판 중 가장 늦은 것. 시행일이 미래인 판은 `next` 로만 보인다. 시행일이 없는 `DRAFT` 는 작업 중이라 절대 현재가 되지 않는다 |
| 유예 (grace) | 새 판이 시행된 뒤 `previous-version-grace` 동안 **바로 앞 판**의 동의도 받아 준다. 모듈 기본 0 |
| 주체 (subject) | `subjectType` + `subjectId`. 기본은 `account` + 계정 id. 조직 · 기기 같은 다른 주체도 같은 서비스를 쓴다 |
| 참조 (referenceId) | 선택. 주문 같은 것에 묶인 동의(`checkout`)를 일반 동의와 따로 센다. 서비스 API 로만 쓴다 — HTTP 는 받지 않는다 (남용 방지) |
| 동의 사건 | `AGREED` / `WITHDRAWN` 한 줄. **더하기만 한다**. 종류 · 판 · 원문 해시 · 본 언어 · 시각 · 출처(`sign-up` · `consent` · `re-consent` · `checkout` · `withdraw`) · (설정하면) IP · UA |

### 문서는 앱이 싣는다

```
src/main/resources/legal/
├── manifest.json          # 종류 · 판 · 시행일 · 상태 · 언어 · (REVIEWED 는) 언어별 sha256
├── terms/2026-10-01.ko.md
├── terms/2026-10-01.en.md
└── privacy/…
```

위치는 `skeleton.legal.location`(스프링 리소스 문자열, 기본 `classpath:legal/`). **`manifest.json` 이 없으면** 모듈이 예시로 주는 TEMPLATE 문서(`terms` · `privacy` · `marketing`, ko/en)로 물러선다 — 그래서 의존성 한 줄만으로 서비스가 뜬다. 그 문서는 `template: true` + `DRAFT` 이고 **stage · prod 에서는 DeployGuard 가 기동을 막는다**(자기 문서로 바꾸거나 `skeleton.legal.acknowledge-template=true` 로 일부러 허락 — 시험 배포용).

본문은 안전한 마크다운 부분집합(제목 · 문단 · 강조 · 목록 · 인용 · 표 · 구분선 · `https:` `mailto:` 상대 · `#앵커` 링크). 원시 HTML · 이미지 · 다른 스킴 링크는 시작 때 거부된다. `{{company-name}}` 같은 자리표시는 `skeleton.legal.facts.<키>` 로 채운다.

### 못 박기 — 발행된 판은 몰래 바뀌지 않는다

1. **원문 해시**: 정규화(BOM · 줄바꿈 · 줄 끝 공백 · 끝 빈 줄)한 원문의 SHA-256. 동의 기록이 이 해시를 들고 있다 — 사용자가 본 글자와 묶인다.
2. **매니페스트의 `sha256`**: `REVIEWED` 판은 언어별로 필수. 원문과 다르면 시작이 실패하고 *계산한 값이 메시지에 나온다* (붙여 넣으면 된다).
3. **장부(`legal_document_versions`)**: 처음 본 (종류 · 판 · 언어)를 상태 · 해시 · 시행일과 함께 더한다. 이후 `REVIEWED` 로 적힌 판의 본문 · 시행일이 바뀌었거나 판이 사라졌으면, `DRAFT` 로 적힌 판이 같은 이름으로 `REVIEWED` 로 올라오면(초안에 받은 동의가 검토본 동의처럼 보이지 않게) 시작이 실패한다. 고치려면 **새 판**. DB 트리거가 장부의 고치기 · 지우기를 막는다.
4. `DRAFT` 는 편집해도 된다(해시를 검사하지 않는다) — 기록에는 그때 본 해시가 들어간다.

## 2. 흐름

```
매니페스트 + 원문 ──(시작)──▶ 검사(문법 · 해시 · 사실 · 필수 종류) ──▶ 장부에 못 박기 ──▶ DeployGuard
공개 GET /documents           현재 판 · 다음 판 · 본문(사실을 채워서) · 캐시(ETag)
가입 ─ consents:[{type,version}] ─▶ 열 때 check(요청만 본다) ─▶ 시도에 실려 저장 ─▶ 코드 확인 때 계정과 같은 트랜잭션에 기록
소셜 · 매직 링크 첫 로그인 ─▶ 계정 생성 · 로그인 성공 ─▶ (재동의 필터) 403 LEGAL.RECONSENT_REQUIRED ─▶ 동의 화면 ─▶ POST /consents
새 판 시행 ─▶ (유예 안) 통과 · GRACE ─▶ (유예 밖) 403 ─▶ 동의
```

### 가입 경로별로 동의가 어떻게 묶이나

| 경로 | 동의가 묶이는 방식 |
|---|---|
| 이메일 + 비밀번호 (코드 확인) | `consents` 가 요청에 실려 **가입 시도에 저장**된다. 코드가 확인되어 계정이 만들어지는 **같은 트랜잭션**(`AccountTransaction`)에서 기록 — 확인되지 않은 시도는 기록을 남기지 않고, 시도 id · 코드 없이는 아무도 동의를 바꿀 수 없다. 기록의 IP · UA 는 가입 *요청*의 것(체크한 곳). 응답은 주소와 무관하게 같다(검사는 요청 본문과 문서만 본다) |
| 이메일 확인을 끈 앱 | 계정을 만드는 같은 트랜잭션에서 즉시 기록 |
| 이메일 확인을 나중에 켠 앱에 남은 미확인 계정을 코드로 이어받을 때 | 메일함 증명과 같은 트랜잭션에서 기록 |
| 소셜 · 매직 링크 (첫 로그인이 계정을 만든다) | **계정 생성 때는 동의가 없다.** 로그인은 성공하고, 세션이 온전히 쓰이기 *전에* 동의를 거친다: 프런트가 로그인 직후 `GET /consents/me` 로 `blocked` 를 보고 동의 화면을 띄우며, 서버는 재동의 필터로 이를 강제한다(필터를 끄면 강제는 없다) |

**소셜 · 매직 링크를 요청에 `consents` 를 싣는 방식으로 하지 않은 이유**: 매직 링크를 여는 페이지 · 소셜 리디렉션 페이지는 그 계정이 이미 있는지 모른다(체크박스를 신규에게만 보이면 가입 여부가 드러나고, 모두에게 매번 묻는 것은 잘못이다). 대가: 계정이 동의 기록 없이 잠시 존재한다 — 동의 · 로그아웃 · 삭제 외에는 할 수 없다.

### 재동의 필터

`skeleton.legal.reconsent.enabled=true`(옵트인; 스타터 · 샘플은 켠다). 실제 엔드포인트(HandlerMethod)에 로그인한 호출자가 필수 종류 중 `MISSING` · `OUTDATED`(유예 밖) · 철회가 있으면 `403 LEGAL.RECONSENT_REQUIRED` + `data.missing`. 제외: `/api/v1/legal/**` · `/api/v1/auth/**` · `/api/v1/account/**`(동의 · 로그아웃 · 삭제로 빠져나갈 길). 없는 경로는 그대로 404. 요청마다 색인 있는 읽기 한 번 — 부담이면 캐시를 얹는 자리가 `ConsentService` 다.

### 철회 · 이력 · 내보내기 · 삭제

- 선택 종류(`required` 에 없는 것)는 `POST /consents/{type}/withdraw` 로 거둔다 — 철회는 **자기 사건**이고 동의 줄은 그대로다. 필수 종류의 철회는 거절(`409`) — 나가는 길은 계정 삭제.
- 사용자 이력 `GET /consents/me/history`(IP 없음), 운영자 `GET /admin/consents`(필터).
- 데이터 내보내기: `AccountDataExporter`(구역 `legal`) — 앱이 모아 쓴다.
- **계정 삭제**(`AccountErasureListener`): 기본 `ANONYMIZE` — 줄은 남기고 사람을 지운다(주체 id → `deleted:<해시>`, IP · UA 비움). 종류 · 판 · 해시 · 시각 · 출처가 남아 "그 시점에 이 판에 동의한 누군가가 있었다" 는 증거가 되지만 누구인지는 알 수 없다. `DELETE` 로 바꾸면 익명화한 뒤 줄까지 지운다. DB 트리거: 지우기는 익명화된 줄에만, 고치기는 익명화 · IP/UA 비우기에만 허락한다 — 그 밖의 `UPDATE`/`DELETE` 는 DB 가 거절한다.
- IP · UA 보관: `record.store-ip` / `store-user-agent`(끄면 아예 안 남긴다), `record.personal-data-retention`(기본 365일)이 지나면 비운다(줄은 남는다) — 기록할 때 간격마다 한 번 돈다(스케줄러 불필요).

## 3. 무엇이 모듈의 일이고 무엇이 앱(법적으로는 주인)의 일인가

| 모듈이 한다 | 앱 · 주인이 한다 |
|---|---|
| 판 · 시행일 · 유예 · 현재 판 계산 | **문서 본문을 쓰고 법률 검토를 받는다** (TEMPLATE 은 예시일 뿐) |
| 원문 해시 · 장부 · 몰래 바뀌지 않는 보장 | 검토가 끝난 판만 `REVIEWED` 로 올린다 — 올리기 전에 `DRAFT` 로 둔다 |
| 동의 기록(append-only) · 멱등 · 동시성 | 무엇을 동의로 받아야 하는지 정한다(필수 · 선택, 가입 · 결제 · 마케팅) — 마케팅 · 청소년 · 국외 이전 같은 별도 동의가 필요한지 |
| 가입 · 재동의 흐름의 기계장치 · 403 | 동의 화면 · 문구 · 링크 배치(프런트), 체크박스를 **미리 체크하지 않기** |
| 철회를 자기 사건으로 | 보관 기간(개인정보 보유 · 파기 정책) 결정, 방침에 적은 처리와 실제 처리를 맞추기 |
| 계정 삭제 때 익명화 | 법이 요구하는 증거 보관을 어디까지 할지(`ANONYMIZE` vs `DELETE`) 정한다 |
| 사실 자리표시(`{{company-name}}`)와 빠진 사실 가드 | 회사 이름 · 연락처 · 개인정보 책임자 같은 **사실을 채운다** |

### 앱이 prod 전에 채워야 하는 것 (체크리스트)

1. `src/main/resources/legal/` 에 자기 문서와 `manifest.json` — `template: true` 를 지운다 (DeployGuard 가 막는다).
2. 검토를 받은 판은 `status: REVIEWED` + `effectiveFrom` + 언어별 `sha256`. 시작 실패 메시지가 계산한 해시를 알려 준다.
3. `skeleton.legal.facts.*` — 문서가 쓰는 모든 `{{키}}` (가드가 빠진 키 이름을 말한다).
4. 필수 · 선택 종류와 유예 기간 정하기 (`required`, `required-at-sign-up`, `previous-version-grace`).
5. IP · UA 를 남길지, 보관 기간, 삭제 방식 정하기.
6. 프런트의 가입 폼 · 동의 화면 · 설정(철회) — [계약](legal-http-contract.md) 체크리스트.
7. **MySQL**: 트리거를 만들려면 `log_bin_trust_function_creators=1` 이거나 binlog 가 꺼져 있거나 마이그레이션 계정에 `SUPER` 가 있어야 한다 ([modules/legal-jdbc.md](modules/legal-jdbc.md)).

## 4. 위협 · 남용 메모 (시험이 있는 것만 적는다)

| 위협 · 남용 | 대응 | 시험 |
|---|---|---|
| 동의 확인 응답으로 가입된 주소를 알아낸다 | 가입 동의 검사는 요청 본문과 문서 집합만 본다 — 새 주소든 있는 주소든 같은 거절 | `SignUpConsentTest` "the refusal does not depend on the address" |
| 남이 시작한 가입 시도로 내 이름의 동의가 기록된다 | 동의는 시도에 실려 있다가 **코드가 확인될 때** 그 시도의 비밀번호로 만들어진 계정에만 기록된다. 확인 안 된 시도는 기록 없음 | `SignUpConsentTest` "nothing is recorded before the code is verified" · "a wrong code or an attacker's attempt …" |
| 기록 실패로 계정만 생기고 동의는 없음 | 같은 트랜잭션 — 둘 다 되돌려진다 | `JdbcAccountTransactionDbTest` · `SignUpConsentTest` |
| 동시에 같은 동의를 눌러 줄이 늘어난다 | 순번 유니크 키 + 다시 읽기 — 16 스레드가 한 줄 | `ConsentServiceTest` · `JdbcLegalDbTest` (두 DB) |
| 동의 · 철회를 번갈아 눌러 줄을 부풀린다 | 주체당 하루 `record.max-events-per-day`(기본 200) → `429 LEGAL.RATE_LIMITED`. 아무것도 안 바뀌는 재시도는 세지 않는다 | `ConsentServiceTest` |
| 발행된 판 본문을 몰래 고친다 | 매니페스트 해시 + DB 장부 → 시작 실패 | `LedgerPinTest` · `LegalAutoConfigurationTest` · `JdbcLegalDbTest` |
| 동의 기록을 고치거나 지운다 | DB 트리거(예외는 익명화 · IP/UA 비우기) | `JdbcLegalDbTest` "the database refuses …" |
| 문서 본문에 스크립트 · 위험 링크를 넣는다 / 사실 값으로 마크업을 밀어 넣는다 | 안전 부분집합 검사(시작 때 · 채운 결과에도) · 값은 한 번만 채움 | `LegalTextTest` |
| 경로 조각으로 디렉터리를 벗어난다 | 조각은 영숫자 · `.` `_` `-` 만, `..` 거절 | `LegalSourcesTest` |
| 예시 문서를 그대로 prod 에 띄운다 | DeployGuard (stage · prod 기동 거부, 값은 싣지 않는다) | `LegalDeployGuardTest` · `test-deploy-contract.sh` |
| 새 판이 났는데 옛 동의로 계속 쓴다 | 재동의 필터 + 유예 | `ReconsentWebTest` · `LegalJourneyIntegrationTest` |

## 5. 결정과 버린 대안

- **모듈 둘(`legal` + `legal-jdbc`)**: board · alert 와 같은 모양. 계약 · 서비스 · HTTP 는 저장 방식을 모르고, 증거는 재시작에 사라지면 안 되므로 **메모리 기본 구현이 없다**(어댑터가 없으면 시작이 실패하고 빠진 빈 이름이 나온다). 앱이 JPA · jOOQ 로 저장하려면 `ConsentStore` · `LegalLedger` 만 구현한다.
- **`account` 와 서로 모른다**: 고리 인터페이스(`SignUpConsentGate`)와 `AccountErasureListener` · `AccountDataExporter` 는 모두 `platform` 에 있다. `account` 는 빈이 있을 때만 부르고(`legal` 없으면 `consents` 는 무시), `legal` 은 `account` 를 클래스패스에 두지 않는다 — `compileOnly` 다리도 필요 없다. `AccountTransaction` 은 `account` 의 작은 인터페이스(`account-jdbc` 가 DB 트랜잭션으로 구현)다.
- **동의를 시도에 싣는 방식**(vs 코드 확인 요청에 싣기): 동의는 폼에서 체크한 순간의 사실이다. 확인 요청은 다른 브라우저일 수 있고 코드만 있는 사람도 보낼 수 있다.
- **장부는 DB, 못 박기는 매니페스트 + 장부 둘 다**: 코드의 `RELEASED` 목록(참고한 프로젝트의 방식)은 사람이 손으로 더해야 하고 잊는다. DB 장부는 첫 기동에 자동으로 쌓이고 운영 DB 에서 가장 강하다 — 대신 로컬에서 초안을 `REVIEWED` 로 올리려면 새 판 이름을 쓰거나 로컬 DB 를 지운다.
- **언어별 해시**: 사용자가 읽은 언어의 원문과 묶인다. 사실(회사 이름)이 바뀌어 렌더링 결과가 달라지는 것은 해시에 반영되지 않는다 — 중대한 사실 변경은 새 판으로.
- **버린 것**: *페이지(콘텐츠 URL)에 동의를 묶기*(이 모듈은 주체에 묶는다), *HTML 렌더링을 서버에서*(프런트가 렌더, 서버는 안전한 마크다운만 검증), *동의 줄 갱신으로 철회 표시*(증거를 고치는 것이다), *재동의를 응답 헤더로만 알리기*(강제할 수 없다).

[모듈 색인](modules/README.md)
