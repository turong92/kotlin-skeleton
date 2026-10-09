# 로그인 아이디(비공개)와 이메일을 받는 시점 — 이메일이 바뀌어도 안 흔들리는 간판 — 설계 초안

- 날짜: 2026-10-09
- 요청: 주인. 지휘 세션이 넘긴 요구를 두 레포의 코드에서 확인하고 채운 결과다
- 기준 커밋(둘 다 병합 직전): kotlin-skeleton `feat/email-required` `a5b2dd9` · react-skeleton `feat/author-profiles` `d4ba06b`
- 상태: **확정** — 첫 판의 「정해야 할 것」 6건은 주인이 정했고(맨 끝에 기록), 그 결정으로 범위가 늘었다(실시간 중복 확인, 넓힌 아이디 규칙, **이메일을 받는 시점 세 단계**). 2판에서 새로 생긴 3건도 2026-10-09 주인이 모두 추천안(가)으로 확정했다. 남은 「정해야 할 것」은 없다. 작은 선택은 "추천안 적용"으로 표시했다
- 경로 줄임: `A/` = `modules/account/src/main/kotlin/dev/sumin/skeleton/account/` · `AJ/` = `modules/account-jdbc/src/main/` · `R:` = react-skeleton 레포

## 1. 목적과 결정

견본 서비스는 민감한 팬덤 판이라 사용자가 **이메일을 자유롭게 갈아탈 수 있어야** 유리하다. 그런데 이메일이 유일한 간판이면 바뀔 때마다 "내 계정이 뭐였지"가 흔들린다. 그래서 **바뀌지 않는 간판 = 로그인 아이디**를 둔다. 아이디는 본인과 운영자만 본다. 간판이 아이디가 됐으니 소셜로 들어온 사람에게 이메일을 문 앞에서 받을 이유도 줄었다 — **이메일은 글을 쓸 때 받는다.**

| 항목 | 결정 (주인) | 이 문서에서 따라 나오는 것 |
|---|---|---|
| 공개 여부 | **비공개.** 다른 사용자에게 어디에도 내지 않는다. 글에는 닉네임 · 작성 프로필만 (공개 `@아이디` 꼬리표는 넣지 않는다 — 이전 결정) | §11 의 응답 · DB · DTO 세 겹 시험으로 고정 |
| 누가 갖나 | **전원.** 이메일 가입은 가입 양식에서, 소셜 첫 로그인은 인증 직후 "아이디를 정해 주세요" 화면에서 | §6 · §8. 매직 링크 가입 · 시드 계정 · 기존 계정도 같은 게이트를 탄다 |
| 로그인 | **아이디 또는 이메일** + 비밀번호 | §7. `auth` 모듈은 고치지 않는다 |
| 변경 | **못 바꾼다.** 운영자 변경 기능도 만들지 않는다 | 바꾸는 API · 화면이 없다. 정하는 순간에만 확인을 받는다 |
| 아이디 규칙 | **영문 · 숫자 · 마침표 · 하이픈 · 밑줄, 4~20자, 숫자로 시작해도 된다.** 대소문자는 구분하지 않고 **소문자로 저장**(입력은 대문자도 받는다). 금지: `@` · 공백 · 맨 앞과 맨 뒤의 기호. 이메일과는 `@` 유무로 가른다 | §4. 연속 기호는 막는다(추천안 적용) |
| 중복 안내 | **입력하는 즉시**(다른 서비스처럼) — 실시간 확인 API. 양식을 낼 때의 `409` 는 경합용으로 그대로 | §6.2. 로그인 없이 부르므로 IP 당 한도 |
| 탈퇴한 계정의 아이디 | **다시 못 쓴다 (재사용 금지)** | §5. 원문은 지우고 되돌릴 수 없는 해시만 남긴다(추천안 적용). 실시간 확인에서도 "쓸 수 없음" |
| 해시의 비밀이 없는 보호 환경 | **기동을 막는다** | §5.3 |
| 아이디 찾기 | **넣는다.** 이메일을 넣으면 그 계정 이메일로 아이디를 보낸다. 존재는 숨긴다 | §10 |
| 비밀번호 재설정 메일 | 아이디를 **싣지 않는다**(아이디 찾기가 따로 있다) | §10. 재설정 메일은 한 글자도 바뀌지 않는다 |
| 가입 양식의 "다른 곳에서 쓰는 아이디와 다르게" 안내 | **넣지 않는다** | §13.2 |
| 비밀번호에 아이디 포함 금지 | 이번에는 **넣지 않는다** | §18 에 남는 위험으로만 |
| **이메일을 받는 시점** | **세 단계: 안 받음 / 쓰기 때 / 로그인 직후(지금 동작).** 소셜 첫 로그인은 **아이디만 먼저** 정하고 이메일은 "쓰기" 때 받는다. 견본 앱은 "쓰기 때" | §9. 설정은 기존 `email.required` 에 `required-when` 을 더한다(추천안 적용) |
| 게이트 순서 | 로그인 직후는 **아이디 → 약관**, 이메일은 쓰기 때 | §8.2 |
| 이메일로 가입하는 사람 | 지금처럼 가입 때 이메일 인증을 한다 — **변함 없음** | §6. 손대지 않는다 |
| 기존 계정(아이디 없음) | 서비스 중이 아니다 — 가장 싼 방법 | 다음 로그인 때 같은 게이트. 자동 채움 · 백필 마이그레이션 없음 |
| 스켈레톤 기본 | **꺼짐**(지금처럼 이메일 가입). 견본 앱(`apps/sample`) yml 에서 켠다. 끄면 API · 응답 · 화면이 지금과 같다 | §3 의 표 · §16 의 "꺼짐" 시험 |
| 이메일 변경 | 지금 그대로 둔다 | 손대지 않는다 |
| 보안 메일 | 계정 이메일로만 간다. 연락받을 이메일로는 가지 않는다 (기존 결정) | §10. `MailRoutingTest` 에 새 메일을 더한다 |

## 2. 범위와 비목표

**범위**
- 로그인 아이디: `account` · `account-jdbc` 의 설정 · 열 · 가입 · 실시간 중복 확인 · 로그인 · 게이트 · 아이디 찾기 · 본인/관리자 응답.
- 이메일을 받는 시점: `account` 의 이메일 필수 인터셉터에 "쓰기 때" 방식, `me` 의 새 키, 버리기(`discard`)의 뜻 다시 정리.
- `apps/sample` 에서 둘 다 켜기, `R:packages/auth` 의 가입 · 로그인 · 게이트 · 찾기 · 설정 화면과 쓰기 전 확인, `R:apps/sample` 연결, 비공개와 "결제는 막힌다"를 지키는 자동 시험, 문서.

**비목표** (만들지 않는다)
- 공개 핸들 · 멘션(`@아이디`) · 프로필 주소 — 아이디는 어떤 공개 응답 · 화면 · URL 에도 나오지 않는다.
- 아이디 변경(본인 · 운영자 모두), 계정 합치기.
- 기존 계정에 아이디를 자동으로 채우는 백필 · 이메일 앞부분에서 아이디를 만들어 주는 추천.
- 가입 인증 대기 중의 아이디 선점(예약) — §6.3. 실시간 확인의 "쓸 수 있음"도 예약이 아니다.
- 아이디만으로 하는 비밀번호 재설정(재설정은 지금처럼 이메일을 넣는다), 재설정 메일의 아이디, 비밀번호에 아이디 포함 금지 규칙.
- `auth` 모듈 변경 — 요청 본문의 `username` 칸이 이미 있다 (§7).
- 교체 가능한 아이디 규칙 빈 — 규칙이 로그인 칸의 `@` 판별 · 열 길이와 묶여 있어 앱이 바꾸면 깨진다. 길이와 예약어만 설정이다.
- 제공자 재확인으로 페이지를 떠날 때 쓰던 글을 보존하는 초안 저장 (§9.5).
- WebSocket 으로 들어오는 쓰기 메시지의 이메일 확인, 모듈이 "내 사소한 쓰기"를 스스로 신고하는 SPI (§9.3).

## 3. 설정 — 키와 꺼짐/켜짐 동작 표

### 3.1 로그인 아이디 — `skeleton.account.login-id.*`

`A/AccountProperties.kt` 에 `LoginId` 묶음 하나, `docs/config/modules/account.yml` 에 같은 조각.

| 키 | 기본 | 뜻 |
|---|---|---|
| `enabled` | `false` | 켜면 아래 표의 "켜짐" 동작 전부. 반쪽 스위치는 두지 않는다 (가입만 · 로그인만 켜는 조합은 "전원이 아이디를 가진다"와 어긋난다) |
| `min-length` · `max-length` | `4` · `20` | 아이디 길이. `1 ≤ min ≤ max ≤ 30`(열 길이) 아니면 기동 실패 |
| `reserved` | `[]` | 내주지 않을 아이디. 닉네임의 `display-name.reserved` 를 **재사용하지 않는다** — 그 목록은 공개 이름의 사칭을 막으려는 것(한글 낱말 포함)이고 아이디는 공개되지 않는다. 이 목록의 쓸모는 문의 응대에서 "아이디 admin 입니다" 같은 혼동을 막는 것뿐이다. 시드 계정은 예외 |
| `check-per-ip` · `check-per-ip-window` | `30` · `10m` | 실시간 중복 확인을 한 IP 가 창 안에 부를 수 있는 횟수 — 넘으면 `429 ACCOUNT.RATE_LIMITED`. 값의 근거는 §6.2 |
| `claim-limit` · `claim-window` | `10` · `1h` | 한 계정이 창 안에 「아이디 정하기」를 **시도**할 수 있는 횟수(성공 · 실패 모두 센다). 넘으면 `429 ACCOUNT.RATE_LIMITED` |
| `extra-exclude-paths` | `[]` | 아이디 없이도 **더** 열어 둘 경로. `email.extra-exclude-paths` 와 같은 문법 · 같은 규칙(더하기만, 못 읽으면 기동 실패) |

아이디 찾기의 한도는 새 키를 만들지 않고 `reset.per-ip` · `reset.per-email` 의 **값**을 쓰되 버킷은 따로다 (추천안 적용 — 같은 성격의 "이메일을 넣으면 메일이 간다" 요청이고 키를 늘릴 이유가 없다).

> **구현에서 바뀜(리뷰 I3 · B6)**: `claim-limit` 은 위 표의 "성공 · 실패 모두" 가 아니라 **형식을 통과한 시도만**(성공 · 실패 모두) 센다 — 형식 오류 `400` 은 세지 않는다(가입 · 실시간 확인과 같은 자리). 그리고 **계정별 하나 + IP 별 하나**(`login-id-claim:ip`, 값은 `claim-limit` · `claim-window` 를 공유하고 버킷만 따로)다. 이유: 소셜 신원 하나로 정하기 → 버리기 → 재로그인을 되풀이하면 계정마다 한도가 새로 열려 아이디를 영구히 태울 수 있었다. 대가: 한 IP 뒤 사용자들이 한도를 같이 쓴다. `check-per-ip-window` · `claim-window` 는 0 보다 커야 기동한다(리뷰 M8).

| 어디 | 꺼짐 (기본 — 지금과 같다) | 켜짐 |
|---|---|---|
| `POST /account/sign-up` 의 `loginId` | 읽지 않는다(값이 있어도 무시, 검증도 안 한다) | 필수. 규칙 위반 `400`(필드 `loginId`), 쓸 수 없는 아이디 `409 ACCOUNT.LOGIN_ID_TAKEN` |
| `POST /auth/verify-email` 의 `loginId` | 무시 | 선택 — 가입 요청의 아이디를 갈아 끼운다(겹쳐서 막힌 사람의 재시도) |
| `POST /account/login-id/availability` | `404` | 있다 (공개) |
| `POST /auth/login` 의 `username` | 이메일로 읽는다 | `@` 가 없으면 아이디, 있으면 이메일 |
| `GET /auth/methods` | 그대로 | `loginId: { minLength, maxLength }` 키가 생긴다 |
| `GET/PATCH /account/me` | 그대로 | `loginId: string \| null` 키가 생긴다 (`null` = 아직 안 정했다) |
| 아이디 없는 계정의 보호된 호출 | 그대로 | `403 ACCOUNT.LOGIN_ID_REQUIRED` (허용 목록 밖 `/api/**` 전부) |
| `POST /account/login-id` · `POST /account/login-id/forgot` | `404` (컨트롤러를 등록하지 않는다) | 있다 |
| 비밀번호 재설정 메일 | 그대로 | **그대로** (아이디를 싣지 않는다) |
| 관리자 계정 응답 · 검색 | 그대로 | `loginId` 키, `?loginId=` 정확히 일치 검색 |
| 스키마 | 새 열 · 새 표는 설정과 무관하게 생긴다(닉네임 열과 같은 관례). 값은 늘 비어 있다 | 값이 채워진다 |

- **나중에 켜기**: 아이디 없는 계정은 다음 호출에서 게이트를 만난다. 따로 할 일이 없다.
- **켰다가 끄기**: 저장된 아이디는 그대로 남고 읽히지 않는다. 다시 켜면 이어진다. 끈 동안 가입한 계정은 다시 켰을 때 게이트를 탄다.
- OpenAPI 문서에는 꺼져 있어도 `SignUpRequest.loginId` · `VerifyEmailRequest.loginId` 선택 칸이 보인다(값은 무시). `verify-email` 의 `displayName` 과 같은 수준의 차이로 본다.

### 3.2 이메일을 받는 시점 — `skeleton.account.email.*`

**추천안 적용: 기존 `required`(boolean)는 그대로 두고 `required-when` 하나를 더한다.**

| 키 | 기본 | 뜻 |
|---|---|---|
| `required` | `false` | (그대로) 계정에 이메일이 있어야 하는가 |
| **`required-when`** (새) | `SIGN_IN` | `required: true` 일 때 **언제 막는가.** `SIGN_IN` = 로그인 직후부터(지금 동작). `WRITE` = 읽기는 통과시키고 변경 요청에서만 |
| `first-registration-window` · `extra-exclude-paths` | (그대로) | (그대로) — 두 방식 모두에서 쓰인다 |

| 주인이 말한 단계 | 설정 |
|---|---|
| 안 받음 | `required: false` (모듈 기본) |
| **쓰기 때** | `required: true` + `required-when: WRITE` (견본 앱) |
| 로그인 직후 | `required: true` (지금 켜 둔 앱은 한 글자도 안 고쳐도 같은 동작) |

- **왜 값 셋짜리 키 하나(`required: NEVER | WRITE | SIGN_IN`)로 바꾸지 않나**: 아직 push 전이라 깨도 되지만 얻는 것이 없다. boolean 을 그대로 두면 `@ConditionalOnProperty(… "required", havingValue = "true")` 로 걸린 자동설정, 방금 쓴 문서(`docs/accounts.md` "이메일 필수" · 계약 FINAL-6 · CHANGELOG), 환경변수 `<P>_ACCOUNT_EMAIL_REQUIRED=true`, `required: true` 를 적어 둔 찍힌 프로젝트의 yml 이 전부 그대로 맞는다. 바뀌는 문서는 "한 줄이 늘었다"뿐이다.
- `required: false` 인데 `required-when: WRITE` 를 적으면 **기동 실패**(설정 이름을 말한다) — "쓰기 때로 켰다"고 믿는데 실제로는 꺼져 있는 사고를 막는다.
- 동작의 세부는 §9.

## 4. 아이디 규칙

한 곳(`A/LoginIds.kt`, `Emails` 옆)에 둔다: `normalize` · `problemOf` · 예약어 비교. 주인이 정한 것은 "결정", 내가 고른 것은 "추천안 적용"이다.

