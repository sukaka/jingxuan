#!/usr/bin/env bash
set -euo pipefail
umask 077

PATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR=""
SKIP_BACKUP=false
ROLLBACK=false
TO_VERSION=""
USE_STANDALONE_COMPOSE=false

APP_SERVICES=(crm-api ai-worker web clamav-proxy)
BASE_INFRA_SERVICES=(mysql redis kafka minio clamav)

usage() {
  cat <<'EOF'
用法：
  # 离线增量升级（仅更新 4 个应用容器，不重启 MySQL/Redis/Kafka/MinIO/ClamAV/Caddy，不覆盖 .env 密钥与证书）
  ./apply-patch.sh <现有安装目录>
  ./apply-patch.sh --target <现有安装目录> [--skip-backup]
  ./apply-patch.sh                          # 自动探测当前目录或父目录是否含 .env 与 compose.yml

  # 一键回滚应用版本（需本地保留旧版 4 个应用镜像）
  ./apply-patch.sh --rollback <现有安装目录>
  ./apply-patch.sh --rollback --target <现有安装目录> [--to-version <版本号>] [--skip-backup]

参数说明：
  <现有安装目录>            已部署的鲸选美学 CRM 目录（含 .env 与 compose.yml）
  --target DIR             显式指定现有安装目录
  --skip-backup            跳过升级前自动备份 MySQL 与 .env（不建议生产使用）
  --rollback [DIR]         回滚应用容器至上一版本（读取 <安装目录>/runtime/last-release-version）
  --to-version VERSION     配合 --rollback 使用，显式指定回滚目标版本号（例如 2.0.2）
  -h, --help               显示本帮助信息

环境变量：
  STARTUP_TIMEOUT              容器健康等待超时秒数，默认 600
  BACKUP_ENCRYPTION_PASSPHRASE 设置后使用 AES-256-CBC 加密备份 .env（输出 .env.enc）

说明：
  1. 本脚本仅修改目标目录 .env 中的 RELEASE_VERSION，绝不覆盖任何密钥、端口、证书或访问地址配置。
  2. 升级默认自动备份 MySQL（backups/crm-pre-patch-*.sql.gz）与 .env（backups/crm-env-pre-patch-*.env[.enc]）。
  3. 如增量版本含数据库迁移，Flyway 迁移仅向前执行，应用回滚不等同于数据库结构回滚。
EOF
}

fail() {
  echo "错误：$*" >&2
  exit 1
}

require_opt_arg() {
  [[ $# -ge 2 && -n "${2:-}" ]] || fail "参数 $1 缺少值，使用 --help 查看帮助"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      require_opt_arg "$@"
      TARGET_DIR="$2"
      shift 2
      ;;
    --target=*)
      TARGET_DIR="${1#*=}"
      [[ -n "$TARGET_DIR" ]] || fail "参数 --target 缺少值，使用 --help 查看帮助"
      shift
      ;;
    --skip-backup)
      SKIP_BACKUP=true
      shift
      ;;
    --rollback)
      ROLLBACK=true
      if [[ $# -ge 2 && -n "${2:-}" && "${2:-}" != -* && -z "$TARGET_DIR" ]]; then
        TARGET_DIR="$2"
        shift 2
      else
        shift
      fi
      ;;
    --to-version)
      require_opt_arg "$@"
      TO_VERSION="$2"
      shift 2
      ;;
    --to-version=*)
      TO_VERSION="${1#*=}"
      [[ -n "$TO_VERSION" ]] || fail "参数 --to-version 缺少值，使用 --help 查看帮助"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      fail "未知参数 $1，使用 --help 查看帮助"
      ;;
    *)
      if [[ -z "$TARGET_DIR" ]]; then
        TARGET_DIR="$1"
        shift
      else
        fail "多余的位置参数：$1（已指定目标目录 $TARGET_DIR）"
      fi
      ;;
  esac
done

if [[ -n "$TO_VERSION" && "$ROLLBACK" != true ]]; then
  fail "--to-version 仅可配合 --rollback 使用"
fi

detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    USE_STANDALONE_COMPOSE=false
    return 0
  fi
  if command -v docker-compose >/dev/null 2>&1 && docker-compose version 2>/dev/null | grep -Eq '(^|[[:space:]])v?2\.'; then
    USE_STANDALONE_COMPOSE=true
    return 0
  fi
  return 1
}

