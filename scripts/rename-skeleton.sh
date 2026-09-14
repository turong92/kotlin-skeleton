#!/usr/bin/env bash
# 새 프로젝트로 찍어내기: 패키지 · 설정 접두사 · 클래스 접두사 · 환경변수 접두사 · 이름 문자열을 한 번에 바꾼다.
#
#   scripts/rename-skeleton.sh <root-package> <config-prefix> <ClassPrefix> [target-dir]
#   예) scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation
#
# 바꾸는 것
#   dev.sumin.skeleton   → <root-package>        (Kotlin 패키지, 디렉토리, AutoConfiguration.imports, spring.factories, persistence.xml, 문서)
#   dev/sumin/skeleton   → <root-package 경로>   (리소스 경로 문자열, 스크립트의 파일 경로)
#   skeleton.<key>       → <config-prefix>.<key>  (application*.yml, @ConfigurationProperties, 로거 이름 skeleton.debug.*)
#   skeleton:            → <config-prefix>:       (YAML 계층 표기의 루트 키 — 점 표기와 달리 빌드로는 안 잡히고 설정이 조용히 무시된다)
#   SKELETON_<ENV>       → <CONFIG_PREFIX>_<ENV>  (yml 의 환경변수 자리표시자, .env.example)
#   Skeleton<Name>       → <ClassPrefix><Name>    (클래스·파일 이름)
#   skeleton<Name>       → <classPrefix><Name>    (빈 이름 등 lowerCamel: skeletonCorsConfigurationSource)
#   kotlin-skeleton      → <config-prefix>        (spring.application.name, JWT issuer, Redis key prefix, SSM 경로, OpenAPI title — .md 제외)
#   skeleton-<name>      → <config-prefix>-<name> (스레드 이름 접두사, Jackson 모듈 이름, Kafka 헤더, AWS 프로파일, 파일 이름 skeleton-*.sql)
#   "Composable Kotlin backend skeleton API" → "<ClassPrefix> API" (OpenAPI description 기본값)
#   rootProject.name     → <config-prefix>
# 바꾸지 않는 것: 테이블 이름 skeleton_jobs (모듈 SQL 과 맞물림), 샘플 API 경로 /api/v1/skeleton/** (react-skeleton 워크벤치 계약),
#   "kotlin-skeleton" 이 들어간 .md 문서 제목(수동), git 히스토리.
# 끝나면 남은 흔적을 검사해 하나라도 있으면 exit 1. 그 다음 `./gradlew build` 로 확인한다. macOS/BSD sed 와 GNU sed 모두 동작.
set -euo pipefail

PKG="${1:?root package (e.g. dev.sumin.ovation)}"
PREFIX="${2:?config prefix (e.g. ovation)}"
CLASS="${3:?class prefix (e.g. Ovation)}"
ROOT="${4:-$(cd "$(dirname "$0")/.." && pwd)}"
ENVPREFIX="$(echo "$PREFIX" | tr '[:lower:]-' '[:upper:]_')"
CAMEL="$(echo "${CLASS:0:1}" | tr '[:upper:]' '[:lower:]')${CLASS:1}"
PKG_DIR="$(echo "$PKG" | tr . /)"
command -v perl >/dev/null || { echo "perl is required"; exit 1; }

cd "$ROOT"
echo "→ $ROOT : dev.sumin.skeleton→$PKG  skeleton.*→$PREFIX.*  SKELETON_*→${ENVPREFIX}_*  Skeleton*→${CLASS}*  kotlin-skeleton→$PREFIX"