| 항목 | 규칙 | 근거 |
|---|---|---|
| 허용 문자 | 영문 `a-z` · 숫자 `0-9` · 마침표 `.` · 하이픈 `-` · 밑줄 `_` | 결정 |
| 길이 | 4~20 (`min-length` · `max-length`) | 결정 |
| 첫 글자 | 영문 또는 **숫자** | 결정 (숫자 시작 허용) |
| 맨 앞 · 맨 뒤 | 기호(`.` `-` `_`)가 올 수 없다 | 결정 |
| 대소문자 | 입력은 대문자도 받고 **소문자로 바꿔 저장 · 비교**한다(`Locale.ROOT`). 앞뒤 공백은 뗀다 | 결정. 저장값이 하나라 DB 정렬 규칙에 기대지 않는다 |
| `@` · 공백 | 올 수 없다 | 결정. 로그인 칸에서 **`@` 가 있으면 이메일, 없으면 아이디**로 가른다 |
| 연속 기호 | **막는다** — 기호 둘이 붙을 수 없다(`..` · `--` · `__` · `._` · `-.` 모두) | 추천안 적용 — 아래 |
| 숫자만 | **막는다** — 영문이 하나는 있어야 한다 | 확정(2026-10-09 주인): 막는다 |
| 그 밖의 글자 | 전각 · 한글 · 다른 기호는 거절한다(정규화로 고쳐 주지 않는다) | 자판 · 언어와 무관하게 다시 칠 수 있어야 하고 닮은 글자 문제가 없다 |

정규화한 값이 맞아야 하는 모양: `^[a-z0-9]+([._-][a-z0-9]+)*$`, 길이 4~20, 영문 하나 이상.

> **구현에서 바뀜(리뷰 I1 · M9)**: ① 정규식을 돌리기 **전에** 길이를 본다 — 30자(열 길이)를 넘는 입력은 모양과 무관하게 `Size` 이다(긴 입력의 정규식 재귀가 스택을 터뜨렸다). 문제가 겹치면 모양 → 길이 순이라 `12` 는 `Pattern` 이다. ② 소문자로 접는 것은 **ASCII 영문만**이다(켈빈 기호 `K` 같은 닮은 글자는 접지 않고 거절한다).

**연속 기호를 막는 이유**
- 눈으로 세기 어렵다. `a__b` 와 `a_b`, `a..b` 와 `a.b` 는 화면과 글꼴에 따라 구별이 안 되고, 문의 응대에서 말로 불러 주기도 어렵다. 본인이 자기 아이디를 틀리게 기억하는 원인이 된다.
- 기호를 셋으로 넓히면서 "거의 같은 아이디"의 수가 이미 늘었다(`a.b` · `a-b` · `a_b` 는 서로 다른 사람이다). 연달아 쓰기까지 열면 그 수가 곱으로 는다.
- 규칙 하나("기호는 맨 앞 · 맨 뒤 · 연달아 못 쓴다")로 말할 수 있다. 그리고 **나중에 풀 수는 있어도 조일 수는 없다** — 정한 아이디를 못 바꾸기 때문이다.

**넓힌 규칙의 대가**: `sumin.kim` · `sumin-kim` · `sumin_kim` 이 세 사람이 될 수 있다. 아이디는 공개되지 않으므로 사칭 문제는 아니고, 본인이 헷갈리는 비용이다. 아이디 찾기(§10)가 그 출구다.

**거절하는 방식**
- 형식: `400 COMMON.VALIDATION_FAILED`, `errors[].field = "loginId"`, `errors[].code` = `Required` · `Size` · `Pattern`. 프론트가 코드로 문구를 고른다(맨 앞뒤 기호 · 연속 기호 · 숫자만은 모두 `Pattern` — 어느 것인지는 프론트가 같은 규칙으로 안다).
- 예약어: 형식 오류가 아니라 **"이미 쓰는 아이디"와 같은 답**(`409 ACCOUNT.LOGIN_ID_TAKEN`)으로 낸다(추천안 적용 — §6.2 의 "이유를 구분할지"). 비교할 때 기호를 빼고 본다(`ad.min` · `ad_min` = `admin`).
- 형식 검사는 **요청 본문만** 본다(계정 저장소를 읽지 않는다). 가입에서는 IP 한도보다 앞이다 — 지금의 닉네임 · 주소 모양 검사와 같은 자리.

## 5. 저장 — 열 · 유니크 제약 · 탈퇴 · 정지

### 5.1 스키마 (두 방언, 새 V 파일만)

**마이그레이션은 동결이다.** 기존 `V20261005223426__accounts.sql` 등은 건드리지 않는다. `./gradlew newMigration -Pname=account_login_id -Pmodule=modules/account-jdbc` 로 `postgresql` · `mysql` 두 폴더에 새 V 파일을 만들고(버전 번호는 두 방언이 같게) `perl scripts/migrations-lock.pl` 로 잠금에 더한다. 다른 브랜치의 미적용 마이그레이션에 기대지 않는다. 이메일을 받는 시점(§9)은 스키마를 바꾸지 않는다.

```sql
-- postgresql (mysql 은 timestamptz → datetime(6), login_id 에 이진 정렬 — 기존 파일처럼 collate 절은 jOOQ 마커로 가린다)
alter table accounts add column login_id varchar(30);                       -- 소문자로 정규화한 값. 없을 수 있다(아직 안 정한 계정 · 기능을 끈 앱)
alter table accounts add constraint uq_accounts_login_id unique (login_id); -- NULL 은 여럿 허용된다

-- 한 번이라도 쓰인 아이디의 해시. 원문은 없다. 계정 id 도 없다(지워진 계정과 이어지지 않는다). 줄은 지워지지 않는다
create table account_used_login_ids (
    hash    varchar(64) primary key,   -- 서버 비밀의 HMAC-SHA256 hex
    used_at timestamptz not null
);
```

백필은 없다. 기존 행의 `login_id` 는 `NULL` 로 남고 그 계정은 다음 로그인에서 게이트를 탄다.

### 5.2 유일함은 두 겹이다

- **살아 있는 아이디**: `accounts.login_id` 의 유니크 제약.
- **한 번이라도 쓰인 아이디**: `account_used_login_ids.hash` 의 기본키. 아이디를 **정하는 순간**(가입 확정 · 게이트) 같은 트랜잭션에서 해시 한 줄을 넣는다. 탈퇴할 때 옮기는 것이 아니라 처음부터 넣어 두므로, 지우기 · 버리기 · 행 삭제 어느 길로 계정이 사라져도 따로 할 일이 없고 "지우는 중에 누가 그 아이디를 채 가는" 틈이 없다.

정하기는 저장소의 한 연산이다(계정 행 락 → 계정이 `ACTIVE` 이고 `login_id` 가 비어 있는지 → 해시 삽입 → 열 갱신). 어느 유니크에 걸리든 결과는 같은 `TAKEN` 하나다 — 살아 있는 아이디와 탈퇴한 아이디를 응답으로 가르지 않는다. 실시간 확인(§6.2)도 두 곳을 다 읽어 같은 답을 낸다.

### 5.3 탈퇴한 계정의 아이디 — 추천안 적용: 원문은 지우고 해시만 남긴다

주인 결정은 "다시 못 쓰게"다. 방법 셋을 견줬다.

| 안 | 내용 | 판정 |
|---|---|---|
| 가 | 지워진(`ERASED`) 행에 아이디 원문을 그대로 둔다 | **버림.** 아이디는 다른 곳에서 쓰는 핸들과 같을 수 있어 개인정보로 봐야 한다. "개인정보만 지우고 행은 남긴다"는 익명화 약속이 깨지고, `deletion.mode=DELETE`(행까지 지움)에서는 아예 동작하지 않는다 |
| 나 | 탈퇴 때 `account_blocks`(정지 계정의 이메일 · 소셜 주체 해시 차단)에 `login_id` 종류로 옮긴다 | **버림.** 그 표는 운영자가 목록으로 보고 지울 수 있고 `blocks.retention` 으로 만료된다 — 둘 다 "재사용 금지"와 어긋난다. 줄에 계정 id 가 있어 해시가 지워진 행과 이어진다. 옮기는 순간의 경합도 따로 막아야 한다 |
| **다** | §5.2 의 전용 표에 **정할 때부터** 해시를 둔다. 탈퇴 익명화는 `login_id` 열만 `NULL` 로 비운다 | **채택.** 원문이 남지 않고, 해시가 계정과 이어지지 않고, 두 삭제 모드에서 같고, 경합이 없다 |

- 해시는 `AccountBlocks` 와 **같은 서버 비밀**(`skeleton.account.blocks.secret`, 비면 JWT 비밀에서 파생)에 용도 접두사 `login-id:` 를 붙인 HMAC-SHA256 이다. 열쇠 없는 SHA-256 은 쓰지 않는다 — 아이디는 짧아서 사전 대입으로 되돌려지고, 그러면 원문을 둔 것과 같다.
- **비밀이 바뀌면** 탈퇴한 아이디의 해시가 맞지 않아 재사용이 조용히 풀린다(살아 있는 아이디는 열의 유니크가 계속 지킨다). 그래서 보호 환경(`stage` · `prod`)에서 `login-id.enabled=true` 인데 `blocks.secret` 이 비어 있으면 `AccountDeployGuard` 가 **기동을 막는다**(결정). 관리자 API 만 켠 앱의 지금 동작(경고)은 그대로 둔다. 견본 앱의 배포 선언(`deploy/app.yaml`)에는 이 비밀이 이미 있다.
- **개인정보 처리방침에 적을 것(앱의 몫)**: 탈퇴 뒤에도 아이디의 되돌릴 수 없는 해시가 재사용을 막기 위해 기한 없이 남는다. `docs/accounts.md` 의 같은 목록에 한 줄을 더한다.

### 5.4 상태별 아이디

| 계정 상태 | 아이디 열 | 아이디로 로그인 | 남이 그 아이디를 정할 수 있나 |
|---|---|---|---|
| `ACTIVE` | 있다 | 된다(비밀번호가 있으면) | 안 된다 |
| `SUSPENDED` (정지는 박제) | 그대로 | 맞는 비밀번호에 `403 AUTH.ACCOUNT_SUSPENDED` (이메일 로그인과 같다) | 안 된다 |
| `DELETED` (탈퇴 유예) | 그대로 — 복구되면 그대로 쓴다 | 없는 계정처럼 `401`. `deletion.self-restore` 면 맞는 비밀번호에 탈퇴 대기 응답 (이메일 로그인과 같다) | 안 된다 |
| `ERASED` (익명화) | `NULL` | 안 된다 | 안 된다 (해시) |
| 행 삭제(`deletion.mode=DELETE`) · 운영자 지우기 · **버리기(`discard`)** | 행 없음 또는 `NULL` | 안 된다 | 안 된다 (해시) |

- 정지 · 탈퇴 유예 중인 계정은 아이디를 **새로 정하지 못한다**(`403 ACCOUNT.NOT_ACTIVE` — 로그인 수단을 붙이지 못하는 기존 규칙과 같은 줄).
- 버린 계정의 아이디도 영영 묶인다. 이제 아이디를 정한 뒤에 버리는 일이 생긴다(§9.6) — 그래서 버리기 길을 아이디를 정하기 **전** 화면에도 둔다(§8.3).

### 5.5 저장소 포트 (`A/AccountModels.kt`)

- `Account.loginId: String? = null`.
- `insert(account, identities, loginIdHash = null)` — 계정에 아이디가 있으면 해시도 같은 트랜잭션에 넣는다. 유니크에 걸리면 지금처럼 `false`, 부르는 쪽이 아이디 → 이메일 → 수단 순으로 다시 조회해 가른다.
- `findByLoginId(loginId)` · `loginIdUsed(hash)` · `claimLoginId(id, loginId, hash, now): CLAIMED | TAKEN | ALREADY_SET | NOT_ACTIVE | NOT_FOUND`.
- `erase` 는 `login_id` 도 비운다. `search` 는 `loginId` 정확히 일치 조건을 받는다.
- `InMemoryAccountRepository` 와 `JdbcAccountRepository` 둘 다 구현한다. 해시는 서비스가 계산해 넘긴다 — 저장소는 비밀을 모른다.

## 6. 가입 (이메일 가입)과 실시간 중복 확인

이메일로 가입하는 사람의 흐름은 **지금 그대로**다(가입 → 메일 코드 → 계정). 요청 본문에 `loginId` 가 하나 늘 뿐이고 닉네임이 이미 쓰는 틀을 따른다.

### 6.1 가입 요청과 확정

**`RegistrationService.signUp` 의 순서** (굵은 것이 새로 들어가는 자리)

1. 닉네임 형식 → 주소 모양 → **아이디 형식**(`400`, 필드 `loginId`). 본문만 본다.
2. IP 한도(`signup:ip`) → 약관 동의 검사 → 캡차.
3. **아이디를 쓸 수 있나** — 예약어, `findByLoginId`, 해시 조회. 못 쓰면 `409 ACCOUNT.LOGIN_ID_TAKEN`.
4. 비밀번호 정책 → 해시 → 가입 시도 저장(프로필 payload 에 `loginId`) → `202`.

- 3번의 답은 **아이디만** 본다. 같이 낸 이메일이 가입돼 있든 아니든 같은 시점 · 같은 응답이라 이메일 존재는 여전히 드러나지 않는다. 요청 스레드가 읽는 것은 아이디 조회뿐이다(주소로는 읽지 않는다는 기존 약속을 지킨다).
- 실시간 확인이 있어도 이 `409` 는 남는다 — "쓸 수 있음"을 본 뒤 내는 사이에 나갈 수 있고, 확인을 건너뛴 클라이언트도 있다.

**확정(`verifyEmail`)**: 계정을 만들 때 아이디를 함께 넣는다. 그 사이 남이 가져갔으면 `409 ACCOUNT.LOGIN_ID_TAKEN` 이고 **시도는 닫히지 않는다**(닉네임 겹침과 같이 `challenges.reopen`). 같은 `signUpId` · 같은 코드에 다른 `loginId` 를 실어 다시 낸다. `AccountCore.insertNamed` 의 결과에 `LOGIN_ID_TAKEN` 하나가 는다. 이메일 확인을 끈 앱(`email-verification=false`)은 가입 요청이 곧 확정이라 같은 `409` 가 바로 나온다.

### 6.2 실시간 중복 확인 — `POST /api/v1/account/login-id/availability` (결정: 입력하는 즉시 알린다)

로그인 없이 부른다(가입 양식). 로그인한 계정도 부른다(「아이디 정하기」 화면) — 그래서 두 게이트의 허용 목록에 든다(§8.1).

- 본문 `{ "loginId": "Sumin.Kim" }` → 늘 `200`:
  - `{ "value": { "normalized": "sumin.kim", "available": true } }`
  - `{ "value": { "normalized": "sumin.kim", "available": false, "reason": "TAKEN" } }`
  - `{ "value": { "normalized": "su", "available": false, "reason": "INVALID", "code": "Size" } }` (`code` = `Required` · `Size` · `Pattern`) — **구현에서 바뀜(리뷰 M4 · M1)**: `reason` · `code` 는 해당 없을 때 **키 자체가 없다**(null 을 싣지 않는다), 판정은 이유와 무관하게 같은 조회를 한다
- **POST 인 이유**: 후보 아이디가 주소 줄 · 접근 로그 · 프록시 로그에 남지 않게. 응답은 `Cache-Control: no-store`.
- **이유를 구분하나 — 추천안 적용: 둘로만 나눈다.**
  - `INVALID`(형식): 구분해 준다. 규칙은 공개된 것이고 프론트도 같은 판정을 하므로 흘리는 것이 없다. 계정 저장소를 읽지 않고, **한도를 쓰지 않는다**(가입의 형식 검사와 같은 자리).
  - `TAKEN`: **예약어 · 살아 있는 아이디 · 탈퇴한 아이디를 하나로** 답한다. 탈퇴한 아이디를 따로 말하면 "예전에 있던 사람"이 드러나고, 예약어를 따로 말해도 사용자가 할 일은 같다(다른 아이디를 고른다). 한 가지 답이면 코드 경로도 하나다. 가입 · 게이트의 `409` 도 같은 뜻이다.
