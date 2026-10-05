#!/usr/bin/env bash
# 새 프로젝트 한 줄 찍어내기: 복사 → 모듈 고르기 → (선택) DB 바꾸기 → 의존성 · 설정 블록 더하기 → rename.
#
#   scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix> \
#       [--modules a,b,c] [--db postgresql|mysql] [--with-workbench] [--with-sample]
#
#   예) scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation
#       scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation --modules job-queue-jdbc,storage-s3,scheduler
#       scripts/new-project.sh ~/work/ovation dev.sumin.ovation ovation Ovation --db mysql --modules job-queue-jdbc
#
# 하는 일
#   1. 이 레포를 <target-dir> 로 복사한다 (build · .gradle · .kotlin · .git · .superpowers · .claude · node_modules · .env 제외).
#      apps/api(스타터)는 남기고 apps/workbench 는 --with-workbench 일 때만 남긴다
#      (워크벤치는 모든 모듈을 쓰므로 그 경우 모든 모듈이 남고 PostgreSQL 전용이다).
#      apps/sample(제품 모양 예시 "Notes", docs/sample.md)은 기본으로 **빠진다** — --with-sample 일 때만 남고, 그 앱이 쓰는 모듈이 닫힘에 더해진다(PostgreSQL 전용).
#   2. 모듈 = 스타터의 모듈 + --modules, 모듈끼리의 project(":modules:x") 의존(api · implementation · runtimeOnly)으로 닫는다.
#      테스트에만 쓰는 모듈 의존(testImplementation)도 테스트가 컴파일되려면 필요하므로 따라온다 — 따라온 이유를 출력한다.
#      고르지 않은 모듈은 디렉토리 · docs/config/modules/<m>.yml · docs/modules/<m>.md (와 색인 행) · settings.gradle.kts include 를 지우고,
#      루트 build.gradle.kts 의 dbTestModules / dbSuites, 스타터 테스트의 "부재 단언"에서도 뺀다.
#   3. --modules 로 요청한 모듈마다 apps/api/build.gradle.kts 에 implementation(project(":modules:<m>")) 한 줄을 더하고,
#      docs/config/modules/<m>.yml 을 apps/api application.yml 끝의 표시된 구역에 주석으로 붙인다.
#   4. --db mysql: db-postgresql → db-mysql, datasource URL, compose 서비스, Testcontainers 설정, 첫 마이그레이션 폴더를 MySQL 로 바꾼다.
#   5. 마지막에 scripts/rename-skeleton.sh 를 대상 안에서 돌린다 (잔여 흔적이 있으면 실패).
#
# 종료 코드: 0 성공 / 1 도중 실패(대상이 남는다) / 2 인자 오류(아무것도 만들지 않는다).
# 소스 레포와 <target-dir> 밖에는 아무것도 쓰지 않는다. macOS bash 3.2 와 GNU 에서 돈다 (연관 배열 · mapfile 을 쓰지 않는다).
set -euo pipefail

SRC="$(cd "$(dirname "$0")/.." && pwd -P)"

usage() {
  cat <<'EOF'
usage: scripts/new-project.sh <target-dir> <root-package> <config-prefix> <ClassPrefix>
                              [--modules a,b,c] [--db postgresql|mysql] [--with-workbench] [--with-sample]

  <target-dir>     새로 만들 디렉토리 (이미 있으면 거부)
  <root-package>   예: dev.sumin.ovation
  <config-prefix>  예: ovation   (설정 접두사 · 환경변수 접두사 · 이름 문자열)
  <ClassPrefix>    예: Ovation   (클래스 이름 접두사)
  --modules        스타터에 더할 모듈, 쉼표로 구분 (예: job-queue-jdbc,notification-mail,storage-s3,scheduler)
  --db             postgresql(기본) | mysql
  --with-workbench apps/workbench(모든 모듈 데모)도 남긴다 — 모든 모듈이 남고 PostgreSQL 전용
  --with-sample    apps/sample(제품 모양 예시 앱 "Notes" — 새 기능을 어떻게 얹는지 보는 정본)도 남긴다 — 그 앱의 모듈이 더해지고 PostgreSQL 전용
EOF
}

die_usage() { echo "✗ $*" >&2; echo >&2; usage >&2; exit 2; }
die_listing() { echo "✗ $*" >&2; echo "valid modules: $(valid_modules | tr '\n' ' ')" >&2; exit 2; }

