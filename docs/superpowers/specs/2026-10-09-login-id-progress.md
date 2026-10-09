# 진행 — login-id 로그인 아이디(비공개) + 이메일을 받는 시점(쓰기 때)
상태: 막힘
리드: lead (2026-10-09 02:24~08:40) · 앞 리드: 없음 · 작업·리뷰·QA·끝 검사는 전부 끝났고 주인 결정 4건(「정해야 할 것」)만 남아 "막힘"으로 둔다
넘겨받기: 지휘 세션의 지시문(파일 없음 — 요지는 아래 「넘겨받은 규칙」) · 스펙 `docs/superpowers/specs/2026-10-09-login-id-design.md` · 시작 HEAD kotlin `a5b2dd9` / react `bb09577`

## 넘겨받은 규칙 (요지)
- 두 레포의 브랜치 `feat/login-id` 에서 조각마다 커밋. **push · main 병합 · 리베이스 금지.**
- 주인 확정(스펙 「정해야 할 것」 3건 모두 추천안): ① 첫 이메일 등록 재인증 규칙 그대로 + 쓰기 전 미리 알림 ② discard 조건 그대로, 뜻·확인 문구만, 아이디 게이트에도 길 ③ 숫자만 아이디 금지.
- 마이그레이션 동결(새 V 파일만 + 잠금), 작업 중엔 바뀐 범위의 시험만, 끝 검사는 깨끗한 복사본에서 리드가 한 번.
- 실행 환경에 관한 세부(포트·컨테이너·검사 스크립트)는 지휘 세션의 지시문에 있다 — 공개 레포인 이 문서에는 적지 않는다.

## 작업
리뷰 방식: 스펙 §17 · 지시문대로 **R1(백엔드 전체) · R2(프론트 전체) 독립 리뷰 + 확인 리뷰**로 묶는다. 그래서 B·F 조각별 리뷰 칸은 "R1/R2 에서"로 적는다(조각마다 따로 부르지 않는 이유).