- **한도 — 추천값: 한 IP 가 10분에 30번** (`check-per-ip` · `check-per-ip-window`). 넘으면 `429 ACCOUNT.RATE_LIMITED` + `Retry-After`.
  - 근거: 프론트는 입력이 멈춘 뒤에만, 형식이 맞을 때만 묻는다(§13.1). 후보 하나에 2~4번, 후보를 예닐곱 개 바꿔 봐도 30번 안이다. 같은 공유기 뒤의 두세 사람이 동시에 가입해도 걸리지 않는다.
  - 넘어도 가입이 막히지는 않는다 — 프론트는 "지금은 확인할 수 없어요"로 물러나고 제출 때의 `409` 가 받는다.
  - 캡차는 걸지 않는다(입력할 때마다 풀게 할 수 없다).
- "쓸 수 있음"은 **예약이 아니다.** 내는 순간에 다시 판정한다.
- 후보 아이디는 이벤트 · 감사 · 로그에 남기지 않는다(요청 DTO 의 `toString` 을 가린다).

**열거 위험 평가** (주인이 "다른 서비스처럼"을 고른 대가를 숫자로)

| | 양식을 낼 때만 (첫 판의 추천) | 실시간 확인 (결정) |
|---|---|---|
| 한 IP 가 물어볼 수 있는 양 | 한 시간 10번, 캡차 뒤 | 10분 30번 = 하루 4,320번, 캡차 없음 |
| 콕 집어 "이 아이디가 있나" 한 번 | 된다 | 된다(더 쉽다) |
| 얻는 것 | 아이디가 "쓰인 적 있다"는 사실. 비밀번호는 여전히 필요하고, 로그인 실패 응답은 존재를 말하지 않는다 | 같다 |

아이디 목록을 훑어 만드는 것은 느려질 뿐 막히지 않는다. 한도는 DB 부하와 대량 수집을 묶는 장치다. 남는 위험은 §18.1 의 1.

### 6.3 인증 대기 중의 선점 — 하지 않는다

가입 시도는 계정이 아니고, 아이디는 **계정이 만들어지는 순간**에만 잡힌다. 예약을 두면 메일함을 증명하지 않은 사람이 주소만 바꿔 가며 아이디를 잠가 둘 수 있고(시도는 주소마다 5개, 주소는 무한), 남의 주소로 낸 시도도 그 아이디를 묶는다. 예약이 없으니 만료를 관리할 것도 없다. 대가는 "양식을 낼 때는 비어 있던 아이디가 10분 사이에 나갔다"는 드문 경우이고 §6.1 의 재시도가 받는다.

- 이미 계정이 있는 주소로 낸 시도는 끝나지 않으므로 아이디도 잡히지 않는다.
- 이메일 확인을 나중에 켠 앱에 남은 미확인 계정을 코드로 이어받는 길(`createOrProve` 의 기존 계정 분기)은 아이디를 쓰지 않는다. 그 계정은 로그인 뒤 게이트에서 정한다 (추천안 적용 — 드문 길에 분기를 늘리지 않는다).
- 시드 계정(`seed.accounts[].login-id`)은 예약어 검사 없이 기동 때 넣는다. 견본 앱의 로컬 시드에 아이디를 주지 않으면 e2e 가 전부 게이트에 막힌다.

## 7. 로그인 (아이디 또는 이메일)

`POST /auth/login` 의 본문은 지금도 `accountId | username | email` 중 하나 + `password` 다. **계약도 `auth` 모듈도 바뀌지 않는다.** 바뀌는 곳은 `account` 의 두 군데다.

- `A/AccountAuthRepository.kt` 의 `findBy`: `username` 이 왔을 때 — 켜져 있고 `@` 가 없으면 `LoginIds.normalize` 뒤 `findByLoginId`, 아니면 지금처럼 이메일로 읽는다. 저장된 아이디가 정규화한 입력과 글자 그대로 같을 때만 돌려준다(`accountByEmail` 과 같은 마지막 방어선). 마침표 · 하이픈이 들어가도 `@` 가 없으면 아이디다(`sumin.kim` 은 아이디, `sumin@kim.dev` 는 이메일).
- `A/abuse/LoginControls.kt` 의 `LoginThrottle.bucketOf`: `username` 이 아이디면 그 계정을 찾아 **이메일 로그인과 같은 버킷**으로 모은다 — 계정에 이메일이 있으면 그 주소, 없으면 `accountId:<계정 id>`, 계정이 없으면 `loginId:<입력>`. 지금 `accountId:` 입력을 주소로 풀어 한 버킷에 모으는 것과 같은 방식이다. 입력 방식을 바꿔 가며 계정당 한도(`login.per-account`)를 두 배로 쓰지 못한다.
  - > **구현에서 바뀜(리뷰 I2)**: 위의 "이메일 로그인과 같은 버킷"은 **버렸다.** 입력 방식마다 버킷이 따로다 — `email` 은 그 주소, `username`(아이디로 읽힘)은 **계정이 있든 없든 `loginId:<정규화한 입력>`**(저장소를 읽지 않는다), `accountId` 는 그 계정의 주소 버킷. 합치면 이메일을 아는 사람이 이메일 버킷을 채운 뒤 후보 아이디를 하나씩 보내 `429` 가 나는지로 그 계정의 아이디를 맞혀 볼 수 있다. 대가: 한 계정의 추측이 창마다 이메일 몫 + 아이디 몫이다(`login.per-account` 의 두 배). 이 줄의 "입력 방식을 바꿔 가며 계정당 한도를 두 배로 쓰지 못한다"도 같이 버렸다. 로그인 실패 · 잠김 이벤트의 `detail.id` 는 아이디 입력이면 서버 비밀의 HMAC(리뷰 M3).

프론트는 입력에 `@` 가 있으면 `email` 칸, 없으면 `username` 칸에 실어 보낸다(`username` 은 64자, `email` 은 254자 상한이라 나눠 싣는다).

**실패 응답은 계정 존재를 흘리지 않는다** — 새 분기를 만들지 않으므로 지금의 보증이 그대로 적용된다.

| 경우 | 응답 |
|---|---|
| 없는 아이디 | `401 AUTH.INVALID_CREDENTIALS` (더미 해시 비교 한 번) |
| 있는 아이디 + 틀린 비밀번호 | 같은 `401` |
| 있는 아이디인데 비밀번호가 없는 계정(소셜만) | 같은 `401` (더미 해시 비교 한 번) |
| 탈퇴한 계정의 아이디 | 같은 `401` |
| 한도 초과 | `429 AUTH.TOO_MANY_ATTEMPTS` — 없는 아이디도 똑같이 센다 |
| 정지 · 탈퇴 대기 | **맞는 비밀번호일 때만** 지금의 `403` |

토큰 · `GET /auth/me` 의 `username` 은 지금 값(이메일 또는 계정 id) 그대로 둔다. **아이디는 JWT 에 싣지 않는다** — 토큰은 로그 · 프록시에 남을 수 있다.

## 8. 아이디 정하기 게이트 (소셜 첫 로그인 · 아이디 없는 기존 계정)

이메일 필수(`EmailRequiredInterceptor` · `EmailRequiredRule` · `403 ACCOUNT.EMAIL_REQUIRED`)와 **같은 틀**을 한 번 더 쓴다. 게이트를 타는 계정: 소셜 첫 로그인, 매직 링크로 만들어진 계정, 아이디 없는 시드, 기능을 켜기 전부터 있던 계정.

### 8.1 서버

- **막기**: `LoginIdRequiredInterceptor`(새, `A/web/`). 로그인한 계정의 `login_id` 가 비어 있으면 허용 목록 밖 `/api/**` 전부(읽기 포함)에서 **`403 ACCOUNT.LOGIN_ID_REQUIRED`**(`data` 없음). 경로 읽기 · 핸들러 종류 무시 · HEAD 는 GET · 익명은 그대로 · preflight 통과는 이메일 게이트와 같다. `skeleton.account.http.enabled=false` 여도 `login-id.enabled` 만 보고 등록된다.
- **허용 목록은 두 게이트가 하나를 같이 쓴다**(코드에 고정, 설정은 더하기만): 지금 이메일 게이트의 고정 목록 + 아이디가 켜져 있으면 `POST /api/v1/account/login-id` · `POST /api/v1/account/login-id/availability`. 곧 `GET /account/me` · 아이디 정하기와 중복 확인 · `email/change`(+ `/confirm`) · `discard` · `delete`(+ `/delete/confirmation`) · `/auth/**` · `/legal/**` · `/docs/**`. `EmailRequiredRule` 의 해석기 · 고정 목록을 둘이 쓰도록 이름만 일반화한다(이메일 게이트의 동작은 그대로 — 기존 `EmailRequired*Test` 를 고치지 않고 통과해야 한다).
- **순서: 아이디(`-110`) → 이메일(`-100`) → 약관 재동의(`0`).** 이메일 방식이 무엇이든 같다. 먼저 도는 인터셉터가 읽은 계정 한 줄을 요청 속성에 두고 뒤의 것이 받아 쓴다(추천안 적용 — 요청마다 한 번만 읽는다).
- **정하기**: `POST /api/v1/account/login-id` `{ "loginId" }` → `200` 과 `me` 와 같은 본문.
  - `400`(필드 `loginId`) · `409 ACCOUNT.LOGIN_ID_TAKEN` · `409 ACCOUNT.LOGIN_ID_ALREADY_SET`(이미 있다 — 바꾸기는 없다) · `403 ACCOUNT.NOT_ACTIVE` · `429 ACCOUNT.RATE_LIMITED`(`claim-limit`).
  - **이메일이 없어도 정할 수 있다**(결정: 아이디만 먼저).
  - 재인증은 없다. 한 번도 없던 값을 처음 정하는 온보딩이고, 아이디만으로는 로그인되지 않는다.
  - 이벤트 `LOGIN_ID_SET`(detail 은 `via: sign_up | gate` 뿐 — 아이디 값은 싣지 않는다).
- **상태 판별**: `me.loginId === null`. 키가 하나면 충분하다 — 켠 앱에서 "아직 없음"이 곧 "정해야 함"이다.

### 8.2 순서와 화면 (결정: 로그인 직후는 아이디 → 약관, 이메일은 쓰기 때)

| 앱의 이메일 방식 | 소셜 첫 로그인이 거치는 화면 | 그 뒤 |
|---|---|---|
| **쓰기 때** (견본 앱) | **아이디 → 약관** | 읽기는 바로. 처음 쓰려 할 때 이메일(§9) |
| 안 받음 | 아이디 → 약관 | |
| 로그인 직후 | 아이디 → 이메일 → 약관 | |

- **아이디가 맨 앞인 이유**: 결정이 "아이디만 먼저"이고, 순서를 방식마다 달리하면 서버 인터셉터와 프론트의 막 겹침이 두 벌이 된다. 그리고 "쓰기 때" 방식에서는 로그인 직후 화면이 뒤에서 보내는 변경 요청(알림 읽음 표시 같은 것)이 먼저 `LOGIN_ID_REQUIRED` 로 답해져야 한다 — 이메일이 먼저 답하면 로그인하자마자 이메일 화면이 뜬다.
- **한 화면으로 합치지 않는다.** 이메일 단계는 인증번호 입력이 딸린 두 걸음짜리이고, 약관은 `legal` 모듈의 화면이며, 닉네임은 막는 게이트가 아니라 띠 + 글쓰기 전 확인이다. 합치면 아이디가 "되돌릴 수 없다"는 확인을 다른 입력과 같이 받게 된다. 한 번에 하나만 보인다.
- `legal` 의 재동의는 `/api/v1/account/**` 를 원래 막지 않으므로 아이디 정하기 호출 자체는 약관 전에도 통과한다.

### 8.3 게이트에서 빠져나가는 길

| 길 | 어떻게 |
|---|---|
| 아이디를 정한다 | 막혔던 호출이 그대로 한 번 더 나가 가던 자리에서 이어진다 |
| 로그아웃 | `/auth/**` 는 열려 있다 |
| **이 계정을 지우고 로그인 화면으로 (`discard`)** | 이메일 필수를 켠 앱의 **주소 없는 계정**에게만 보인다. "이미 계정이 있나요?" — 이메일로 가입해 둔 사람이 소셜로 잘못 들어온 경우의 출구다. **아이디를 정하기 전에** 나갈 수 있어야 아이디 하나를 헛되이 태우지 않는다. 조건과 뜻은 §9.6 |
| 계정 삭제 | 계정 설정(`/account`)에서는 막이 걷힌다(이메일 게이트의 `standAside` 와 같다). 일반 탈퇴 — 유예 30일. 주소가 있어 버리기가 안 되는 계정(매직 링크 가입 등)의 길 |

## 9. 이메일을 받는 시점 — 안 받음 / 쓰기 때 / 로그인 직후

설정은 §3.2. 여기는 동작이다. **이메일로 가입하는 사람은 해당 없다** — 가입 때 인증한 주소가 이미 있다. 이 절은 주소 없이 만들어지는 계정(Kakao · Naver · X · LINE · 비-Gmail Google · 이메일 동의를 안 한 소셜)의 이야기다.

### 9.1 세 단계

| 단계 | 주소 없는 계정이 할 수 있는 것 | 막히는 것 | `GET /account/me` |
|---|---|---|---|
| 안 받음 | 전부 | 없다 | 관련 키 없음 |
| **쓰기 때** | 읽기 전부 + 허용 목록의 변경 | 허용 목록 밖의 **변경 요청** → `403 ACCOUNT.EMAIL_REQUIRED` | `emailRequired: false` · **`emailRequiredToWrite: true`** |
| 로그인 직후 (지금 동작) | 허용 목록만 | 나머지 전부 → 같은 `403` | `emailRequired: true` |

### 9.2 "쓰기 때" 규칙

같은 `EmailRequiredInterceptor` 에 판정 하나가 는다: `required-when=WRITE` 이고 메서드가 **`GET` · `HEAD` · `OPTIONS`** 면 통과. 그 밖의 메서드(`POST` · `PUT` · `PATCH` · `DELETE` 와 나머지 전부)는 지금처럼 허용 목록 밖이면 `403 ACCOUNT.EMAIL_REQUIRED` 다. 범위(`/api/**`) · 경로 읽는 법(디코드 후 — `/%61pi/…` 우회 없음) · 익명 요청은 건드리지 않음 · preflight · 에러 디스패치는 그대로다. 에러 코드도 본문도 같다 — 프론트는 같은 코드를 같은 화면으로 받는다.

**"쓰기 때"의 허용 목록** = 고정 목록(§8.1, 이메일 등록 · 로그아웃 · 계정 삭제 · 버리기 · `auth` · `legal` 동의 · 아이디 정하기와 중복 확인) + **`PATCH /api/v1/account/me`**(이 방식에서만 — 아래 표) + 앱의 `email.extra-exclude-paths`.

### 9.3 무엇이 읽기이고 무엇이 쓰기인가

메서드로 가르는 규칙은 단순한 대신 경계에 걸리는 것이 있다. 하나씩 정한다.

