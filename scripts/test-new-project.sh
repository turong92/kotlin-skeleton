#!/usr/bin/env bash
# scripts/new-project.sh 의 테스트.
#
#   scripts/test-new-project.sh            # 빠른 검사 (기본, 수 초~수십 초): 인자 검증 · 모듈 닫힘 · 파일 가지치기 · rename 잔여 검사
#   scripts/test-new-project.sh --quick    # 위와 같다 (./gradlew check 가 부른다)
#   scripts/test-new-project.sh --full     # 위 + 일곱 조합을 임시 디렉토리에 찍어 각각 ./gradlew build (Docker/Testcontainers 필요, 순차, 수 분)
#
# --full 은 CI 의 별도 워크플로(.github/workflows/new-project.yml)가 돈다. 조합:
#   1. 기본값만
#   2. --modules job-queue-jdbc,notification-mail,storage-s3,scheduler
#   3. --db mysql --modules job-queue-jdbc,alert-jdbc   (주인 경보 + 그 DB 기록을 MySQL 로 — compileOnly 연동 모듈이 닫힘에 따라온다)
#   4. --modules persistence-jooq,job-queue-jdbc,notification-jdbc   (jOOQ 코드 생성이 형제 모듈 마이그레이션을 파싱한다)
#   5. --with-sample   (제품 모양 예시 앱 apps/sample 이 남고, 그 앱의 모듈이 닫힘에 더해진다)
#   6. --modules board,board-jdbc   (게시판: board 의 compileOnly 의존 notification · idempotency 가 소스로 따라오고, board-jdbc 의 dbTest 가 두 DB 로 돈다)
#   7. --modules redis-core,redis-lock   (Redis 환경변수 테스트가 프로젝트 접두사로 찍혀 돈다 — 배포 계약의 <ENV_PREFIX>_REDIS_*)
# macOS bash 3.2 와 GNU bash 에서 돈다. 임시 디렉토리는 끝나면 지운다 (KEEP=1 이면 남긴다).
set -euo pipefail

MODE="${1:---quick}"
case "$MODE" in --quick|--full) ;; *) echo "usage: $0 [--quick|--full]" >&2; exit 2 ;; esac

SRC="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$SRC/scripts/new-project.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/new-project-test.XXXXXX")"
trap '[ "${KEEP:-0}" = 1 ] || rm -rf "$TMP"' EXIT

FAILURES=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILURES=$((FAILURES + 1)); }
check() { # check "<설명>" <명령...>  — 명령이 성공하면 통과
  local what="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$what"; else fail "$what"; fi
}
has_line() { grep -Eq -- "$1" "$2"; }
lacks_line() { ! grep -Eq -- "$1" "$2"; }
includes() { { grep -o 'include(":[a-z0-9:-]*")' "$1" || true; } | sed -E 's/include\("(.*)"\)/\1/' | sort | tr '\n' ' ' | sed 's/ $//'; }

expect_exit() { # expect_exit <기대 종료코드> "<설명>" <명령...>
  local want="$1" what="$2"; shift 2
  local out got=0
  out="$("$@" 2>&1)" || got=$?
  if [ "$got" = "$want" ]; then pass "$what (exit $got)"; else fail "$what — exit $got, expected $want: $(echo "$out" | head -3)"; fi
  LAST_OUTPUT="$out"
}

stamp() { # stamp <dir> <옵션...>  — 항상 같은 이름으로 찍는다
  local dir="$1"; shift
  bash "$SCRIPT" "$dir" dev.sumin.ovation ovation Ovation "$@"
}

