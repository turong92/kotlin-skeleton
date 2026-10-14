#!/usr/bin/env bash
# 로컬에서 프로젝트 둘(a.localhost · b.localhost) 견본을 띄운다 — `multi` 프로필 (docs/multi-project.md).
#   scripts/dev-multi.sh        # DB 컨테이너 → 프로젝트 계정 · 스키마 만들기(멱등) → ./gradlew :apps:api:bootRun --spring.profiles.active=local,multi
#   DEV_DRY_RUN=1 scripts/dev-multi.sh   # 만들 SQL 만 찍고 끝낸다
# 계정 · 스키마는 로컬 Docker Postgres 의 `app` 데이터베이스에 `proj_a` · `proj_b` 로 새로 만든다 — `public` 스키마는 건드리지 않는다. 비밀번호는 로컬 전용 값이다.
set -euo pipefail
cd "$(dirname "$0")/.."

SQL_TEMPLATE="scripts/project-account.sql"
# 프로젝트 이름 → 계정/스키마 (application-multi.yml 과 같아야 한다: 계정 = 스키마 이름, 비밀번호 = <이름>_dev_pw)
PROJECTS="proj_a proj_b"

sql_for() { sed -e "s/{{user}}/$1/g" -e "s/{{schema}}/$1/g" -e "s/{{password}}/$1_dev_pw/g" "$SQL_TEMPLATE"; }

if [ -n "${DEV_DRY_RUN:-}" ]; then for p in $PROJECTS; do sql_for "$p"; done; exit 0; fi

docker compose up -d --wait postgres
for p in $PROJECTS; do
  sql_for "$p" | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U "${POSTGRES_USER:-app}" -d "${POSTGRES_DB:-app}"
done
echo "== 백엔드(api, multi): http://a.localhost:${SERVER_PORT:-8080} · http://b.localhost:${SERVER_PORT:-8080}"
./gradlew :apps:api:bootRun --args='--spring.profiles.active=local,multi'