| 키 | 내용 | 상태 | 리뷰 | 커밋 |
|---|---|---|---|---|
| B1 | 설정·LoginIds·오류 코드·이벤트 (+스펙 확정 표기, 스펙 첫 커밋) | 끝 (지정 시험 2개 + 설정 문서 가드 통과, 계정 전체 스위트는 안 돌림) | R1 에서 | 5081245 |
| B2 | 새 V 파일(두 방언)·잠금·저장소 포트·해시·erase·배포 가드 | 끝 (두 방언 DB 시험·잠금 check·capabilities check 통과; 전체 스위트·apps 시험 안 돌림) | R1 에서 | b211fdd |
| B3 | 가입(아이디)·확정 409·시드 | 끝 (지정 시험 3 + 건드린 클래스 기존 시험 통과; account 전체·noOptionalTest·DB·apps 안 돌림) | R1 에서 | 2645737 |
| B4 | 실시간 중복 확인 | 끝 (지정 시험 + 가드 통과; 전체·apps 안 돌림) | R1 에서 | 3dbb974 |
| B5 | 로그인(아이디 또는 이메일)·버킷 | 끝 (지정 시험 + 건드린 클래스 기존 시험 통과; 전체·auth 모듈·DB·apps 안 돌림) | R1 에서 | 3aeb904 |
| B6 | 아이디 게이트(정하기·인터셉터·허용 목록 일반화·me/methods) | 끝 (B6 시험 묶음 + EmailRequired* 무수정 + noOptionalTest 통과; account 전체·platform 가드·DB·apps 안 돌림) | R1 에서 | 6134c14 |
| B7 | 이메일 "쓰기 때" | 끝 (EmailOnWrite 24 + EmailRequired*·Discard 무수정 + noOptionalTest 통과; account 전체·platform 가드·DB·apps 안 돌림) | R1 에서 | 9803763 |
| B8 | 아이디 찾기·메일 | 끝 (지정 시험 + 가드 통과, 기존 골든 무변; account 전체·apps 안 돌림) | R1 에서 | d058790 |
| B9 | 관리자 응답·검색 | 끝 (AccountWebTest·LoginIdAdminWebTest·LoginIdDisabledWebTest·noOptionalTest 통과; 전체·apps 안 돌림) | R1 에서 | 0e18682 |
| B10 | apps/sample 켜기·통합 시험·업그레이드 시험 | 끝 (apps:sample 전체 93 · apps:api 26 · test-new-project.sh --quick 190 통과; 레포 전체·--full·deploy-contract 안 돌림) | R1 뒤 회차에서 | 5677f7f |
| B11 | 문서·CHANGELOG·카탈로그 (+배포 계약 스크립트 수정, L1 시험 추가) | 끝 (test-deploy-contract.sh · test-new-project.sh quick · 잠금/카탈로그 check · platform 문서 가드 통과; 전체 빌드 안 돌림) | R1 2회차에서 | 9cb99c2 · fbafb46 · 93d6580 |
| F1 | 오류 코드·타입·API·규칙·문구·가짜 API | 끝 (api-client 57 · auth 599 통과, 앱 typecheck·스토리 전체는 안 돌림) | R2 에서 | 6a6b1c2 |
| F2 | LoginIdField·실시간 확인 훅 | 끝 (screens 194 · 새 스토리 11 · 루트 가드 통과, auth 전체·스토리 전체는 안 돌림, 캡처 눈 확인 안 함) | R2 에서 | 04fdca1 |
| F3 | 가입 화면·LoginIdRetry | 끝 (auth 634 · SignUp 스토리 44 · 루트 가드 통과; 루트 전체·스토리 전체 안 돌림) | R2 에서 | 563f12f |
| F4 | 로그인 칸·아이디 찾기 화면 | 끝 (auth 652 · 스토리 2파일 40 · 루트 가드 · starter 두 앱 typecheck 통과; 루트 전체·스토리 전체 안 돌림) | R2 에서 | 4058555 |
| F5 | 아이디 게이트 | 끝 (auth 678 · 새 스토리 15 · EmailRequired 스토리 20 · 루트 가드 통과; apps typecheck·루트 전체 안 돌림) | R2 에서 | 1d56725 |
| F6 | 이메일 "쓰기 때" 막 | 끝 (auth 711 · EmailRequired 스토리 34 · 루트 가드 통과; 루트 전체·스토리 전체·실제 브라우저 캡처 안 함) | R2 에서 | c31e090 |
| F7 | 설정·관리자 | 끝 (auth 726 · 스토리 4파일 135 · 루트 가드 통과; 루트 전체·실제 브라우저 안 함) | R2 에서 | 4239226 |
| F8 | apps/sample 연결·e2e | 끝 (e2e 전체 10파일 127 통과 · 1 건너뜀(약관 재동의 필터 — 환경변수 필요), 백엔드 93d6580 복사본; sample 단위 163 · typecheck · 루트 가드 통과; 루트 전체·스토리 전체 안 돌림) | R2 2회차에서 | 51ac6b1 · c268048 |
| F9 | README·카탈로그·CHANGELOG | 끝 (capabilities check · 루트 가드 7파일 통과; 루트 전체·스토리 안 돌림) | R2 2회차에서 | 475ead7 |
| R1 | 백엔드 전체 독립 리뷰 → 수정 → 확인 리뷰 | **2회차(확인, 0e18682..93d6580) 끝: Critical 0 · Important 1(코드 아님 — 이 진행 문서의 공개 전 내용 정리, 반영함) · Minor 12 · 1회차 14건 전부 "고쳐짐" · 병합 전 고칠 코드 결함 없음.** 수정 2회차 끝 `2d79a05`(M2·M3·M4(문서)·M7~M11 반영; 보강한 누출 시험은 임시 주입 두 가지를 잡는 것 확인 후 원복, 실제 누출 없음). 2회차 수정은 시험·문서·로컬 yml 뿐이라 3회차 리뷰는 부르지 않음(확인 리뷰가 "병합 전 고칠 코드 결함 없음"으로 끝났고 그 뒤 main 코드 변경 없음). / 1회차(모듈 B1~B9, a5b2dd9..0e18682) 끝: Critical 0 · Important 3 · Minor 11 → 수정 1회차 끝 `701d548`(I1~I3 · M1~M11 전부 고침, 반박 없음; account 지정 269 · noOptionalTest · 두 방언 DB · sample 여정 묶음 통과). 확인 리뷰(2회차)는 B11 뒤에 전체 범위로 | | |
| R2 | 프론트 전체 독립 리뷰 → 수정 → 확인 리뷰 | 1회차(패키지 F1~F7, bb09577..4239226) 끝: Critical 1 · Important 4 · Minor 10 → 수정 1회차 끝 `9e52432`(C1·I1~I4·M2~M4·M7·M8·M10 고침, M5·M9 부분, M1·M6 안 고침; auth 745 · 스토리 7파일 224 · 가드 · 앱 3개 typecheck 통과). **2회차(확인, 4239226..475ead7) 끝: Critical 0 · Important 3 · Minor 12, 1회차 지적 전부 고쳐짐.** 수정 2회차 끝 `fd74220`(I1~I3 모두 수정 전 재현됨 → 고침; m2·m4~m11·QA 문구 반영; e2e 전체 10파일 132 통과 · 1 건너뜀, auth 747 · sample 160 · board 123 · author-profile 94 · 스토리 144 · 가드 통과). **3회차(475ead7..fd74220) 끝: Critical 0 · Important 1(I-A: `/account` 에서 닉네임 띠 단추가 조용히 죽음 — I1 수정이 만든 새 문제) · Minor 4.** 수정 3회차(마지막 회송) 끝 `2366d46`: I-A(수정 전 e2e 로 재현됨 → `/account` 에서는 안내 한 줄 + 이메일 구역으로) · lint 오류 · `node:fs` 시험(루트 가드로 옮김) · M1·M2 문서. 루트 lint·typecheck·format·capabilities · auth 746 · sample 160 · `test-new-project.sh --full` · e2e 전체 통과. **4회차(fd74220..2366d46) 끝: Critical 0 · Important 0 · Minor 5 — 병합 전 반드시 고칠 것 없음.** 남은 Minor(안 고침): 새 e2e 가 `me` 두 번의 타이밍에 기댐(실패 쪽으로만 흔들림) · `toBeInViewport` 가 스크롤을 증명 못 할 수 있음 · `/account` 이동의 스크롤 주석·포커스·히스토리 누적 · I-A 분기에 단위 시험 없음(+`useEmailBeforeWrite` JSDoc) · 커밋에 의도 넷이 섞임 | | |
| Q1 | 실제 스택 QA + 캡처 | 끝 — 11항목 전부 통과(백엔드 2d79a05 · 프론트 475ead7, 별도 포트·compose). 캡처 201장 + captions.md 는 지휘 세션 스크래치의 `login-id/`. 못 본 것: 알림 읽음의 화면 클릭(API 로만), 작성자 프로필 화면, 영어 화면 눈 확인, 오래된 세션 재인증 길. **R2 2회차 I1(닉네임 대화상자 밑에 깔리는 이메일 화면)은 QA 목록에 없어 안 봄 → 수정 뒤 따로 확인** | | |
| Q2 | 설정 바꿔 가며 확인 | 끝 — ① 아이디 끔 ② 로그인 직후(아이디 → 이메일 → 약관, "나중에 하기" 없음) ③ 이메일 필수 끔 모두 통과(환경변수로 백엔드 재기동) | | |
| E1·E2 | 끝 검사(깨끗한 복사본 전체 빌드·찍어내기·배포 계약 · 프론트 전체 · e2e 전체) | 끝 — 전부 통과(아래 「끝 검사」) | | kotlin `2d79a05` · react `2366d46` |
| E3 | 스펙 「정해야 할 것」 확정 표기 | 끝 (B1 커밋에 포함) | | 5081245 |