echo "== 1. 인자 검증"
expect_exit 2 "인자 없이 부르면 사용법과 함께 exit 2" bash "$SCRIPT"
expect_exit 2 "없는 모듈은 exit 2" bash "$SCRIPT" "$TMP/x1" dev.sumin.ovation ovation Ovation --modules no-such-module
echo "$LAST_OUTPUT" | grep -q "job-queue-jdbc" && pass "없는 모듈 오류가 유효한 모듈 목록을 보여 준다" || fail "유효한 모듈 목록이 없다: $LAST_OUTPUT"
[ ! -e "$TMP/x1" ] && pass "검증 실패는 아무것도 만들지 않는다" || fail "검증 실패인데 $TMP/x1 이 생겼다"
mkdir "$TMP/exists"
expect_exit 2 "대상 디렉토리가 이미 있으면 exit 2" bash "$SCRIPT" "$TMP/exists" dev.sumin.ovation ovation Ovation
expect_exit 2 "--db 는 postgresql | mysql" bash "$SCRIPT" "$TMP/x2" dev.sumin.ovation ovation Ovation --db oracle
expect_exit 2 "알 수 없는 옵션은 exit 2" bash "$SCRIPT" "$TMP/x3" dev.sumin.ovation ovation Ovation --nope
expect_exit 2 "--modules 에 방언 모듈을 넣으면 --db 를 쓰라며 exit 2" bash "$SCRIPT" "$TMP/x4" dev.sumin.ovation ovation Ovation --modules db-mysql
expect_exit 2 "--with-workbench 는 PostgreSQL 전용이라 --db mysql 과 같이 못 쓴다" bash "$SCRIPT" "$TMP/x5" dev.sumin.ovation ovation Ovation --with-workbench --db mysql
expect_exit 2 "--with-sample 은 PostgreSQL 전용이라 --db mysql 과 같이 못 쓴다" bash "$SCRIPT" "$TMP/x6" dev.sumin.ovation ovation Ovation --with-sample --db mysql
expect_exit 2 "대상이 소스 레포 안이면 exit 2" bash "$SCRIPT" "$SRC/stamped-inside" dev.sumin.ovation ovation Ovation
[ ! -e "$SRC/stamped-inside" ] && pass "레포 안에는 아무것도 만들지 않는다" || { fail "레포 안에 만들었다"; rm -rf "$SRC/stamped-inside"; }
# 레포 안에서 `../내-프로젝트` 로 부르는 것이 가장 흔한 첫 시도다 — 경로를 정리하지 않으면 "레포 안" 으로 오인된다
REL="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$TMP/relative-target" "$SRC")"
expect_exit 0 "레포 안에서 ../ 로 레포 밖(\$REL)을 가리키면 찍힌다" bash -c "cd '$SRC' && bash scripts/new-project.sh '$REL' dev.sumin.ovation ovation Ovation"
check "상대 경로 대상이 실제 레포 밖에 만들어졌다" test -f "$TMP/relative-target/settings.gradle.kts"

