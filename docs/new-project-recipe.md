# 새 프로젝트 레시피 (백엔드) — 제품 한 문단에서 돌아가는 서버까지

에이전트가 「X, Y, Z 가 필요한 새 프로젝트를 세팅해」를 받았을 때 **그대로 따라 하는** 순서다. 먼저 이미 있는 것을 찾고(만들지 않는다), `scripts/new-project.sh` 를 한 번 돌리고, 설정 · 비밀을 채우고, 돌려 보고, 배포 선언을 채운다.
프런트는 형제 레포 `react-skeleton` 이 같은 모양의 레시피를 가진다 — [react-skeleton/docs/new-project-recipe.md](../../react-skeleton/docs/new-project-recipe.md)(화면마다 복사할 Pattern · 프런트 환경변수). 두 레시피의 작업 예는 같은 제품 3개이고 서로의 명령을 담고 있다.
명령 조각은 `capabilities.json` 에서 계산한 것이고, 아래 작업 예의 명령은 `CapabilitiesCatalogTest` 가 카탈로그와 비교하고 `bash scripts/test-new-project.sh --full` 이 실제로 찍어 `./gradlew build` 한다 — 이 문서가 낡으면 빌드가 실패한다.

## 0. 놓는 곳

```
~/work/<이름>/
├── api/   # kotlin-skeleton 에서 찍은 백엔드(이 레포)
└── web/   # react-skeleton 에서 찍은 프런트
```

두 스켈레톤 레포는 형제 폴더로 이미 있다. 명령은 각 스켈레톤 레포 루트에서 돌린다. `scripts/dev.sh` 는 옆의 `../web` 을 찾아 같이 띄운다.

## 1. 제품 한 문단 → 필요한 것

1. 제품 설명에서 기능 말(로그인 · 소셜 · 게시판 · 댓글 · 결제 · 알림 · 파일 · 메일 · 경보 · 작업 큐 · 캐시 · 락 · 다국어 · 랜딩 …)을 뽑는다.
2. `llms.txt` 의 「필요한 것 → 고를 것」 결정표에서 그 말을 찾는다. 없으면 `docs/capabilities.md` 의 「전체 목록」 키워드 열(한국어 / 영어)을 훑거나 `capabilities.json` 의 `keywords` 를 검색한다.
3. 고른 항목의 `id`(= 모듈 이름)를 모은다. 결정표의 조각이 이미 짝(예: `board` → `board-jdbc`)을 포함한다. `자동으로 따라온다` 는 적지 않는다.
4. 항목의 `notFor` 를 읽는다 — 이 제품에 안 맞으면 다른 항목이다. `status: experimental`(결제 · Kafka · SSM)은 실제 외부 서비스에 붙여 확인한 증거가 없으니 쓰기 전에 직접 확인한다.
5. **카탈로그에 없는 것은 만들기 전에** `docs/modules/README.md` 와 `docs/minimal-composition.md` 에 있는지 본다. 그래도 없을 때만 새로 만든다 — 모듈 규칙은 CLAUDE.md.

## 2. 명령 만들기

```
scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> [--modules a,b,c] [--db postgresql|mysql] [--with-workbench] [--with-sample] [--dry-run]
```

- 결정표의 조각을 합친다: `--modules` 는 하나로(쉼표) 합치고 `--db mysql` · `--with-sample` 은 덧붙인다. 스타터(`platform` · `auth` · `persistence-jdbc` · `db-postgresql` · `migration-flyway` · `time`)에 이미 있는 모듈은 적지 않는다.
- 소셜 제공자(`auth-social-google` | `-kakao` | `-naver`)와 실시간 전달(`notification-sse` | `notification-websocket`)은 하나 이상 고른다 — 아래 예는 첫 번째를 쓴다.
- **먼저 `--dry-run` 을 붙여** 고른 모듈과 따라온 이유를 본다(아무것도 만들지 않는다). 결과가 기대와 같으면 `--dry-run` 을 뗀다.
- `<config-prefix>` 는 설정 접두사 · 환경변수 접두사(`my-app` → `MY_APP_…`) · 배포 이름의 어근이다 — 배포 이름은 2~20자.
- `--with-sample`/`--with-workbench` 는 PostgreSQL 전용이라 `--db mysql` 과 함께 못 쓴다.