files() {
  find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \
      -o -name '*.imports' -o -name '*.factories' -o -name '*.md' -o -name '*.sql' -o -name '*.json' -o -name '*.xml' \
      -o -name '.env' -o -name '.env.*' -o -name 'Dockerfile' -o -name '*.py' -o -name '*.sh' \) \
      -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/.kotlin/*' -not -path '*/node_modules/*' -not -path './.git/*' \
      -not -path './scripts/rename-skeleton.sh'
}

# 1. 텍스트 치환 (perl: macOS/BSD sed 에는 \b 가 없다). 순서 중요: 긴 패턴 먼저
files | while read -r f; do
  case "$f" in
    *.md) kotlin_rule='' ;;                                   # 문서 제목의 "kotlin-skeleton" 은 수동
    *)    kotlin_rule="s/\\bkotlin-skeleton\\b/${PREFIX}/g;" ;;
  esac
  perl -pi -e "
    s/dev\\.sumin\\.skeleton/${PKG}/g;
    s#\\bdev/sumin/skeleton\\b#${PKG_DIR}#g;
    s/\\bSKELETON_/${ENVPREFIX}_/g;
    ${kotlin_rule}
    s/^(\\s*)skeleton:(\\s*)\$/\${1}${PREFIX}:\${2}/;
    s/\\bskeleton\\.(?=[a-z])/${PREFIX}./g;
    s/\\bskeleton-(?=[a-z])/${PREFIX}-/g;
    s/Composable Kotlin backend skeleton API/${CLASS} API/g;
    s/\\bSkeleton(?=[A-Z])/${CLASS}/g;
    s/\\bskeleton(?=[A-Z])/${CAMEL}/g;
  " "$f"
done
perl -pi -e "s/rootProject\\.name = \"kotlin-skeleton\"/rootProject.name = \"${PREFIX}\"/" settings.gradle.kts

# 2. 패키지 디렉토리 이동  (…/kotlin/dev/sumin/skeleton/<rest> → …/kotlin/<PKG_DIR>/<rest>)
find . -type d -path '*/dev/sumin/skeleton' -not -path '*/build/*' | while read -r d; do
  base="${d%/dev/sumin/skeleton}"
  mkdir -p "$base/$PKG_DIR"
  # 내용만 옮기고 빈 옛 디렉토리 정리
  (cd "$d" && find . -mindepth 1 -maxdepth 1 -exec mv {} "$OLDPWD/$base/$PKG_DIR/" \;)
  rmdir "$d" 2>/dev/null || true
  rmdir "$base/dev/sumin" 2>/dev/null || true
  rmdir "$base/dev" 2>/dev/null || true
done

# 3. 파일 이름 (SkeletonFoo.kt → <CLASS>Foo.kt, skeleton-foo.sql → <PREFIX>-foo.sql)
find . -type f -name 'Skeleton*.kt' -not -path '*/build/*' | while read -r f; do
  mv "$f" "$(dirname "$f")/${CLASS}${f##*/Skeleton}"
done
find . -type f -name 'skeleton-*' -not -path '*/build/*' -not -path './.git/*' | while read -r f; do
  mv "$f" "$(dirname "$f")/${PREFIX}-${f##*/skeleton-}"
done

# 4. 검증: 남은 흔적이 있으면 실패. 빌드는 이걸 못 잡는다 (예: YAML 루트 키가 skeleton: 이면 설정이 조용히 무시된다)
leftovers="$(files | xargs perl -ne '
  next if /skeleton_jobs|\/skeleton\//;                       # 의도적으로 남기는 것
  next if $ARGV =~ /\.md$/ && /kotlin-skeleton/;               # 문서 제목
  print "$ARGV:$.: $_" if /dev\.sumin\.skeleton|dev\/sumin\/skeleton|\bSKELETON_|^\s*skeleton:\s*$|\bskeleton\.[a-z]|\bskeleton-[a-z]|\bSkeleton[A-Z]|\bskeleton[A-Z]|\bkotlin-skeleton\b/;
  close ARGV if eof;
' || true)"
if [ -n "$leftovers" ]; then
  echo "✗ skeleton 흔적이 남았다:"; echo "$leftovers"; exit 1
fi
if find . -type d -path '*/dev/sumin/skeleton' -not -path '*/build/*' | grep -q .; then
  echo "✗ dev/sumin/skeleton 디렉토리가 남았다"; exit 1
fi
echo "✓ done (no skeleton leftovers). next: ./gradlew build"