compose() {
  if [[ "$USE_STANDALONE_COMPOSE" == true ]]; then
    docker-compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
  else
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
  fi
}

require_tools() {
  local tool
  for tool in docker curl gzip sed awk; do
    command -v "$tool" >/dev/null 2>&1 || fail "缺少命令 $tool"
  done
  if [[ -n "${BACKUP_ENCRYPTION_PASSPHRASE:-}" && "$SKIP_BACKUP" != true ]]; then
    command -v openssl >/dev/null 2>&1 || fail "已设置 BACKUP_ENCRYPTION_PASSPHRASE，但缺少 openssl 命令"
  fi
  detect_compose || fail "需要 Docker Compose v2（docker compose 插件或 docker-compose v2）"
  docker info >/dev/null 2>&1 || fail "无法连接 Docker，请启动 Docker（systemctl enable --now docker）并检查当前用户权限"
}

check_host_arch() {
  [[ "$(uname -s)" == "Linux" ]] || return 0
  local target_platform="linux/amd64"
  if [[ -f "$PATCH_DIR/manifest.txt" ]]; then
    local parsed
    parsed="$(sed -n 's|^TARGET_PLATFORM=||p' "$PATCH_DIR/manifest.txt" | tail -n 1)"
    if [[ -n "$parsed" ]]; then
      target_platform="$parsed"
    fi
  fi
  local host_arch expected_arch
  host_arch="$(uname -m)"
  case "$target_platform" in
    linux/amd64) expected_arch="x86_64" ;;
    linux/arm64) expected_arch="aarch64" ;;
    *) return 0 ;;
  esac
  if [[ "$host_arch" != "$expected_arch" ]]; then
    if [[ "$expected_arch" == "aarch64" && "$host_arch" == "arm64" ]]; then
      return 0
    fi
    fail "增量包目标架构为 ${target_platform}（需要 ${expected_arch}），当前服务器架构为 ${host_arch}，请使用匹配架构的增量更新包"
  fi
}

resolve_target_dir() {
  if [[ -n "$TARGET_DIR" ]]; then
    [[ -d "$TARGET_DIR" ]] || fail "指定的安装目录不存在：$TARGET_DIR"
    TARGET_DIR="$(cd "$TARGET_DIR" && pwd)"
    return 0
  fi

  local candidate
  for candidate in "$PWD" "$(cd "$PWD/.." 2>/dev/null && pwd || true)" "$(cd "$PATCH_DIR/.." 2>/dev/null && pwd || true)"; do
    if [[ -n "$candidate" && -f "$candidate/.env" && -f "$candidate/compose.yml" ]]; then
      TARGET_DIR="$candidate"
      echo "已自动探测现有安装目录：${TARGET_DIR}"
      return 0
    fi
  done

  fail "未在当前目录或父目录检测到 .env 与 compose.yml，请显式传入现有安装目录：./apply-patch.sh <现有安装目录>"
}

env_value() {
  sed -n "s|^$1=||p" "$ENV_FILE" | tail -n 1
}

set_env() {
  local key="$1"
  local value="$2"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || fail "$key 不能包含换行"
  local tmp_env
  tmp_env="$(mktemp "${ENV_FILE}.XXXXXX")"
  chmod 600 "$tmp_env"
  ENV_KEY="$key" ENV_VAL="$value" awk '
    BEGIN {
      key = ENVIRON["ENV_KEY"]
      val = ENVIRON["ENV_VAL"]
      prefix = key "="
      replaced = 0
    }
    index($0, prefix) == 1 && !replaced {
      print prefix val
      replaced = 1
      next
    }
    { print }
    END {
      if (!replaced) {
        print prefix val
      }
    }
  ' "$ENV_FILE" > "$tmp_env"
  mv "$tmp_env" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
}