| 종류 | 스켈레톤에서의 예 | 취급 |
|---|---|---|
| 읽기 (`GET` · `HEAD`) | 목록 · 상세 · 알림함 · 실시간 알림 스트림(`GET /notifications/sse`) | **통과** |
| WebSocket | `/ws/notifications` 의 연결 | 이 인터셉터의 범위(`/api/**`) 밖이라 **지금도 앞으로도 보지 않는다.** 스켈레톤의 소켓은 서버가 알림을 내려보내는 용도다. 소켓으로 **쓰기 메시지를 받는 앱**은 그 핸들러에서 직접 확인해야 한다 — 문서에 규칙으로 적고, 쓰는 곳이 없는 헬퍼는 만들지 않는다 |
| 읽기처럼 보이는 `POST` | 첨부 보기용 `POST /storage/presign-download`, (앱의) 검색 `POST` | 메서드 규칙으로는 막힌다 → **앱이 `extra-exclude-paths` 에 적어 연다.** 견본 앱은 `presign-download` 를 연다(글의 첨부를 볼 수 있어야 읽기다) |
| 자기 계정 살림 | 언어 · 시간대 · 닉네임(`PATCH /account/me`), 세션 닫기(`/auth/**`), 약관 동의(`/legal/**`) | **통과** — `account` 가 아는 경로라 코드에 고정. 언어를 바꿨다고 이메일 화면이 뜨면 안 된다 |
| 사소한 쓰기 (다른 모듈) | 알림 읽음 표시 `PATCH /notifications/{id}/read` · `PATCH /notifications/read-all` | **통과시켜야 한다** — 알림함을 여는 것은 읽기인데 화면이 읽음 표시를 보낸다. `account` 는 다른 모듈의 경로를 모르므로 **견본 앱 yml 의 `extra-exclude-paths`** 에 적는다(추천안 적용) |
| 남에게 보이거나 남는 쓰기 | 글 · 댓글 · 반응 · 작성 프로필 만들기 · 첨부 올리기(`presign` · `multipart/*`) · 노트 · 내보내기 | **`403`** |
| **결제** | 결제 모듈의 사용자 엔드포인트(`/api/v1/payments/**` 의 변경 메서드) | **반드시 `403`.** 허용 목록에 넣지 않는다 — 영수증 · 환불 안내가 갈 주소가 없는 결제를 만들지 않는다. 제공자가 부르는 웹훅은 익명 호출이라 이 게이트와 무관하고, 주소 없는 계정은 주문을 만들 수 없으니 그 계정의 웹훅도 생기지 않는다 |
| 계정의 다른 변경 | 소셜 연결 · 해제, 연락받을 이메일, 비밀번호 | `403` (주소가 먼저다) |

- **기본이 "막힘"이다.** 새 변경 엔드포인트는 누가 목록에 적지 않는 한 막힌다. 결제가 실수로 열리려면 누군가 그 경로를 yml 에 적어야 한다.
- **왜 모듈이 스스로 신고하는 SPI 가 아니라 앱 yml 인가**: 무엇이 "사소한가"는 서비스의 판단이다(알림 읽음은 사소하지만 어떤 앱에서는 반응도 사소하다고 볼 수 있다). 모듈은 메커니즘만 내고 사용 방식은 앱이 정한다는 원칙과 같고, 이미 있는 키라 새 추상화가 없다. `docs/accounts.md` 에 "쓰기 때 방식에서 모듈별로 열어 둘 만한 경로" 표를 싣는다.
- **`GET` 은 상태를 바꾸지 않는다**는 HTTP 규칙을 믿는다. `GET` 으로 무언가를 바꾸는 엔드포인트는 이 게이트를 지나간다 — 스켈레톤 모듈에는 없다(조회수 같은 것은 쓰기로 보지 않는다).
- **시험으로 고정**(§16): 견본 앱의 변경 메서드 핸들러를 **전부** 뽑아, 주소 없는 계정에게 열린 것이 기대 목록과 **정확히 같은지** 본다. 결제 모듈이 조립돼 있으면 그 경로가 막히는지 이름으로 한 번 더 본다.

견본 앱 yml:

```yaml
skeleton:
  account:
    email:
      required: true
      required-when: WRITE            # 이 앱의 선택: 읽기는 이메일 없이, 쓰려 할 때 받는다
      extra-exclude-paths:            # 이메일 없이도 되는 사소한 쓰기 · 읽기처럼 보이는 POST
        - PATCH /api/v1/notifications/*/read
        - PATCH /api/v1/notifications/read-all
        - POST /api/v1/storage/presign-download
    login-id:
      enabled: true
```

### 9.4 `me` 와 상태 판별

- `emailRequired` 의 뜻은 **그대로**다: "지금 주소가 없어서 허용 목록 밖이 전부 막힌다". "쓰기 때" 방식에서는 키가 있고 늘 `false` 다.
- 새 키 **`emailRequiredToWrite`**: "쓰기 때" 방식에서만 있다. 주소가 없으면 `true`.
- 키를 둘로 나누는 이유: `emailRequired` 를 읽는 지금의 코드(로그인 직후 막을 연다)가 뜻이 바뀐 값을 받지 않게 한다. FINAL-6 의 E1 은 한 글자도 바뀌지 않는다.
- **프론트는 같이 고쳐야 한다.** 지금의 `EmailRequiredGate` 는 막이 열려 있는 동안 `me` 를 다시 읽어 `emailRequired !== true` 면 "다른 탭에서 등록했다"고 보고 막을 접는다 — "쓰기 때" 서버에서는 그 값이 늘 `false` 라 막이 뜨자마자 접힌다. 판정을 "주소가 생겼나"(`email !== null`)로 바꾼다(§13.1). 두 레포를 한 묶음으로 내보낸다.

### 9.5 첫 이메일 등록 — 재인증, 하던 쓰기 이어 가기, 미리 알리기

등록 API 는 지금 그대로다(`POST /account/email/change` → 코드 → `/confirm`). 남의 주소를 넣었을 때의 같은 `202` 와 "코드가 안 오면 이미 계정이 있을 수 있어요" 안내도 그대로다.

**재인증 — 확정(2026-10-09 주인): 지금 규칙 그대로.** 지금 규칙: 주소가 한 번도 없던 계정의 첫 등록은 **로그인으로 연 지 1시간(`first-registration-window`) 안의 세션**이면 재인증이 없고, 그보다 오래된 세션은 제공자 재확인(`403 ACCOUNT.REAUTH_REQUIRED` → `socialReauth`)이다. "로그인 직후" 방식에서는 거의 늘 창 안이었다. **"쓰기 때" 방식에서는 대부분 창 밖이다** — 며칠 읽다가 처음 쓰는 사람의 세션은 오래됐다. 확정한 것은 규칙을 그대로 두는 것이고(훔친 세션이 남의 계정에 자기 주소를 심어 영구 자격으로 바꾸는 것을 막는 장치다), 그 대가로 첫 쓰기의 보통 길에 제공자 화면을 한 번 다녀오는 걸음이 생긴다. 제공자에 이미 로그인돼 있으면 화면이 스쳐 지나간다.

**하던 쓰기를 이어 가게 하나 — 추천안 적용: 한다, 새로 만드는 것 없이.**

| 경우 | 동작 | 비용 |
|---|---|---|
| 같은 화면에서 등록이 끝난다(세션이 창 안) | `403` 을 받은 쓰기 호출을 붙잡아 두었다가 등록이 끝나면 **같은 호출을 그대로 한 번 더** 보낸다. 막 아래의 글쓰기 폼은 살아 있다(입력 유지) | **없다** — 지금의 `recoverForbidden` · `EmailRequiredGate` 가 이미 이렇게 돈다 |
| 제공자 재확인으로 페이지를 떠난다(세션이 창 밖) | 붙잡은 호출과 쓰던 글은 **사라진다.** 떠나기 전에 그렇게 말한다 | 문구 한 줄. 초안 보존은 폼마다 저장소가 필요해 이번 범위 밖(비목표) |

**글쓰기 화면에 들어설 때 미리 알리나 — 추천안 적용: 알린다.** 위 둘째 줄 때문이다. 글을 다 쓴 뒤에야 막히면 제공자 왕복에서 글을 잃는다. `me.emailRequiredToWrite` 가 `true` 면:
- 글쓰기 단추를 눌렀을 때(쓰기 화면으로 가기 전) 이메일 등록 화면을 먼저 연다 — 견본 앱의 「쓰기 전 확인」(`useProfileGate`: 닉네임 없으면 먼저 묻기)과 같은 자리, 순서는 이메일 → 닉네임.
- 댓글 칸처럼 늘 보이는 입력 위에는 막지 않는 띠 한 줄("이메일을 등록하면 글을 쓸 수 있어요" + 단추).
- 그래도 빠져나간 쓰기(반응 단추 등)는 `403` 이 받는다.

**닫을 수 있어야 한다.** "쓰기 때" 방식의 이메일 화면에는 **"나중에 하기"** 가 있다 — 닫으면 붙잡은 호출은 원래의 `403` 으로 끝나고 읽기를 계속한다. ("로그인 직후" 방식의 화면에는 지금처럼 없다.)

### 9.6 버리기(`POST /account/discard`)를 다시 본다 — 확정(2026-10-09 주인): 조건 그대로

**지금의 뜻**: "등록 미완료 계정을 유예 없이 즉시 지운다." 이메일로 가입해 둔 사람이 소셜로 들어와 생긴 껍데기 계정이 그 소셜 로그인을 붙잡고 있어서, 진짜 계정에 연결하려면 껍데기를 당장 치워야 한다(일반 탈퇴는 30일 동안 수단을 쥐고 있다). 조건: `email.required=true` · 주소 없음 · `ACTIVE`. 재인증: 방금 로그인한 세션이면 면제, 아니면 제공자 재확인.

**달라진 것**: "쓰기 때" 방식에서는 주소 없는 계정이 **몇 분이 아니라 몇 달** 살 수 있다. 그동안 아이디를 정했고, 약관에 동의했고, 닉네임 · 언어를 바꿨고, 알림을 읽었을 수 있다. "등록 미완료"라는 말이 더는 맞지 않는다.

**달라지지 않은 것**: 그 계정은 **허용 목록 밖의 쓰기를 한 번도 하지 못했다.** 글 · 댓글 · 반응 · 첨부 · 결제가 없다. 즉시 지워도 남에게 보이던 것이 사라지지 않는다는, 유예를 건너뛰어도 되는 근거는 두 방식에서 똑같이 성립한다. 그리고 출구가 필요한 이유(소셜 로그인을 당장 풀어야 한다)도 그대로다 — 오히려 "이미 그 주소로 계정이 있었다"를 알게 되는 순간이 첫 쓰기로 미뤄졌을 뿐이다.

**확정: 조건은 그대로 두고 뜻과 말을 고친다.**

- 조건: `email.required=true`(방식 무관) · 주소를 가진 적 없음 · `ACTIVE`. 재인증 규칙도 그대로.
- 이름: 문서 · 화면에서 "등록 미완료 계정" → **"이메일 없는 계정"**. 뜻: "아무것도 쓰지 못한 계정을 유예 없이 지운다."
- 지워지는 것은 같은 지우기 길(`AccountPurgeService` · 다른 모듈의 `AccountErasureListener`)을 타므로 아이디 열 · 약관 동의 기록 · 알림함 · 세션이 함께 정리된다. **아이디는 다시 쓸 수 없다**(§5).
- 확인 대화상자가 그 사실을 말한다: 아이디가 있는 계정이면 "아이디 `…` 도 함께 사라지고 다시 쓸 수 없어요."
- 길을 두 곳에 둔다: 아이디 게이트(아이디를 태우기 전 — §8.3)와 이메일 등록 화면(지금 자리).
- 앱이 `extra-exclude-paths` 로 쓰기를 넓게 열었다면 그 계정에 글이 있을 수 있다. 그래도 같은 지우기 길이 글을 "삭제된 사용자"로 돌린다 — 다만 유예가 없다는 점을 그 설정의 문서에 적는다.

버린 대안은 맨 끝 「정해야 할 것 — 가.」의 2번에 있다(만든 지 며칠 안의 계정만 / "쓰기 때" 방식에서는 버리기를 없앤다).

### 9.7 "로그인 직후" 방식은 한 글자도 바뀌지 않는다

`required-when` 을 적지 않은 앱은 지금과 같다 — 기존 `EmailRequiredInterceptorTest` · `EmailRequiredWebTest` · `EmailRequiredWiringTest` 를 고치지 않고 통과해야 한다. 바뀌는 것은 아이디가 켜졌을 때 허용 목록에 두 경로가 늘고 아이디 게이트가 앞에 선다는 것뿐이다.

## 10. 아이디 찾기

`POST /api/v1/account/login-id/forgot` (공개) — 본문 `{ "email", "captchaToken"? }` → **늘 `202 { status: "ACCEPTED" }`**. `PasswordService.forgot` 과 한 글자씩 같은 틀이다.

- 요청 스레드는 IP 한도(`login-id-forgot:ip`, 값은 `reset.per-ip`)와 캡차만 본다. 주소 조회 · 메일은 `AccountTaskRunner` 로 뒤에서 한다. 응답과 응답 시점이 계정 존재와 무관하다. IP 가 한도를 넘었을 때만 `429`.
- 뒤에서: 그 주소의 계정이 `ACTIVE` 또는 `PENDING_VERIFICATION` 이면 주소별 한도(`login-id-forgot:email`, 값은 `reset.per-email`, 넘으면 조용히) 안에서 메일 `LOGIN_ID_REMINDER` 를 **계정 이메일로** 보낸다. 연락받을 이메일로는 가지 않는다.
  - 아이디가 있으면: 아이디 한 줄 + 로그인 페이지 링크(토큰 없음).
  - 아이디를 아직 안 정한 계정이면: "아직 아이디를 정하지 않았어요. 로그인하면 정할 수 있어요" + 같은 링크. 보내지 않으면 그 사람은 아무 답도 못 받는다 (추천안 적용).
  - 정지 · 탈퇴한 계정, 계정이 없는 주소: 아무것도 보내지 않는다(재설정과 같다).
- 이벤트 `LOGIN_ID_REMINDER_SENT`(계정 id 만).
- 메일은 한국어 · 영어, 텍스트 + HTML. `mail-golden` 에 두 가지 본문 × 두 언어를 더한다.

아이디는 비밀이 아니라 **비공개**다. 메일함을 쥔 사람은 이미 비밀번호를 재설정할 수 있으므로 이 메일이 여는 문은 없다.

**비밀번호 재설정 메일에는 아이디를 싣지 않는다**(결정). `PASSWORD_RESET` · `ALREADY_REGISTERED` 를 비롯한 기존 메일은 문구도 골든 파일도 바뀌지 않는다. 메일에 아이디가 나오는 곳은 이 메일 하나다.

**주소 없는 계정**("쓰기 때" 방식에서 아직 이메일을 등록하지 않은 소셜 계정)은 아이디 찾기를 쓸 수 없다. 아이디는 설정 화면에서 본다. 로그인은 소셜이라 아이디를 몰라도 들어온다.

## 11. 본인 · 관리자 응답과 비공개 고정

**아이디가 나가는 곳은 셋뿐이다.**

| 곳 | 누가 본다 |
|---|---|
| `GET/PATCH /account/me` · `POST /account/login-id` 의 응답 `loginId` | 본인 |
| 계정 이메일로 가는 메일 하나 (아이디 찾기) | 본인의 메일함 |
| `GET /admin/accounts` · `GET /admin/accounts/{id}` 의 `loginId` (`admin.enabled` 인 앱). 찾기는 `?loginId=` **정확히 일치만** — 조각 검색은 없다(훑어보기 방지) | `ADMIN` — 매 호출 역할을 다시 읽는 기존 규칙 그대로 |

실시간 확인의 응답은 부른 사람이 **자기가 넣은 값**을 돌려받는 것이라(`normalized`) 남의 아이디가 나가는 곳이 아니다.

그 밖에는 싣지 않는다: 토큰 · `GET /auth/me`, 게시판 · 작성자 프로필 · 알림 · 첨부 응답, `AccountName`(다른 모듈이 이름을 읽는 줄), 계정 이벤트 · 감사 행의 detail, 로그(요청 DTO 의 `toString` 은 가린다 — `SecretsStayOutOfToStringTest`).

**시험으로 고정하는 세 겹** (하나가 놓쳐도 다른 것이 잡게)