echo "== 2. 기본값만"
MARKER="$TMP/marker"; touch "$MARKER"; sleep 1
A="$TMP/a"
expect_exit 0 "기본 조합을 찍는다 (rename 잔여 검사 포함)" stamp "$A"
check "스타터 앱만 남는다 (apps/workbench 없음)" test -d "$A/apps/api" -a ! -e "$A/apps/workbench"
check "build · .gradle · .git · .claude · .superpowers 는 복사하지 않는다" bash -c "! ls -d '$A/build' '$A/.gradle' '$A/.git' '$A/.claude' '$A/.superpowers' '$A/.kotlin' 2>/dev/null | grep -q ."
want=":apps:api :modules:auth :modules:db-postgresql :modules:migration :modules:migration-flyway :modules:persistence-jdbc :modules:platform :modules:time"
got="$(includes "$A/settings.gradle.kts")"
[ "$got" = "$want" ] && pass "settings.gradle.kts 는 스타터 모듈과 그 닫힘만 포함한다" || fail "settings includes: [$got] expected [$want]"
check "샘플 앱은 기본으로 빠진다 (apps/sample · 그 문서 · 그 실행 스크립트)" bash -c "test ! -e '$A/apps/sample' && test ! -e '$A/docs/sample.md' && test ! -e '$A/scripts/dev-sample.sh' && test ! -e '$A/scripts/sample-e2e-backend.sh'"
check "안내 문서(CLAUDE.md · README.md)에 샘플 구역과 표식이 남지 않는다" bash -c "! grep -q 'apps/sample\|sample:start\|sample:end' '$A/CLAUDE.md' '$A/README.md'"
check "샘플 앱만 쓰는 모듈은 기본 조합에 없다 (idempotency · notification-sse · storage-s3)" bash -c "! grep -q 'idempotency\|notification-sse\|storage-s3' '$A/settings.gradle.kts'"
check "선택되지 않은 모듈 디렉토리가 없다 (redis-core)" test ! -e "$A/modules/redis-core"
check "선택되지 않은 모듈의 설정 블록도 없다" test ! -e "$A/docs/config/modules/redis-core.yml"
check "선택된 모듈의 설정 블록은 남는다 (time)" test -f "$A/docs/config/modules/time.yml"
check "선택되지 않은 모듈의 문서 쪽(docs/modules)도 없다 (redis-core)" test ! -e "$A/docs/modules/redis-core.md"
check "선택된 모듈의 문서 쪽은 남는다 (time)" test -f "$A/docs/modules/time.md"
check "모듈 색인에서 지운 모듈의 행이 빠지고 남은 모듈의 행은 남는다" bash -c "! grep -q '](redis-core.md)' '$A/docs/modules/README.md' && ! grep -q '](async.md)' '$A/docs/modules/README.md' && grep -q '](time.md)' '$A/docs/modules/README.md' && grep -q '](auth.md)' '$A/docs/modules/README.md'"
check "dbTestModules 가 비어 있다" has_line 'val dbTestModules = emptySet<String>\(\)' "$A/build.gradle.kts"
check "새 프로젝트에는 new-project 도구가 따라오지 않는다" bash -c "! ls '$A/scripts/new-project.sh' '$A/scripts/test-new-project.sh' '$A/.github/workflows/new-project.yml' 2>/dev/null | grep -q ."
check "rootProject.name 이 바뀐다" has_line 'rootProject.name = "ovation"' "$A/settings.gradle.kts"
check "패키지가 바뀐다" test -f "$A/apps/api/src/main/kotlin/dev/sumin/ovation/app/api/ApiApplication.kt"
check "starter 테스트의 부재 단언은 그대로 (모듈을 더하지 않았다)" has_line 'software.amazon.awssdk.services.s3.S3Client' "$A/apps/api/src/test/kotlin/dev/sumin/ovation/app/api/StarterCompositionIntegrationTest.kt"
check "application.yml 에 모듈 블록 구역이 없다 (--modules 를 안 줬다)" lacks_line 'new-project: module config blocks' "$A/apps/api/src/main/resources/application.yml"
check "로컬 한 줄 실행 스크립트가 따라온다 (scripts/dev.sh — 문법 검사 통과)" bash -c "test -x '$A/scripts/dev.sh' && bash -n '$A/scripts/dev.sh'"
check "compose 에 로컬 S3(profile s3)와 버킷 준비 서비스가 있다 — 프론트 업로드를 시험하는 데 필요하다" bash -c "grep -q '^  s3:' '$A/docker-compose.yml' && grep -q '^  s3-init:' '$A/docker-compose.yml' && grep -q 'profiles: \\[s3\\]' '$A/docker-compose.yml'"
check "compose 프로젝트 이름이 새 이름이다 — 폴더 이름(api)을 쓰면 다른 프로젝트의 컨테이너 · 볼륨과 섞인다" has_line '^name: ovation$' "$A/docker-compose.yml"
check "storage-s3 를 고르지 않으면 application-local.yml 에 S3 설정이 붙지 않는다" lacks_line 'storage-s3' "$A/apps/api/src/main/resources/application-local.yml"
check "배포 선언이 따라오고 name · image · env_prefix 가 새 접두사다 (deploy/app.yaml)" bash -c "grep -Eq '^name: ovation( |\$)' '$A/deploy/app.yaml' && grep -q '^image: ghcr.io/OWNER/ovation-api' '$A/deploy/app.yaml' && grep -q '^env_prefix: OVATION' '$A/deploy/app.yaml'"
check "배포 선언의 DB · Redis 는 고른 모듈을 따른다 (postgres · redis false)" bash -c "grep -q '^db: postgres' '$A/deploy/app.yaml' && grep -q '^redis: false' '$A/deploy/app.yaml'"
check "배포 선언에 skeleton · SKELETON 흔적이 없다 (스위치 이름은 OVATION_ENV)" bash -c "! grep -i 'skeleton' '$A/deploy/app.yaml' | grep -v 'react-skeleton' | grep -q . && grep -q 'OVATION_ENV: prod' '$A/deploy/app.yaml'"
check "배포 선언에서 고르지 않은 모듈의 비밀 설명이 지워진다 (storage-s3 · payment-toss), 쓰는 모듈(auth)은 남는다" bash -c "! grep -q '\[storage-s3\]\|\[payment-toss\]\|\[redis-core\]' '$A/deploy/app.yaml' && grep -q '\[auth\]' '$A/deploy/app.yaml'"
check "배포 선언은 compose · Caddyfile · cloudflared · 백업을 만들지 않는다 (플랫폼 몫)" bash -c "test ! -e '$A/deploy/docker-compose.yml' && test ! -e '$A/deploy/Caddyfile' && test ! -e '$A/deploy/cloudflared' && ls '$A/deploy' | grep -qx 'app.yaml' && [ \"\$(ls '$A/deploy' | wc -l | tr -d ' ')\" = 1 ]"
check "이미지 워크플로가 따라오고 v* 태그 푸시만 트리거한다 (브랜치 푸시 없음 · latest 없음)" bash -c "grep -q \"tags: \\['v\\*'\\]\" '$A/.github/workflows/image.yml' && ! grep -q 'branches' '$A/.github/workflows/image.yml' && ! grep -q ':latest' '$A/.github/workflows/image.yml'"
check "이미지 워크플로의 이름과 선언의 image 가 같은 어근이다 (<prefix>-api)" bash -c "grep -q -- '-api' '$A/.github/workflows/image.yml' && grep -q 'ovation-api' '$A/deploy/app.yaml'"
check "계약 테스트 스크립트와 그 CI 가 따라온다 (문법 검사 통과)" bash -c "test -x '$A/scripts/test-deploy-contract.sh' && bash -n '$A/scripts/test-deploy-contract.sh' && test -f '$A/.github/workflows/deploy-contract.yml'"
check "Dockerfile 은 APP 인자를 받는다 (api 가 기본)" has_line '^ARG APP=api' "$A/Dockerfile"
check "배포 가드 · Redis 환경변수 테스트가 새 접두사로 찍힌다 — 접두사가 따라간다" bash -c "grep -rq 'skeleton.env\|ovation.env' '$A/modules/platform/src/test' && ! grep -rq 'SKELETON_' '$A/modules/platform/src/test' '$A/modules/auth/src/test'"
check "소스 레포는 건드리지 않는다" bash -c "[ -z \"\$(find '$SRC' -newer '$MARKER' -type f -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.kotlin/*' -not -path '*/.git/*' -not -path '*/node_modules/*' 2>/dev/null | head -1)\" ]"