load_target_config() {
  ENV_FILE="$TARGET_DIR/.env"
  COMPOSE_FILE="$TARGET_DIR/compose.yml"
  RUNTIME_DIR="$TARGET_DIR/runtime"
  CERTS_DIR="$RUNTIME_DIR/certs"
  BACKUPS_DIR="$TARGET_DIR/backups"
  LAST_VERSION_FILE="$RUNTIME_DIR/last-release-version"

  [[ -f "$ENV_FILE" ]] || fail "目标目录缺少 .env：$ENV_FILE"
  [[ -f "$COMPOSE_FILE" ]] || fail "目标目录缺少 compose.yml：$COMPOSE_FILE"
  if grep -q 'REPLACE_ON_INSTALL' "$ENV_FILE"; then
    fail "目标目录 .env 仍包含 REPLACE_ON_INSTALL 占位符，请先完成首次安装后再执行增量更新"
  fi

  URL="$(env_value PUBLIC_URL)"
  [[ -n "$URL" ]] || fail "目标目录 .env 缺少 PUBLIC_URL 配置"
  HTTP_PORT="$(env_value HTTP_PORT)"
  HTTP_PORT="${HTTP_PORT:-48080}"
  HTTPS_PORT="$(env_value HTTPS_PORT)"
  HTTPS_PORT="${HTTPS_PORT:-48443}"
  CURRENT_VERSION="$(env_value RELEASE_VERSION)"
  CURRENT_VERSION="${CURRENT_VERSION:-unknown}"
}

