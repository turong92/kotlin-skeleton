#!/usr/bin/env bash
# bash deploy/cloudrun/tests/render.test.sh — render.sh 시험 (yq v4 · jq 필요). 사례마다 `ok <이름>` 을 찍는다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDER="$HERE/../render.sh"
FX="$HERE/fixtures"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT

export PLATFORM_ENV_JSON PLATFORM_SECRETS_JSON IMAGE_REPO IMAGE_TAG RUNTIME_SA
PLATFORM_ENV_JSON="$(cat "$FX/platform-env.json")"
PLATFORM_SECRETS_JSON="$(cat "$FX/platform-secrets.json")"
IMAGE_REPO="asia-northeast3-docker.pkg.dev/acme-proj/acme/acme"
IMAGE_TAG="v1.2.3"
RUNTIME_SA="acme-run@acme-proj.iam.gserviceaccount.com"

fail() { echo "FAIL $1" >&2; exit 1; }

# 종료 코드를 돌려준다(set -e 에 걸리지 않게)
code_of() { local c=0; "$@" >/dev/null 2>&1 || c=$?; echo "$c"; }

# render writes expected files for fixture
out="$TMP/basic"
bash "$RENDER" "$FX/app.yaml" "$out" || fail "render writes expected files for fixture (exit $?)"
for f in env.yaml secrets.txt build-env build-args.txt deploy-flags.txt dockerfile image; do
  diff -u "$FX/expected/$f" "$out/$f" || fail "render writes expected files for fixture ($f)"
done
echo "ok render writes expected files for fixture"

# render keeps tricky env values verbatim
out="$TMP/tricky"
bash "$RENDER" "$FX/app-tricky.yaml" "$out" || fail "render keeps tricky env values verbatim (exit $?)"
b64="$(sed -n 's/^--build-arg BUILD_ENV_B64=//p' "$out/build-args.txt")"
[ -n "$b64" ] || fail "BUILD_ENV_B64 없음"
printf '%s' "$b64" | base64 -d > "$TMP/build-env.decoded"
cmp -s "$TMP/build-env.decoded" "$out/build-env" || fail "BUILD_ENV_B64 가 build-env 와 다르다"
for k in K_SPACE K_QUOTE K_EQ K_COLON K_HASH K_SHELL K_NL; do
  want="$(yq -r ".env.$k" "$FX/app-tricky.yaml")"
  got_yaml="$(yq -r ".$k" "$out/env.yaml")"
  got_build="$(sh -c '. "$1"; eval "printf %s \"\$$2\""' _ "$TMP/build-env.decoded" "$k")"
  [ "$got_yaml" = "$want" ] || fail "env.yaml $k: want [$want] got [$got_yaml]"
  [ "$got_build" = "$want" ] || fail "build-env $k: want [$want] got [$got_build]"
done
echo "ok render keeps tricky env values verbatim"

# render accepts empty secrets
out="$TMP/nosecrets"
PLATFORM_SECRETS_JSON='[]' bash "$RENDER" "$FX/app-nosecrets.yaml" "$out" || fail "render accepts empty secrets (exit $?)"
[ "$(cat "$out/secrets.txt")" = "" ] && [ "$(wc -l < "$out/secrets.txt" | tr -d ' ')" = "1" ] || fail "빈 secrets.txt 는 빈 줄 하나"
echo "ok render accepts empty secrets"

# render fails 2 on env collision with platform
[ "$(code_of bash "$RENDER" "$FX/app-collision.yaml" "$TMP/collision")" = "2" ] || fail "render fails 2 on env collision with platform"
echo "ok render fails 2 on env collision with platform"

# render fails 2 on reserved build env names
[ "$(code_of bash "$RENDER" "$FX/app-reserved.yaml" "$TMP/reserved")" = "2" ] || fail "render fails 2 on reserved build env names"
echo "ok render fails 2 on reserved build env names"

# render fails 2 without cloudrun block
[ "$(code_of bash "$RENDER" "$FX/app-nocloudrun.yaml" "$TMP/nocr")" = "2" ] || fail "render fails 2 without cloudrun block"
echo "ok render fails 2 without cloudrun block"
