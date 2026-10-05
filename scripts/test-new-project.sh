#!/usr/bin/env bash
# scripts/new-project.sh 의 테스트.
#
#   scripts/test-new-project.sh            # 빠른 검사 (기본, 수 초~수십 초): 인자 검증 · 모듈 닫힘 · 파일 가지치기 · rename 잔여 검사
#   scripts/test-new-project.sh --quick    # 위와 같다 (./gradlew check 가 부른다)
#   scripts/test-new-project.sh --full     # 위 + 세 조합을 임시 디렉토리에 찍어 각각 ./gradlew build (Docker/Testcontainers 필요, 순차, 수 분)
#
# --full 은 CI 의 별도 워크플로(.github/workflows/new-project.yml)가 돈다. 조합:
#   1. 기본값만
#   2. --modules job-queue-jdbc,notification-mail,storage-s3,scheduler
#   3. --db mysql --modules job-queue-jdbc
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
includes() { { grep -o 'include(":[a-z:-]*")' "$1" || true; } | sed -E 's/include\("(.*)"\)/\1/' | sort | tr '\n' ' ' | sed 's/ $//'; }

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
expect_exit 2 "대상이 소스 레포 안이면 exit 2" bash "$SCRIPT" "$SRC/stamped-inside" dev.sumin.ovation ovation Ovation
[ ! -e "$SRC/stamped-inside" ] && pass "레포 안에는 아무것도 만들지 않는다" || { fail "레포 안에 만들었다"; rm -rf "$SRC/stamped-inside"; }

echo "== 2. 기본값만"
MARKER="$TMP/marker"; touch "$MARKER"; sleep 1
A="$TMP/a"
expect_exit 0 "기본 조합을 찍는다 (rename 잔여 검사 포함)" stamp "$A"
check "스타터 앱만 남는다 (apps/workbench 없음)" test -d "$A/apps/api" -a ! -e "$A/apps/workbench"
check "build · .gradle · .git · .claude · .superpowers 는 복사하지 않는다" bash -c "! ls -d '$A/build' '$A/.gradle' '$A/.git' '$A/.claude' '$A/.superpowers' '$A/.kotlin' 2>/dev/null | grep -q ."
want=":apps:api :modules:auth :modules:db-postgresql :modules:migration :modules:migration-flyway :modules:persistence-jdbc :modules:platform :modules:time"
got="$(includes "$A/settings.gradle.kts")"
[ "$got" = "$want" ] && pass "settings.gradle.kts 는 스타터 모듈과 그 닫힘만 포함한다" || fail "settings includes: [$got] expected [$want]"
check "선택되지 않은 모듈 디렉토리가 없다 (redis-core)" test ! -e "$A/modules/redis-core"
check "선택되지 않은 모듈의 설정 블록도 없다" test ! -e "$A/docs/config/modules/redis-core.yml"
check "선택된 모듈의 설정 블록은 남는다 (time)" test -f "$A/docs/config/modules/time.yml"
check "dbTestModules 가 비어 있다" has_line 'val dbTestModules = emptySet<String>\(\)' "$A/build.gradle.kts"
check "새 프로젝트에는 new-project 도구가 따라오지 않는다" bash -c "! ls '$A/scripts/new-project.sh' '$A/scripts/test-new-project.sh' '$A/.github/workflows/new-project.yml' 2>/dev/null | grep -q ."
check "rootProject.name 이 바뀐다" has_line 'rootProject.name = "ovation"' "$A/settings.gradle.kts"
check "패키지가 바뀐다" test -f "$A/apps/api/src/main/kotlin/dev/sumin/ovation/app/api/ApiApplication.kt"
check "starter 테스트의 부재 단언은 그대로 (모듈을 더하지 않았다)" has_line 'software.amazon.awssdk.services.s3.S3Client' "$A/apps/api/src/test/kotlin/dev/sumin/ovation/app/api/StarterCompositionIntegrationTest.kt"
check "application.yml 에 모듈 블록 구역이 없다 (--modules 를 안 줬다)" lacks_line 'new-project: module config blocks' "$A/apps/api/src/main/resources/application.yml"
check "소스 레포는 건드리지 않는다" bash -c "[ -z \"\$(find '$SRC' -newer '$MARKER' -type f -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.kotlin/*' -not -path '*/.git/*' -not -path '*/node_modules/*' 2>/dev/null | head -1)\" ]"

echo "== 3. --modules job-queue-jdbc,notification-mail,storage-s3,scheduler"
B="$TMP/b"
expect_exit 0 "조합 2 를 찍는다" stamp "$B" --modules job-queue-jdbc,notification-mail,storage-s3,scheduler
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
check "persistence-jooq 가 없으니 그 모듈이 읽던 형제 폴더 문제도 없다" test ! -e "$B/modules/persistence-jooq"

echo "== 4. --db mysql --modules job-queue-jdbc"
C="$TMP/c"
expect_exit 0 "조합 3 을 찍는다" stamp "$C" --db mysql --modules job-queue-jdbc
check "db-postgresql 모듈이 사라지고 db-mysql 이 들어온다" bash -c "grep -q 'include(\":modules:db-mysql\")' '$C/settings.gradle.kts' && ! grep -q 'db-postgresql' '$C/settings.gradle.kts' && test ! -e '$C/modules/db-postgresql'"
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

echo "== 6. persistence-jooq (테스트가 db-postgresql 을 요구한다)"
E="$TMP/e"
expect_exit 0 "persistence-jooq 를 찍는다" stamp "$E" --modules persistence-jooq
JOOQ_OUTPUT="$LAST_OUTPUT"
check "persistence-jooq 가 포함된다" bash -c "grep -q 'include(\":modules:persistence-jooq\")' '$E/settings.gradle.kts'"
echo "$JOOQ_OUTPUT" | grep -q 'tests of persistence-jooq need db-postgresql' && pass "테스트용 의존이 안내에 적힌다" || fail "테스트용 의존 안내가 없다"

if [ "$MODE" = "--full" ]; then
  echo "== 7. 조합마다 ./gradlew build (순차)"
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
fi

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "✗ $FAILURES 개 실패"
  exit 1
fi
echo "✓ all passed ($MODE)"