## 지금 하는 일
없음 — 돌려줌(08:40). 최종 커밋: kotlin `2d79a05`(+ 이 진행 문서만 고친 마지막 커밋) · react `2366d46`. push·병합은 하지 않았다.

## R1 리뷰 1회차 지적 (백엔드 모듈)
- **I1** 아이디 입력에 길이 상한이 없어 공개 경로(`login-id/availability` 등)에서 정규식 재귀 `StackOverflowError`(500 + 전체 스택 로그). 6KB 입력으로 실측. → 정규식 전에 길이 상한.
- **I2** 로그인 버킷 합치기(스펙 §7)가 "이 아이디는 이 이메일 계정의 것"을 확인해 준다: 이메일 버킷을 채운 뒤 후보 아이디를 한 번씩 보내 429 면 맞음. → **리드 결정: 추천안 (가)로 진행** — 아이디 로그인은 계정 존재와 무관하게 늘 `loginId:<정규화>` 버킷(대가: 계정당 추측이 창마다 2배). 스펙 §7·§14 L5 에서 벗어남 → 「정해야 할 것」 2.
- **I3** 소셜 신원 하나로 claim → discard → 재로그인을 반복해 아이디를 무제한 영구 소각. → claim 에 IP 버킷.
- Minor 11: M1 판정 단락 평가 타이밍 · M2 `used_at = createdAt` 이 해시를 계정 행과 이음 · M3 로그인 실패 이벤트 id 가 열쇠 없는 SHA-256 · M4 availability 응답 `reason`/`code` null 직렬화 가능성 · M5 `ResponseEntity<*>` · M6 응답 DTO toString · M7 claim-limit 문구 · M8 창 길이 검증 없음 · M9 켈빈 기호 K · M10 두 유니크 잠금 순서 반대(교착 → 재시도) · M11 시드 아이디 실패 진단
- 스펙 밖 결정 11건: 10 수용/조건부, 1 반대(약) = `used_at`(M2)
- 초점 3(인터셉터 우회)·4(쓰기 때 허용 목록)·5(꺼짐 동일성): 문제 없음

## R1 리뷰 2회차 (확인) 지적
- **I-1 (공개 전, 코드 아님)** 이 진행 문서에 고치지 않은 약점의 세부·기계 사정이 적혀 있었다 → 리드가 이 문서에서 줄임. **단, 앞선 커밋들의 이 문서 판본에는 남아 있다(히스토리)** → 「정해야 할 것」 3.
- Minor 12: M1 IP 한도와 계정 한도가 같은 값(같은 IP 의 신규 사용자 진입 지연) · M2 누출 값 스캔이 GET 뿐 · M3 타입 가드가 상위 클래스·sealed 하위 타입을 못 봄 · M4 한도 저장소 키는 아이디의 열쇠 없는 해시 · M5 로그인 username·관리자 검색에 모양 검사 없음(NUL → 500 추측, 기존 이메일 길과 같음) · M6 결제 단정의 접두사가 추측(`/api/v1/payments`) · M7 로컬 yml 의 claim/check 한도가 낮아 손 확인 11번째에 429 · M8~M11 문서 부정확 4건 · M12 MySQL 업그레이드 시험은 SQL 수준만
- 수정 2회차에서 고침: M2·M3·M7·M8~M11. 보고만: M1·M4·M5·M6·M12.

