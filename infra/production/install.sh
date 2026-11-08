#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
ENV_FILE="$ROOT/.env"
RUNTIME_DIR="$ROOT/runtime"
CREDENTIAL_FILE="$ROOT/initial-admin.txt"
BOOTSTRAP_ENV=""
URL=""
SITE_ADDRESS=""
FILES_URL=""
FILES_SITE_ADDRESS=""
HTTP_PORT="80"
HTTPS_PORT="443"
PROJECT_NAME="whale-crm"
ALLOW_HTTP=false
COMMAND="${1:-install}"
if [[ $# -gt 0 ]]; then shift; fi

usage() {
  cat <<'EOF'
用法：
  ./install.sh install --url https://crm.example.com [--files-url https://files.example.com]
  ./install.sh upgrade
  ./install.sh start|stop|status|logs|backup|create-admin

首次安装参数：
  --url URL                  系统访问地址，生产环境必须使用 HTTPS
  --site-address URL        Caddy 监听站点，默认与系统地址相同
  --files-url URL           文件公开访问地址，配置后启用上传下载
  --files-site-address URL  文件站点监听地址，默认与文件地址相同
  --http-port PORT          默认 80
  --https-port PORT         默认 443
  --project-name NAME       Docker Compose 项目名，默认 whale-crm
  --allow-insecure-http     明确允许非本机 HTTP（不建议生产使用）

本地验证可使用：--url http://127.0.0.1:18080 --site-address http://127.0.0.1 --http-port 18080
管理员密码只在 initial-admin.txt 中保存，不会打印到终端。
EOF
}

fail() { echo "错误：$*" >&2; exit 1; }
cleanup() { if [[ -n "$BOOTSTRAP_ENV" ]]; then rm -f "$BOOTSTRAP_ENV"; fi; }
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) URL="${2:-}"; shift 2 ;;
    --site-address) SITE_ADDRESS="${2:-}"; shift 2 ;;
    --files-url) FILES_URL="${2:-}"; shift 2 ;;
    --files-site-address) FILES_SITE_ADDRESS="${2:-}"; shift 2 ;;
    --http-port) HTTP_PORT="${2:-}"; shift 2 ;;
    --https-port) HTTPS_PORT="${2:-}"; shift 2 ;;
    --project-name) PROJECT_NAME="${2:-}"; shift 2 ;;
    --allow-insecure-http) ALLOW_HTTP=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "未知参数 $1，使用 --help 查看帮助" ;;
  esac
done

compose() {
  docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yml" "$@"
}

require_tools() {
  local tool
  for tool in docker openssl curl tar gzip sed; do
    command -v "$tool" >/dev/null 2>&1 || fail "缺少命令 $tool"
  done
  docker compose version >/dev/null 2>&1 || fail "需要 Docker Compose v2 插件"
  docker info >/dev/null 2>&1 || fail "无法连接 Docker，请启动 Docker 并检查当前用户权限"
}

env_value() {
  sed -n "s|^$1=||p" "$ENV_FILE" | tail -n 1
}

set_env() {
  local key="$1"
  local value="$2"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || fail "$key 不能包含换行"
  value="${value//\\/\\\\}"
  value="${value//&/\\&}"
  value="${value//|/\\|}"
  sed -i.bak "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  rm -f "$ENV_FILE.bak"
}

