#!/usr/bin/env bash
# CRaC 체크포인트 이미지를 만든다 (Dockerfile.crac · docs/native-image.md "CRaC") — 선택 · 실험.
#   scripts/crac-build.sh [--app <앱 디렉토리 이름>] [--tag <이미지:태그>] [--ffmpeg]
# 순서: (1) `base` 단계(앱 jar + Zulu CRaC JDK)를 만든다 (2) 그 이미지로 앱을 **체크포인트 환경으로 띄워** Warp 체크포인트를 `.crac/` 에 만든다 (3) 그 이미지 위에 체크포인트를 얹어 굳힌다.
# 환경변수: CRAC_BUILD_ENV(체크포인트 때 앱에 줄 환경변수, 공백 구분 KEY=VALUE — DB 주소 · 비밀번호 등. **이 값이 이미지에 굳는다**) · CRAC_DOCKER_ARGS(체크포인트 컨테이너의 docker run 인자, 예: "--add-host host.docker.internal:host-gateway")
# 앱은 DB 에 붙어야 뜬다(체크포인트가 빈 생성 뒤에 찍힌다) — 체크포인트용 DB 를 미리 띄워 둔다. 끝나면 `.crac/` 는 지워도 된다(이미지에 복사됐다).
set -euo pipefail
cd "$(dirname "$0")/.."

APP=api; TAG=""; FFMPEG=0
while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP="$2"; shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --ffmpeg) FFMPEG=1; shift ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
DIR="apps/$APP"; [ -d "$DIR" ] || DIR="tools/$APP"
[ -d "$DIR" ] || { echo "앱 디렉토리가 없다: apps/$APP · tools/$APP" >&2; exit 2; }
TAG="${TAG:-kotlin-skeleton-crac-$APP:latest}"

docker build -q -f Dockerfile.crac --target base --build-arg "APP_DIR=$DIR" --build-arg "FFMPEG=$FFMPEG" -t "$TAG-base" .
# 이전 체크포인트는 root 가 만들었다 — 컨테이너로 비운다
mkdir -p .crac && docker run --rm -v "$PWD/.crac:/c" --entrypoint sh "$TAG-base" -c 'rm -rf /c/* /c/.[a-z]*'
ENVARGS=(); for kv in ${CRAC_BUILD_ENV:-}; do ENVARGS+=(-e "$kv"); done
set +e
# 체크포인트가 끝나면 Warp 가 프로세스를 SIGKILL 한다 — 종료 코드 137 이 정상이다
docker run --rm -v "$PWD/.crac:/crac" ${CRAC_DOCKER_ARGS:-} ${ENVARGS[@]+"${ENVARGS[@]}"} "$TAG-base" \
  java -XX:CRaCEngine=warp -XX:CRaCCheckpointTo=/crac -Dspring.context.checkpoint=onRefresh -jar /app/app.jar
rc=$?
set -e
[ "$rc" = 137 ] && [ -f .crac/core.img ] || { echo "✗ 체크포인트가 만들어지지 않았다(종료 코드 $rc) — 위 로그의 CheckpointOpenSocketException(열린 소켓) · 'org.crac:crac' 안내를 본다" >&2; exit 1; }
docker run --rm -v "$PWD/.crac:/c" --entrypoint sh "$TAG-base" -c "chown -R $(id -u):$(id -g) /c"   # root 가 만든 파일을 docker build 컨텍스트로 읽게
# 체크포인트는 jar 를 다시 빌드하지 않도록 `.crac` 만 컨텍스트로 하는 작은 빌드로 얹는다 (jar 경로 · 내용은 체크포인트 때와 같아야 복원된다)
printf 'FROM %s\nCOPY . /opt/crac/\nENTRYPOINT ["java", "-XX:CRaCEngine=warp", "-XX:CRaCRestoreFrom=/opt/crac"]\n' "$TAG-base" | docker build -q -f - -t "$TAG" .crac >/dev/null
echo "OK $TAG (체크포인트 $(du -sh .crac | cut -f1)) — 실행: docker run --cap-drop ALL <옵션> $TAG   (기동 = 복원)"