## R2 리뷰 2회차 (확인) 지적
- **I1** 닉네임 대화상자(네이티브 모달 `<dialog>`) 위에서 붙잡힌 403 이 여는 이메일 화면이 그 대화상자 밑에 깔려 눌리지 않는다 — 주소 없는 소셜 계정이 처음 권유받는 행동(홈 띠 「닉네임 정하기」)이 이 경로. 닉네임 저장은 `PATCH /account/me` 가 아니라 작성 프로필 API(스펙상 403 — 서버는 스펙대로). → 닉네임 대화상자를 여는 모든 입구에 이메일 확인을 앞에.
- **I2** 아이디 막에서 「로그아웃」 → 로그아웃 뒤 `GET /account/me` 가 나가 "계속하려면 로그인해 주세요" 토스트. 원인: 이메일 막의 `me` 를 미루는 취소 불가 래퍼(`afterLoginId`). → `EmailRequiredGate` 에 `deferCheck` 류 prop.
- **I3 (추측)** 패키지 `AccountPage` 의 `onDiscarded` 는 `logout` 과 `navigate` 경합이 그대로(견본 앱 두 막만 고쳐짐) → 같은 순서로 + e2e.
- Minor 12: 래퍼 누수 · `/account` 쓰기 때 decline 이 `onLater` 를 안 부름 · I2(1회차) 잔여 · e2e `openBoardReady` 고정 대기 250ms · `fillLoginId` 조건부 건너뛰기 · `emailReauth.e2e.ts` 단언 교체(정당) · README 문구 불일치 · 시험 픽스처 `sumin@kim.dev` · 영어 낱말 "ID" vs "Username" · 관리자 찾기 초안 · 막의 계정별 상태가 전환 때 안 비워짐(추측) · 커밋 트레일러(주인 지시대로 — 해당 없음)
- 닉네임 띠 관찰 판정: 서버는 스펙대로(작성 프로필 쓰기 = 403), 프론트 순서가 문제(I1).

## R2 리뷰 3회차 지적
- **I-A** `/account`(막이 `standAside`)에서 띠의 「닉네임 정하기」를 누르면 `ensure()` 가 막을 요청하고 false 를 돌려주는데 막은 곧바로 `decline()` → 화면도 안내도 없이 아무 일도 안 일어난다.
- Minor: M1 관리자 정지 확인 창은 열기 전 확인이 없는 남은 모달 경로(주소 없는 ADMIN 이라는 좁은 전제) · M2 `ensure()` 는 `when==='unknown'` 이면 통과(막의 `me` 가 오기 전 틈) · M3(추측) 계정이 A→B 로 곧장 바뀌면 이메일 막의 `me` 가 한 번 헛나감 · M4 `openBoardReady` 주석과 약관 없는 구성
- 범위 밖 기존 문제: 약관 재동의 403 이 모달 안에서 나면 같은 "밑에 깔림"이 원래부터 있다(`packages/legal` 은 이 묶음에서 무변경).

## R2 리뷰 1회차 지적 (프론트 패키지)
- **C1** 아이디 막의 「계정 설정」이 react-router `Link` — 막은 라우터 밖이라 열리는 순간 앱이 죽는다 (`LoginIdRequiredScreen.tsx`)
- **I1** 설정에서 아이디를 정해도 막 컨트롤러가 모른다 + `AccountPage` 가 `ctx.loginId` 규칙을 설정에 안 넘긴다 (`routes/pages.tsx`, `createAuthRoutes.tsx`)
- **I2** 쓰기 때 방식에서 `/account` 의 막힌 쓰기가 끝나지 않는다(막이 걷혀 있어 붙잡힌 호출이 멈춤)
- **I3** 아이디를 켠 로그인 화면에서 앞뒤 공백 붙은 이메일이 400 (`type=text` 가 되며 브라우저 trim 이 사라짐)
- **I4** 아이디 막의 다시 묻기가 늦게 온 `me` 답(이전 계정)을 버리지 않는다
- Minor 10: M1 전환 뒤 늦게 온 이전 계정의 403(기존 동작, 남는 위험) · M2 서버 409 뒤에도 "쓸 수 있는" 기억 · M3 인증 단계에서 아이디·닉네임 둘 다 겹치면 막힘 · M4 설정에서 정한 뒤 확인 대화상자가 남음 · M5 ALREADY_SET 뒤 me 실패 시 무언 · M6 로그인 직후 방식+아이디에서 이메일 화면 첫 포커스(F8 에서 isReady 로 풀림 — README 에) · M7 `loginIdServerCode` 공개 export·서버와 다른 순서 · M8 관리자 아이디 찾기가 글자마다 질의 · M9 매직 링크 모드의 라벨 · M10 설정 아이디 줄 좁은 화면 줄바꿈
- 스펙 밖 결정: 전부 수용/조건부 수용 — 조건: `SignInCredentials` 타입 변화·빈 상태 h2·methods 덮어쓴 앱 미지원을 CHANGELOG/README 에 적을 것(F9)

## 정해야 할 것
1. **notification 모듈의 소켓 설정에서 이 묶음과 무관한 점검 거리 하나** (B7 실측 중 발견, 이 묶음 전부터 있던 동작, 손대지 않음) — 별도 작업으로 추적한다. 세부는 지휘 세션 보고에만 있다(공개 레포에 적지 않는다).
2. **아이디 로그인의 한도 버킷** (R1 I2 — 스펙 §7 은 "한 계정의 email·username·accountId 입력이 한 버킷")
   - (가) 아이디 입력은 계정 존재와 무관하게 늘 `loginId:<정규화한 입력>` 버킷 **(추천 — 이대로 구현해 둠)** — 이메일을 아는 사람이 429 시점으로 "그 사람의 아이디"를 맞혀 보는 길(후보 하나에 요청 1개)을 닫는다. 아이디 비공개가 이 묶음의 핵심이라 이쪽을 골랐다. 대가: 한 계정의 비밀번호 추측이 창마다 이메일 몫 + 아이디 몫으로 2배
   - (나) 스펙대로 한 버킷으로 합치고 §18.1 에 위험으로 적는다 — 추측 한도는 그대로, 아이디·가입 사실이 새는 길이 남는다
   - 되돌리기: `LoginThrottle.bucketOf` 한 곳 + 시험 두 곳