1. **응답 전수 스캔** — `apps/sample` 통합 시험 `LoginIdLeakIntegrationTest`. 눈에 띄는 아이디(`zq7.probe-x1`)를 가진 X 가 글 · 댓글 · 프로필 · 첨부 · 알림을 만든 뒤, 다른 사용자 Y · 익명 · `MODERATOR` 가 읽을 수 있는 응답의 **본문과 헤더**에서 그 문자열을 찾는다(기존 `leak/LeakScan.forbiddenHits`). 대상 경로는 손으로 적지 않고 **OpenAPI 문서(`/api/v1/docs`)의 GET 전부**에서 뽑는다 — 새 엔드포인트가 생기면 자동으로 훑는다. 경로 변수는 여정에서 얻은 id 로 채우고, 채우지 못한 경로가 있으면 시험이 실패한다(조용히 건너뛰지 않는다). 허용: X 본인의 `me`, `ADMIN` 의 관리자 조회.
2. **응답 타입 가드** — 앱 컨텍스트의 모든 핸들러 반환 타입을 재귀로 훑어 `loginId` 라는 속성을 가진 타입이 `MeView` · `AdminAccountResponse` 와, 값이 아니라 길이 규칙만 담는 `AuthMethodsView` **셋뿐**인지 본다. 누가 다른 DTO 에 칸을 더하면 여기서 깨진다.
3. **DB 전수 스캔** — `account-jdbc` 의 `PlantedDataScan`(모든 표 · 모든 열)으로 아이디 원문이 `accounts.login_id` 와 가입 대기 중의 `account_challenges.payload` 에만 있는지 본다(게시판 · 알림 · 감사 · 세션 표에 복사되지 않았다). 탈퇴 익명화 뒤에는 **어디에도 없어야** 한다 — `JdbcErasureDbTest` 에 심는 값으로 더한다.

프론트 e2e 는 같은 일을 화면 쪽에서 한 번 더 한다(§16).

## 12. 소셜로만 들어오는 계정 — 아이디 · 이메일 · 비밀번호

견본 앱(아이디 켬 · 이메일은 쓰기 때)에서 소셜 계정은 이렇게 자란다.

| 가진 것 | 할 수 있는 것 |
|---|---|
| 아이디만 (첫 로그인 뒤) | 읽기 전부. 로그인은 소셜. 아이디는 설정에서 보고, 문의할 때 대고, 운영자가 그것으로 계정을 찾는다 |
| 아이디 + 이메일 (처음 쓰려 할 때 등록) | 쓰기 전부. 아이디 찾기 |
| 아이디 + 이메일 + 비밀번호 (설정에서 만들기 — 지금처럼 메일 코드로 확인) | **아이디 또는 이메일 + 비밀번호로도 로그인** |

- **아이디 + 비밀번호 로그인은 비밀번호를 만든 뒤에만 된다.** 비밀번호는 지금처럼 확인된 이메일이 있어야 만들 수 있다(`403 ACCOUNT.REAUTH_REQUIRED` → 메일 코드, 주소가 없으면 `400 ACCOUNT.PASSWORD_REQUIRED`).
- 로그인 화면은 이 경우를 따로 말하지 않는다. 비밀번호가 없는 계정에 아이디 + 비밀번호를 넣으면 다른 실패와 같은 `401` 이다(§7) — "이 아이디는 소셜 계정"이라고 말하면 그것이 존재 오라클이다.
- 대신 **본인에게 미리** 말한다(문구는 §13.2):
  - 게이트: "아이디는 다른 사람에게 보이지 않아요. 한 번 정하면 바꿀 수 없어요."
  - 설정의 아이디 줄(`hasPassword=false`): "지금은 {제공자}(으)로 로그인해요. 비밀번호를 만들면 아이디나 이메일로도 로그인할 수 있어요." — 주소가 있으면 비밀번호 구역으로 가는 단추, 없으면 이메일 구역으로 가는 단추.

## 13. 프론트 (react-skeleton)

**켜짐을 아는 법**: 아이디는 `GET /auth/methods` 의 `loginId` 키(있으면 켜짐, 길이 규칙이 들어 있다). 이메일 방식은 `GET /account/me` 의 `emailRequired` · `emailRequiredToWrite`. 앱이 환경변수로 따로 맞추지 않는다. 키가 없으면(옛 서버 · 꺼진 서버) 모든 화면이 지금과 같다.

**화면 원칙** (모든 새 화면에 적용): 눌리는 요소는 눌리게 보인다 — 단추는 테두리나 채움이 있고, **주 행동은 화면에 채워진 단추 하나**, 나머지는 테두리 단추, 글자 링크는 밑줄. 글자만 있는 단추는 두지 않는다. 막힌 이유는 꺼진 단추가 아니라 단추 위 요약 + 칸 옆 오류로 말한다(지금 화면들의 방식). **상태는 색만으로 말하지 않는다 — 글자로 말한다.**

### 13.1 `@skeleton/auth`

**아이디 칸과 실시간 확인** — 조각 하나(`LoginIdField`)를 가입 · 인증 단계 재입력 · 게이트 · 설정이 같이 쓴다.

- 칸: `autoComplete="username"`, `autoCapitalize="none"`, `spellCheck=false`. 칸 아래에 안내 한 줄과 **상태 한 줄**.
- 확인(`useLoginIdAvailability`): **입력이 멈춘 뒤 0.4초**(디바운스)에, **형식이 맞을 때만** `POST /account/login-id/availability` 를 부른다. 그 사이 값이 바뀌면 늦게 온 답은 버린다(마지막 입력의 답만 쓴다). 같은 값을 다시 묻지 않는다.
- 상태 줄은 글자 + 아이콘이고 `aria-live="polite"` 로 읽힌다. 색은 보조다.

| 상태 | 상태 줄의 글자 | 내기 |
|---|---|---|
| 형식이 안 맞는다 | 무엇이 틀렸는지(길이 · 쓸 수 없는 글자 · 맨 앞뒤나 연달아 온 기호 · 숫자만) — 지금 화면들처럼 칸을 벗어나거나 내려 할 때 | 막힌다(칸 옆 오류) |
| 확인하는 중 | "확인하는 중…" | 된다 |
| 쓸 수 있다 | "쓸 수 있는 아이디예요" — 대문자를 넣었으면 "`sumin.kim` (으)로 저장돼요"를 덧붙인다 | 된다 |
| 쓸 수 없다 | "이미 쓰고 있거나 쓸 수 없는 아이디예요" | 막힌다(칸 옆 오류) |
| 확인하지 못했다(`429` · 네트워크) | "지금은 확인할 수 없어요. 내면 확인해 드려요." | 된다 — 서버가 판정한다 |

**화면 · 조각**

| 화면 · 조각 | 바뀌는 것 |
|---|---|
| 가입 `SignUpScreen` | 아이디 칸(이메일 다음, 비밀번호 앞)과 실시간 확인. 서버의 `errors[].field=loginId` · `409 LOGIN_ID_TAKEN` 도 같은 칸 옆 오류로. 이메일 칸의 `autoComplete` 는 `email`. **"다른 곳에서 쓰는 아이디와 다르게" 안내는 넣지 않는다**(결정) |
| 가입의 인증번호 단계 | 코드는 맞았는데 아이디가 그 사이 나갔다(`409`) → 그 자리에서 아이디만 다시 받는다(실시간 확인 포함). 지금의 `NicknameRetry` 와 같은 조각(`LoginIdRetry`), 같은 `signUpId` · 같은 코드로 다시 낸다 |
| 로그인 `SignInScreen` | 켜져 있으면 칸 라벨이 "아이디 또는 이메일", `type="text"`(브라우저의 이메일 검사가 아이디를 막지 않게). 입력을 요청 본문으로 바꾸는 곳은 한 군데(`@` → `email`, 아니면 `username`). 매직 링크 모드에서 `@` 가 없으면 "이메일을 입력해 주세요". 비밀번호 찾기 링크 옆에 "아이디 찾기" 링크(밑줄). 화면 콜백의 모양은 깨지 않는다 |
| 아이디 찾기 `ForgotLoginIdScreen` (새, 경로 `/forgot-id`) | 이메일 칸 하나 + 채워진 단추 "아이디 보내기". 보낸 뒤 화면은 "있다면 보냈다"만 말한다(`ForgotPasswordScreen` 과 같은 틀 · 같은 캡차 자리) |
| 아이디 게이트 `LoginIdGate` + `LoginIdRequiredScreen` (새) | `EmailRequiredGate` 와 같은 구조: 아래 화면을 `inert` 로 두고 덮는다, 403 으로 붙잡은 호출을 정한 뒤 다시 보낸다, 다른 탭에서 정했으면 접는다, 계정이 바뀌면 접는다. 아이디 칸 + 채워진 단추 "이 아이디로 정하기" + 테두리 단추 "로그아웃" + 밑줄 링크 "계정 설정". 내면 **확인 대화상자**("`sumin.kim` (으)로 정할까요? 정한 뒤에는 바꿀 수 없어요"). 주소 없는 계정이면 아래에 "이미 계정이 있나요?"(지금의 `DiscardPath` 조각 그대로) |
| 컨트롤러 `createLoginIdRequiredController` (새) | `createEmailRequiredController` 와 같은 상태 기계. 둘이 쓰는 내부 공통 부분만 뽑고 기존 export · 동작은 그대로 둔다 (추천안 적용 — 기존 시험을 고치지 않고 통과) |
| 이메일 게이트 `EmailRequiredGate` · `EmailRequiredScreen` | **"쓰기 때" 모습을 더한다**: 로그인 직후에는 `me.emailRequired === true` 일 때만 연다(지금과 같다 — "쓰기 때" 서버에서는 `false` 라 열리지 않는다). 쓰기의 `403` 이나 쓰기 전 확인이 열었을 때는 제목 · 본문이 쓰기용이고 **테두리 단추 "나중에 하기"** 가 있다(닫으면 붙잡은 호출은 원래 `403` 으로 끝난다). 제공자 재확인으로 떠나기 전에는 "쓰던 내용은 사라져요" 한 줄. 열려 있는 동안 "다른 탭에서 등록했나"를 보는 판정은 `emailRequired` 가 아니라 **주소가 생겼는지(`me.email`)** 로 바꾼다(§9.4 — 안 바꾸면 "쓰기 때" 서버에서 막이 뜨자마자 접힌다) |
| 쓰기 전 확인 `useEmailBeforeWrite` (새) | `me.emailRequiredToWrite` 를 읽어 `{ needed, ensure() }` 를 준다. `ensure()` 는 필요하면 이메일 게이트를 열고 `false` — 견본 앱의 `useProfileGate` 와 같은 모양 |
| 버리기 `DiscardPath` | 확인 문구에 아이디 줄(있을 때만) |
| 설정 `AccountSettings` | 「아이디」 줄: 값 + "바꿀 수 없어요". 비밀번호 없는 계정이면 §12 의 안내와 단추. 아직 안 정한 계정(막이 걷힌 `/account`)이면 같은 칸 · 같은 확인으로 여기서 정한다(그동안은 제한 모드). 이메일 구역: `emailRequiredToWrite` 면 "이메일을 등록하면 글을 쓸 수 있어요" |
| 관리자 `AdminAccounts` | 표에 「아이디」 열(키가 있을 때만), 아이디로 찾기(정확히 일치) |
| 타입 · API | `SignUpRequest.loginId?` · `AccountMe.loginId?: string \| null` · `AccountMe.emailRequiredToWrite?` · `AdminAccount.loginId?` · `accountApi.checkLoginId` · `setLoginId` · `forgotLoginId` · 인증 재시도의 `loginId` |
| 오류 코드 `@skeleton/api-client` | `ACCOUNT_LOGIN_ID_TAKEN` · `ACCOUNT_LOGIN_ID_REQUIRED` · `ACCOUNT_LOGIN_ID_ALREADY_SET` |

### 13.2 문구 (`labels.ko.ts` · `labels.ts`, 해요체)

| 키 | ko | en |
|---|---|---|
| `loginId` | 아이디 | Username |
| `loginIdHint` | 영문 · 숫자 · 마침표(.) · 하이픈(-) · 밑줄(_), {min}~{max}자. 대문자는 소문자로 저장돼요. 다른 사람에게 보이지 않고, 한 번 정하면 바꿀 수 없어요. | Letters, numbers, periods, hyphens and underscores, {min}–{max} characters. Capitals are saved as lowercase. Nobody else sees it, and it cannot be changed later. |
| `problemLoginIdMissing` | 아이디를 입력해 주세요 | Enter a username |
| `problemLoginIdSize` | 아이디는 {min}~{max}자로 정해 주세요 | Use {min} to {max} characters |
| `problemLoginIdChars` | 영문 · 숫자 · 마침표(.) · 하이픈(-) · 밑줄(_)만 쓸 수 있어요 | Use only letters, numbers, periods, hyphens and underscores |
| `problemLoginIdSymbols` | 기호로 시작하거나 끝날 수 없고, 기호를 연달아 쓸 수 없어요 | It cannot start or end with a symbol, and symbols cannot sit next to each other |
| `problemLoginIdDigitsOnly` | 숫자만으로는 정할 수 없어요. 영문을 하나 넣어 주세요 | It cannot be numbers only — add a letter |
| `loginIdChecking` | 확인하는 중… | Checking… |
| `loginIdAvailable` | 쓸 수 있는 아이디예요 | This username is available |
| `loginIdSavedAs` | {id} (으)로 저장돼요 | It will be saved as {id} |
| `errorLoginIdTaken` | 이미 쓰고 있거나 쓸 수 없는 아이디예요 | This username is taken or not available |
| `loginIdCheckUnknown` | 지금은 확인할 수 없어요. 내면 확인해 드려요. | We can't check right now — it will be checked when you submit. |
| `loginIdRetryHint` | 그 사이 다른 분이 이 아이디를 정했어요. 다른 아이디를 적어 주세요. 인증번호는 다시 받지 않아도 돼요. | Someone took this username meanwhile. Pick another one — you do not need a new code. |
| `signInIdentifier` | 아이디 또는 이메일 | Username or email |
| `problemIdentifierMissing` | 아이디 또는 이메일을 입력해 주세요 | Enter your username or email |
| `forgotLoginIdLink` | 아이디 찾기 | Forgot your username? |
| `forgotLoginIdTitle` | 아이디 찾기 | Find your username |
| `forgotLoginIdSubtitle` | 가입한 이메일을 적으면 그 주소로 아이디를 보내 드려요. | Enter your account email and we will send your username there. |
| `forgotLoginIdSubmit` | 아이디 보내기 | Send my username |
| `forgotLoginIdSentBody` | {email} 에 계정이 있다면 아이디를 보냈어요. | If {email} has an account, we have sent the username there. |
| `loginIdRequiredTitle` | 아이디를 정해 주세요 | Choose your username |
| `loginIdRequiredBody` | 로그인할 때와 문의할 때 쓰는, 바뀌지 않는 내 계정 이름이에요. 다른 사람에게는 보이지 않아요. | The name of your account that never changes — for signing in and for support. Nobody else sees it. |
| `loginIdRequiredSubmit` | 이 아이디로 정하기 | Use this username |
| `loginIdConfirmTitle` | {id} (으)로 정할까요? | Use {id}? |
| `loginIdConfirmBody` | 정한 뒤에는 바꿀 수 없어요. | You cannot change it afterwards. |
| `loginIdConfirm` · `loginIdConfirmBack` | 정하기 · 다시 입력 | Confirm · Edit |
| `settingsLoginIdFixed` | 바꿀 수 없어요 | Cannot be changed |
| `settingsLoginIdNoPassword` | 지금은 {provider}(으)로 로그인해요. 비밀번호를 만들면 아이디나 이메일로도 로그인할 수 있어요. | You sign in with {provider} now. Set a password to sign in with your username or email too. |
| `settingsLoginIdSetPassword` · `settingsLoginIdAddEmail` | 비밀번호 만들기 · 이메일 등록하기 | Set a password · Register an email |
| `emailRequiredWriteTitle` | 글을 쓰려면 이메일을 등록해 주세요 | Register an email to post |
| `emailRequiredWriteBody` | 읽기는 지금처럼 할 수 있어요. 글 · 댓글 · 반응을 남기려면 이메일이 필요해요. 다른 사람에게는 보이지 않아요. | You can keep reading. To post, comment or react you need an email. Nobody else sees it. |
| `emailRequiredLater` | 나중에 하기 | Not now |
| `emailRequiredWriteNotice` · `emailRequiredWriteNoticeAction` | 이메일을 등록하면 글을 쓸 수 있어요 · 이메일 등록 | Register an email to start posting · Register email |
| `emailRequiredDeclined` | 이메일을 등록한 뒤에 쓸 수 있어요 | You can post after registering an email |
| `emailRequiredReauthLosesDraft` | {provider}(으)로 다녀오면 지금 쓰던 내용은 사라져요. | Going to {provider} discards what you were writing. |
| `emailRequiredDiscardConfirmLoginId` | 아이디 {id} 도 함께 사라지고 다시 쓸 수 없어요. | The username {id} goes with it and can never be used again. |
| `settingsEmailToWrite` | 이메일을 등록하면 글을 쓸 수 있어요. | Register an email to start posting. |