## 3. 채울 설정 · 비밀

모듈은 켜자마자 기본값으로 뜬다. 값이 필요한 것만 채운다 — 찍은 프로젝트의 `apps/api/src/main/resources/application.yml` 끝에 고른 모듈의 설정 블록이 **주석으로** 붙어 있다(바꿀 키만 풀어 합친다). 키 전체와 기본값은 `docs/config/modules/<모듈>.yml`.
모든 키는 환경변수가 된다(`<접두사>.board.seed-boards` → `<PREFIX>_BOARD_SEED_BOARDS`). 서드파티가 주는 비밀은 yml 이 아니라 환경변수로만 넣는다.

| 모듈 | 채울 것 (환경변수의 `<P>` = 설정 접두사의 대문자) | 비어 있으면 |
|---|---|---|
| `auth` | `JWT_SECRET`(32바이트 이상 · 배포 선언의 `secrets:` 가 무작위로 만들어 넣는다 — 이름이 빠지면 가드가 `JWT_SECRET` 을 말하며 기동 실패) | 보호 환경(`<P>_ENV=stage\|prod` · 프로필 `prod\|staging`)에서 **기동 실패** |
| `account`(스타터) | `<P>_ACCOUNT_MAIL_LINK_BASE_URL`(프론트 주소) · 메일 발송 길(`--modules notification-mail` + `<P>_NOTIFICATION_MAIL_ENABLED=true` · `_FROM` · `SPRING_MAIL_HOST`) · 선택 `<P>_ACCOUNT_BOOTSTRAP_ADMIN_EMAIL` · 클라이언트 IP 모드(`<P>_WEB_CLIENT_IP_MODE` · `_TRUSTED_PROXIES` — 홈서버 플랫폼이 넣는다) | 보호 환경에서 **기동 실패**(DeployGuard 가 이름을 나열한다) |
| `auth-social-*` | `<P>_AUTH_SOCIAL_PROVIDERS_<X>_ENABLED=true` · `_CLIENT_ID` · `_CLIENT_SECRET` · `redirect-uri` | 켰는데 비면 기동 실패 |
| `board` | `board.seed-boards`(게시판 코드) · `board.reaction.types`(반응 종류) | 게시판이 비어 있다 |
| `payment-toss` / `-stripe` | `<P>_PAYMENT_<X>_ENABLED=true` · `_SECRET_KEY` | 제공자 빈이 없다 — 결제 라우팅 실패 |
| `captcha-turnstile` | `<P>_CAPTCHA_TURNSTILE_ENABLED=true` · `_SECRET_KEY` | 검증기 빈이 없다 |
| `storage-s3` | `<P>_STORAGE_S3_BUCKET` 외 엔드포인트 · 키 | 저장소 빈이 없다 · 키가 비면 첫 사용에서 실패 |
| `notification-mail` | `<P>_NOTIFICATION_MAIL_ENABLED=true` · `_FROM` · `SPRING_MAIL_HOST` · `_USERNAME` · `_PASSWORD` | 발송기 빈이 없다 |
| `alert` | `<P>_ALERT_WEBHOOK_URL` | 경보가 로그로만 남는다 |

고른 모듈마다의 전체 목록은 `capabilities.json` 의 `secrets`(= `docs/deploy.md` §7)와 `.env.example` 의 `[모듈]` 구역.

## 4. 찍은 뒤 처음 한 번

```bash
cd ~/work/<이름>/api
git init && git add -A && git commit -m "Initial commit (from the skeleton)"
./gradlew build            # Docker 가 필요하다(Testcontainers). 모듈 테스트 · 문서 가드 · 카탈로그 가드가 함께 돈다
```

`CLAUDE.md` · `llms.txt` · `capabilities.json` 이 이미 찍은 프로젝트에 맞게 걸러져 있다 — 새 기능을 만들기 전에 그것부터 읽는다. 스켈레톤에 있던 다른 모듈이 필요해지면 그 파일의 「이 프로젝트에 없는 것」을 본다.

## 5. 실행 — `scripts/dev.sh`

```bash
cd ~/work/<이름>/api && scripts/dev.sh      # DB(+ 로컬 S3) 컨테이너 → 백엔드 :8080 → 옆의 ../web 이 있으면 pnpm dev(:5173)
scripts/dev.sh down                          # 컨테이너를 내린다(데이터 볼륨은 남는다)
```

