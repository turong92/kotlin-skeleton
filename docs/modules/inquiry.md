# inquiry

문의 · 의견 받기 — 사람이 질문 · 기능 제안 · 오류 신고 · 기타를 한 줄 폼으로 보낸다. 로그인 없이도 되고(사람 확인 필요), 로그인한 호출은 계정이 붙고 사람 확인을 건너뛴다.
저장은 포트(`InquiryRepository`)이고 기본은 인메모리다 — DB 는 `inquiry-jdbc` 가 붙인다.

**공개 경로**(서블릿 웹 앱 + Spring Security 가 있을 때, 기본 켜짐 `skeleton.inquiry.enabled`): `POST /api/v1/inquiries` → 202 `{value: {status: "RECEIVED"}}`(`Cache-Control: no-store`).
본문 `{kind: QUESTION|FEATURE|BUG|OTHER, body(10~2000자), replyEmail?, context?(≤100자 선택 문자열 — 앱이 뜻을 정한다, 예: 어느 화면에서 왔는지), lang?(ko|en|ja), captchaToken?}`.
오류: 400 `INQUIRY.INVALID`(`data.violations` — 어긴 칸을 한꺼번에) · 400 `INQUIRY.CAPTCHA_FAILED` · 429 `INQUIRY.TOO_MANY`(`data.retryAfterSeconds`).
이 경로는 모듈이 공개로 풀어 준다(`PublicEndpointContributor`). 유효한 액세스 토큰이 오면 `Authentication.name`(auth 모듈에서는 계정 id)이 계정으로 붙는다 — 잘못된 토큰은 보안 필터가 401 로 먼저 막는다.

**접수 순서**: 값 검사 → 한도 → (로그인하지 않았으면) 사람 확인 → 저장 → 리스너. 한도를 사람 확인보다 앞에 둬 1회용 토큰을 헛쓰지 않는다. 한도는 로그인하지 않은 호출은 클라이언트 키(`ClientIps.limitKey`)별 `per-client`, 로그인한 호출은 계정별 `per-account` 를 `window` 동안.
한도 저장소는 `RateLimitStore` 빈이 있으면 그것(`redis-rate-limit` 이면 여러 대가 공유), 없으면 메모리(서버 한 대 기준, 다시 켜면 빈다).
본문 · 답장 이메일은 로그에 싣지 않는다(`InquiryRequest.toString` 이 가린다).

**사람 확인** `InquiryHumanCheck` — 기본은 **항상 통과**다. 공개 서비스는 하나를 켠다: `captcha-turnstile` 이 있으면 검증기를 자동으로 잇고(`InquiryTurnstileAutoConfiguration`, 앱이 `InquiryHumanCheck` 빈을 두면 그쪽이 이긴다), 아니면 앱이 직접 구현한다. 사람 확인 없이 열면 한도(`per-client`)만 남는다.
**접수 경보** `InquiryListener` — 저장 뒤 불린다(여럿 가능, 던져도 접수는 이미 끝났으니 삼킨다). `alert` 모듈이 있으면 `OwnerAlerts` 로 「문의 #번호 (종류)」 한 줄을 알린다(`InquiryAlertAutoConfiguration` — 본문 · 이메일은 싣지 않는다). `inquiryOwnerAlert` 빈을 두면 바꿀 수 있다.

**운영자 경로**(`skeleton.inquiry.admin.enabled=true` 일 때만 — 모듈 기본 꺼짐, 꺼져 있으면 경로가 없어 404; 익명 401 · 권한 없음 403):

| 메서드 · 경로 | 권한 | 하는 일 |
|---|---|---|
| `GET /api/v1/admin/inquiries?status&limit` | `inquiry.read` | 최신순 목록(`limit` 기본 100 · 최대 200, `status` = `NEW`·`DONE`, 모르는 값은 400). 줄 `{id, kind, body, replyEmail, signedIn, context, lang, status, createdAt, handledAt}` — 계정 id 는 없다(로그인해서 보냈는지만). 본문 · 이메일 원문이 드니 꼭 필요한 운영자에게만 |
| `PUT /api/v1/admin/inquiries/{id}/status {status}` | `inquiry.handle` | `DONE`(처리 시각을 적는다) · `NEW`(되돌리면 비운다). 없는 번호 404 `INQUIRY.NOT_FOUND` |

권한은 기본 역할이 없어 전권(`ADMIN`)만 가진다 — 역할 관리에서 나눠 준다. 처리 표시는 `OperatorAuditLog` 에 `inquiry.status.changed`(대상 = 문의 번호, 상태만)를 남긴다. 답장은 화면에서 `replyEmail` 을 복사해 직접 보낸다(메일 발송 기능은 없다).
이 모듈이 주는 운영 쪽은 목록과 처리 표시까지다 — 운영 화면은 앱이 만든다(`InquiryAdminService` 빈을 써도 된다).

| 항목 | 내용 |
|---|---|
| 의존성 한 줄 | `implementation(project(":modules:inquiry"))` |
| 함께 오는 모듈 | `platform` |
| 컴파일 전용 | `alert`, `captcha-turnstile` |
| 설정 접두사 | `skeleton.inquiry` — [docs/config/modules/inquiry.yml](../config/modules/inquiry.yml) |
| 기본 동작 | 켜짐. 저장은 인메모리, 사람 확인은 항상 통과, 서블릿 앱이면 `POST /api/v1/inquiries` 도 켜진다. 운영자 경로는 꺼짐(404). |
| 부팅에 필요한 것 | `TimeProvider` 빈(platform). 운영에는 `inquiry-jdbc`(저장)와 사람 확인이 필요하다. |
| 교체 지점 | `InquiryRepository`, `InquiryHumanCheck`(사람 확인), `InquiryListener`(접수 뒤 — 여럿 가능), `InquiryController`, `InquiryAdminController`, `inquiryPermissionDeclaration`, `inquiryOwnerAlert` |
| 마이그레이션 | 없음 (`inquiry-jdbc` 가 가진다) |
| 프론트 짝 | `@skeleton/inquiry` |
| 테스트 | `modules/inquiry/src/test` |

계정이 지워질 때 문의의 계정 연결만 끊는 고리(`InquiryErasureListener` — 본문 · 답장 이메일은 남는다)가 함께 등록된다(`AccountErasureListener`). 본문에 개인정보가 들어올 수 있으니 보관 기간은 앱이 정한다(이 모듈은 지우지 않는다).

[모듈 색인](README.md) · [최소 구성 가이드](../minimal-composition.md)