게이트의 "로그아웃" · "계정 설정" · "이미 계정이 있나요?"는 이메일 게이트의 기존 문구를 쓴다.

### 13.3 `apps/sample`

- **막의 겹침** (`main.tsx`): `<LoginIdGate>` → `<EmailGate>` → `<LegalGate>` → 라우터. 서버 인터셉터와 같은 순서다. 안쪽 막은 바깥 막이 확인을 마치고 걷힐 때까지 묻지 않는다(지금 `LegalGate` 가 이메일 막을 기다리는 `isReady` 와 같은 방식). 견본 앱은 "쓰기 때"라 로그인 직후에는 아이디 → 약관만 보인다.
- `api/client.ts`: `chainForbiddenRecovery(loginIdRequired.recover, emailRequired.recover, reconsent.recover)`. `subscribeGateRelease` 목록에 아이디 막을 더한다(막이 걷히면 멈춘 알림 스트림이 다시 이어진다). 전역 오류 토스트에서 `LOGIN_ID_REQUIRED` 를 뺀다.
- **쓰기 전 확인**: 지금의 `useProfileGate`(닉네임 없으면 먼저 묻기)를 부르는 자리(`BoardPage` 의 글쓰기 단추, `BoardFormPage`, `BoardPostPage` 의 댓글)에 이메일 확인을 **앞에** 둔다 — 이메일 → 닉네임. 댓글 칸 위에는 막지 않는 띠. 노트 만들기 단추도 같다.
- **"나중에 하기"로 닫았을 때**: 붙잡은 쓰기는 `403` 으로 끝난다. 전역 토스트가 이 코드를 거르고 있으므로 `emailRequiredDeclined` 한 줄을 따로 띄운다 — 아무 말 없이 실패하지 않는다.
- `i18n/ko.ts` · `en.ts`, 인증 라우트에 `/forgot-id`.
- e2e 도우미: 가입 도우미가 아이디를 채운다, 소셜 · 매직 링크 여정이 아이디 막을 지난다(막이 뜰지는 지금처럼 서버의 `me` 에게 먼저 묻는다). `registerEmailViaGate` 는 "쓰려 할 때" 막이 뜨는 것으로 바뀐다.
- `apps/starter` · `apps/starter-ssr` 는 손대지 않는다 — 키가 없는 서버에서 지금과 같아야 한다(§16).

## 14. 계약 변경 표

`docs/account-http-contract.md` 에 **FINAL-7 change list** 로 싣는다(더하기만 — FINAL-6 의 칸 · 엔드포인트 · 오류 코드는 하나도 빠지지 않는다).

**로그인 아이디** — 전부 `skeleton.account.login-id.enabled=true` 일 때만

| # | 어디 | 무엇 |
|---|---|---|
| L1 | `GET /auth/methods` | `value.loginId: { minLength, maxLength }` — **켠 앱에서만 키가 있다.** 있으면 가입에 아이디 칸, 로그인 칸은 "아이디 또는 이메일" |
| L2 | `POST /account/sign-up` | 본문 `loginId`(필수). `400 COMMON.VALIDATION_FAILED` `errors[].field="loginId"`(`Required` · `Size` · `Pattern`), **`409 ACCOUNT.LOGIN_ID_TAKEN`**(이메일과 무관하게 아이디만 보고 답한다 — 이미 쓰는 · 탈퇴한 · 예약된 아이디 모두). 그 밖의 응답 · `202` 본문은 그대로 |
| L3 | `POST /auth/verify-email` | 본문 `loginId?`(가입 요청의 아이디를 갈아 끼운다). `409 ACCOUNT.LOGIN_ID_TAKEN` 은 시도를 닫지 않는다 — 같은 `signUpId` · 코드로 다시 |
| L4 | **`POST /account/login-id/availability`** (공개, 로그인해도 된다) | 본문 `{ loginId }` → `200 { value: { normalized, available, reason?: "INVALID" \| "TAKEN", code?: "Required" \| "Size" \| "Pattern" } }`. `Cache-Control: no-store`. `429 ACCOUNT.RATE_LIMITED`(`Retry-After`) — 한 IP 10분 30번, 형식 오류 답은 세지 않는다. "쓸 수 있음"은 예약이 아니다 |
| L5 | `POST /auth/login` | 본문 그대로. `username` 에 `@` 없는 값이 오면 아이디로 읽는다. 오류 · 한도는 그대로이고 한 계정의 `email` · `username` · `accountId` 입력이 한 버킷이다 **(구현에서 바뀜 — 리뷰 I2: 한 버킷이 아니다. 아이디로 읽힌 `username` 은 `loginId:<입력>` 의 자기 버킷이고 `email` · `accountId` 만 주소 버킷을 나눈다. 계약 문서 L5)** |
| L6 | `GET/PATCH /account/me` | `value.loginId: string \| null` — 켠 앱에서만 키가 있다. `null` = 아직 안 정했다 → 게이트 |
| L7 | 허용 목록 밖 모든 엔드포인트(읽기 포함) | 아이디 없는 로그인 계정에 **`403 ACCOUNT.LOGIN_ID_REQUIRED`**(`data` 없음). 순서: **`ACCOUNT.LOGIN_ID_REQUIRED` → `ACCOUNT.EMAIL_REQUIRED` → `LEGAL.RECONSENT_REQUIRED`**. 허용 목록 = FINAL-6 E2 의 목록 + L4 · L8 |
| L8 | `POST /account/login-id` (로그인 필요) | 본문 `{ loginId }` → `200` `me` 와 같은 본문. **이메일이 없어도 된다.** `400`(필드 `loginId`) · `409 ACCOUNT.LOGIN_ID_TAKEN` · **`409 ACCOUNT.LOGIN_ID_ALREADY_SET`** · `403 ACCOUNT.NOT_ACTIVE` · `429 ACCOUNT.RATE_LIMITED` **(구현에서 바뀜 — 리뷰 I3: 한도는 계정별 + IP 별이고 형식을 통과한 시도만 센다, §3.1 의 주)** |
| L9 | `POST /account/login-id/forgot` (공개) | 본문 `{ email, captchaToken? }` → 늘 `202 { status: "ACCEPTED" }`. IP 한도를 넘으면 `429 ACCOUNT.RATE_LIMITED`. 메일은 계정 이메일로만 |
| L10 | 메일 | 새 메일 `LOGIN_ID_REMINDER` 하나. 기존 메일은 바뀌지 않는다(재설정 메일 포함) |
| L11 | `GET /admin/accounts` · `/{id}` | 항목에 `loginId`(켠 앱에서만 키). 쿼리 `loginId=`(정확히 일치) |
| L12 | 오류 코드 | `ACCOUNT.LOGIN_ID_TAKEN`(409) · `ACCOUNT.LOGIN_ID_REQUIRED`(403) · `ACCOUNT.LOGIN_ID_ALREADY_SET`(409) |
| L13 | 바뀌지 않는 것 | 토큰 · `GET /auth/me` · `GET /account/identities` · 게시판 · 작성자 프로필 · 알림 응답. 아이디를 바꾸는 엔드포인트는 없다 |

**꺼져 있을 때**: L1 · L6 · L11 의 키가 없고, L2 · L3 의 `loginId` 는 읽지 않고, L5 의 `username` 은 이메일이고, L4 · L8 · L9 는 `404` 다. **(구현에서 바뀜 — 리뷰 B4: 로그인한 호출은 `404` 이지만 *익명* 호출은 `401` 이다. 새 공개 경로를 공개 목록에 더하지 않으므로 다른 모르는 경로와 같은 답이고, "꺼지면 지금과 같다"와 맞는다. 계약 문서 FINAL-7.)**

**이메일을 받는 시점** — `skeleton.account.email.required=true` + `required-when=WRITE` 일 때만 (FINAL-6 의 E1~E6 에 이어서)

| # | 어디 | 무엇 |
|---|---|---|
| E7 | `GET/PATCH /account/me` | `value.emailRequired` 는 키가 있고 늘 `false`(로그인 직후에 막지 않는다). 새 키 **`emailRequiredToWrite`**: 주소가 없으면 `true` — 변경 요청이 막힌다는 뜻. 이 키가 없으면 "쓰기 때" 방식이 아니다 |
| E8 | 허용 목록 밖 엔드포인트 | 주소 없는 계정: **`GET` · `HEAD` · `OPTIONS` 는 통과**, 그 밖의 메서드는 `403 ACCOUNT.EMAIL_REQUIRED`(E5 와 같은 코드 · 같은 본문). 경로 읽는 법 · 익명 · preflight 는 E2 와 같다 |
| E9 | 허용 목록 | E2 의 목록 + **`PATCH /account/me`**(언어 · 시간대 · 닉네임) + 앱이 더한 경로(견본 앱: 알림 읽음 표시 둘, `POST /storage/presign-download`). **결제는 열지 않는다** |
| E10 | `POST /account/email/change` (첫 등록) | E3 그대로 — 로그인으로 연 지 1시간 안의 세션만 재인증이 없다. "쓰기 때"에서는 오래된 세션이 보통이므로 `403 ACCOUNT.REAUTH_REQUIRED` → `socialReauth` 가 보통 길이다 |
| E11 | `POST /account/discard` | 조건 그대로(E6). 뜻이 "등록 미완료 계정"에서 **"이메일 없는 계정(아무것도 쓰지 못한 계정)"** 으로 바뀐다 — 아이디를 정한 계정도 버릴 수 있고 그 아이디는 다시 쓸 수 없다 |
| E12 | 프론트가 할 일 | 로그인 직후 막은 `emailRequired === true` 일 때만. 쓰기의 `403` 은 등록 화면을 열고 끝나면 같은 호출을 다시 보낸다. `emailRequiredToWrite === true` 면 쓰기 화면에 들어서기 전에 알린다. 화면은 닫을 수 있다 |

**`required-when` 을 적지 않은 앱**(= `SIGN_IN`)에는 E7~E12 가 없다 — FINAL-6 그대로다.

## 15. 문서 · CHANGELOG · new-project.sh 영향

**kotlin-skeleton**

| 문서 | 바뀌는 것 |
|---|---|
| `docs/accounts.md` | 새 절 「로그인 아이디 (`skeleton.account.login-id.*`)」— 규칙 · 재사용 금지와 해시 · 실시간 확인과 열거 위험 · 게이트 순서. 「이메일 필수」 절에 세 단계 표 · "쓰기 때" 규칙 · 무엇이 읽기이고 쓰기인가 표 · 모듈별로 열어 둘 만한 경로 · WebSocket 과 결제 줄. 「등록 미완료 계정 버리기」 절의 이름과 뜻. 「정해 둔 것」의 "`username` = 이메일" 줄. 위협 모델 표, 삭제 수명주기의 "남는 것 / 지워지는 것" 표, 처리방침 목록 |
| `docs/account-http-contract.md` | FINAL-7 change list(§14), 2 · 3 · 5 · 6 · 9 · 10 절의 해당 줄 |
| `docs/modules/account.md` · `docs/config/modules/account.yml` | 설정 키(`login-id.*` · `email.required-when`), 새 표 |
| `docs/errors.md` | 오류 코드 셋 |
| `docs/sample.md` | 견본 앱이 켠 선택 둘(아이디 · 이메일은 쓰기 때)과 `extra-exclude-paths` 세 줄의 이유, 로컬 시드의 아이디 |
| `docs/deploy.md` | 업그레이드 표: 아이디를 켜는 앱은 `<P>_ACCOUNT_BLOCKS_SECRET` 이 **없으면 기동 실패**(관리자 API 만 켠 앱은 지금처럼 경고). `deploy/app.yaml` 의 그 줄 주석("관리자 API 를 켠 앱만")을 고친다 |
| `capabilities.json` → `docs/capabilities.md` · `llms.txt` | 새 경로 셋 — 생성물은 스크립트로 다시 만든다 |
| `CHANGELOG.md` | `[Unreleased]` 에 두 묶음: "새 기능 (꺼진 채 시작) — 로그인 아이디"(설정 · 새 V 파일 이름 · 계약 · 배포 선언에서 바꿀 것)와 "이메일을 받는 시점 — `email.required-when`"(기본 `SIGN_IN` = 지금과 같다, **견본 앱은 `WRITE` 로 바뀌었다**, 프런트가 할 일) |
| `apps/sample` yml | §9.3 의 조각 + 예약어 몇 개, `application-local.yml` 시드 셋에 `login-id` |

**찍힌 프로젝트와 `scripts/new-project.sh`**: 스크립트는 고치지 않는다.
- 찍힌 프로젝트는 `apps/api`(스타터)에서 출발하고 스타터는 아이디도 이메일 필수도 켜지 않는다. 새 V 파일은 `account-jdbc` 와 함께 따라가 빈 열 · 빈 표만 만든다.
- 이미 `email.required: true` 를 적어 둔 프로젝트는 그대로 "로그인 직후"다 — 키 이름도 값도 바뀌지 않았다.
- `--with-sample` 로 찍으면 견본 앱의 yml 이 따라가 아이디 켬 · 이메일은 쓰기 때로 돈다(시드에 아이디가 있어야 한다).
- 확인은 `scripts/test-new-project.sh` (§16).

**react-skeleton**: `packages/auth/README.md`(막 셋을 붙이는 법 · 순서 · 쓰기 전 확인), `docs/ui-catalog.md`(새 스토리 줄), `capabilities.json` → `pnpm capabilities`, `CHANGELOG.md`(Added — 백엔드 FINAL-7 에 맞춤. Changed — 견본 앱의 이메일 화면이 로그인 직후에서 쓰기 때로), `apps/sample/README.md`.

## 16. 검증

작업 중에는 **바뀐 범위의 시험만** 이름을 지정해 돌린다. 전체 스위트는 끝 검사에서 한 번이다(§17).

