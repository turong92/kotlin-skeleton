#!/usr/bin/env bash
# 일회용: 이미 있는 DB 의 옛 행 id(접두사 + 32자 16진수 · UUID v4)를 시간순 id(접두사 + ULID · UUIDv7)로 바꾼다. PostgreSQL 전용.
# 하는 일 · 바꾸지 않는 것 · 검사: scripts/convert-ids-to-sortable.sql 머리말. 새 DB 에는 필요 없다(새 id 는 처음부터 시간순).
#
# 순서
#   1. 앱을 내린다(전부 — 바꾸는 동안 쓰는 쪽이 없어야 한다).
#   2. 새 빌드를 한 번 띄워 Flyway 가 V20261012153000(payment_subscriptions.dedupe_key)을 적용하게 하고 다시 내린다. (없으면 스크립트가 알려 주고 멈춘다.)
#   3. 리허설(되돌린다):   scripts/convert-ids-to-sortable.sh --container kotlin-skeleton-postgres-1 --dry-run
#   4. 실제:               scripts/convert-ids-to-sortable.sh --container kotlin-skeleton-postgres-1
#   5. 앱을 올린다. 로그인이 풀려 있으면(액세스 토큰의 계정 id 가 옛 값) 다시 로그인한다.
#
# 연결
#   --container NAME   docker 컨테이너 안의 psql · pg_dump 를 쓴다 (기본 사용자 app · DB app · --user · --db 로 바꾼다)
#   --url URL          호스트의 psql · pg_dump 를 쓴다 (postgresql://user:pw@host:port/db)
# 백업
#   바꾸기 전에 pg_dump -Fc 를 <backup-dir>(기본 ./.id-conversion-backups)/ids-before-<시각>.dump 에 쓰고 경로를 출력한다. --dry-run 에서도 만든다.
#   되돌리기: docker exec -i NAME pg_restore -U app -d app --clean --if-exists < 그_파일   (앱을 내린 채로)
# 그 밖
#   --dry-run          모든 변환과 검사를 하고 마지막에 되돌린다 — 검사 출력만 본다.
#   --app-port N / --force   앱이 떠 있으면(기본 127.0.0.1:8080 이 열려 있으면) 멈춘다. 포트를 바꾸거나 건너뛴다.
#   --no-backup        백업을 건너뛴다(리허설 DB 에서만).
set -euo pipefail

app_port=8080 force=0 container="" url="" user="app" db="app" dry=0 backup=1
backup_dir="./.id-conversion-backups"
here="$(cd "$(dirname "$0")" && pwd)"

while [ $# -gt 0 ]; do
  case "$1" in
    --container) container="$2"; shift 2 ;;
    --url) url="$2"; shift 2 ;;
    --user) user="$2"; shift 2 ;;
    --db) db="$2"; shift 2 ;;
    --backup-dir) backup_dir="$2"; shift 2 ;;
    --dry-run) dry=1; shift ;;
    --app-port) app_port="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --no-backup) backup=0; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "알 수 없는 인자: $1 (--help)" >&2; exit 2 ;;
  esac
done

if [ -n "$container" ] && [ -z "$url" ]; then
  psql_run() { docker exec -i "$container" psql -X -q -U "$user" -d "$db" "$@"; }
  dump_run() { docker exec "$container" pg_dump -U "$user" -Fc "$db"; }
elif [ -n "$url" ] && [ -z "$container" ]; then
  psql_run() { psql -X -q "$url" "$@"; }
  dump_run() { pg_dump -Fc "$url"; }
else
  echo "--container NAME 또는 --url URL 중 하나를 준다 (--help)" >&2; exit 2
fi

if [ "$force" = 0 ] && (exec 3<>"/dev/tcp/127.0.0.1/$app_port") 2>/dev/null; then
  echo "127.0.0.1:$app_port 에서 뭔가 떠 있다 — 앱을 내린 뒤 다시 실행한다 (다른 포트면 --app-port, 무시하려면 --force)" >&2; exit 1
fi
psql_run -At -v ON_ERROR_STOP=1 -c "select 1" > /dev/null || { echo "DB 에 연결하지 못했다" >&2; exit 1; }

mkdir -p "$backup_dir"
if [ "$backup" = 1 ]; then
  file="$backup_dir/ids-before-$(date +%Y%m%d-%H%M%S).dump"
  dump_run > "$file"
  echo "백업: $(cd "$(dirname "$file")" && pwd)/$(basename "$file") ($(wc -c < "$file" | tr -d ' ') 바이트)"
fi

echo "변환을 시작한다$([ "$dry" = 1 ] && echo ' (--dry-run: 끝에서 되돌린다)')"
psql_run -v ON_ERROR_STOP=1 -v "dry=$dry" < "$here/convert-ids-to-sortable.sql"
echo "끝났다."