echo "== 3. --modules job-queue-jdbc,notification-mail,storage-s3,scheduler"
B="$TMP/b"
expect_exit 0 "조합 2 를 찍는다" stamp "$B" --modules job-queue-jdbc,notification-mail,storage-s3,scheduler
echo "$LAST_OUTPUT" | grep -q 'crypto (compile-only for storage-s3' && pass "crypto 는 storage-s3 의 컴파일 전용 의존으로 안내된다 (런타임 전이 없음)" || fail "crypto 가 컴파일 전용이라는 안내가 없다"
for m in job-queue-jdbc notification-mail storage-s3 scheduler storage crypto; do
  check "모듈 $m 이 포함된다 (요청했거나 닫힘으로 따라왔다)" bash -c "grep -q 'include(\":modules:$m\")' '$B/settings.gradle.kts' && test -d '$B/modules/$m'"
done
check "닫히지 않는 모듈은 없다 (redis-core)" bash -c "! grep -q 'redis-core' '$B/settings.gradle.kts'"
for m in job-queue-jdbc notification-mail storage-s3 scheduler; do
  check "apps/api 의존성에 $m 한 줄이 생긴다" has_line "implementation\\(project\\(\":modules:$m\"\\)\\)" "$B/apps/api/build.gradle.kts"