3. **이 진행 문서의 옛 판본이 브랜치 히스토리에 남아 있다** (R1 2회차 I-1) — 앞선 커밋들에 실린 판본에는 고치지 않은 점검 거리의 세부와 작업 기계 사정이 적혀 있다. 최신 판본에서는 줄였지만 push 하면 히스토리로 공개된다
   - (가) push·병합 전에 지휘 세션이 브랜치를 squash 병합하거나 이 파일의 히스토리를 정리한다 **(추천)** — 공개 레포에 옛 판본이 나가지 않는다. 리드는 리베이스·병합 금지라 손대지 않았다
   - (나) 그대로 push 한다 — 가장 간단. 옛 판본이 커밋 히스토리로 읽힌다
   - (다) 진행 문서를 레포에서 빼고(히스토리 정리 포함) 스펙만 남긴다

4. **막 화면이 네이티브 모달 대화상자 밑에 깔리는 구조** (R2 2·3회차) — 이메일 "쓰기 때" 막은 "쓰기를 보내는 모달은 열기 전에 확인" 규칙으로 견본 앱의 입구를 막았지만, 약관 재동의 막은 같은 문제가 이 묶음 전부터 있고(세션 중 새 약관 판 + 모달 안의 쓰기), 이메일 막도 `me` 를 읽기 전의 짧은 틈과 관리자 정지 확인 창이 남는다
   - (가) 별도 작업으로 세 막의 화면을 top layer(모달 `<dialog>`)로 올린다 **(추천)** — 근본 해결. 세 막의 포커스·inert·Esc 규칙과 스토리·e2e 를 함께 다시 써야 해서 이 묶음에 얹기엔 크다
   - (나) 지금처럼 규칙(README) + 입구별 확인으로 둔다 — 새 모달을 만들 때마다 사람이 기억해야 한다
   - (다) 이 묶음에서 이메일 막만 먼저 올린다 — 막 셋의 동작이 서로 달라진다