포트가 겹치면 `SERVER_PORT` · `DB_PORT` · `S3_PORT`. 프런트 없이 백엔드만이면 `WEB_DIR= scripts/dev.sh`. 로컬 시드 사용자는 `user@example.com` / `password` (로컬 프로필 전용 — 계정은 스타터의 `account` 라 가입 · 이메일 확인 · 재설정이 이미 돈다).

## 6. 검증

```bash
curl -s localhost:8080/api/v1/hello                                                   # 스타터의 HelloController
curl -s localhost:8080/actuator/health                                                # {"status":"UP"}
curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password"}'                             # accessToken 이 온다
./gradlew build                                                                       # 전체 시험(Docker 필요)
perl scripts/build-capabilities.pl --check                                            # 카탈로그가 모듈 · 문서와 맞는가
```

모듈을 더하거나 지웠으면 `./gradlew build` 의 `Capabilities*Test` 가 실패하며 항목에서 붙이거나 지울 객체를 그대로 보여 준다 — 붙이고 `perl scripts/build-capabilities.pl` 를 한 번 돌린다.

## 7. 배포 — `deploy/app.yaml`

컨테이너 · DB · Redis · Caddy · 터널은 홈서버 플랫폼이 선언 한 장에서 만든다(운영용 compose · Caddyfile 을 이 프로젝트에 두지 않는다). `new-project.sh` 가 `name` · `env_prefix` · `db` · `redis`(redis 모듈을 골랐으면 true)를 채웠다. 직접 채울 것:

1. `image: ghcr.io/OWNER/<이름>-api` 의 `OWNER`(소문자 계정 · 조직).
2. `secrets:` 는 플랫폼이 **무작위로 만들어 넣는** 비밀(JWT)만 — 서드파티 키(결제 · SMTP · R2 · OAuth)의 값을 넣는 길은 플랫폼과 정할 일이다(`docs/deploy.md` §7 · §9).
3. `git tag v0.1.0 && git push origin v0.1.0` 이 이미지를 올리고, 선언의 `tag` 를 올리는 것이 배포다.
4. SSR 프런트는 이 선언으로 안 된다(`web:` 은 정적 파일 서빙뿐) — `docs/deploy.md` §8.

## 작업 예

### 예 1. 커뮤니티 사이트 — 소셜 로그인 · 게시판(댓글 · 공감) · 알림 · 다국어 · 랜딩

> 관심사가 같은 사람들이 모이는 커뮤니티. 비로그인 방문자는 랜딩을 보고, 구글 · 카카오로 가입해 글을 쓰고 댓글 · 공감을 주고받으며, 내 글에 댓글이 달리면 알림이 바로 뜬다. 화면은 한국어 · 영어.

고르기(백엔드): `auth` · `auth-social`(둘 다 스타터 — 고를 것은 제공자) `auth-social-google`(카카오가 필요하면 `auth-social-kakao` 도) · `board` + `board-jdbc` · `notification` + `notification-jdbc` + `notification-sse`. 다국어 · 랜딩 · 약관 · 쿠키 동의는 프런트만의 일이다(백엔드 모듈 없음 — react 레시피).

<!-- kotlin-stamp: community -->

```bash
scripts/new-project.sh ~/work/community/api dev.example.community community Community --modules auth-social-google,board,board-jdbc,notification,notification-jdbc,notification-sse
```

그다음:

```bash
cd ~/work/community/api && ./gradlew build
# application.yml 에 게시판 · 소셜 설정을 풀어 채운다: community.board.seed-boards, community.auth-social.providers.google.{enabled,client-id,redirect-uri}
# 비밀은 환경변수로: COMMUNITY_AUTH_SOCIAL_PROVIDERS_GOOGLE_CLIENT_SECRET
scripts/dev.sh
```

프런트: `scripts/new-project.sh ~/work/community/web community --packages board,notifications,realtime,i18n,marketing,seo --with-sample`(react-skeleton 에서 — 그쪽 레시피 예 1).

