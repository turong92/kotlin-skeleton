#!/usr/bin/env bash
# 새 프로젝트로 찍어내기: 패키지 · 설정 접두사 · 클래스 접두사 · 환경변수 접두사를 한 번에 바꾼다.
#
#   scripts/rename-skeleton.sh <root-package> <config-prefix> <ClassPrefix> [target-dir]
#   예) scripts/rename-skeleton.sh dev.sumin.ovation ovation Ovation
#
# 바꾸는 것
#   dev.sumin.skeleton   → <root-package>      (Kotlin 패키지, 디렉토리, AutoConfiguration.imports, spring.factories, 문서)
#   skeleton.<key>       → <config-prefix>.<key>   (application*.yml, @ConfigurationProperties, 로거 이름 skeleton.debug.*)
#   SKELETON_<ENV>       → <CONFIG_PREFIX>_<ENV>   (yml 의 환경변수 자리표시자)
#   Skeleton<Name>       → <ClassPrefix><Name>     (클래스·파일 이름)
#   rootProject.name     → <config-prefix>
# 바꾸지 않는 것: "kotlin-skeleton" 문구가 들어간 문서 제목(수동), git 히스토리.
# 실행 후 `./gradlew build` 로 확인한다 (스켈레톤 CI 와 같은 검증). macOS/BSD sed 와 GNU sed 모두 동작.
set -euo pipefail

PKG="${1:?root package (e.g. dev.sumin.ovation)}"
PREFIX="${2:?config prefix (e.g. ovation)}"
CLASS="${3:?class prefix (e.g. Ovation)}"
ROOT="${4:-$(cd "$(dirname "$0")/.." && pwd)}"
ENVPREFIX="$(echo "$PREFIX" | tr '[:lower:]-' '[:upper:]_')"
PKG_DIR="$(echo "$PKG" | tr . /)"
command -v perl >/dev/null || { echo "perl is required"; exit 1; }

cd "$ROOT"
echo "→ $ROOT : dev.sumin.skeleton→$PKG  skeleton.*→$PREFIX.*  SKELETON_*→${ENVPREFIX}_*  Skeleton*→${CLASS}*"

files() {
  find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \
      -o -name '*.imports' -o -name '*.factories' -o -name '*.md' -o -name '*.sql' -o -name '*.json' \) \
      -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/node_modules/*' -not -path './.git/*'
}

# 1. 텍스트 치환 (perl: macOS/BSD sed 에는 \b 가 없다). 순서 중요: 긴 패턴 먼저
files | while read -r f; do
  perl -pi -e "
    s/dev\\.sumin\\.skeleton/${PKG}/g;
    s/\\bSKELETON_/${ENVPREFIX}_/g;
    s/\\bskeleton\\.(?=[a-z])/${PREFIX}./g;
    s/\\bSkeleton(?=[A-Z])/${CLASS}/g;
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

# 3. 클래스 파일 이름 (SkeletonFoo.kt → <CLASS>Foo.kt)
find . -type f -name 'Skeleton*.kt' -not -path '*/build/*' | while read -r f; do
  mv "$f" "$(dirname "$f")/${CLASS}${f##*/Skeleton}"
done

echo "✓ done. next: ./gradlew build"