## 스펙에서 벗어난 결정
- R2 수정 2회차: 닉네임 대화상자·삭제 확인 등 **쓰기를 보내는 모달은 열기 전에 이메일 확인**(`ensure()`)을 한다 — 새 선택 prop `BoardComments.beforeDelete`·`AuthorProfileManager.beforeRemove`, 새 문구 키 `loginIdRequiredDiscardBody`, `EmailRequiredGate` 의 `deferCheck` prop. 막 화면을 top layer 로 올리는 방어선은 하지 않음(세 막의 포커스·inert·Esc 규칙을 다시 써야 함) → README 에 규칙으로.
- F8: 댓글은 쓰기 전 확인으로 막지 않고 403 → 등록 → 재전송 길로 둔다("다시 적지 않아도 올라간다"를 위해; 띠는 둔다). 글쓰기 폼은 주소로 곧장 들어오면 마운트 때 이메일 확인을 한 번 연다. e2e 백엔드는 `globalSetup` 에서 `claim-limit`·`check-per-ip` 를 올린다(같은 IP 에서 계정 수십 개). 버리기 뒤 이동은 `logout().finally(navigate)` 순서로 고침(견본 앱 결함).
- R1 수정: 정하기에 IP 한도 `login-id-claim:ip`(값은 `claim-limit`·`claim-window` 공유 — 한 IP 뒤 사용자들이 시간당 10번을 같이 씀). 30자 초과 입력은 모양과 무관하게 `Size`. 정규화는 ASCII 만 소문자로(켈빈 기호 등 거절). `used_at` 은 UTC 자정으로 자름. 로그인 실패 이벤트 id 는 아이디 입력일 때만 HMAC. availability 응답의 `reason`·`code` 는 없을 때 키 자체가 없다(전에는 null 이 실렸다).
- R2 수정: 새 문구 키 `settingsNeedsEmail`·`adminFindLoginId`. 쓰기 때 방식의 주소 없는 계정 설정에서 비밀번호·연락 이메일·로그인 수단 구역을 안내 카드로 접는다. 관리자 아이디 찾기는 Enter/단추로만 질의(기존 이 묶음 스토리 2개 수정). 링크 전용 로그인 화면만 라벨 "이메일". `loginIdServerCode` 는 공개 export 에서 뺌.
- **R1 I2: 아이디 로그인은 이메일 로그인과 버킷을 합치지 않는다**(스펙 §7·L5 와 다름 — 「정해야 할 것」 2).
- F7: 문구 키 `adminSearchLoginId` 추가. 관리자 표 빈 상태의 제목 수준을 h3 → h2 로 바꿈(a11y 검사 때문 — 기존 동작의 작은 변화). 설정의 제한 모드는 아이디 없는 계정에게 아이디·이메일·삭제 구역만 보인다. 이메일 경고 알림은 `emailRequired === true` 일 때만.
- B7: 읽기 메서드 판정은 대소문자 구분(`get` 은 쓰기로 취급해 막음). `DISCARD_NOT_ALLOWED` 의 기본 에러 문구("Only an unregistered account…")와 `DiscardUnregisteredAccountTest` 파일명은 그대로 둠(응답 본문 문구·무수정 요구).
- F6: "다른 탭에서 등록했나" 판정은 순수 `me.email` 이 아니라 "주소가 없고 방식이 none 이 아님"이면 열어 둠(기존 스토리 무수정 통과 때문). 403 으로 열렸는데 `me` 를 모르면 로그인 직후 모습("나중에 하기" 없음)으로 열고 `me` 를 읽은 뒤 쓰기 모습으로 바뀐다. `useEmailBeforeWrite(controller).ensure()` 는 promise 가 아니라 boolean(등록 뒤 사용자가 다시 누른다 — `useProfileGate` 관례). 실측: 붙잡았다 다시 보내는 호출은 같은 `Idempotency-Key`·본문으로 나간다(시험 추가).
- B6: `claim-limit` 은 **형식을 통과한 시도만** 센다(형식 오류 400 은 안 셈 — 가입·실시간 확인과 같은 자리. 스펙 §3.1 은 "성공·실패 모두"). `extra-exclude-paths` 는 게이트별(이메일 쪽 extra 가 아이디 게이트를 열지 않는다). 예약어는 저장소 호출 전에 TAKEN(아이디가 이미 있는 계정도 예약어를 내면 ALREADY_SET 이 아니라 TAKEN). `EmailRequiredRules.kt` → `GatePathRules.kt` 로 파일 이름 변경(`typealias EmailRequiredRule` 유지).
- F5: 문구 키 `loginIdRequiredSettings`("계정 설정")를 새로 더했다(이메일 게이트에 그 문구가 없었다). "이미 계정이 있나요?"가 보이는 조건은 `!me.email && (me.emailRequired || me.emailRequiredToWrite)`(스펙 §9.6 에서 추론 — 서버 discard 조건과 맞는지 R2·e2e 에서 확인). 확인 대화상자는 위험색이 아닌 primary, 첫 포커스는 "다시 입력".
- B4: **꺼짐일 때 새 공개 경로의 익명 호출은 404 가 아니라 401**(공개 목록에 더하지 않으므로 다른 모르는 경로와 같다 — 기존 `ContactEmailDisabledWebTest` 관례, "꺼지면 지금과 같다"와 맞음). 로그인한 호출은 404. 스펙 §3.1 표·§14 의 "꺼지면 404"와 글자가 다르다 → B11 에서 계약 문서에 이대로 적는다.
- F3: `LoginIdRetry` 의 단추 문구 키가 스펙 §13.2 에 없어 `loginIdRequiredSubmit`("이 아이디로 정하기")을 썼다. 서버 `Pattern` 필드 오류는 보낸 값에 프론트 규칙을 다시 돌려 문구를 고른다.
- B3: 확정 때 갈아 끼운 아이디가 예약어면 `LOGIN_ID_TAKEN` + 시도 되살림(스펙에 없는 경우 — 같은 답이 되게). 갈아 끼우는 아이디의 형식 오류는 코드 확인보다 앞의 400(시도를 쓰지 않음, 닉네임과 같은 자리).

## 리뷰 때 볼 것 (리드 메모)
- R1 수정의 M10(잠금 순서) 시험은 수정 전에도 통과한다(`LockRetry` 가 교착을 가림 — 결과만 지키는 회귀 시험). I2 웹 시나리오도 수정 전 RED 는 단위 쪽에서만 확인.
- B10 걱정: `scripts/test-deploy-contract.sh` 의 sample 구간은 `SKELETON_ENV=prod` 로 띄우는데 아이디를 켠 앱은 `…_ACCOUNT_BLOCKS_SECRET` 이 없으면 기동이 막힌다 → 끝 검사 전에 B11 에서 확인·수정(`test-new-project.sh` 의 `secret_env` 2줄 shape 검사와 얽힘).
- B10: 견본 앱 로컬 시드 아이디 `sample-user` · `sample-admin` · `sample-moderator`. 주소 없는 계정에게 열린 변경 경로 22개(직접 11 + auth/legal 11) — 진행 문서 아닌 `WriteGateCensusTest` 가 정본.
- B8: 로컬 편의 `LogOnlyMailTransport(showLinks=true)` 는 본문 전체를 로그에 찍어 아이디도 찍힌다(코드·링크와 같은 취급, 숨김 기본값에서만 시험으로 고정). 아이디 없는 계정이 로그인한 채 `login-id/forgot` 을 부르면 게이트가 403(허용 목록에 없음 — `password/forgot` 과 같음). R1 에서 판단.
- 형식 문제가 겹칠 때 고르는 순서가 두 쪽이 다를 수 있다: 백엔드 `LoginIds.problemOf` 는 모양(영문 하나 포함) → 길이, 프론트 `loginIdProblem` 은 missing → chars → size → symbols → digitsOnly. 예: `12` 는 백엔드 Pattern / 프론트 size. 화면 문구는 프론트 규칙으로 고르므로 동작 차이는 없을 것으로 보이나 R1·R2 에서 확인.

- F2: 서버가 `available:false` 면 이유가 INVALID 여도 프론트는 `taken` 문구로 보여 준다(프론트 규칙으로는 맞는데 서버가 형식 오류라 한 경우). 모든 확인 실패는 `unknown`. R2 에서 확인.