read_patch_version() {
  [[ -f "$PATCH_DIR/manifest.txt" ]] || fail "增量包缺少 manifest.txt：$PATCH_DIR/manifest.txt"
  PATCH_VERSION="$(sed -n 's|^PATCH_VERSION=||p' "$PATCH_DIR/manifest.txt" | tail -n 1)"
  if [[ -z "$PATCH_VERSION" ]]; then
    PATCH_VERSION="$(sed -n 's|^RELEASE_VERSION=||p' "$PATCH_DIR/manifest.txt" | tail -n 1)"
  fi
  [[ "$PATCH_VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || fail "manifest.txt 中的版本号格式不正确：${PATCH_VERSION:-<空>}"
}

is_service_running() {
  local svc="$1"
  local cid
  cid="$(compose ps -q "$svc" 2>/dev/null | head -n 1 || true)"
  [[ -n "$cid" ]] || return 1
  local state
  state="$(docker inspect --format '{{.State.Running}}' "$cid" 2>/dev/null || true)"
  [[ "$state" == "true" ]]
}

ensure_base_infra_running() {
  local stopped=()
  local svc
  for svc in "${BASE_INFRA_SERVICES[@]}"; do
    if ! is_service_running "$svc"; then
      stopped+=("$svc")
    fi
  done
  if [[ ${#stopped[@]} -gt 0 ]]; then
    echo "检测到以下基础中间件容器尚未运行，先启动以保障应用依赖健康（不重建既有容器）：${stopped[*]}"
    compose up -d --no-recreate --wait --wait-timeout "${STARTUP_TIMEOUT:-600}" "${stopped[@]}"
  fi
}

ensure_caddy_running() {
  if ! is_service_running caddy; then
    echo "检测到反向代理容器 caddy 尚未运行，正在启动（不重建既有配置）..."
    compose up -d --no-deps --no-recreate --wait --wait-timeout "${STARTUP_TIMEOUT:-600}" caddy
  fi
}

backup_database() {
  local ts="$1"
  mkdir -p "$BACKUPS_DIR"
  local destination="$BACKUPS_DIR/crm-pre-patch-${ts}.sql.gz"
  if ! compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction --quick --routines --triggers --hex-blob -uroot medical_crm' | gzip > "$destination"; then
    rm -f "$destination"
    fail "数据库备份失败，已中止升级（未修改 .env 或容器）"
  fi
  [[ -s "$destination" ]] || { rm -f "$destination"; fail "数据库备份为空，已中止升级"; }
  gzip -t "$destination" || { rm -f "$destination"; fail "数据库备份 gzip 校验失败，已中止升级"; }
  chmod 600 "$destination"
  BACKUP_SQL_FILE="$destination"
  echo "数据库备份完成：$destination"
}

backup_env() {
  local ts="$1"
  mkdir -p "$BACKUPS_DIR"
  if [[ -n "${BACKUP_ENCRYPTION_PASSPHRASE:-}" ]]; then
    local destination="$BACKUPS_DIR/crm-env-pre-patch-${ts}.env.enc"
    if ! openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt \
      -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in "$ENV_FILE" -out "$destination"; then
      rm -f "$destination"
      fail ".env 密钥文件加密备份失败，已中止升级"
    fi
    chmod 600 "$destination"
    BACKUP_ENV_FILE="$destination"
    echo "环境配置已加密备份：$destination"
  else
    local destination="$BACKUPS_DIR/crm-env-pre-patch-${ts}.env"
    cp "$ENV_FILE" "$destination"
    chmod 600 "$destination"
    BACKUP_ENV_FILE="$destination"
    echo "警告：未设置 BACKUP_ENCRYPTION_PASSPHRASE，已以明文（权限 600）备份 .env 到 ${destination}。" >&2
    echo "环境配置备份完成：$destination"
  fi
}

verify_app_images_present() {
  local ver="$1"
  local img
  local required_images=(
    "whale-crm/crm-api:${ver}"
    "whale-crm/web:${ver}"
    "whale-crm/ai-worker:${ver}"
    "whale-crm/clamav-proxy:${ver}"
  )
  for img in "${required_images[@]}"; do
    docker image inspect "$img" >/dev/null 2>&1 || fail "本地缺少应用镜像：${img}"
  done
}

load_patch_images() {
  local archive="$PATCH_DIR/images/application.tar.gz"
  local images_txt="$PATCH_DIR/images/images.txt"
  [[ -f "$archive" ]] || fail "增量包缺少应用镜像归档：$archive"
  [[ -f "$images_txt" ]] || fail "增量包缺少镜像清单：$images_txt"

  echo "正在载入应用镜像：$(basename "$archive")"
  docker load -i "$archive"

  local img
  while IFS= read -r img; do
    [[ -z "$img" ]] || docker image inspect "$img" >/dev/null 2>&1 || fail "镜像未正确载入：$img"
  done < "$images_txt"
  verify_app_images_present "$PATCH_VERSION"
}

record_last_version() {
  local prev_ver="$1"
  local target_ver="$2"
  [[ "$prev_ver" != "unknown" && -n "$prev_ver" ]] || return 0
  mkdir -p "$RUNTIME_DIR"
  chmod 755 "$RUNTIME_DIR"
  if [[ "$prev_ver" != "$target_ver" || ! -s "$LAST_VERSION_FILE" ]]; then
    printf '%s\n' "$prev_ver" > "$LAST_VERSION_FILE"
    chmod 644 "$LAST_VERSION_FILE"
  fi
}

rollout_app_services() {
  compose config --quiet
  echo "正在滚动更新 4 个应用容器（不重启 MySQL/Redis/Kafka/MinIO/ClamAV/Caddy）：${APP_SERVICES[*]}"
  compose up -d --no-deps --wait --wait-timeout "${STARTUP_TIMEOUT:-600}" "${APP_SERVICES[@]}"
  ensure_caddy_running
  compose ps
}

probe_url() {
  local target_url="$1"
  local curl_tls_args=()
  local ca_file=""
  if [[ "$target_url" == https://* && -s "$CERTS_DIR/ca.crt" ]]; then
    ca_file="$CERTS_DIR/ca.crt"
    curl_tls_args=(--cacert "$ca_file")
  fi
  if curl --noproxy '*' "${curl_tls_args[@]}" --fail --silent --max-time 15 -o /dev/null "$target_url" 2>/dev/null; then
    return 0
  fi
  if [[ "$target_url" == https://* ]] && command -v python3 >/dev/null 2>&1; then
    python3 - "$target_url" "$ca_file" <<'PY' >/dev/null 2>&1 && return 0 || true
import ssl, sys, urllib.request
url, ca = sys.argv[1], sys.argv[2]
ctx = ssl.create_default_context(cafile=ca or None)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ctx))
with opener.open(url, timeout=15) as resp:
    if resp.status >= 400:
        raise SystemExit(1)
PY
  fi
  return 1
}

check_access() {
  local attempt local_fallback_url
  if [[ "$URL" == https://* ]]; then
    local_fallback_url="https://127.0.0.1:${HTTPS_PORT}/login"
  else
    local_fallback_url="http://127.0.0.1:${HTTP_PORT}/login"
  fi
  for attempt in $(seq 1 40); do
    if probe_url "$URL/login"; then
      echo "HTTPS/访问探活通过：$URL/login"
      return 0
    fi
    if [[ ! "$URL" =~ ^https?://(127\.0\.0\.1|localhost)(:[0-9]+)?$ && "$attempt" -ge 3 ]]; then
      if probe_url "$local_fallback_url"; then
        echo "本机 HTTPS 回环探活通过：${local_fallback_url}（从本机直连 ${URL}/login 未通，常见于云主机弹性 IP 无回环或安全组策略，请从外部浏览器访问 ${URL}/login 验证）"
        return 0
      fi
    fi
    if (( attempt < 40 )); then sleep 5; fi
  done
  compose ps
  fail "应用容器已更新，但 $URL/login 暂不可访问。请检查应用与 Caddy 日志，或执行回滚：./apply-patch.sh --rollback \"$TARGET_DIR\""
}

BACKUP_SQL_FILE=""
BACKUP_ENV_FILE=""

run_upgrade() {
  require_tools
  check_host_arch
  resolve_target_dir
  load_target_config
  read_patch_version

  echo "============================================================"
  echo "鲸选美学 CRM 离线增量升级：${CURRENT_VERSION} -> ${PATCH_VERSION}"
  echo "目标安装目录：${TARGET_DIR}"
  echo "============================================================"

  ensure_base_infra_running

  if [[ "$SKIP_BACKUP" == true ]]; then
    echo "提示：已指定 --skip-backup，跳过 MySQL 与 .env 升级前备份。"
  else
    local ts
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    backup_database "$ts"
    backup_env "$ts"
  fi

  load_patch_images
  record_last_version "$CURRENT_VERSION" "$PATCH_VERSION"

  echo "仅原地更新 ${ENV_FILE} 中的 RELEASE_VERSION=${PATCH_VERSION}（其余密钥、端口与证书保持不变）"
  set_env RELEASE_VERSION "$PATCH_VERSION"

  rollout_app_services
  check_access

  cat <<EOF

============================================================
鲸选美学 CRM 离线增量升级完成
============================================================
安装目录：${TARGET_DIR}
版本变更：${CURRENT_VERSION} -> ${PATCH_VERSION}
已更新服务：${APP_SERVICES[*]}（MySQL/Redis/Kafka/MinIO/ClamAV/Caddy 均未重启）
访问地址：${URL}/login
上一版本记录：${LAST_VERSION_FILE}
EOF
  if [[ -n "$BACKUP_SQL_FILE" ]]; then
    echo "数据库备份：${BACKUP_SQL_FILE}"
  fi
  if [[ -n "$BACKUP_ENV_FILE" ]]; then
    echo "环境配置备份：${BACKUP_ENV_FILE}"
  fi
  cat <<EOF
一键回滚命令：
  ./apply-patch.sh --rollback "${TARGET_DIR}"
提示：若版本包含数据库迁移，Flyway 迁移仅向前执行，应用回滚不等同于数据库回滚。
============================================================
EOF
}

run_rollback() {
  require_tools
  resolve_target_dir
  load_target_config

  local rollback_ver="$TO_VERSION"
  if [[ -z "$rollback_ver" ]]; then
    [[ -s "$LAST_VERSION_FILE" ]] || fail "未找到上一版本记录（${LAST_VERSION_FILE}），请通过 --to-version <版本号> 显式指定回滚目标版本"
    rollback_ver="$(tr -d '\r\n ' < "$LAST_VERSION_FILE")"
  fi
  [[ "$rollback_ver" =~ ^[A-Za-z0-9._-]+$ ]] || fail "回滚版本号格式不正确：${rollback_ver:-<空>}"

  echo "============================================================"
  echo "鲸选美学 CRM 应用版本回滚：${CURRENT_VERSION} -> ${rollback_ver}"
  echo "目标安装目录：${TARGET_DIR}"
  echo "============================================================"

  verify_app_images_present "$rollback_ver"
  ensure_base_infra_running

  if [[ "$SKIP_BACKUP" != true ]]; then
    local ts
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    backup_env "$ts"
  fi

  echo "正在恢复 ${ENV_FILE} 中的 RELEASE_VERSION=${rollback_ver}（其余配置保持不变）"
  set_env RELEASE_VERSION "$rollback_ver"

  rollout_app_services
  check_access

  cat <<EOF

============================================================
鲸选美学 CRM 应用版本回滚完成
============================================================
安装目录：${TARGET_DIR}
版本回滚：${CURRENT_VERSION} -> ${rollback_ver}
已重启服务：${APP_SERVICES[*]}（中间件容器均未重启）
访问地址：${URL}/login
提示：应用回滚仅切换应用镜像版本，不会回滚已执行的 Flyway 数据库迁移。
============================================================
EOF
}

if [[ "$ROLLBACK" == true ]]; then
  run_rollback
else
  run_upgrade
fi