| 층 | 무엇을 본다 | 어디 |
|---|---|---|
| 단위 | 규칙 표(§4)의 줄마다 받는 값 · 거절 코드: 마침표 · 하이픈 · 밑줄, 숫자 시작, 맨 앞뒤 기호, 연속 기호의 모든 조합, 숫자만, 대문자 정규화, 예약어(기호를 빼고 비교). 실시간 확인: 형식 오류는 저장소도 한도도 건드리지 않는다, 예약어 · 살아 있는 · 탈퇴한 아이디가 **같은 답**이다. 가입 순서(형식은 한도 앞, 중복 판정은 캡차 뒤). 확정 때 겹치면 시도가 살아 있다. 로그인 버킷이 입력 방식마다 따로다(`email` · `accountId` · 아이디로 읽히지 않는 `username` 은 주소 버킷, 아이디로 읽힌 `username` 은 `loginId:<입력>` 버킷 — 최초 설계의 "하나"는 리뷰 I2 에서 버렸다, §7 의 정정 주). 정하기의 다섯 결과(이메일 없이도 된다). 아이디 찾기가 계정 유무와 무관하게 요청 스레드에서 같은 일을 한다 | `modules/account` `test` — 새 `LoginIdRulesTest` · `LoginIdAvailabilityTest` · `LoginIdSignUpTest` · `LoginIdSignInTest` · `LoginIdClaimTest` · `LoginIdReminderTest`, 기존 `SignUpFormatBeforeLimitTest` · `LoginControlsTest` · `MailRoutingTest` · `AccountMailGoldenTest` 에 줄 추가 |
| 웹 — 아이디 | §14 L 줄마다 상태 · 본문. `401` 본문이 없는 아이디 / 틀린 비밀번호 / 비밀번호 없는 계정에서 **글자까지 같다**. 실시간 확인의 `429` 와 `no-store`. 게이트: 허용 목록의 경로마다 통과, 밖은 읽기도 403, `/%61pi/…` 우회 없음, 순서 아이디 → 이메일 → 약관 | 새 `LoginIdWebTest` · `LoginIdRequiredInterceptorTest` · `LoginIdRequiredWebTest` |
| 웹 — 이메일 "쓰기 때" | 메서드 × 경로 표: `GET` · `HEAD` · `OPTIONS` 통과, `POST` · `PUT` · `PATCH` · `DELETE` 는 허용 목록 밖에서 403, `PATCH /account/me` 통과, `extra-exclude-paths` 의 `METHOD /패턴` 이 그 메서드만 연다, 주소가 생기면 전부 통과. `me` 의 두 키. `required=false` + `required-when=WRITE` 는 기동 실패 | 새 `EmailOnWriteInterceptorTest` · `EmailOnWriteWebTest`, `AccountAutoConfigurationTest` 에 줄 추가 |
| **꺼짐 · 기본 = 지금과 같다** | ① 기존 시험을 **고치지 않고** 전부 통과(`EmailRequired*Test` · `AccountWebTest` · `DiscardUnregisteredAccountTest` · 골든 메일 포함 — `required-when` 기본값이 지금 동작이라는 증거) ② 새 `LoginIdDisabledWebTest`: `methods` · `me` · 관리자 응답에 키가 없다, 가입 본문의 `loginId` 는 무시된다(계정이 만들어지고 열은 `NULL`), `username` 은 이메일로 읽힌다, 새 경로 셋은 `404` ③ `apps/api`(스타터)의 통합 시험을 고치지 않고 통과 | `modules/account` `test` · `noOptionalTest`, `apps/api` |
| DB (두 방언) | 유니크: 같은 아이디를 두 계정이 동시에 정하면 하나만 이긴다. 대문자 입력이 같은 아이디로 겹친다. 탈퇴 익명화 뒤 열이 `NULL` 이고 **같은 아이디를 다시 정할 수 없다**. `DELETE` 모드 · 운영자 지우기 · 버리기 뒤에도 못 정한다. 표 전체 스캔(§11 의 3) | `modules/account-jdbc` `postgresTest` · `mysqlTest` — 새 `JdbcLoginIdDbTest`, 기존 `JdbcErasureDbTest` 에 심는 값 추가 |
| 잠금 · 배포 가드 | 기존 V 파일의 해시가 그대로이고 새 파일만 늘었다. 보호 환경에서 아이디를 켰는데 비밀이 없으면 기동이 막힌다(관리자 API 만 켠 경우는 지금처럼 경고) | `perl scripts/migrations-lock.pl --check`, `MigrationLockTest`, `AccountDeployGuardTest` |
| 업그레이드 | **데이터가 있는 DB 에 새 V 를 적용**한다: 기준선까지 올리고 계정을 넣은 뒤 최신까지. 기존 계정은 `login_id` 가 `NULL`, 이메일로 로그인되고, 다음 호출에서 게이트를 만나고, 아이디를 정하면 풀린다 | `apps/sample` 의 `upgrade/MigrationUpgradeIntegrationTest` · `MySqlMigrationUpgradeTest` 에 단계 추가 |
| 조립 — 아이디 | 견본 앱 여정: 가입(아이디) → 코드 → 아이디로 로그인 → 이메일 변경 → **같은 아이디로 여전히 로그인** → 아이디 찾기 메일 → 탈퇴 → 그 아이디가 실시간 확인에서 "쓸 수 없음", 가입에서 `409`. 비공개 스캔 두 가지(§11 의 1 · 2) | `apps/sample` — `AccountJourneyIntegrationTest` · `GlobalSocialJourneyIntegrationTest` · `LegalJourneyIntegrationTest` 갱신, 새 `leak/LoginIdLeakIntegrationTest` |
| 조립 — 이메일 "쓰기 때" | 소셜 첫 로그인 → 아이디 → 약관 → **읽기 전부 통과**(글 · 알림함 · 첨부 보기 · 알림 읽음 표시 · 언어 바꾸기) → 글쓰기 `403` → 이메일 등록(방금 연 세션: 재인증 없음 / 오래된 세션: `REAUTH_REQUIRED`) → 같은 글쓰기 통과. 버리기: 아이디를 정하기 전 · 정한 뒤 모두 되고 정한 아이디는 다시 못 쓴다. **변경 엔드포인트 전수**: 앱의 변경 메서드 핸들러를 전부 뽑아 주소 없는 계정에게 열린 것이 기대 목록과 정확히 같다, 결제 경로는 막힌다(§9.3). "로그인 직후" 방식의 기존 여정은 `required-when=SIGN_IN` 을 얹어 그대로 둔다 | `apps/sample` — 새 `EmailOnWriteJourneyIntegrationTest` · `WriteGateCensusTest`, 기존 `EmailRequiredJourneyIntegrationTest` 는 설정 한 줄만 얹는다 |
| 찍어내기 | 옵션 꺼진 찍힌 프로젝트가 빌드되고 돈다. `--with-sample` 도 | `scripts/test-new-project.sh` |
| 프론트 단위 | 규칙 함수(백엔드 표와 같은 사례 묶음). 실시간 확인 훅(가짜 시계): 멈춘 뒤에만 부른다, 형식이 틀리면 안 부른다, 늦게 온 답은 버린다, `429` 는 "확인 못 함"이고 내기를 막지 않는다. 상태 줄이 글자로 나온다. 로그인 입력 → 본문 변환, 발견(`loginId` 키 유무), 컨트롤러 상태 기계. 이메일 막: `emailRequired=false` 인 `me` 에서 로그인 직후 열리지 않는다, 쓰기 `403` 에 열리고 등록 뒤 **붙잡은 호출이 다시 나간다**, "나중에 하기"는 그 호출을 `403` 으로 끝낸다, 열려 있는 동안 `me.email` 이 생기면 접는다. **키가 없는 서버 픽스처에서 기존 렌더 시험이 그대로 통과** | `R:packages/auth` `pnpm --filter @skeleton/auth test`, `R:packages/api-client` |
| 스토리 | 아이디 칸의 다섯 상태, 가입(인증 단계 재입력 포함), 로그인(아이디 또는 이메일), 아이디 찾기(입력 · 보냄), 아이디 게이트(빈 칸 · 확인 대화상자 · 버리기 길), 이메일 막의 쓰기 모습("나중에 하기" · 떠나기 전 경고), 설정(아이디 줄 세 상태 · 이메일 구역), 관리자 표. `play` 로 클릭 · 키보드 · 라벨 연결, a11y 위반 없음. 눌리는 요소가 눌리게 보이는지 · 상태가 글자로 읽히는지는 스토리 캡처로 눈으로 확인 | `pnpm test:stories` |
| e2e (진짜 백엔드) | `loginId.e2e.ts`: 양식 가입(입력 즉시 "쓸 수 있는/없는" 글자가 뜬다) → 아이디로 로그인 → 이메일 바꾼 뒤 아이디로 로그인 → 설정에 아이디가 보이고 고치는 요소가 없다 → 아이디 찾기 메일(mailpit) → 소셜 첫 로그인의 게이트(확인 대화상자) → 새로고침해도 막이 그대로 → 로그아웃. `emailRequired.e2e.ts`(쓰기 때로 다시 쓴다): 소셜 계정이 **읽는 화면을 전부 돌아도 이메일 막이 뜨지 않는다** → 글쓰기 단추에서 막 → 등록 → 쓰기 화면. 댓글을 적고 낸다 → 막 → 코드 → **다시 적지 않아도 댓글이 올라간다**. "나중에 하기" 뒤 계속 읽는다. 아이디 게이트에서 버리기. **값 스캔**: X 의 아이디가 Y 가 받는 게시판 · 프로필 · 알림 응답의 어떤 잎 값에도 없다(기존 `valueScan.ts`). 그 밖의 기존 e2e 는 도우미만 고쳐 통과 | `pnpm e2e:sample` (백엔드는 `scripts/sample-e2e-backend.sh`) |
| 실제 스택 화면 캡처 | 견본 앱을 띄우고 브라우저로 직접: 가입 양식(아이디 상태 줄) · 인증 단계 재입력 · 로그인 · 아이디 찾기 두 상태 · 아이디 게이트 · 읽기 화면(이메일 없이) · 쓰기 전 이메일 화면과 "나중에 하기" · 댓글 위 띠 · 설정 · 관리자 표 · 메일 한 통 — ko / en. `walkthrough.loginId.shots.ts` · 기존 `walkthrough.email.shots.ts` 갱신 | QA 단계 |

## 17. 작업 목록

크기: S = 파일 5개 안팎 · M = 10~20. 조각마다 그 조각의 시험만 돌린다. 두 방언의 V 파일은 **같은 버전 번호**로 만든다(기존 관례).

**백엔드** (`kotlin-skeleton`, 기준 `feat/email-required`)

| # | 작업 | 크기 | 검증 (끝났다는 증거) |
|---|---|---|---|
| B1 | 설정: `LoginId` 묶음 · `email.required-when`(둘 다 검증 포함) · `LoginIds`(§4) · `Account.loginId` · 오류 코드 셋 · 이벤트 종류 둘 · `docs/config/modules/account.yml` | S | `./gradlew :modules:account:test --tests '*LoginIdRulesTest' --tests '*AccountAutoConfigurationTest'` |
| B2 | 새 V 파일(두 방언) + `perl scripts/migrations-lock.pl`. 저장소 포트(§5.5)와 메모리 · JDBC 구현, 해시 계산기(`AccountBlocks` 의 키 + `login-id:` 접두사), `erase` 가 열을 비움, `AccountDeployGuard` 가 비밀 없으면 기동을 막음 | M | `./gradlew :modules:account-jdbc:postgresTest --tests '*LoginId*' --tests '*Erasure*'`, 같은 것을 `mysqlTest` 로, `perl scripts/migrations-lock.pl --check`, `--tests '*AccountDeployGuardTest'` |
| B3 | 가입: `SignUpCommand.loginId` · 순서(§6.1) · payload · 확정 때의 `409` 와 시도 되살리기 · `insertNamed` 결과 · DTO 두 곳(Bean Validation 을 붙이지 않는다) · 시드의 `login-id` | M | `:modules:account:test --tests '*LoginIdSignUpTest' --tests '*SignUpFormatBeforeLimitTest' --tests '*LoginIdWebTest'` |
| B4 | 실시간 확인: 서비스(가입 3번과 **같은 판정 함수**) · 공개 엔드포인트 · IP 한도 · `no-store` | S | `--tests '*LoginIdAvailabilityTest' --tests '*LoginIdWebTest'` |
| B5 | 로그인: `AccountAuthRepository.findBy` · `LoginThrottle.bucketOf` | S | `--tests '*LoginIdSignInTest' --tests '*LoginControlsTest'` — `401` 본문 비교와 버킷 분리(최초 설계의 "버킷 공유"는 리뷰 I2 에서 버렸다 — 입력 방식마다 따로, 아이디로 읽히지 않는 `username` 만 이메일 버킷) |
| B6 | 아이디 게이트: `LoginIdService.claim` · `POST /account/login-id`(조건부 컨트롤러) · `LoginIdRequiredInterceptor`(순서 `-110`) + 자동설정 · 경로 규칙과 허용 목록 일반화 · `me.loginId` · `methods.loginId` | M | `--tests '*LoginIdClaimTest' --tests '*LoginIdRequired*' --tests '*LoginIdDisabledWebTest' --tests '*EmailRequired*'`(마지막은 **무수정** 통과), `:modules:account:noOptionalTest` |
| B7 | 이메일 "쓰기 때": 인터셉터의 메서드 판정 · 이 방식의 고정 경로 `PATCH /account/me` · `me.emailRequiredToWrite` · 버리기의 문서 주석과 OpenAPI 문구 · `DiscardService` 는 조건을 그대로 두고 아이디 있는 계정의 시험만 더한다 | M | `--tests '*EmailOnWrite*' --tests '*EmailRequired*' --tests '*DiscardUnregisteredAccountTest'`(뒤의 둘은 무수정 통과) |
| B8 | 아이디 찾기: 서비스 · 공개 엔드포인트 · `MailKind.LOGIN_ID_REMINDER` · ko/en 문구(텍스트 + HTML, 두 본문) · 골든 파일. 기존 메일은 건드리지 않는다 | S | `--tests '*LoginIdReminderTest' --tests '*AccountMailGoldenTest' --tests '*MailRoutingTest' --tests '*MailPathLogLeakTest' --tests '*SecretsStayOutOfToStringTest'` |
| B9 | 관리자: 응답의 `loginId`(꺼지면 키 없음) · `?loginId=` 정확히 일치 | S | `--tests '*AccountWebTest'` 의 관리자 줄 + 새 줄 |
| B10 | `apps/sample` 에서 켠다: yml(§9.3) · 로컬 시드 · 통합 시험 갱신 · `LoginIdLeakIntegrationTest` · `EmailOnWriteJourneyIntegrationTest` · `WriteGateCensusTest` · 업그레이드 시험 둘 | M | `./gradlew :apps:sample:test --tests '*Journey*' --tests '*LoginIdLeak*' --tests '*WriteGateCensus*' --tests '*MigrationUpgrade*'`, `bash scripts/test-new-project.sh` |
| B11 | 문서(§15) · CHANGELOG · 카탈로그 생성물 | S | 문서 · 카탈로그 가드 시험, 계약 문서의 줄마다 B3~B9 의 시험 이름이 짝지어져 있다 |

순서: B1 → B2 → B3 → B4 → B5 → B6 → B7 → B8 → B9 → B10 → B11. B3~B9 는 논리로는 나란히 갈 수 있지만 같은 파일(`AccountProperties` · 자동설정 · `AccountDtos` · `AccountErrors` · 경로 규칙)을 건드리므로 **한 줄로** 간다. B7 은 B6 의 경로 규칙 위에 얹힌다.

**프론트** (`react-skeleton`, 기준 `feat/author-profiles`)

| # | 작업 | 크기 | 검증 |
|---|---|---|---|
| F1 | 오류 코드 셋 · 타입 · `accountApi` 세 호출 · 발견(`loginId`) · 규칙 함수 · 문구 ko/en · 가짜 API(`stories/fakeAccountApi`) | S | `pnpm --filter @skeleton/api-client test`, `pnpm --filter @skeleton/auth test`(규칙 · 발견 · 문구 시험) |
| F2 | 아이디 칸 조각 `LoginIdField` 와 실시간 확인 훅(디바운스 · 늦은 답 버리기 · 다섯 상태의 글자) · 스토리 | M | `pnpm --filter @skeleton/auth test`(가짜 시계), `pnpm test:stories` |
| F3 | 가입 화면의 아이디 칸 · 인증 단계 재입력(`LoginIdRetry`) · 스토리 | M | 같은 둘 |
| F4 | 로그인 칸(아이디 또는 이메일) · 입력 → 본문 변환 · `ForgotLoginIdScreen` + 경로 · 스토리 | M | 같은 둘. 키 없는 픽스처에서 기존 로그인 스토리 · 시험이 무수정 통과 |
| F5 | 아이디 게이트: 컨트롤러(공통 부분 뽑기) · `LoginIdGate` · `LoginIdRequiredScreen` · 확인 대화상자 · 버리기 길 · 스토리 | M | 컨트롤러 시험(기존 `emailRequired.test.ts` 무수정 통과) + `pnpm test:stories` |
| F6 | 이메일 "쓰기 때": 막의 쓰기 모습("나중에 하기" · 떠나기 전 경고) · 접는 판정을 `me.email` 로 · `useEmailBeforeWrite` · 버리기 확인 문구 · 스토리 | M | 렌더 · 상태 시험(로그인 직후 방식의 기존 시험 무수정 통과) + `pnpm test:stories` |
| F7 | 설정의 아이디 줄(세 상태) · 이메일 구역 문구 · 관리자 표의 열과 찾기 · 스토리 | S | 렌더 시험 + `pnpm test:stories` |
| F8 | `apps/sample` 연결(§13.3: 막의 겹침 · 복구 사슬 · 쓰기 전 확인 · 닫았을 때의 토스트) · e2e 도우미 · `loginId.e2e.ts` · `emailRequired.e2e.ts` 다시 쓰기 · 화면 캡처 스크립트 | M | `pnpm --filter sample test`, `pnpm e2e:sample`(B10 뒤) |
| F9 | README · `docs/ui-catalog.md` · `pnpm capabilities` · CHANGELOG | S | `pnpm capabilities:check`, `tests/stories.test.ts` |