- B2: 기존 시험 `SignUpConsentTest` 의 `insert` override 시그니처 한 줄을 3-인자로 고쳤다(포트 변경으로 컴파일이 깨져서 — 동작 무변). `insert` 의 `used_at` 은 `account.createdAt`.

- F4: `methods` 를 환경변수로 덮어쓰는(발견을 끈) 앱은 아이디 화면이 켜지지 않고 `/forgot-id` 도 등록되지 않는다 — 스펙 §13 "앱이 환경변수로 따로 맞추지 않는다"와 맞지만 R2·F8 에서 견본 앱이 발견을 쓰는지 확인. `onPasswordSignIn` 인자 타입이 `SignInCredentials` 합집합으로 바뀜(스펙 "화면 콜백의 모양은 깨지 않는다" — R2 에서 확인). `AuthPaths` 에 필수 키 `forgotLoginId`.

## QA 관찰 (실패 아님)
- 글쓰기 단추로 이메일 등록을 마친 뒤 쓰기 폼으로 자동으로 가지 않는다(다시 눌러야 함 — `ensure()` 가 boolean 인 설계, 스펙 §9.5 는 막힌 호출 재전송만 약속. 댓글은 자동으로 이어짐).
- 아이디 게이트의 버리기 안내문이 "이 주소로 이미 가입한 계정이 있다면…" — 아직 이메일을 받기 전이라 어색(스펙 §13.1 이 기존 `DiscardPath` 조각 그대로 쓰라고 한 결과).
- 버리기 단추가 연한 붉은 채움 — 같은 구역에 채운 단추가 둘로 보일 수 있음(위험 동작 표시).
- 틀린 비밀번호 문구가 아이디로 로그인해도 "이메일 또는 비밀번호가 맞지 않아요."
- 아이디 찾기 메일의 로그인 링크는 백엔드 `link-base-url` 설정값(로컬 기본 5173)을 가리킨다.
- 설정 캡처 일부에서 고정 머리글이 제목을 가림(캡처 현상일 가능성, 미확정). 오류 알림의 스크린리더용 접두 "Error" 가 한국어 화면에서도 영어(미확인).

## 범위 밖에서 본 것
- (R2 수정 3회차 전수) 이 묶음 전부터 있던 패키지·앱 시험 중 `node:` 내장 모듈을 import 하는 것들: `apps/sample` 의 `useAuthorPicker.test.tsx` · `*.affordance.test.ts` 3개, `packages/` 의 `author-profile/noticeLayout` · `board/formLayout` · `notifications/NotificationBell.affordance` · `theme/ThemeToggle.affordance` · `theme/theme.test` · `tokens/*` · `ui/Button/affordance` · `ui/RowMenu`. 찍어내기 `--full` 은 통과하므로 지금은 문제가 드러나지 않지만(그 조합에 node 타입이 있거나 그 패키지가 빠짐) 같은 종류의 함정 — 손대지 않음.
- (R2 3회차) 약관 재동의 막(`packages/legal` `ReconsentGate`)도 이메일 막과 같은 구조라, 세션 중 새 약관 판이 나와 모달 대화상자 안의 쓰기가 403 `LEGAL.RECONSENT_REQUIRED` 를 맞으면 재동의 화면이 그 모달 밑에 깔린다 — 이 묶음 전부터 있던 문제, 손대지 않음(「정해야 할 것」 4).
- (F8 관찰) 이메일 없는 계정이 닉네임 안내 띠의 「닉네임 정하기」를 저장하면 그 PATCH 가 `EMAIL_REQUIRED` 로 붙잡혀 이메일 화면이 뜬다(등록하면 이어짐). **확인 필요**: 백엔드는 쓰기 때 방식에서 `PATCH /account/me` 를 열어 뒀다 — 닉네임 저장이 다른 경로(작성 프로필 등)를 타는지 R2 2회차에서 본다.
- (B11) 문서 곳곳의 "메일 13종" 숫자가 실제 `MailKind` 개수와 이미 안 맞는다(이번에 고치지 않음). `DiscardUnregisteredAccountTest` 클래스 주석은 아직 "등록 미완료 계정".
- (B11 실측) 배포 계약: `deploy/app.yaml` 의 `secrets:` 에는 `SKELETON_ACCOUNT_BLOCKS_SECRET` 이 이미 있었고(스펙 §5.3 맞음) 시험 스크립트만 그 값을 안 넘겨 sample D 구간 1건이 실제로 실패했다 → 스크립트 수정으로 통과.
- (B7 실측) **WebSocket**: MVC 인터셉터는 소켓 핸드셰이크에 경로와 무관하게 걸리지 않는다(`/api/` 아래로 옮겨도) → 문서에 "경로와 무관하게 이 게이트 밖"으로 적음(B11). 같은 실측에서 본 소켓 설정의 점검 거리는 별도 작업으로 추적(「정해야 할 것」 1).
- (B7 실측) 상태를 바꾸는 GET 은 게시글 상세의 조회수 증가 하나(스펙이 쓰기로 보지 않기로 한 것). `GET /sessions`·`/me` 계열·`legal /me` 는 컨트롤러만 읽고 서비스 안쪽은 따라가지 않음.
- (F3 실측, 코드 읽기만 — 실제 캡차로는 확인 못 함) 가입 화면은 제출이 실패해도 캡차 토큰을 비우지 않고 캡차 자리(`renderCaptcha({ onToken })`)에 위젯을 되돌리는 길이 없다. 캡차를 켠 앱에서는 비밀번호 정책 위반·이메일 중복·아이디 409 뒤 재시도가 쓴 토큰을 다시 보낸다(이 묶음 전부터 있던 동작). 고치려면 캡차 자리 계약에 `resetKey` 같은 것이 필요 — 이 묶음에서는 고치지 않았다.

