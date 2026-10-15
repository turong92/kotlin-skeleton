#!/usr/bin/env bash
# 네이티브 이미지(리눅스 실행 파일)를 GraalVM 공식 Docker 이미지 안에서 만든다 — 맥/윈도에서도 같은 결과 (docs/native-image.md).
#   scripts/native-build.sh [--app <앱 디렉토리 이름>]      기본 api. 결과: apps/<앱>/build/native/nativeCompile/app
#   (시험용 조립은 --app native-check — 이 앱은 tools/ 에 있다)
# 환경변수: NATIVE_IMAGE_BUILDER(기본 ghcr.io/graalvm/native-image-community:25) · NATIVE_GRADLE_HOME(컨테이너의 Gradle 캐시, 기본 $HOME/.gradle-native — 매번 의존성을 다시 받지 않게 둔다)
#           · NATIVE_PLATFORM(예: linux/amd64 — 기본은 이 머신의 아키텍처. Cloud Run 은 amd64 라 CI 에서는 비워 둔다. 맥에서 amd64 는 에뮬레이션이라 매우 느리다)
#           · NATIVE_GRADLE_RO_CACHE(읽기 전용 의존성 캐시 폴더 — 오프라인에서 ~/.gradle/caches 를 넘길 때. 있으면 --offline)
#           · NATIVE_BUILD_ENV(빌드 컨테이너에 그대로 넘길 환경변수 **이름**들, 공백 구분 — 모듈 켜기/끄기 조건이 빌드 때 굳으므로 앱이 쓰는 구성(프로필 · SKELETON_* …)을 여기로 준다. 값이 바뀌면(숫자는 무시) AOT 처리를 다시 한다)
#           · NATIVE_CPUS · NATIVE_MEMORY(둘 다 기본은 한도 없음 — 러너가 작으면 맞춰서 준다. 빌드 최대 메모리는 앱에 따라 5~6GB 안팎이다. 한도를 넘는 값은 docker 가 거부한다)
# 이 스크립트는 Gradle 을 컨테이너 안에서 -Pnative -PjavaToolchain=25 로 돌린다. 기본 JVM 빌드(./gradlew build)에는 아무 영향이 없다.
set -euo pipefail
cd "$(dirname "$0")/.."

APP=api
while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
DIR="apps/$APP"; [ -d "$DIR" ] || DIR="tools/$APP"
[ -d "$DIR" ] || { echo "앱 디렉토리가 없다: apps/$APP · tools/$APP" >&2; exit 2; }
GRADLE_PATH=":$(echo "$DIR" | tr / :):nativeCompile"

IMAGE="${NATIVE_IMAGE_BUILDER:-ghcr.io/graalvm/native-image-community:25}"
GHOME="${NATIVE_GRADLE_HOME:-$HOME/.gradle-native}"
mkdir -p "$GHOME"
ARGS=(--rm -v "$PWD:/src" -w /src -v "$GHOME:/gh" -e GRADLE_USER_HOME=/gh)
[ -z "${NATIVE_CPUS:-}" ] || ARGS+=(--cpus "$NATIVE_CPUS")
[ -z "${NATIVE_MEMORY:-}" ] || ARGS+=(--memory "$NATIVE_MEMORY")
[ -z "${NATIVE_PLATFORM:-}" ] || ARGS+=(--platform "$NATIVE_PLATFORM")
BUILD_ENV_STAMP=""
for name in ${NATIVE_BUILD_ENV:-}; do
  ARGS+=(-e "$name"); BUILD_ENV_STAMP+="$name=$(printf %s "${!name:-}" | tr 0-9 '#')"$'\n'
done
GRADLE_ARGS=("$GRADLE_PATH" -Pnative -PjavaToolchain=25 -x test --console=plain)
if [ -n "${NATIVE_GRADLE_RO_CACHE:-}" ]; then
  ARGS+=(-v "$NATIVE_GRADLE_RO_CACHE:/ro-cache:ro" -e GRADLE_RO_DEP_CACHE=/ro-cache); GRADLE_ARGS+=(--offline)
fi

# 같은 출력 폴더를 다른 아키텍처로 다시 만들 때 Gradle 은 "최신" 이라며 건너뛴다(플랫폼은 입력이 아니다) — 마지막으로 만든 플랫폼을 적어 두고 다르면 지운다
PLATFORM_MARK="$DIR/build/native/.platform"; WANT="${NATIVE_PLATFORM:-native}"
if [ -f "$PLATFORM_MARK" ] && [ "$(cat "$PLATFORM_MARK")" != "$WANT" ]; then rm -rf "$DIR/build/native/nativeCompile"; fi

# AOT 처리(processAot)의 입력에 환경변수는 없다 — 빌드 환경이 바뀌었으면 AOT 결과를 지워서 다시 하게 한다
ENV_MARK="$DIR/build/native/.buildenv"
if [ "$(cat "$ENV_MARK" 2>/dev/null || true)" != "$(printf %s "$BUILD_ENV_STAMP")" ]; then
  rm -rf "$DIR"/build/generated/aot* "$DIR"/build/classes/*/aot "$DIR/build/native/nativeCompile"
fi

echo "== $IMAGE 에서 $GRADLE_PATH (캐시 $GHOME)"
START=$(date +%s)
docker run "${ARGS[@]}" --entrypoint ./gradlew "$IMAGE" "${GRADLE_ARGS[@]}"
BIN="$DIR/build/native/nativeCompile/app"
mkdir -p "$DIR/build/native" && echo "$WANT" > "$PLATFORM_MARK" && printf %s "$BUILD_ENV_STAMP" > "$ENV_MARK"
[ -x "$BIN" ] || { echo "✗ 실행 파일이 없다: $BIN" >&2; exit 1; }
echo "OK $BIN ($(du -h "$BIN" | cut -f1), $(( $(date +%s) - START ))초) — 리눅스 실행 파일: 같은 아키텍처의 glibc 리눅스(컨테이너)에서만 돈다"