F1~F7 은 §14 의 계약만 정해지면 가짜 API 로 백엔드와 나란히 간다. F8 은 B10 뒤다.

**리뷰 · QA · 끝 검사**

| # | 무엇 | 검증 |
|---|---|---|
| R1 | 백엔드 코드 리뷰 — 초점: 가입 순서가 이메일 존재를 흘리지 않는가 · 실시간 확인과 가입이 같은 판정을 쓰는가 · 정하기의 경합(두 유니크) · "쓰기 때"에서 열린 변경 경로가 목록과 같은가(결제 포함) · 꺼짐에서 정말 아무것도 읽지 않는가 · 아이디가 로그 · 이벤트 · 토큰에 실리지 않는가 | 지적마다 고친 커밋 또는 반박 근거. 리뷰어는 시험을 다시 돌리지 않는다 |
| R2 | 프론트 코드 리뷰 — 초점: 막 셋의 순서와 계정 바뀜 · 붙잡은 쓰기가 다시 나가는 길과 닫았을 때 · 실시간 확인의 늦은 답 · 화면 원칙(주 단추 하나, 테두리, 밑줄, 상태는 글자로) · 키 없는 서버에서 기존 화면과 같은가 | 같다 |
| Q1 | 실제 스택 QA: 견본 앱 두 쪽을 띄우고 §16 마지막 줄의 화면을 브라우저로 눌러 본다(ko · en). 메일은 mailpit 에서 연다. 소셜 계정으로 **읽기만 하며 한 바퀴** 돌아 이메일 화면이 뜨지 않는지, 쓰려 할 때 뜨고 등록 뒤 이어지는지 | 캡처 묶음 + 어긋난 것 목록 |
| Q2 | 설정을 바꿔 가며 확인: ① `login-id.enabled` 끔 — 아이디 칸 · 막 · 설정 줄이 없다 ② `required-when` 을 지움(로그인 직후) — 아이디 → 이메일 → 약관 순으로 뜬다 ③ `email.required` 끔 — 이메일 화면이 어디에도 없다 | 캡처 |
| E1 | 전체 스위트 한 번씩: `./gradlew build`(두 방언 포함) · `pnpm lint && pnpm typecheck && pnpm test && pnpm test:stories && pnpm build` · `pnpm e2e:sample` | 전부 초록. 돌리지 않은 범위가 있으면 보고에 적는다 |
| E2 | 잠금 · 생성물 · 찍어내기: `perl scripts/migrations-lock.pl --check` · 카탈로그 검사 · `bash scripts/test-new-project.sh` · `R:` 의 `bash scripts/test-new-project.sh --quick` | 통과 |
| E3 | 이 문서의 「정해야 할 것」이 모두 "결정됨"으로 바뀌었고 본문이 결정과 맞는다 (2026-10-09 완료) | 문서 상태 줄 |

## 18. 위험

### 18.1 이 설계가 막지 못하는 것

1. **아이디 하나가 "있는지"는 누구나 한 번의 요청으로 확인한다.** 실시간 확인은 로그인도 캡차도 없이 답한다(결정 — 다른 서비스와 같은 수준). 사용자가 다른 곳에서 쓰는 핸들을 아이디로 정하면, 그 핸들을 아는 사람이 "이 서비스에 가입했다"를 추정할 수 있다 — 민감한 판에서는 실제 위험이다. 훑어서 목록을 만드는 것은 한 IP 하루 4,320번으로 느려질 뿐이고, **콕 집어 묻는 한 번**은 막지 못한다. 가입 양식에 "다른 곳과 다르게" 안내는 넣지 않기로 했다(결정).
2. **로그인한 계정도 물어볼 수 있다.** 아이디가 없는 계정은 「아이디 정하기」의 `409` 로도 안다(계정당 한 시간 10번).
3. **넓힌 규칙은 닮은 아이디를 만든다.** `a.b` · `a-b` · `a_b` 는 서로 다른 사람이다. 아이디가 공개되지 않아 사칭은 아니지만 본인이 헷갈리고, 문의 응대에서 받아 적다 틀린다. 규칙은 **나중에 넓힐 수는 있어도 좁힐 수는 없다.**
4. **서버 비밀이 바뀌면 탈퇴한 아이디의 재사용 금지가 풀린다**(§5.3). 보호 환경에서 비밀이 없으면 기동을 막지만, 있던 비밀을 다른 값으로 바꾸는 것은 막지 못한다. 잃어버린 뒤 되살릴 방법은 없다.
5. **못 바꾼다는 것 자체.** 오타 · 후회 · 아이디가 남에게 알려진 경우에도 길은 탈퇴 뒤 새 계정뿐이다(옛 아이디는 영영 묶인다). 게이트는 확인 대화상자를 거치지만 가입 양식은 안내문 한 줄뿐이다.
6. **첫 쓰기에 제공자 화면을 한 번 다녀온다.** "쓰기 때" 방식에서 오래된 세션의 첫 이메일 등록은 제공자 재확인을 요구한다(확정: 규칙 그대로, §9.5). 미리 알리기를 지나쳐 글을 다 쓴 뒤 막히면 그 글은 왕복에서 사라진다.
7. **주소 없는 계정이 오래 산다.** 읽기만 하는 소셜 계정은 이메일을 끝내 등록하지 않을 수 있다. 그 계정에는 보안 알림을 보낼 곳이 없고, 제공자 계정을 잃으면 되찾을 길도 없다. 쓴 것이 없으니 잃을 것도 적다는 판단이다.
8. **"쓰기 때"의 경계는 메서드와 목록이다.** `GET` 으로 상태를 바꾸는 엔드포인트, 소켓으로 들어오는 쓰기 메시지는 이 게이트를 지나간다. 앱이 `extra-exclude-paths` 에 결제 경로를 적으면 결제가 열린다 — 견본 앱은 전수 시험이 지키지만 다른 앱은 스스로 지켜야 한다. 반대로 모듈에 새 "사소한 쓰기"가 생겼는데 목록에 안 적으면 읽기 화면에서 이메일 막이 뜬다(견본 앱은 e2e 의 "읽기 한 바퀴"가 잡는다).
9. **버리기는 되돌릴 수 없고, 이제 아이디도 태운다.** 몇 달 쓴 읽기 계정을 확인 한 번으로 지울 수 있다(확정: 조건 그대로, §9.6).
10. **아이디가 없는 계정에 붙은 탈취된 세션**은 주인 대신 아이디를 정해 버릴 수 있다(재인증이 없다). 기능을 켜는 순간의 기존 계정에만 해당하고, 서비스 중인 계정이 없다는 전제로 받아들인다.
11. **아이디를 아는 사람은 그 계정의 비밀번호 로그인을 잠깐 막을 수 있다**(10분에 10번 한도를 대신 써 버린다). 이메일에 이미 있는 대가다. 소셜 · 매직 링크 로그인은 영향받지 않는다.
12. **비밀번호가 아이디와 같아도 막지 않는다**(결정: 이번에는 넣지 않는다).
13. **"로그인 직후" 방식의 e2e 가 사라진다.** 견본 앱이 "쓰기 때"로 바뀌므로 그 방식은 백엔드 통합 시험과 프론트 패키지 시험 · 스토리 · Q2 의 손 확인만 남는다.
14. 운영자 · DB · 서버 로그를 보는 사람에게는 아이디를 숨기지 않는다. 해시 표는 계정 수만큼(한 줄 64자) 자라고 줄어들지 않는다 — 무시할 크기다.
15. **계정당 비밀번호 추측 한도가 창마다 이메일 몫 + 아이디 몫이다.** 아이디 로그인이 따로 세어지므로(§7 의 정정 주, 리뷰 I2) 한 계정에 걸린 `login.per-account` 가 입력 방식 둘의 합이다. 이메일 버킷과 합치면 429 시점으로 아이디를 맞히는 오라클이 생기므로 받아들인 대가다.
16. **정하기 · 실시간 확인의 IP 한도를 한 IP 뒤 사용자들이 같이 쓴다**(`claim-limit` · `check-per-ip` — 공유기 · 회사 망 · 로컬 개발). 한 사용자의 연타가 같은 IP 의 다른 사용자를 429 로 막을 수 있다. 가입은 확정 때의 `409` 가 받으므로 막히지 않지만, 로컬 개발은 `application-local.yml` 에서 값을 올린다.
17. **한도 저장소의 키는 아이디의 열쇠 없는 SHA-256 이다**(`AccountRateLimits.digest`). Redis 한도 저장소를 쓰면 창 길이만큼 남고 짧은 아이디는 사전으로 되돌려질 수 있다 — 저장소를 읽는 사람에게는 숨기지 않는다는 14 와 같은 취급.

### 18.2 구현 전에 실측할 것 (읽기만으로는 확정하지 못한 것)

- MySQL 의 `alter table … add column` 에서 `collate` 절을 jOOQ 마커로 가린 문장이 jOOQ `DDLDatabase` 와 Flyway 양쪽에서 읽히는가 — 기존 파일은 `create table` 안에서만 그 마커를 썼다. (B2)
- `scripts/migrations-lock.pl` 이 새 파일을 받는 조건("순서가 어긋난 새 파일은 거절") — 새 버전이 가장 뒤여야 하는지. (B2)
- 캡차를 통과한 뒤 `409` 가 났을 때 프론트의 캡차 자리가 새 토큰을 받는가 — 지금 서버 비밀번호 정책 위반에서의 동작을 본다. (F3)
- WebSocket: 기본 경로(`/ws/notifications`)는 `/api/**` 밖이다. 앱이 경로를 `/api/` 아래로 옮기면 MVC 인터셉터가 그 연결에 걸리는가, 그리고 스켈레톤의 소켓 설정이 클라이언트 → 서버 메시지를 받는 목적지를 기본으로 여는가. (B7 — 결과를 `docs/accounts.md` 의 WebSocket 줄에 적는다)
- 스켈레톤 모듈의 `GET` 중 상태를 바꾸는 것이 정말 없는가(전수 확인). (B7)
- 붙잡았다 다시 보내는 쓰기 호출이 같은 `Idempotency-Key` 로 나가는가, "나중에 하기"로 끝난 `403` 을 글쓰기 폼이 어떻게 받는가. (F6 · F8)
- 설정 화면의 제한 모드가 아이디 없는 계정에서 403 없이 그려지는가(`GET /account/password/policy` 같은 호출). (F7)
- OpenAPI 문서에서 GET 경로를 전부 뽑아 경로 변수를 채우는 방식, 그리고 변경 핸들러 전수가 조건부 컨트롤러 · 함수형 라우트까지 덮는가. 못 덮는 경로는 손으로 적은 목록을 **더하고** 그 사실을 시험에 적는다. (B10)
- `apps/starter` · `apps/starter-ssr` 가 발견 값을 화면에 어떻게 넘기는지 — 키가 없을 때 무변인지. (F4)
- `--with-sample` 로 찍은 프로젝트의 시드가 아이디를 갖고 뜨는가. (B10)

## 정해야 할 것

### 가. 2판에서 생긴 3건 — 모두 확정 (2026-10-09 주인: 추천안 (가))

본문은 확정한 선택으로 적혀 있다. 아래는 그때 견준 안의 기록이다.

1. **"쓰기 때" 방식에서 첫 이메일 등록의 재인증** (§9.5) — **확정: (가)**
   - (가) **지금 규칙 그대로**: 로그인으로 연 지 1시간 안의 세션만 재인증이 없고 그 뒤는 제공자 화면을 한 번 다녀온다. 쓰기 화면에 들어서기 전에 미리 알려 글을 잃지 않게 한다 — 훔친 세션이 남의 계정에 자기 주소를 심어 영구 자격으로 바꾸는 길을 계속 막는다. 제공자에 로그인돼 있으면 왕복은 스쳐 지나간다
   - (나, 버림) 견본 앱에서 창을 길게 잡는다(`first-registration-window: 30d` 같은 값) — 그 기간 안에 훔친 세션은 주소를 심을 수 있다
   - (다, 버림) 주소가 한 번도 없던 계정은 세션 나이와 무관하게 재인증 없이 — 훔친 세션 하나가 곧 계정이다
2. **버리기(`discard`)의 조건** (§9.6) — **확정: (가)**
   - (가) **조건은 그대로**(이메일 필수 앱 · 주소를 가진 적 없음 · `ACTIVE`), 뜻과 문구만 고치고("아이디도 함께 사라지고 다시 쓸 수 없어요") 아이디 게이트에도 길을 둔다 — 유예를 건너뛰어도 되는 근거("아무것도 쓰지 못한 계정")가 두 방식에서 같고, 소셜 로그인을 당장 풀어야 하는 사정도 같다
   - (나, 버림) 만든 지 며칠 안의 계정만 버릴 수 있다 — 그 기간이 지난 뒤 "이미 계정이 있었다"를 안 사람은 소셜을 연결하지 못하는 막다른 길이 되살아난다
   - (다, 버림) "쓰기 때" 방식에서는 버리기를 없애고 일반 탈퇴만 — 같은 막다른 길이 모든 사람에게 생긴다
3. **숫자만으로 된 아이디** (§4) — **확정: (가) 막는다**(영문이 하나는 있어야 한다)
   - (가) 숫자 시작을 허용하면서 전화번호 · 생일 · 학번이 그대로 아이디가 되는 것만 막는다
   - (나, 버림) 허용한다 — 개인정보에 가까운 숫자열이 아이디로 쌓인다

### 나. 결정됨 (기록 — 2026-10-09 주인)

| # | 물음 | 결정 |
|---|---|---|
| 1 | 이미 쓰는 아이디를 언제 말하나 | **입력하는 즉시** — 실시간 확인 API. IP 당 한도, 탈퇴한 아이디도 "쓸 수 없음", 제출 때의 `409` 는 그대로. (첫 판 추천은 "양식을 낼 때만"이었다) |
| 2 | 아이디 규칙 | **넓힌다** — 영문 · 숫자 · 마침표 · 하이픈 · 밑줄, 4~20자, 숫자 시작 허용, 소문자로 저장. 금지: `@` · 공백 · 맨 앞뒤 기호. (첫 판 추천은 밑줄만 · 영문 시작이었다) |
| 3 | 해시의 비밀이 없는 보호 환경 | **기동을 막는다** |
| 4 | 비밀번호 재설정 메일에 아이디 | **싣지 않는다** — 아이디 찾기가 따로 있다. (첫 판 추천은 "싣는다"였다) |
| 5 | 게이트 순서 | 로그인 직후는 **아이디 → 약관**, **이메일은 쓰기 때** — 이메일을 받는 시점 세 단계를 이 묶음에 넣는다 |
| 6 | 비밀번호에 아이디 포함 금지 | **이번에는 넣지 않는다** |
| — | 가입 양식의 "다른 곳에서 쓰는 아이디와 다르게" 안내 | **넣지 않는다** |
| — | 탈퇴한 계정의 아이디 | **다시 못 쓴다** |
| — | 아이디 찾기 | **넣는다** |
| — | 이메일로 가입하는 사람 | 지금처럼 가입 때 인증 — **변함 없음** |

**추천안으로 정해 본문에 적용한 것** (주인이 "추천안으로"라고 맡긴 것): 실시간 확인의 한도 값(한 IP 10분 30번), 이유 구분(형식 / 쓸 수 없음 둘 — 예약어 · 사용 중 · 탈퇴는 하나로), 연속 기호 금지, 설정 키(`email.required` 는 두고 `required-when` 을 더함), 사소한 쓰기를 여는 방식(앱 yml), 하던 쓰기 이어 가기(한다 — 새로 만드는 것 없이), 쓰기 화면에 들어설 때 미리 알리기(한다).