valid_url() {
  [[ "$1" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]
}

valid_site() {
  valid_url "$1"
}

validate_url() {
  local url="$1"
  local allow_http="$2"
  valid_url "$url" || fail "地址必须为不带路径的 http(s)://域名[:端口]，当前为 $url"
  if [[ "$url" == http://* && "$allow_http" != true && ! "$url" =~ ^http://(127\.0\.0\.1|localhost|[A-Za-z0-9.-]*\.localhost)(:[0-9]+)?$ ]]; then
    fail "生产访问地址必须使用 HTTPS；仅本地测试可使用 HTTP"
  fi
}

prepare_config() {
  [[ ! -f "$ENV_FILE" ]] || fail "已有 .env 配置，请使用 ./install.sh upgrade，避免覆盖密钥"
  [[ -f "$ROOT/.env.example" ]] || fail "安装包缺少 .env.example"
  if [[ -z "$URL" && -t 0 ]]; then
    read -r -p "请输入系统访问地址（例如 https://crm.example.com）：" URL
  fi
  [[ -n "$URL" ]] || fail "首次安装必须指定 --url"
  validate_url "$URL" "$ALLOW_HTTP"
  if [[ "$URL" == https://* && -z "$FILES_URL" ]]; then
    fail "正式安装必须通过 --files-url 配置文件域名，确保上传下载可用"
  fi
  SITE_ADDRESS="${SITE_ADDRESS:-$URL}"
  valid_site "$SITE_ADDRESS" || fail "--site-address 格式不正确"
  [[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail "项目名只能包含小写字母、数字、下划线和连字符"
  [[ "$HTTP_PORT" =~ ^[0-9]+$ && "$HTTPS_PORT" =~ ^[0-9]+$ ]] || fail "端口必须为数字"
  (( HTTP_PORT >= 1 && HTTP_PORT <= 65535 && HTTPS_PORT >= 1 && HTTPS_PORT <= 65535 )) || fail "端口范围为 1-65535"
  (( HTTP_PORT != HTTPS_PORT )) || fail "HTTP 与 HTTPS 端口不能相同"

  cp "$ROOT/.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  set_env COMPOSE_PROJECT_NAME "$PROJECT_NAME"
  set_env PUBLIC_URL "$URL"
  set_env CADDY_SITE_ADDRESS "$SITE_ADDRESS"
  set_env HTTP_PORT "$HTTP_PORT"
  set_env HTTPS_PORT "$HTTPS_PORT"
  if [[ "$URL" == https://* ]]; then
    set_env COOKIE_SECURE true
  else
    set_env COOKIE_SECURE false
    echo "提示：当前为 HTTP 验证模式，请勿用于正式公网业务数据。"
  fi
  if [[ -n "$FILES_URL" ]]; then
    validate_url "$FILES_URL" "$ALLOW_HTTP"
    [[ "${FILES_URL%%:*}" == "${URL%%:*}" ]] || fail "文件地址与系统地址必须同为 HTTPS"
    FILES_SITE_ADDRESS="${FILES_SITE_ADDRESS:-$FILES_URL}"
    valid_site "$FILES_SITE_ADDRESS" || fail "--files-site-address 格式不正确"
    set_env FILES_ENABLED true
    set_env FILES_PUBLIC_ENDPOINT "$FILES_URL"
    set_env FILES_SITE_ADDRESS "$FILES_SITE_ADDRESS"
  fi
  set_env MYSQL_ROOT_PASSWORD "$(openssl rand -hex 24)"
  set_env MYSQL_PASSWORD "$(openssl rand -hex 24)"
  set_env REDIS_PASSWORD "$(openssl rand -hex 24)"
  set_env MINIO_ROOT_USER "crm_$(openssl rand -hex 12)"
  set_env MINIO_ROOT_PASSWORD "$(openssl rand -hex 32)"
  set_env JWT_SECRET "$(openssl rand -hex 48)"
  set_env APP_AES_KEY_BASE64 "$(openssl rand -base64 32 | tr -d '\n')"
  set_env APP_BLIND_INDEX_PEPPER "$(openssl rand -hex 48)"
  set_env AI_INTERNAL_TOKEN "$(openssl rand -hex 32)"
  grep -q '^RELEASE_VERSION=' "$ENV_FILE" || fail "配置缺少 RELEASE_VERSION"
  if grep -q 'REPLACE_ON_INSTALL' "$ENV_FILE"; then
    rm -f "$ENV_FILE"
    fail "密钥生成不完整，未启动任何服务"
  fi
}

load_existing_config() {
  [[ -f "$ENV_FILE" ]] || fail "未找到 .env，请先执行 install"
  URL="$(env_value PUBLIC_URL)"
  FILES_URL="$(env_value FILES_PUBLIC_ENDPOINT)"
  [[ -n "$URL" ]] || fail "配置缺少 PUBLIC_URL"
  grep -q 'REPLACE_ON_INSTALL' "$ENV_FILE" && fail "请先替换 .env 中的安装占位符"
  return 0
}

load_images() {
  [[ -f "$ROOT/images/images.txt" ]] || fail "安装包缺少 images/images.txt，不能静默联网拉取镜像"
  local archive image
  for archive in "$ROOT"/images/*.tar.gz; do
    [[ -f "$archive" ]] || fail "安装包缺少镜像归档"
    echo "载入镜像 $(basename "$archive")"
    docker load --input "$archive"
  done
  while IFS= read -r image; do
    [[ -z "$image" ]] || docker image inspect "$image" >/dev/null 2>&1 || fail "镜像未正确载入：$image"
  done < "$ROOT/images/images.txt"
}

render_caddy() {
  mkdir -p "$RUNTIME_DIR"
  cat "$ROOT/Caddyfile.core" > "$RUNTIME_DIR/Caddyfile"
  if [[ "$(env_value FILES_ENABLED)" == true ]]; then
    [[ -n "$(env_value FILES_PUBLIC_ENDPOINT)" ]] || fail "启用文件功能时必须配置 FILES_PUBLIC_ENDPOINT"
    [[ -n "$(env_value FILES_SITE_ADDRESS)" ]] || fail "启用文件功能时必须配置 FILES_SITE_ADDRESS"
    cat "$ROOT/Caddyfile.files" >> "$RUNTIME_DIR/Caddyfile"
  fi
  chmod 644 "$RUNTIME_DIR/Caddyfile"
}

start_stack() {
  compose config --quiet
  compose up -d --remove-orphans --wait --wait-timeout "${STARTUP_TIMEOUT:-600}"
  compose ps
}

create_admin() {
  if [[ -f "$CREDENTIAL_FILE" ]]; then
    echo "管理员凭据文件已存在，不重复创建或覆盖账号。"
    return 0
  fi
  local username="${CRM_ADMIN_USERNAME:-admin}"
  local display_name="${CRM_ADMIN_DISPLAY_NAME:-系统管理员}"
  local password="${CRM_ADMIN_PASSWORD:-$(openssl rand -hex 18)}"
  [[ "$username" =~ ^[A-Za-z0-9_.-]{3,32}$ ]] || fail "管理员登录名格式不正确"
  [[ "$password" =~ ^[A-Za-z0-9@._-]{12,64}$ ]] || fail "管理员密码需为 12-64 位字母、数字或 @._-"
  [[ "$display_name" != *$'\n'* && "$display_name" != *$'\r'* ]] || fail "管理员显示名不能换行"
  mkdir -p "$RUNTIME_DIR"
  BOOTSTRAP_ENV="$(mktemp "$RUNTIME_DIR/bootstrap.XXXXXX")"
  chmod 600 "$BOOTSTRAP_ENV"
  printf 'BOOTSTRAP_ADMIN_USERNAME=%s\nBOOTSTRAP_ADMIN_DISPLAY_NAME=%s\nBOOTSTRAP_ADMIN_PASSWORD=%s\n' \
    "$username" "$display_name" "$password" > "$BOOTSTRAP_ENV"
  local output
  if ! output="$(compose run --rm --no-deps -T --env-from-file "$BOOTSTRAP_ENV" \
    --entrypoint java crm-api \
    -cp /app/app.jar \
    -Dloader.main=com.whale.crm.platform.bootstrap.BootstrapAdminCommand \
    org.springframework.boot.loader.launch.PropertiesLauncher)"; then
    printf '%s\n' "$output" >&2
    return 1
  fi
  printf '%s\n' "$output"
  rm -f "$BOOTSTRAP_ENV"
  BOOTSTRAP_ENV=""
  if [[ "$output" == *"创建成功"* ]]; then
    printf 'username=%s\npassword=%s\n' "$username" "$password" > "$CREDENTIAL_FILE"
    chmod 600 "$CREDENTIAL_FILE"
    echo "管理员已初始化，凭据保存在 ${CREDENTIAL_FILE}（权限 600），请妥善保管。"
  else
    echo "管理员已存在，未修改账号，也未生成新的初始密码。"
  fi
}

check_access() {
  local attempt
  for attempt in $(seq 1 40); do
    if curl --fail --silent --show-error --max-time 15 -o /dev/null "$URL/login"; then
      echo "访问检查通过：$URL/login"
      return 0
    fi
    if (( attempt < 40 )); then sleep 5; fi
  done
  compose ps
  fail "服务已启动，但 $URL/login 暂不可访问。请检查域名解析、防火墙和 Caddy 日志"
}

backup_database() {
  mkdir -p "$ROOT/backups"
  local destination="$ROOT/backups/crm-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  if ! compose exec -T mysql sh -c 'exec mysqldump --single-transaction --quick --routines --triggers --hex-blob -uroot -p"$MYSQL_ROOT_PASSWORD" medical_crm' | gzip > "$destination"; then
    rm -f "$destination"
    fail "数据库备份失败，未继续升级"
  fi
  [[ -s "$destination" ]] || { rm -f "$destination"; fail "数据库备份为空，未继续升级"; }
  chmod 600 "$destination"
  echo "数据库备份完成：$destination"
}

case "$COMMAND" in
  install)
    require_tools
    prepare_config
    load_images
    render_caddy
    start_stack
    create_admin
    check_access
    echo "安装完成。首次登录后请创建正式运营账号，并妥善保管管理员凭据。"
    ;;
  upgrade)
    require_tools
    load_existing_config
    backup_database
    load_images
    render_caddy
    start_stack
    check_access
    echo "升级完成。数据库迁移只向前执行，应用回滚不等于数据库回滚。"
    ;;
  start)
    require_tools
    load_existing_config
    render_caddy
    start_stack
    create_admin
    check_access
    ;;
  stop)
    load_existing_config
    compose down
    echo "服务已停止，数据卷保留。"
    ;;
  status)
    load_existing_config
    compose ps
    ;;
  logs)
    load_existing_config
    if [[ -n "${1:-}" ]]; then
      compose logs --tail "${LOG_LINES:-100}" "$1"
    else
      compose logs --tail "${LOG_LINES:-100}"
    fi
    ;;
  backup)
    require_tools
    load_existing_config
    backup_database
    ;;
  create-admin)
    require_tools
    load_existing_config
    create_admin
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    fail "未知命令 $COMMAND，使用 --help 查看帮助"
    ;;
esac