done
check "닫힘으로 따라온 모듈은 앱 의존성에 줄을 더하지 않는다 (crypto)" lacks_line 'modules:crypto' "$B/apps/api/build.gradle.kts"
check "dbTestModules 는 선택된 DB 모듈만 남는다" has_line 'val dbTestModules = setOf\(":modules:job-queue-jdbc"\)' "$B/build.gradle.kts"
check "application.yml 에 표시된 모듈 블록 구역이 생긴다" has_line 'new-project: module config blocks' "$B/apps/api/src/main/resources/application.yml"
check "블록은 주석이고 접두사가 새 이름이다" has_line '^# ovation:$' "$B/apps/api/src/main/resources/application.yml"
check "storage-s3 블록이 들어 있다" has_line '^#     bucket: ""' "$B/apps/api/src/main/resources/application.yml"
check "블록을 붙인 yml 이 여전히 유효하다 (주석뿐이라 키가 늘지 않는다)" bash -c "! grep -E '^[a-z]' '$B/apps/api/src/main/resources/application.yml' | sort | uniq -d | grep -q ."
check "starter 테스트의 부재 단언에서 S3 · 메일이 빠진다" bash -c "! grep -q 'awssdk.services.s3.S3Client\|jakarta.mail.Session' '$B/apps/api/src/test/kotlin/dev/sumin/ovation/app/api/StarterCompositionIntegrationTest.kt'"
check "starter 테스트의 부재 단언에서 나머지(Redis · Kafka · JPA)는 남는다" bash -c "grep -q 'RedisTemplate' '$B/apps/api/src/test/kotlin/dev/sumin/ovation/app/api/StarterCompositionIntegrationTest.kt'"
check "storage-s3 를 고르면 application-local.yml 에 로컬 S3 설정(버킷 · 엔드포인트 · 키)이 새 접두사로 붙는다" bash -c "grep -q '^ovation:' '$B/apps/api/src/main/resources/application-local.yml' && grep -q 'endpoint-override: http://localhost:8333' '$B/apps/api/src/main/resources/application-local.yml' && grep -q 'bucket: app' '$B/apps/api/src/main/resources/application-local.yml'"
check "붙은 application-local.yml 에 skeleton 흔적이 없다" bash -c "! grep -qi 'skeleton' '$B/apps/api/src/main/resources/application-local.yml'"
check "dev.sh 는 S3 를 쓰는 조합에서 s3 서비스를 같이 올린다" has_line 'storage-s3' "$B/scripts/dev.sh"
check "dev.sh 는 앱이 쓰는 모듈로 인프라를 정한다 — storage-s3 를 고른 조합은 postgres + s3" bash -c "[ \"\$(DEV_DRY_RUN=1 APP=api bash -c 'cd \"$B\" && bash scripts/dev.sh' 2>&1 | tail -1)\" = 'infra: postgres s3=1 app=api' ]"
check "dev.sh 는 기본 조합에서 s3 를 올리지 않는다" bash -c "[ \"\$(DEV_DRY_RUN=1 bash -c 'cd \"$A\" && bash scripts/dev.sh' 2>&1 | tail -1)\" = 'infra: postgres s3=0 app=api' ]"
check "dev.sh 는 소스 레포(두 DB 모듈이 다 있다)에서도 스타터 apps/api 에 postgres 를 고른다 — 폴더 존재로 고르면 mysql 이 된다" bash -c "[ \"\$(DEV_DRY_RUN=1 bash -c 'cd \"$SRC\" && bash scripts/dev.sh' 2>&1 | tail -1)\" = 'infra: postgres s3=0 app=api' ]"
check "dev.sh APP=sample 은 postgres + s3" bash -c "[ \"\$(DEV_DRY_RUN=1 APP=sample bash -c 'cd \"$SRC\" && bash scripts/dev.sh' 2>&1 | tail -1)\" = 'infra: postgres s3=1 app=sample' ]"
check "persistence-jooq 가 없으니 그 모듈이 읽던 형제 폴더 문제도 없다" test ! -e "$B/modules/persistence-jooq"
check "배포 선언에 고른 모듈(storage-s3 · notification-mail)의 비밀 설명이 남고 고르지 않은 모듈(payment-toss)은 없다" bash -c "grep -q '\[storage-s3\] OVATION_STORAGE_S3_BUCKET' '$B/deploy/app.yaml' && grep -q '\[notification-mail\]' '$B/deploy/app.yaml' && ! grep -q '\[payment-toss\]' '$B/deploy/app.yaml'"

echo "== 4. --db mysql --modules job-queue-jdbc,alert-jdbc"
C="$TMP/c"
expect_exit 0 "조합 3 을 찍는다" stamp "$C" --db mysql --modules job-queue-jdbc,alert-jdbc
for m in alert alert-jdbc job-queue-jdbc notification-mail; do
  check "alert-jdbc 를 고르면 $m 이 따라온다 (alert · 컴파일 전용 연동 모듈의 닫힘)" bash -c "grep -q 'include(\":modules:$m\")' '$C/settings.gradle.kts' && test -d '$C/modules/$m'"
