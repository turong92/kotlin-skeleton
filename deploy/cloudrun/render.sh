#!/usr/bin/env bash
# 선언(deploy/app.yaml)을 Cloud Run 배포 입력 파일로 바꾼다 — deploy-cloudrun.yml 이 부른다. 필요 도구: yq(mikefarah v4) · jq.
#   사용: PLATFORM_ENV_JSON=… PLATFORM_SECRETS_JSON=… IMAGE_REPO=… IMAGE_TAG=… RUNTIME_SA=… render.sh <선언 경로> <출력 폴더>
#   출력: env.yaml · secrets.txt · build-env · build-args.txt · deploy-flags.txt · dockerfile · image (설명은 deploy/cloudrun/README.md)
#   종료 코드: 0 성공 · 2 입력 잘못(cloudrun 칸 없음 · 필수 칸 없음 · 선언과 플랫폼의 이름 겹침 · 잘못된 이름)
# 값(env)은 jq 로만 다룬다 — 셸 변수에 담아 풀어쓰면 공백 · 따옴표 · 줄바꿈이 깨진다.
set -euo pipefail

die() { echo "render.sh: $*" >&2; exit 2; }

[ $# -eq 2 ] || die "사용법: render.sh <선언 경로> <출력 폴더>"
DECL="$1"
OUT="$2"
[ -r "$DECL" ] || die "선언을 읽을 수 없다: $DECL"
for v in PLATFORM_ENV_JSON PLATFORM_SECRETS_JSON IMAGE_REPO IMAGE_TAG RUNTIME_SA; do
  [ -n "${!v:-}" ] || die "환경변수 $v 가 비었다"
done

command -v yq >/dev/null || die "yq 가 필요하다"
command -v jq >/dev/null || die "jq 가 필요하다"

DJ="$(yq -o=json '.' "$DECL")"
jq -e '.cloudrun | type == "object"' <<<"$DJ" >/dev/null || die "선언에 cloudrun 칸이 없다"
jq -e 'type == "object"' <<<"$PLATFORM_ENV_JSON" >/dev/null || die "PLATFORM_ENV_JSON 은 객체여야 한다"
jq -e 'type == "array"' <<<"$PLATFORM_SECRETS_JSON" >/dev/null || die "PLATFORM_SECRETS_JSON 은 목록이어야 한다"

jq -e 'has("port") and .port != null' <<<"$DJ" >/dev/null || die "필수 칸이 없다: port"
for f in dockerfile cpu memory min_instances max_instances concurrency timeout; do
  jq -e --arg f "$f" '.cloudrun | has($f) and .[$f] != null' <<<"$DJ" >/dev/null || die "필수 칸이 없다: cloudrun.$f"
done

# 이름 모양 — env 이름은 build-env 를 source 할 때 셸이 읽는다
bad="$(jq -r '[((.env // {}) | keys[]), ((.cloudrun.build_args // {}) | keys[])] | map(select(test("^[A-Za-z_][A-Za-z0-9_]*$") | not)) | .[]' <<<"$DJ")"
[ -z "$bad" ] || die "env · build_args 이름이 잘못됐다: $bad"
jq -e '[(.cloudrun.build_args // {}) | .[] | tostring | select(contains("\n"))] | length == 0' <<<"$DJ" >/dev/null || die "cloudrun.build_args 값에 줄바꿈을 쓸 수 없다"

# 선언과 플랫폼의 이름 겹침 — 이름만 말한다(값은 싣지 않는다)
dup="$(jq -r --argjson penv "$PLATFORM_ENV_JSON" --argjson psec "$PLATFORM_SECRETS_JSON" '
  ((.env // {}) | keys) as $e | ((.secrets // []) | map(tostring)) as $s
  | [ ($e[] | select(. as $k | ($penv | has($k)) or ($psec | index($k)))),
      ($s[] | select(. as $k | ($penv | has($k)) or ($psec | index($k)))),
      ($e[] | select(. as $k | $s | index($k))) ]
  | unique | .[]' <<<"$DJ")"
[ -z "$dup" ] || die "선언과 플랫폼의 이름이 겹친다: $(tr '\n' ' ' <<<"$dup")"

mkdir -p "$OUT"

# env.yaml — 선언 env + 플랫폼 env, 값은 전부 따옴표 문자열
jq --argjson penv "$PLATFORM_ENV_JSON" '
  def s: if . == null then "" else tostring end;
  ((.env // {}) | map_values(s)) + ($penv | map_values(s))' <<<"$DJ" \
  | yq -p=json -o=yaml '.[] style="double"' > "$OUT/env.yaml"

# secrets.txt — NAME=NAME:latest 쉼표 연결 한 줄(비면 빈 줄)
jq -r --argjson psec "$PLATFORM_SECRETS_JSON" \
  '(((.secrets // []) | map(tostring)) + ($psec | map(tostring))) | map("\(.)=\(.):latest") | join(",")' <<<"$DJ" > "$OUT/secrets.txt"

# build-env — 선언 env + 플랫폼 env(비밀 아님)를 셸이 그대로 읽을 수 있게(작은따옴표). 줄바꿈이 든 값은 여러 줄에 걸친다.
# 플랫폼 env 도 넣는 이유: 네이티브는 빌드 때 굳는 스위치(<P>_REDIS_LOCK_ENABLED · <P>_CLIENT_IP_MODE)를 빌드 환경에서 읽는다
jq -r --argjson penv "$PLATFORM_ENV_JSON" '((.env // {}) + $penv) | to_entries[] | "\(.key)=\((if .value == null then "" else (.value | tostring) end) | @sh)"' <<<"$DJ" > "$OUT/build-env"

# build-args.txt — 줄마다 `--build-arg K=V`
{
  jq -r '(.cloudrun.build_args // {}) | to_entries[] | "--build-arg \(.key)=\(.value | tostring)"' <<<"$DJ"
  echo "--build-arg BUILD_ENV_B64=$(base64 < "$OUT/build-env" | tr -d '\n')"
} > "$OUT/build-args.txt"

# deploy-flags.txt — 줄마다 플래그 하나
jq -r --arg sa "$RUNTIME_SA" '
  .cloudrun as $c
  | "--port=\(.port)", "--cpu=\($c.cpu)", "--memory=\($c.memory)",
    "--min-instances=\($c.min_instances)", "--max-instances=\($c.max_instances)",
    "--concurrency=\($c.concurrency)", "--timeout=\($c.timeout)", "--cpu-boost",
    "--service-account=\($sa)", "--allow-unauthenticated", "--region=asia-northeast3"' <<<"$DJ" > "$OUT/deploy-flags.txt"

jq -r '.cloudrun.dockerfile' <<<"$DJ" > "$OUT/dockerfile"
echo "$IMAGE_REPO:$IMAGE_TAG" > "$OUT/image"
