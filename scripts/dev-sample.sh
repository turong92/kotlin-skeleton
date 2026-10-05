#!/usr/bin/env bash
# 샘플 앱 "Notes" 풀스택 한 줄 실행: DB + 로컬 S3 → 백엔드(apps/sample) → 짝 프론트(react-skeleton 의 apps/sample).
#
#   scripts/dev-sample.sh                       # 백엔드 http://localhost:8080, 프론트 http://localhost:5173 (로그인 user@example.com / password)
#   REACT_DIR=~/work/react-skeleton scripts/dev-sample.sh
#   WEB_DIR= scripts/dev-sample.sh              # 백엔드만
#   SERVER_PORT=18080 DB_PORT=15432 S3_PORT=18333 WEB_DEV_ARGS="--port 15173" scripts/dev-sample.sh   # 포트가 겹칠 때
#
# react-skeleton 은 이 레포와 나란히 둔다 (기본 ../react-skeleton). 끝내려면 Ctrl-C (프론트도 같이 내려간다), 컨테이너는 `scripts/dev.sh down`.
# 얇은 래퍼다 — 하는 일은 scripts/dev.sh 가 한다.
set -euo pipefail
cd "$(dirname "$0")/.."

export APP=sample
export WEB_DIR="${WEB_DIR-${REACT_DIR:-../react-skeleton}/apps/sample}"
export API_PROXY_TARGET="${API_PROXY_TARGET:-http://localhost:${SERVER_PORT:-8080}}"   # 프론트 Vite 프록시의 목적지
exec scripts/dev.sh "$@"