done
check "alert-jdbc 의 MySQL 마이그레이션이 남고 PostgreSQL 것은 앱 클래스패스 밖이다" bash -c "ls '$C/modules/alert-jdbc/src/main/resources/db/migration/mysql/' | grep -q skeleton_alerts"
check "dbTestModules 에 alert-jdbc 가 들어간다" has_line 'val dbTestModules = setOf\(":modules:alert-jdbc", ":modules:job-queue-jdbc"\)' "$C/build.gradle.kts"
check "--db mysql 이면 배포 선언의 db 가 mysql 이다" has_line '^db: mysql' "$C/deploy/app.yaml"
check "db-postgresql 모듈이 사라지고 db-mysql 이 들어온다" bash -c "grep -q 'include(\":modules:db-mysql\")' '$C/settings.gradle.kts' && ! grep -q 'db-postgresql' '$C/settings.gradle.kts' && test ! -e '$C/modules/db-postgresql'"
check "dev.sh 는 mysql 조합에서 mysql 컨테이너를 고른다" bash -c "[ \"\$(DEV_DRY_RUN=1 bash -c 'cd \"$C\" && bash scripts/dev.sh' 2>&1 | tail -1)\" = 'infra: mysql s3=0 app=api' ]"
check "apps/api 가 db-mysql 을 쓴다" bash -c "grep -q 'project(\":modules:db-mysql\")' '$C/apps/api/build.gradle.kts' && ! grep -q 'project(\":modules:db-postgresql\")\|testcontainers-postgresql' '$C/apps/api/build.gradle.kts'"
check "apps/api 테스트 컨테이너가 MySQL 이다" has_line 'MySQLContainer' "$C/apps/api/src/test/kotlin/dev/sumin/ovation/app/api/TestcontainersConfiguration.kt"
check "datasource URL 이 MySQL 이다" has_line 'jdbc:mysql://localhost:3306/app' "$C/apps/api/src/main/resources/application.yml"
check "첫 마이그레이션이 mysql 폴더에 있다" test -f "$C/apps/api/src/main/resources/db/migration/mysql/V20261005000000__init.sql"
check "postgresql 마이그레이션 폴더는 앱에서 사라진다" test ! -e "$C/apps/api/src/main/resources/db/migration/postgresql"
check "루트 DB 테스트 묶음은 mysqlTest 만 남는다" bash -c "grep -q '\"mysqlTest\" to' '$C/build.gradle.kts' && ! grep -q '\"postgresTest\" to' '$C/build.gradle.kts'"
check "postgres 전용 테스트 소스는 지워진다" test ! -e "$C/modules/job-queue-jdbc/src/postgresTest"
check "compose 는 mysql 이 기본 서비스, postgres 는 없다" bash -c "grep -q '^  mysql:' '$C/docker-compose.yml' && ! grep -q '^  postgres:' '$C/docker-compose.yml' && ! grep -q 'profiles: \[mysql\]' '$C/docker-compose.yml'"
check ".env.example 의 datasource URL 이 MySQL 이다" has_line '^SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/app' "$C/.env.example"

echo "== 5. 같은 모듈을 두 번 주거나 순서가 달라도 결과가 같다"
D="$TMP/d"
expect_exit 0 "중복 · 공백이 있는 --modules" stamp "$D" --modules "scheduler, scheduler,storage-s3"
check "중복 요청은 한 줄만 더한다" bash -c "[ \"\$(grep -c 'modules:scheduler' '$D/apps/api/build.gradle.kts')\" = 1 ]"

echo "== 6. persistence-jooq (다른 모듈에 기대지 않는다: 방언 모듈도 테스트용으로 끌고 오지 않는다)"
E="$TMP/e"
expect_exit 0 "persistence-jooq 를 MySQL 로 찍는다" stamp "$E" --db mysql --modules persistence-jooq
JOOQ_OUTPUT="$LAST_OUTPUT"
check "persistence-jooq 가 포함된다" bash -c "grep -q 'include(\":modules:persistence-jooq\")' '$E/settings.gradle.kts'"
check "MySQL 조합에 db-postgresql 이 따라오지 않는다" bash -c "! grep -q 'db-postgresql' '$E/settings.gradle.kts' && test ! -e '$E/modules/db-postgresql'"
echo "$JOOQ_OUTPUT" | grep -q 'tests of persistence-jooq need' && fail "persistence-jooq 테스트가 다른 모듈을 요구한다고 안내한다" || pass "테스트용 모듈 의존 안내가 없다"
check "main 에는 예시 DDL 이 없다 (src/test/resources 에 있다)" bash -c "test ! -e '$E/modules/persistence-jooq/src/main/resources/db' && test -f '$E/modules/persistence-jooq/src/test/resources/db/jooq-probe-postgresql.sql'"
F="$TMP/f"
expect_exit 0 "persistence-jooq + 형제 마이그레이션 모듈을 찍는다" stamp "$F" --modules persistence-jooq,job-queue-jdbc,notification-jdbc

