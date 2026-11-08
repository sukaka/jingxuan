#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ARCHIVE="${1:?用法：verify-package.sh <安装包路径> [全新的验收目录]}"
WORK="${2:-$ROOT/tmp/installer-smoke}"
if [[ "$WORK" != /* ]]; then
  WORK="$ROOT/$WORK"
fi
case "$WORK" in
  "$ROOT"/tmp/*) ;;
  *) echo "验收目录必须位于项目 tmp 下" >&2; exit 2 ;;
esac
[[ ! -e "$WORK" ]] || { echo "验收目录已存在，请使用一个全新的目录，避免覆盖数据" >&2; exit 2; }
[[ -f "$ARCHIVE" && -f "$ARCHIVE.sha256" ]] || { echo "安装包或 SHA-256 文件不存在" >&2; exit 2; }

while IFS= read -r entry; do
  case "$entry" in
    */.env|*/initial-admin.txt|*.pem|*.key|*id_rsa*)
      echo "安装包包含不应分发的配置或密钥：$entry" >&2
      exit 1
      ;;
    */images/|*/images/images.txt|*/images/application.tar.gz|*/images/caddy.tar.gz|*/images/clamav.tar.gz|*/images/kafka.tar.gz|*/images/minio.tar.gz|*/images/mysql.tar.gz|*/images/redis.tar.gz)
      ;;
    */images/*)
      echo "安装包包含未知镜像文件：$entry" >&2
      exit 1
      ;;
  esac
done < <(tar -tzf "$ARCHIVE")

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$(dirname "$ARCHIVE")" && sha256sum -c "$(basename "$ARCHIVE").sha256")
else
  (cd "$(dirname "$ARCHIVE")" && shasum -a 256 -c "$(basename "$ARCHIVE").sha256")
fi

mkdir -p "$WORK"
tar -xzf "$ARCHIVE" -C "$WORK"
PACKAGE_DIR=""
for candidate in "$WORK"/whale-crm-*; do
  if [[ -d "$candidate" && -x "$candidate/install.sh" ]]; then
    [[ -z "$PACKAGE_DIR" ]] || { echo "安装包包含多个应用目录" >&2; exit 1; }
    PACKAGE_DIR="$candidate"
  fi
done
[[ -n "$PACKAGE_DIR" ]] || { echo "安装包缺少可执行 install.sh" >&2; exit 1; }

cleanup() {
  if [[ -f "$PACKAGE_DIR/.env" ]]; then
    if [[ "${DEBUG_INSTALL_ON_ERROR:-true}" == true ]]; then
      (cd "$PACKAGE_DIR" && docker compose --env-file .env -f compose.yml ps && \
        docker compose --env-file .env -f compose.yml logs --tail 80 kafka crm-api clamav) || true
    fi
    (cd "$PACKAGE_DIR" && ./install.sh stop) || true
  fi
}
trap cleanup EXIT

export CRM_ADMIN_PASSWORD="${CRM_ADMIN_PASSWORD:-Install-Smoke-Only-2026}"
export STARTUP_TIMEOUT=600
PROJECT_NAME="whale-crm-smoke-$(basename "$WORK" | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9_-]/_/g')"
"$PACKAGE_DIR/install.sh" install \
  --url http://127.0.0.1:18080 \
  --site-address http://127.0.0.1 \
  --files-url http://localhost:18080 \
  --files-site-address http://localhost \
  --http-port 18080 \
  --https-port 18443 \
  --project-name "$PROJECT_NAME"
if [[ "${CHECK_CLAMAV_UPDATE:-false}" == true ]]; then
  (cd "$PACKAGE_DIR" && docker compose --env-file .env -f compose.yml exec -T clamav sh -c \
    'database_updates_ready() {
      for database in main daily bytecode; do
        grep -E "${database}\.(cvd|cld).*(updated|up-to-date)" /var/log/clamav/freshclam.log >/dev/null 2>&1 || return 1
      done
    }
    for attempt in $(seq 1 60); do
      if clamdscan --ping 3 >/dev/null 2>&1 && database_updates_ready; then
        clamdscan --ping 3 && freshclam --version && echo "后台病毒库更新日志检查通过"
        exit 0
      fi
      sleep 5
    done
    tail -n 60 /var/log/clamav/freshclam.log
    exit 1')
fi
run_smoke() {
  python3 "$ROOT/infra/production/tests/runtime_smoke.py" \
    --url http://127.0.0.1:18080 --credentials "$PACKAGE_DIR/initial-admin.txt" --files
  if [[ "${RUN_BUSINESS_SMOKE:-false}" == true ]]; then
    python3 "$ROOT/infra/production/tests/business_smoke.py" \
      --url http://127.0.0.1:18080 --credentials "$PACKAGE_DIR/initial-admin.txt" \
      --compose-dir "$PACKAGE_DIR" --mock-knowledge
  fi
}

verify_backups() {
  local backup found=false
  for backup in "$PACKAGE_DIR"/backups/*.sql.gz; do
    [[ -f "$backup" ]] || continue
    found=true
    gzip -t "$backup"
  done
  [[ "$found" == true ]] || { echo "未生成数据库备份" >&2; exit 1; }
}

run_smoke
"$PACKAGE_DIR/install.sh" backup
verify_backups
"$PACKAGE_DIR/install.sh" upgrade
verify_backups
run_smoke
"$PACKAGE_DIR/install.sh" create-admin
"$PACKAGE_DIR/install.sh" status
"$PACKAGE_DIR/install.sh" stop
docker volume inspect "${PROJECT_NAME}_mysql_data" >/dev/null
"$PACKAGE_DIR/install.sh" start
python3 "$ROOT/infra/production/tests/runtime_smoke.py" \
  --url http://127.0.0.1:18080 --credentials "$PACKAGE_DIR/initial-admin.txt" --files
"$PACKAGE_DIR/install.sh" status
"$PACKAGE_DIR/install.sh" stop
docker volume inspect "${PROJECT_NAME}_mysql_data" >/dev/null
trap - EXIT
echo "离线安装、登录、文件链路、升级备份与启停验收通过"
if [[ "${RUN_BUSINESS_SMOKE:-false}" == true ]]; then
  echo "升级前后真实 API 核心业务及 Mock 知识同步验收通过（不含真实供应商）"
fi