손으로 써야 하는 것: 소셜 가입 · 병합 정책은 앱 yml 의 선택이다(`community.account.social.sign-up` · `merge-on-verified-email` — 스타터의 `account` 가 소셜을 계정으로 이어 주고, 병합은 제공자가 확인한 같은 이메일일 때만), 운영에서는 메일 발송 길(`notification-mail`)과 `COMMUNITY_ACCOUNT_MAIL_LINK_BASE_URL`, 게시판 코드 · 반응 종류(`community.board.seed-boards` · `reaction.types`), 운영자 계정에 `MODERATOR` 역할, 알림을 만드는 코드(`NotificationPublisher.publish` — 댓글 알림은 `board` 가 `notification` 이 있으면 기본으로 보낸다).

### 예 2. 유료 SaaS 대시보드 — 로그인 · 대시보드/목록/설정 · 요금제 · 결제 · 알림

> 월 구독으로 쓰는 업무 도구. 로그인 후 대시보드 · 목록 · 상세 · 설정 화면이 있고, 요금제 페이지에서 플랜을 골라 토스로 결제하며, 가입 폼은 봇을 막고, 결제 · 작업 완료 알림을 받는다.

고르기(백엔드): `auth`(스타터) · `payment` + `payment-toss` · `notification` + `notification-jdbc`; `captcha-turnstile` 은 스타터에 들어 있어 켜기만 한다(SAAS_CAPTCHA_TURNSTILE_*). DB 는 MySQL 로 둔다(`--db mysql`).

<!-- kotlin-stamp: saas -->

```bash
scripts/new-project.sh ~/work/saas/api dev.example.saas saas Saas --modules payment,payment-toss,notification,notification-jdbc --db mysql
```

그다음:

```bash
cd ~/work/saas/api && ./gradlew build
# 환경변수: SAAS_PAYMENT_TOSS_ENABLED=true  SAAS_PAYMENT_TOSS_SECRET_KEY=…  SAAS_CAPTCHA_TURNSTILE_ENABLED=true  SAAS_CAPTCHA_TURNSTILE_SECRET_KEY=…
scripts/dev.sh        # MySQL 컨테이너가 올라온다
```

프런트: `scripts/new-project.sh ~/work/saas/web saas --packages marketing,payment,notifications,captcha-turnstile --scope @acme`(그쪽 레시피 예 2).

손으로 써야 하는 것: **결제 HTTP** — 주문 · 금액 검증 컨트롤러와 성공/실패 리다이렉트 처리, 웹훅 서명 검증(`payment` 모듈은 HTTP 를 열지 않는다 — `PaymentService.confirm` 은 받은 금액을 그대로 넘기므로 서버의 주문 금액과 비교하는 코드가 앱에 있어야 한다. `docs/modules/payment.md`), 가입 · 로그인에서 `TurnstileVerifier` 호출, 알림을 만드는 코드, 구독 · 요금제 도메인(계정 · 가입 · 로그인은 스타터의 `account` — 캡차는 `saas.account.captcha.required=true`, 운영에서는 메일 발송 길).

### 예 3. 콘텐츠 · 랜딩 사이트 (서버 렌더링) — 검색에 노출되는 소개 · 요금 · 약관

> 제품 소개 · 요금제 · 약관을 보여 주는 공개 사이트. 검색 결과와 링크 미리보기가 중요해 서버가 첫 HTML 을 그리고, 한국어 · 영어, 쿠키 동의 배너, 404 화면이 있다. 로그인 · 데이터 입력은 없다.

고르기(백엔드): 스타터 그대로 — 모듈을 더하지 않는다(화면 · SSR 은 프런트). 정말 API 가 필요 없으면 스타터의 `HelloController` 만 남는다.

<!-- kotlin-stamp: content-ssr -->

```bash
scripts/new-project.sh ~/work/site/api dev.example.site site Site
```

그다음:

```bash
cd ~/work/site/api && ./gradlew build && scripts/dev.sh
```

프런트: `scripts/new-project.sh ~/work/site/web site --packages marketing,i18n --ssr`(그쪽 레시피 예 3).

손으로 써야 하는 것: 백엔드가 정말 필요하면 그때 모듈을 더한다(결정표). **SSR 프런트는 `deploy/app.yaml` 의 `web:`(정적 서빙)으로 배포되지 않는다** — 자기 이미지 · 포트의 선언이 따로 필요하다(`docs/deploy.md` §8).