echo "== 7. --with-sample (샘플 앱 \"Notes\" 은 요청할 때만 남는다)"
G="$TMP/g"
expect_exit 0 "조합 5 를 찍는다 (rename 잔여 검사 포함)" stamp "$G" --with-sample
want=":apps:api :apps:sample :modules:alert :modules:alert-jdbc :modules:auth :modules:board :modules:board-jdbc :modules:crypto :modules:db-postgresql :modules:idempotency :modules:job-queue-jdbc :modules:json :modules:migration :modules:migration-flyway :modules:notification :modules:notification-jdbc :modules:notification-mail :modules:notification-sse :modules:persistence-jdbc :modules:platform :modules:storage :modules:storage-s3 :modules:time"
got="$(includes "$G/settings.gradle.kts")"
[ "$got" = "$want" ] && pass "settings.gradle.kts 는 스타터 + 샘플 앱의 모듈과 그 닫힘을 포함한다" || fail "settings includes: [$got] expected [$want]"
check "샘플 앱 소스가 새 패키지로 옮겨진다" test -f "$G/apps/sample/src/main/kotlin/dev/sumin/ovation/app/sample/SampleApplication.kt"
check "샘플 앱의 설정 루트 키가 새 접두사다 (ovation:) — skeleton: 이 남으면 설정이 조용히 무시된다" bash -c "grep -q '^ovation:' '$G/apps/sample/src/main/resources/application.yml' && ! grep -q '^skeleton:' '$G/apps/sample/src/main/resources/application.yml' '$G/apps/sample/src/main/resources/application-local.yml'"
check "샘플 문서 · 실행 스크립트가 남고 안내 문서의 표식 줄만 지워진다" bash -c "test -f '$G/docs/sample.md' && test -x '$G/scripts/dev-sample.sh' && test -x '$G/scripts/sample-e2e-backend.sh' && grep -q 'apps/sample' '$G/CLAUDE.md' && ! grep -q 'sample:start\|sample:end' '$G/CLAUDE.md' '$G/README.md'"
check "스타터 apps/api 는 그대로다 — 샘플 앱의 모듈을 얹지 않는다" bash -c "! grep -q 'storage-s3\|notification\|idempotency' '$G/apps/api/build.gradle.kts'"
check "스타터 테스트의 부재 단언(S3 클라이언트)은 그대로다 — 스타터 클래스패스에는 여전히 없다" has_line 'software.amazon.awssdk.services.s3.S3Client' "$G/apps/api/src/test/kotlin/dev/sumin/ovation/app/api/StarterCompositionIntegrationTest.kt"
check "스타터 설정에 샘플 모듈의 설정 블록이 붙지 않는다" lacks_line 'new-project: module config blocks' "$G/apps/api/src/main/resources/application.yml"
check "dbTestModules 에 샘플이 쓰는 DB 모듈이 들어간다" has_line 'val dbTestModules = setOf\(":modules:alert-jdbc", ":modules:board-jdbc", ":modules:job-queue-jdbc", ":modules:notification-jdbc"\)' "$G/build.gradle.kts"
check "dev.sh 는 APP=sample 을 알고 문법 검사를 통과한다" bash -c "grep -q 'APP' '$G/scripts/dev.sh' && bash -n '$G/scripts/dev.sh' && bash -n '$G/scripts/dev-sample.sh' && bash -n '$G/scripts/sample-e2e-backend.sh'"

echo "== 8. --modules board,board-jdbc (게시판 — board 는 저장소를 모르고 board-jdbc 가 포트를 구현한다)"
H="$TMP/h"
expect_exit 0 "조합 6 을 찍는다 (rename 잔여 검사 포함)" stamp "$H" --modules board,board-jdbc
echo "$LAST_OUTPUT" | grep -q 'notification (compile-only for board' && pass "notification 은 board 의 컴파일 전용 의존으로 안내된다 (런타임 전이 없음)" || fail "notification 이 컴파일 전용이라는 안내가 없다"
for m in board board-jdbc notification idempotency; do
  check "모듈 $m 이 포함된다 (요청했거나 닫힘으로 따라왔다)" bash -c "grep -q 'include(\":modules:$m\")' '$H/settings.gradle.kts' && test -d '$H/modules/$m'"
