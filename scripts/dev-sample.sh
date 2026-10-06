#!/usr/bin/env bash
# 샘플 앱 "Notes" 풀스택 한 줄 실행: DB + 로컬 S3 → 백엔드(apps/sample) → 짝 프론트(react-skeleton 의 apps/sample).
#
#   scripts/dev-sample.sh                       # 백엔드 http://localhost:8080, 프론트 http://localhost:5173 (로그인 user@example.com / password)
#   REACT_DIR=~/work/react-skeleton scripts/dev-sample.sh
#   WEB_DIR= scripts/dev-sample.sh              # 백엔드만
#   SERVER_PORT=18080 DB_PORT=15432 S3_PORT=18333 WEB_DEV_ARGS="--port 15173" scripts/dev-sample.sh   # 포트가 겹칠 때
#
# 진짜 소셜 로그인 · 진짜 메일(docs/real-provider-setup.md): 저장소 루트의 `.env.local`(git 밖, `.env.example` 에서 복사)을 읽는다 — `ENV_FILE=other.env` 로 바꾼다.
#   값이 빈 줄은 환경에서 지운다 → 비워 두면 오늘과 똑같이(소셜 꺼짐 · 메일은 로컬 mailpit) 동작한다. 형식은 셸 문법(`NAME=값`, 공백 · & 가 있으면 따옴표).
# react-skeleton 은 이 레포와 나란히 둔다 (기본 ../react-skeleton). 끝내려면 Ctrl-C (프론트도 같이 내려간다), 컨테이너는 `scripts/dev.sh down`.
# 얇은 래퍼다 — 하는 일은 scripts/dev.sh 가 한다.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env.local}"
if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
  for name in $(grep -oE '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE" | tr -d '='); do [ -n "${!name:-}" ] || unset "$name"; done   # 빈 값은 "없음" — Boolean 칸에 빈 문자열이 들어가 기동이 깨지지 않게
  echo "== 환경 파일: $ENV_FILE ($(grep -cE '^[A-Za-z_][A-Za-z0-9_]*=.+' "$ENV_FILE") 개 값)"
fi
# 진짜 SMTP 가 없으면 로컬 메일 수신기(mailpit, 받은 메일 http://localhost:${MAIL_HTTP_PORT:-8025})를 올려 둔다 — 실패해도 개발은 계속한다
if [ -z "${SPRING_MAIL_HOST:-}" ] && [ -z "${DEV_DRY_RUN:-}" ] && [ "${1:-}" != down ]; then
  docker compose --profile mail up -d mail >/dev/null 2>&1 && echo "== 메일 수신기: http://localhost:${MAIL_HTTP_PORT:-8025}" || echo "== (mailpit 을 올리지 못했다 — 메일은 보내지지 않고 로그만 남는다)"
fi

export APP=sample
export WEB_DIR="${WEB_DIR-${REACT_DIR:-../react-skeleton}/apps/sample}"
export API_PROXY_TARGET="${API_PROXY_TARGET:-http://localhost:${SERVER_PORT:-8080}}"   # 프론트 Vite 프록시의 목적지
exec scripts/dev.sh "$@"