valid_modules() { # 소스 레포의 모듈 이름들 (정렬)
  local d
  for d in "$SRC"/modules/*/; do
    [ -f "$d/build.gradle.kts" ] && basename "$d"
  done | sort
}

in_list() { printf '%s\n' "$2" | grep -Fxq -- "$1"; }

# project_deps <build.gradle.kts>  →  "main <module>" | "compile <module>" | "test <module>" 줄들 (// 주석은 뺀다)
# compile = compileOnly: 컴파일에만 필요하고 런타임 전이는 없다 (예: storage-s3 → crypto). 소스는 따라오지만 앱의 런타임 클래스패스에는 안 들어간다.
project_deps() {
  perl -ne 's#//.*##; while (/\b(api|implementation|runtimeOnly|compileOnly|testImplementation|testRuntimeOnly)\(project\(":modules:([a-z0-9-]+)"\)\)/g) { my ($cfg, $mod) = ($1, $2); print(($cfg =~ /^test/ ? "test" : $cfg eq "compileOnly" ? "compile" : "main"), " $mod\n") }' "$1"
}

# ---------------------------------------------------------------------------------------------------- 인자
POSITIONAL=()
MODULES_ARG=""
DB="postgresql"
WITH_WORKBENCH=0
WITH_SAMPLE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --modules) [ $# -ge 2 ] || die_usage "--modules needs a value"; MODULES_ARG="$2"; shift 2 ;;
    --modules=*) MODULES_ARG="${1#--modules=}"; shift ;;
    --db) [ $# -ge 2 ] || die_usage "--db needs a value"; DB="$2"; shift 2 ;;
    --db=*) DB="${1#--db=}"; shift ;;
    --with-workbench) WITH_WORKBENCH=1; shift ;;
    --with-sample) WITH_SAMPLE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --*) die_usage "unknown option: $1" ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done
[ "${#POSITIONAL[@]}" -eq 4 ] || die_usage "expected 4 arguments (<target-dir> <root-package> <config-prefix> <ClassPrefix>), got ${#POSITIONAL[@]}"
TARGET_ARG="${POSITIONAL[0]}"; PKG="${POSITIONAL[1]}"; PREFIX="${POSITIONAL[2]}"; CLASS="${POSITIONAL[3]}"

echo "$PKG" | grep -Eq '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' || die_usage "root package must look like dev.sumin.ovation: $PKG"
echo "$PREFIX" | grep -Eq '^[a-z][a-z0-9-]*$' || die_usage "config prefix must be lower-case letters, digits, hyphens: $PREFIX"
echo "$CLASS" | grep -Eq '^[A-Z][A-Za-z0-9]*$' || die_usage "class prefix must look like Ovation: $CLASS"
case "$DB" in postgresql|mysql) ;; *) die_usage "--db must be postgresql or mysql: $DB" ;; esac
if [ "$WITH_WORKBENCH" = 1 ] && [ "$DB" = mysql ]; then
  die_usage "--with-workbench is PostgreSQL-only (the workbench depends on db-postgresql); drop --db mysql or --with-workbench"
fi

ALL_MODULES="$(valid_modules)"
REQUESTED=""   # 요청 순서 유지, 중복 제거
if [ -n "$MODULES_ARG" ]; then
  while IFS= read -r m; do
    m="$(echo "$m" | tr -d '[:space:]')"
    [ -n "$m" ] || continue
    case "$m" in
      db-postgresql|db-mysql) die_usage "$m is chosen with --db, not --modules" ;;
    esac
    in_list "$m" "$ALL_MODULES" || die_listing "unknown module: $m"
    in_list "$m" "$REQUESTED" || REQUESTED="${REQUESTED:+$REQUESTED$'\n'}$m"
  done <<EOF
$(printf '%s' "$MODULES_ARG" | tr ',' '\n')
EOF
fi

if [ "$WITH_SAMPLE" = 1 ] && [ "$DB" = mysql ]; then
  die_usage "--with-sample is PostgreSQL-only (the sample's migration and queries are PostgreSQL); drop --db mysql or --with-sample"
fi

case "$TARGET_ARG" in /*) TARGET="$TARGET_ARG" ;; *) TARGET="$PWD/$TARGET_ARG" ;; esac
TARGET="${TARGET%/}"
# `../내-프로젝트` · 심볼릭 링크를 정리한다 — 안 하면 레포 안에서 부른 `../x` 가 "레포 안" 으로 오인된다 (아직 없는 끝 구간은 그대로 붙인다)
normalize_path() {
  local p="$1" d b
  d="$(dirname "$p")"; b="$(basename "$p")"
  if [ -d "$d" ]; then printf '%s/%s' "$(cd "$d" && pwd -P)" "$b"; else printf '%s/%s' "$(normalize_path "$d")" "$b"; fi
}
[ -z "$TARGET" ] || TARGET="$(normalize_path "$TARGET")"
[ -n "$TARGET" ] || die_usage "target must not be /"
[ ! -e "$TARGET" ] || die_usage "target already exists: $TARGET"
case "$TARGET/" in "$SRC"/*) die_usage "target must be outside the skeleton repo ($SRC): $TARGET" ;; esac
command -v perl >/dev/null || { echo "✗ perl is required" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------------- 모듈 닫힘
STARTER="$(project_deps "$SRC/apps/api/build.gradle.kts" | awk '$1 == "main" { print $2 }')"
if [ "$DB" = mysql ]; then
  STARTER="$(printf '%s\n' "$STARTER" | sed 's/^db-postgresql$/db-mysql/')"
fi

SAMPLE_DEPS=""
[ "$WITH_SAMPLE" = 0 ] || SAMPLE_DEPS="$(project_deps "$SRC/apps/sample/build.gradle.kts" | awk '$1 == "main" { print $2 }')"

NOTES=""
# close <시작 모듈 목록>  →  전역 CLOSED (줄 목록), REASONS (추가 이유), NOTES (테스트용 의존 안내)
close() {
  CLOSED="$1"; REASONS=""
  local changed=1 m kind dep
  while [ "$changed" = 1 ]; do
    changed=0
    for m in $CLOSED; do
      while read -r kind dep; do
        [ -n "${dep:-}" ] || continue
        if [ "$kind" = test ]; then
          local note="tests of $m need $dep"
          in_list "$note" "$NOTES" || NOTES="${NOTES:+$NOTES$'\n'}$note"
        fi
        if ! in_list "$dep" "$CLOSED"; then
          CLOSED="$CLOSED"$'\n'"$dep"
          local why="needed by $m"
          [ "$kind" = test ] && why="needed by the tests of $m"
          [ "$kind" = compile ] && why="compile-only for $m, not on its runtime classpath"
          REASONS="$REASONS  + $dep ($why)"$'\n'
          changed=1
        fi
      done <<EOF
$(project_deps "$SRC/modules/$m/build.gradle.kts")
EOF
    done
  done
}

# BASE = 스타터만 · API_SELECTED = 스타터 + 요청(apps/api 의 클래스패스) · SELECTED = 거기에 샘플 앱이 쓰는 모듈까지(디렉토리로 남는 전부)
if [ "$WITH_WORKBENCH" = 1 ]; then
  SELECTED="$ALL_MODULES"
  BASE="$ALL_MODULES"
  API_SELECTED="$ALL_MODULES"
  REASONS="  (--with-workbench keeps every module)"$'\n'
else
  close "$STARTER"; BASE="$CLOSED"
  close "$(printf '%s\n%s' "$STARTER" "$REQUESTED" | grep -v '^$')"; API_SELECTED="$CLOSED"
  close "$(printf '%s\n%s\n%s' "$STARTER" "$REQUESTED" "$SAMPLE_DEPS" | grep -v '^$')"; SELECTED="$CLOSED"
fi
SELECTED_SORTED="$(printf '%s\n' "$SELECTED" | sort -u)"
API_SORTED="$(printf '%s\n' "$API_SELECTED" | sort -u)"
REMOVED="$(comm -23 <(printf '%s\n' "$ALL_MODULES") <(printf '%s\n' "$SELECTED_SORTED"))"

echo "→ $TARGET"
echo "  package $PKG, prefix $PREFIX, class prefix $CLASS, db $DB$([ "$WITH_WORKBENCH" = 1 ] && echo ', with workbench')$([ "$WITH_SAMPLE" = 1 ] && echo ', with sample')"
echo "  modules ($(printf '%s\n' "$SELECTED_SORTED" | wc -l | tr -d ' ')): $(printf '%s\n' "$SELECTED_SORTED" | tr '\n' ' ')"
[ -z "$REQUESTED" ] || echo "  requested: $(printf '%s\n' "$REQUESTED" | tr '\n' ' ')"
printf '%s' "$REASONS" | sed 's/^/  /'
[ -z "$NOTES" ] || printf '%s\n' "$NOTES" | sed 's/^/  note: /'

# ---------------------------------------------------------------------------------------------------- 복사
mkdir -p "$TARGET"
trap 'rc=$?; if [ $rc -ne 0 ]; then echo "✗ failed (exit $rc) — $TARGET is left as is for inspection" >&2; fi' EXIT
export COPYFILE_DISABLE=1   # macOS tar 가 ._* 파일을 만들지 않게
(cd "$SRC" && tar \
  --exclude=.git --exclude=.gradle --exclude=.kotlin --exclude=.superpowers --exclude=.claude \
  --exclude=build --exclude=node_modules --exclude=.idea --exclude=out --exclude=.DS_Store \
  --exclude=.env --exclude=.env.local \
  -cf - .) | (cd "$TARGET" && tar -xf -)
cd "$TARGET"

# 새 프로젝트에는 찍어내는 도구가 필요 없다 (CI 워크플로가 그 도구를 시험한다)
rm -rf scripts/new-project.sh scripts/test-new-project.sh scripts/new-project.d .github/workflows/new-project.yml

# ---------------------------------------------------------------------------------------------------- 지우기
[ "$WITH_WORKBENCH" = 1 ] || { rm -rf apps/workbench; perl -ni -e 'print unless /^include\(":apps:workbench"\)\s*$/' settings.gradle.kts; }
# 샘플 앱은 기본으로 빠진다 — 앱 · 그 문서 · 실행 스크립트 · 안내 문서의 샘플 구역(<!-- sample:start --> … <!-- sample:end -->)까지. --with-sample 이면 표식 줄만 지운다
if [ "$WITH_SAMPLE" = 1 ]; then
  for f in CLAUDE.md README.md; do [ ! -f "$f" ] || perl -ni -e 'print unless /^<!-- sample:(start|end) -->\s*$/' "$f"; done
else
  rm -rf apps/sample docs/sample.md scripts/dev-sample.sh scripts/sample-e2e-backend.sh
  perl -ni -e 'print unless /^include\(":apps:sample"\)\s*$/' settings.gradle.kts
  for f in CLAUDE.md README.md; do [ ! -f "$f" ] || perl -0pi -e 's/<!-- sample:start -->.*?<!-- sample:end -->\n?//gs' "$f"; done
fi
while IFS= read -r m; do
  [ -n "$m" ] || continue
  rm -rf "modules/$m" "docs/config/modules/$m.yml" "docs/modules/$m.md"
  M="$m" perl -ni -e 'print unless /^include\(":modules:\Q$ENV{M}\E"\)\s*$/' settings.gradle.kts
  [ ! -f docs/modules/README.md ] || M="$m" perl -ni -e 'print unless /\]\(\Q$ENV{M}\E\.md\)/' docs/modules/README.md
done <<EOF
$REMOVED
EOF

# 루트 build.gradle.kts: DB 통합 테스트 묶음 (선택된 모듈 · 선택된 방언만)
DB_TEST_MODULES=""
for m in job-queue-jdbc notification-jdbc alert-jdbc; do
  in_list "$m" "$SELECTED_SORTED" && DB_TEST_MODULES="${DB_TEST_MODULES:+$DB_TEST_MODULES, }\":modules:$m\""
done
if [ -z "$DB_TEST_MODULES" ]; then NEW_SET='emptySet<String>()'; else NEW_SET="setOf($DB_TEST_MODULES)"; fi
NEW="val dbTestModules = $NEW_SET" perl -pi -e 's/^val dbTestModules = .*$/$ENV{NEW}/' build.gradle.kts
in_list db-postgresql "$SELECTED_SORTED" || { perl -ni -e 'print unless /^\s*"postgresTest" to /' build.gradle.kts; rm -rf modules/*/src/postgresTest; }
in_list db-mysql "$SELECTED_SORTED" || { perl -ni -e 'print unless /^\s*"mysqlTest" to /' build.gradle.kts; rm -rf modules/*/src/mysqlTest; }

# 스타터 테스트의 "부재 단언": 선택한 모듈이 가져오는 클래스는 더 이상 없어야 하는 것이 아니다
STARTER_TEST="$(ls apps/api/src/test/kotlin/dev/sumin/skeleton/app/api/StarterCompositionIntegrationTest.kt)"
drop_absent() { CLS="$1" perl -ni -e 'print unless /"\Q$ENV{CLS}\E",/' "$STARTER_TEST"; }
# (apps/api 의 클래스패스 기준이다 — 샘플 앱만 쓰는 모듈은 스타터 클래스패스에 없으니 부재 단언을 그대로 둔다)
in_list storage-s3 "$API_SORTED" && drop_absent software.amazon.awssdk.services.s3.S3Client
in_list notification-mail "$API_SORTED" && drop_absent jakarta.mail.Session
in_list persistence-jpa "$API_SORTED" && drop_absent jakarta.persistence.EntityManager
in_list event-kafka "$API_SORTED" && drop_absent org.apache.kafka.clients.producer.KafkaProducer
if printf '%s\n' "$API_SORTED" | grep -q '^redis-'; then
  drop_absent org.springframework.data.redis.core.RedisTemplate
  perl -ni -e 'print unless /containsBean\("redisConnectionFactory"\)/' "$STARTER_TEST"
fi
perl -pi -e 's/val absent = listOf\(/val absent = listOf<String>(/' "$STARTER_TEST"

# ---------------------------------------------------------------------------------------------------- MySQL
if [ "$DB" = mysql ]; then
  perl -pi -e 's#:modules:db-postgresql#:modules:db-mysql#; s#testcontainers-postgresql#testcontainers-mysql#; s#// 방언은 정확히 하나.*$#// 방언은 정확히 하나 (PostgreSQL 이면 :modules:db-postgresql)#' apps/api/build.gradle.kts
  perl -pi -e 's#jdbc:postgresql://localhost:5432/app#jdbc:mysql://localhost:3306/app#; s#modules:db-postgresql#modules:db-mysql#; s#db-postgresql ↔ postgresql#db-mysql ↔ mysql#' apps/api/src/main/resources/application.yml
  perl -pi -e 's#^SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/app#SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/app#' .env.example
  rm -rf apps/api/src/main/resources/db/migration/postgresql
  mkdir -p apps/api/src/main/resources/db/migration/mysql
  cp "$SRC/scripts/new-project.d/mysql/V20261005000000__init.sql" apps/api/src/main/resources/db/migration/mysql/
  cp "$SRC/scripts/new-project.d/mysql/TestcontainersConfiguration.kt" apps/api/src/test/kotlin/dev/sumin/skeleton/app/api/TestcontainersConfiguration.kt
  perl -0pi -e '
    s/^  postgres:\n(?:[ ]{4}.*\n)+\n//m;
    s/^  # db-mysql 모듈로 조립한 앱용: --profile mysql\n/  # db-mysql 모듈로 조립한 앱 (기본 서비스)\n/m;
    s/^    profiles: \[mysql\]\n//m;
    s/^      - postgres$/      - mysql/m;
    s#jdbc:postgresql://postgres:5432/app#jdbc:mysql://mysql:3306/app#;
    s/^  postgres-data:\n//m;
  ' docker-compose.yml
fi

# ---------------------------------------------------------------------------------------------------- 요청한 모듈의 의존성 한 줄
ADD_LIST=""
while IFS= read -r m; do
  [ -n "$m" ] || continue
  in_list "$m" "$STARTER" || ADD_LIST="${ADD_LIST:+$ADD_LIST }$m"
done <<EOF
$REQUESTED
EOF
if [ -n "$ADD_LIST" ]; then
  ADD_MODULES="$ADD_LIST" perl -e '
    my $f = shift; open(my $in, "<", $f) or die; my @l = <$in>; close $in;
    my $last = -1;
    for my $i (0 .. $#l) { $last = $i if $l[$i] =~ /^    implementation\(project\(":modules:[a-z0-9-]+"\)\)/ }
    die "no module dependency line in $f" if $last < 0;
    my @add = map { "    implementation(project(\":modules:$_\"))\n" } split(/ /, $ENV{ADD_MODULES});
    splice(@l, $last + 1, 0, @add);
    open(my $out, ">", $f) or die; print $out @l; close $out;' apps/api/build.gradle.kts
fi

# ---------------------------------------------------------------------------------------------------- rename
if [ -x scripts/rename-skeleton.sh ] || [ -f scripts/rename-skeleton.sh ]; then
  bash scripts/rename-skeleton.sh "$PKG" "$PREFIX" "$CLASS" "$TARGET"
else
  echo "✗ scripts/rename-skeleton.sh is missing" >&2; exit 1
fi

# ---------------------------------------------------------------------------------------------------- 설정 블록 (rename 뒤에 붙인다: 주석 속 `skeleton:` 루트 키는 rename 이 못 잡는다)
APP_YML=apps/api/src/main/resources/application.yml
BLOCK_MODULES=""
while IFS= read -r m; do
  [ -n "$m" ] || continue
  [ -f "docs/config/modules/$m.yml" ] || continue
  BLOCK_MODULES="${BLOCK_MODULES:+$BLOCK_MODULES$'\n'}$m"
done <<EOF
$(printf '%s\n' "$REQUESTED"; printf '%s\n' "$API_SELECTED" | while IFS= read -r s; do in_list "$s" "$BASE" || in_list "$s" "$REQUESTED" || echo "$s"; done)
EOF
if [ -n "$BLOCK_MODULES" ] && [ "$WITH_WORKBENCH" != 1 ]; then
  {
    echo
    echo "# ===== new-project: module config blocks ============================================================"
    echo "# docs/config/modules/<module>.yml 의 모든 키와 기본값이다. 모듈은 이 값 그대로 동작한다 — 바꿀 키만 주석을 풀어 위 설정에 합친다"
    echo "# (최상위 키 \`$PREFIX:\` 가 두 번 나오면 안 된다). 모듈 기본값과 같은 값은 남기지 않는다. 쓰지 않는 블록은 지워도 된다."
    while IFS= read -r m; do
      [ -n "$m" ] || continue
      echo "# ----- $m -----"
      sed 's/^/# /' "docs/config/modules/$m.yml"
    done <<EOF2
$BLOCK_MODULES
EOF2
  } >> "$APP_YML"
fi

# ---------------------------------------------------------------------------------------------------- 로컬 S3 (storage-s3 를 고르면 local 프로필이 compose 의 S3 에 붙는다)
if in_list storage-s3 "$API_SORTED"; then
  sed "s/^skeleton:/$PREFIX:/" "$SRC/scripts/new-project.d/application-local-storage-s3.yml" >> apps/api/src/main/resources/application-local.yml
fi

# ---------------------------------------------------------------------------------------------------- 끝
trap - EXIT
DB_SERVICE="$([ "$DB" = mysql ] && echo mysql || echo postgres)"
SAMPLE_HINT=""
[ "$WITH_SAMPLE" = 0 ] || SAMPLE_HINT="  APP=sample scripts/dev.sh                         # 샘플 앱(Notes) — docs/sample.md. 짝 프론트는 react-skeleton 에서 --with-sample 로 찍어 ../web 에 둔다
"
cat <<EOF

✓ $TARGET
  modules: $(printf '%s\n' "$SELECTED_SORTED" | tr '\n' ' ')

next:
  cd $TARGET
  git init && git add -A && git commit -m "Initial commit (from the skeleton: $(printf '%s\n' "$SELECTED_SORTED" | wc -l | tr -d ' ') modules)"
  ./gradlew build                                  # Docker 가 필요하다 (Testcontainers)
  scripts/dev.sh                                    # 로컬 한 줄 실행: $DB_SERVICE$(in_list storage-s3 "$SELECTED_SORTED" && echo " + s3") 컨테이너 → 백엔드 (../web 이 있으면 프론트도)
$SAMPLE_HINT  # 손으로: docker compose up -d $DB_SERVICE && ./gradlew :apps:api:bootRun --args='--spring.profiles.active=local'
module 하나 더: apps/api/build.gradle.kts 에 implementation(project(":modules:<m>")) 한 줄 (모듈이 없으면 이 도구를 다시 쓰지 말고 스켈레톤에서 디렉토리를 복사한 뒤 settings.gradle.kts 에 include).
설정이 필요하면 docs/config/modules/<m>.yml 에서 바꿀 키만 apps/api application.yml 로 옮긴다. 환경변수는 .env.example 의 [모듈] 구역.
EOF