done
for m in board board-jdbc; do
  check "apps/api 의존성에 $m 한 줄이 생긴다" has_line "implementation\\(project\\(\":modules:$m\"\\)\\)" "$H/apps/api/build.gradle.kts"
done
check "닫힘으로 따라온 컴파일 전용 모듈은 앱 의존성에 줄을 더하지 않는다 (notification · idempotency)" bash -c "! grep -q 'modules:notification\|modules:idempotency' '$H/apps/api/build.gradle.kts'"
check "dbTestModules 에 board-jdbc 가 들어간다 (src/dbTest 가 있는 모듈 전부)" has_line 'val dbTestModules = setOf\(":modules:board-jdbc"\)' "$H/build.gradle.kts"
check "게시판 문서 쪽과 설정 블록이 남고 새 접두사로 붙는다" bash -c "test -f '$H/docs/modules/board.md' && test -f '$H/docs/modules/board-jdbc.md' && grep -q '^# ovation.board\|^#   board:' '$H/apps/api/src/main/resources/application.yml' && grep -q 'ovation.board' '$H/modules/board/src/main/kotlin/dev/sumin/ovation/board/BoardProperties.kt'"
check "찍은 프로젝트에 skeleton 이름 흔적이 없다 (BoardController 경로 속성 포함)" bash -c "! grep -rq 'skeleton\.board' '$H/modules/board/src/main'"
I="$TMP/i"
expect_exit 0 "board 만 요청하면 찍히되 board-jdbc 를 더하라고 알려 준다" stamp "$I" --modules board
echo "$LAST_OUTPUT" | grep -q 'board has no storage of its own' && pass "저장소 없음 안내가 나온다" || fail "board 만 요청했는데 board-jdbc 안내가 없다"

echo "== 9. --modules redis-core,redis-lock (Redis 환경변수 이름이 프로젝트 접두사를 따라간다 — 배포 계약)"
J="$TMP/j"
expect_exit 0 "redis 조합을 찍는다" stamp "$J" --modules redis-core,redis-lock
check "배포 선언의 redis 가 true 다 (redis 모듈을 골랐다)" has_line '^redis: true' "$J/deploy/app.yaml"
check "Redis 환경변수 테스트가 프로젝트 접두사(OVATION_REDIS_*)로 찍힌다" bash -c "grep -q 'OVATION_REDIS_SSL_ENABLED' '$J/modules/redis-core/src/test/kotlin/dev/sumin/ovation/redis/core/RedisEnvironmentVariablesTest.kt' && grep -q 'OVATION_REDIS_LOCK_ENABLED' '$J/modules/redis-lock/src/test/kotlin/dev/sumin/ovation/redis/lock/RedisLockEnvironmentVariablesTest.kt'"
check "찍은 Redis 테스트에 SKELETON_ 이름이 남지 않는다" bash -c "! grep -rq 'SKELETON_' '$J/modules/redis-core/src/test' '$J/modules/redis-lock/src/test'"
check "배포 선언에 redis-core 안내가 남는다" has_line '\[redis-core\]' "$J/deploy/app.yaml"

if [ "$MODE" = "--full" ]; then
  echo "== 10. 조합마다 ./gradlew build (순차)"
  build_composition() { # build_composition <dir> <이름>
    local dir="$1" name="$2" started ended
    started="$(date +%s)"
    if (cd "$dir" && ./gradlew build --console=plain > "$TMP/build-$name.log" 2>&1); then
      ended="$(date +%s)"
      pass "$name: ./gradlew build 통과 ($((ended - started))초)"
    else
      ended="$(date +%s)"
      fail "$name: ./gradlew build 실패 ($((ended - started))초) — 로그 마지막 줄:"
      tail -25 "$TMP/build-$name.log" | sed 's/^/      /'
    fi
  }
  build_composition "$A" "1-defaults"
  build_composition "$B" "2-modules"
  build_composition "$C" "3-mysql"
  build_composition "$F" "4-jooq"
  build_composition "$G" "5-sample"
  build_composition "$H" "6-board"
  build_composition "$J" "7-redis"
fi

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "✗ $FAILURES 개 실패"
  exit 1
fi
echo "✓ all passed ($MODE)"