## 결제 묶음과 만날 때
- 새 V 파일: `modules/account-jdbc/.../db/migration/{postgresql,mysql}/V20261008173435__account_login_id.sql` (UTC 시각). `migrations.lock` 에 두 줄 추가 → 결제 묶음도 같은 파일 끝에 줄을 더하면 **텍스트 충돌**이 난다(양쪽 줄을 다 살리고 `perl scripts/migrations-lock.pl` 로 다시 만든다).
- `migrations-lock.pl` 은 새 파일의 버전이 같은 방언의 **잠긴 최고 버전보다 낮으면 거절**한다(모듈 무관, 같으면 통과). 먼저 main 에 들어간 쪽의 버전보다 나중 쪽 V 가 낮으면 나중 쪽이 `newMigration` 으로 다시 지어야 한다.
- `capabilities.json`(kotlin): 모듈 목록·id·basePaths 는 안 바뀜. `account` summary/keywords, `ACCOUNT_BLOCKS_SECRET` 의 `whenMissing`, `app-sample` summary 만 바뀜 → 결제 묶음이 같은 파일에 새 모듈 항목을 더하면 텍스트 충돌 가능(생성물 `docs/capabilities.md`·`llms.txt` 는 병합 뒤 `perl scripts/build-capabilities.pl` 로 다시). 프론트의 대조 시험(`tests/capabilities.skeleton.test.ts`)은 형제 레포 **main 체크아웃**의 모듈 이름·basePaths·newProject 플래그·문서 경로를 보고 summary/keywords 글자는 안 본다 → 이번 묶음은 **병합 순서 제약 없음**(이 브랜치의 백엔드 capabilities 로 같은 함수를 돌려도 결과 `[]`).
- 배포 계약: `scripts/test-deploy-contract.sh` 의 `base_d_env` 에 `SKELETON_ACCOUNT_BLOCKS_SECRET` 추가 + 단계 `d5` — 결제 묶음이 같은 스크립트를 고쳤으면 충돌 가능.
- `CHANGELOG.md` `[Unreleased]`, `docs/accounts.md`, `docs/errors.md`, `apps/sample/.../application.yml` 도 양쪽이 고쳤을 가능성이 높다.

## 끝 검사
- **백엔드 (깨끗한 복사본, HEAD `2d79a05`)**: `build=0`(`BUILD SUCCESSFUL in 9m 9s`, 346 tasks, FAILED 0줄) · `newproject=0`(`✓ all passed (--full)`) · `deploy=0`(`✓ all passed (deploy contract: api,sample)`). 직전 묶음의 `account-jdbc:postgresTest` 클래스 초기화 오류는 다시 보이지 않음.
- **e2e 전체** (프론트 `fd74220` 작업 트리 + 백엔드 `2d79a05` 복사본, 별도 포트·compose, 파일 직렬 — R2 수정 2회차 developer 가 실행): `Test Files  10 passed (10)` / `Tests  132 passed | 1 skipped (133)`. 건너뛴 1건은 환경변수가 있어야 도는 약관 재동의 필터 시험(이 묶음 전부터).
- **프론트 (깨끗한 복사본, HEAD `fd74220`) — 1차: 실패 2단계.** `install=0 lint=1 typecheck=0 format=0 capabilities=0 test=0 stories=0(98 파일 839) build=0 newproject=1(--full 10개 실패)`. 원인 둘(둘 다 R2 수정 1회차 `9e52432` 에서 들어옴, 작업 중 범위 시험으로는 안 드러남):
  1. `packages/auth/src/patterns/AccountSettings.stories.tsx:1281` — eslint `react-hooks/immutability` 오류("Cannot reassign variable in async function") → 루트 lint 와 찍힌 프로젝트 9개 조합의 lint 실패.
  2. `packages/auth/src/screens/loginIdRequired.noRouter.test.tsx:1` 가 `node:fs` 를 import — `--without-storybook` SSR 조합(4-ssr-without-storybook)의 찍힌 프로젝트에는 node 타입이 없어 `pnpm typecheck` 실패(TS2591).
  → R2 수정 3회차 `2366d46` 에서 고침.
- **e2e 전체 (최종 커밋: 프론트 `2366d46` 작업 트리 + 백엔드 `2d79a05` 복사본)**: `Test Files  10 passed (10)` / `Tests  133 passed | 1 skipped (134)` (로그 끝에 `Timeout terminating forks worker` 한 줄, exit 0).
- **프론트 (깨끗한 복사본, HEAD `2366d46`) — 2차: 전부 통과.** `install=0 lint=0 typecheck=0 format=0 capabilities=0 test=0 stories=0 build=0 newproject=0` (`pnpm test` 루트 묶음 `Tests 1417 passed | 3 skipped`, 스토리 통과, `test-new-project.sh --full` → `all passed (--full)`; lint 는 이 묶음 전부터 있던 경고 1건만).
