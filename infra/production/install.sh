#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
ENV_FILE="$ROOT/.env"
RUNTIME_DIR="$ROOT/runtime"
CERTS_DIR="$RUNTIME_DIR/certs"
CREDENTIAL_FILE="$ROOT/initial-admin.txt"
BOOTSTRAP_ENV=""
URL=""
SITE_ADDRESS=""
FILES_URL=""
FILES_SITE_ADDRESS=""
FILES_PROXY_MODE=""
TLS_MODE=""
TLS_CERT_SRC=""
TLS_KEY_SRC=""
IP_ADDR=""
AUTO_IP=false
HTTP_PORT="48080"
HTTPS_PORT="48443"
HTTP_PORT_SET=false
HTTPS_PORT_SET=false
PROJECT_NAME="whale-crm"
ALLOW_HTTP=false
OPEN_FIREWALL=false
SKIP_PORT_CHECK=false
USE_STANDALONE_COMPOSE=false
COMMAND="${1:-install}"
if [[ $# -gt 0 ]]; then shift; fi
POSITIONAL_ARGS=()

usage() {
  cat <<'EOF'
用法：
  # 欧拉（openEuler）40000+ 高位端口默认 HTTPS 一键安装（默认 HTTPS 48443，不提供明文 HTTP 服务）
  ./install.sh
  ./install.sh install --ip <服务器IP> [--https-port 48443] [--http-port 48080] [--open-firewall]
  ./install.sh install --auto-ip [--https-port 48443] [--open-firewall]

  # 使用自有商业/企业 SSL 证书部署高位端口 HTTPS
  ./install.sh install --ip <服务器IP> --https-port 48443 --tls-cert /path/fullchain.pem --tls-key /path/privkey.pem
  ./install.sh install --url https://crm.example.com:48443 --https-port 48443 --tls-cert /path/fullchain.pem --tls-key /path/privkey.pem

  # 公网标准域名 HTTPS 安装（使用独立系统域名与文件域名，Caddy 自动签发证书）
  ./install.sh install --url https://crm.example.com --files-url https://files.example.com --http-port 80 --https-port 443

  # 日常维护与升级
  ./install.sh upgrade
  ./install.sh start|stop|status|backup|create-admin
  ./install.sh logs [服务名 ...]

首次安装参数：
  --ip IP                   服务器 IPv4（一键启用 https://<IP>:<HTTPS_PORT> 与同源 HTTPS 文件上传下载）
  --auto-ip                 自动探测服务器本机 IPv4 并以 HTTPS 一键安装（适合无人值守）
  --url URL                 系统访问地址，默认必须使用 HTTPS
  --site-address URL        Caddy 监听站点，默认与系统地址相同（高位端口同源 HTTPS 模式默认 :443）
  --files-url URL           文件公开访问地址，配置后启用上传下载（高位端口/IP 模式自动同源 HTTPS）
  --files-site-address URL  文件站点监听地址，默认与文件地址相同
  --https-port PORT         宿主机 HTTPS 访问端口，默认 48443（40000+ 高位端口，避免与其他系统冲突）
  --http-port PORT          宿主机 HTTP 重定向端口，默认 48080（仅 308 跳转至 HTTPS，不提供明文业务访问）
  --tls-cert FILE           自定义 SSL 证书文件路径（PEM/fullchain）；未指定时在高位端口/IP 模式下自动离线生成本地 CA+SAN 证书
  --tls-key FILE            自定义 SSL 私钥文件路径（PEM，与 --tls-cert 成对使用）
  --project-name NAME       Docker Compose 项目名，默认 whale-crm（隔离容器/卷/网络）
  --open-firewall           检测到 openEuler firewalld 运行时自动放行 HTTPS 端口（需 root）
  --allow-insecure-http     仅限显式本地/隔离调试允许 HTTP（默认禁止）
  --skip-port-check         跳过安装前宿主机端口占用预检

端口与隔离说明：
  系统默认仅通过 HTTPS（默认 48443）对外提供访问，COOKIE_SECURE 强制为 true，HTTP 端口仅作 HTTPS 跳转。
  MySQL、Redis、Kafka、MinIO、ClamAV、crm-api、ai-worker、web 全部运行在独立 Docker 内部网络，不占用宿主机端口。

备份说明（backup / upgrade）：
  同时备份 MySQL 数据库（backups/crm-*.sql.gz）、MinIO 对象存储（backups/crm-minio-*.tar.gz）
  及 .env 密钥文件（设置 BACKUP_ENCRYPTION_PASSPHRASE 时加密为 .env.enc）。

管理员密码只在 initial-admin.txt 中保存，不会打印到终端。
EOF
}

fail() { echo "错误：$*" >&2; exit 1; }
cleanup() { if [[ -n "$BOOTSTRAP_ENV" ]]; then rm -f "$BOOTSTRAP_ENV"; fi; }
trap cleanup EXIT

require_opt_arg() {
  [[ $# -ge 2 && -n "${2:-}" ]] || fail "参数 $1 缺少值，使用 --help 查看帮助"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip) require_opt_arg "$@"; IP_ADDR="$2"; shift 2 ;;
    --auto-ip) AUTO_IP=true; shift ;;
    --url) require_opt_arg "$@"; URL="$2"; shift 2 ;;
    --site-address) require_opt_arg "$@"; SITE_ADDRESS="$2"; shift 2 ;;
    --files-url) require_opt_arg "$@"; FILES_URL="$2"; shift 2 ;;
    --files-site-address) require_opt_arg "$@"; FILES_SITE_ADDRESS="$2"; shift 2 ;;
    --http-port) require_opt_arg "$@"; HTTP_PORT="$2"; HTTP_PORT_SET=true; shift 2 ;;
    --https-port) require_opt_arg "$@"; HTTPS_PORT="$2"; HTTPS_PORT_SET=true; shift 2 ;;
    --tls-cert) require_opt_arg "$@"; TLS_CERT_SRC="$2"; shift 2 ;;
    --tls-key) require_opt_arg "$@"; TLS_KEY_SRC="$2"; shift 2 ;;
    --project-name) require_opt_arg "$@"; PROJECT_NAME="$2"; shift 2 ;;
    --open-firewall) OPEN_FIREWALL=true; shift ;;
    --allow-insecure-http) ALLOW_HTTP=true; shift ;;
    --skip-port-check) SKIP_PORT_CHECK=true; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) fail "未知参数 $1，使用 --help 查看帮助" ;;
    *) POSITIONAL_ARGS+=("$1"); shift ;;
  esac
done

if [[ "$COMMAND" == "logs" ]]; then
  if [[ ${#POSITIONAL_ARGS[@]} -gt 0 ]]; then
    for svc in "${POSITIONAL_ARGS[@]}"; do
      [[ "$svc" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail "非法的服务名：$svc"
    done
  fi
elif [[ "$COMMAND" != "-h" && "$COMMAND" != "--help" && "$COMMAND" != "help" ]]; then
  if [[ ${#POSITIONAL_ARGS[@]} -gt 0 ]]; then
    fail "命令 $COMMAND 不接受位置参数：${POSITIONAL_ARGS[*]}"
  fi
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
    docker-compose --env-file "$ENV_FILE" -f "$ROOT/compose.yml" "$@"
  else
    docker compose --env-file "$ENV_FILE" -f "$ROOT/compose.yml" "$@"
  fi
}

require_tools() {
  local tool
  for tool in docker openssl curl tar gzip sed awk; do
    command -v "$tool" >/dev/null 2>&1 || fail "缺少命令 $tool"
  done
  detect_compose || fail "需要 Docker Compose v2（docker compose 插件或 docker-compose v2）"
  docker info >/dev/null 2>&1 || fail "无法连接 Docker，请启动 Docker（systemctl enable --now docker）并检查当前用户权限"
}

check_host_arch() {
  [[ -f "$ROOT/manifest.txt" ]] || return 0
  [[ "$(uname -s)" == "Linux" ]] || return 0
  local target_platform host_arch expected_arch
  target_platform="$(sed -n 's|^TARGET_PLATFORM=||p' "$ROOT/manifest.txt" | tail -n 1)"
  [[ -n "$target_platform" ]] || return 0
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
    fail "安装包目标架构为 ${target_platform}（需要 ${expected_arch}），当前服务器架构为 ${host_arch}，请使用匹配架构的安装包"
  fi
}

check_linux_runtime_hints() {
  [[ "$(uname -s)" == "Linux" ]] || return 0
  if [[ -r /proc/sys/net/ipv4/ip_forward ]]; then
    local ip_fwd
    ip_fwd="$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || true)"
    if [[ "$ip_fwd" == "0" ]]; then
      echo "警告：检测到 net.ipv4.ip_forward=0，可能导致容器网络异常。建议执行：sysctl -w net.ipv4.ip_forward=1" >&2
    fi
  fi
  if [[ -r /proc/meminfo ]]; then
    local mem_kb mem_mb
    mem_kb="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
    mem_mb="$(( mem_kb / 1024 ))"
    if (( mem_mb > 0 && mem_mb < 6144 )); then
      echo "提示：当前服务器物理内存约 ${mem_mb} MB，建议 8 GB 以上以保障 MySQL/Kafka/ClamAV 稳定运行。" >&2
    fi
  fi
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
}

valid_ipv4() {
  local ip="$1"
  [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  local o1 o2 o3 o4
  IFS='.' read -r o1 o2 o3 o4 <<< "$ip"
  (( 10#$o1 >= 1 && 10#$o1 <= 255 && 10#$o2 >= 0 && 10#$o2 <= 255 && 10#$o3 >= 0 && 10#$o3 <= 255 && 10#$o4 >= 0 && 10#$o4 <= 255 ))
}

detect_host_ip() {
  local ip=""
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") { print $(i+1); exit }}')"
  fi
  if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ && $i !~ /^127\./ && $i !~ /^172\.17\./) { print $i; exit }}')"
  fi
  if [[ -n "$ip" ]] && valid_ipv4 "$ip"; then
    printf '%s\n' "$ip"
    return 0
  fi
  return 1
}

extract_url_host() {
  local raw="$1"
  raw="${raw#http://}"
  raw="${raw#https://}"
  printf '%s\n' "${raw%%:*}"
}

valid_url() {
  [[ "$1" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]
}

valid_site() {
  [[ "$1" == ":443" || "$1" == "https://:443" || "$1" == ":80" || "$1" == "http://:80" ]] || valid_url "$1"
}

validate_url() {
  local url="$1"
  local allow_http="$2"
  valid_url "$url" || fail "地址必须为不带路径的 https://域名或IP[:端口]，当前为 $url"
  if [[ "$url" == http://* && "$allow_http" != true && ! "$url" =~ ^http://(127\.0\.0\.1|localhost|[A-Za-z0-9.-]*\.localhost)(:[0-9]+)?$ ]]; then
    fail "生产环境必须使用 HTTPS（不允许明文 HTTP）；请使用 --ip <服务器IP> 或 https:// 地址"
  fi
}

port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltnH 2>/dev/null | awk -v p=":$port" '
      {
        addr = $4
        if (length(addr) >= length(p) && substr(addr, length(addr) - length(p) + 1) == p) {
          found = 1
          exit 0
        }
      }
      END { exit(found ? 0 : 1) }
    '
    return $?
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  fi
  if command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | awk -v p1=":$port" -v p2=".$port" '
      $1 ~ /^tcp/ {
        addr = $4
        if ((length(addr) >= length(p1) && substr(addr, length(addr) - length(p1) + 1) == p1) ||
            (length(addr) >= length(p2) && substr(addr, length(addr) - length(p2) + 1) == p2)) {
          found = 1
          exit 0
        }
      }
      END { exit(found ? 0 : 1) }
    '
    return $?
  fi
  return 1
}

check_ports_available() {
  [[ "$SKIP_PORT_CHECK" != true ]] || return 0
  local port label
  for item in "HTTPS:${HTTPS_PORT}" "HTTP:${HTTP_PORT}"; do
    label="${item%%:*}"
    port="${item#*:}"
    if port_in_use "$port"; then
      fail "宿主机 ${label} 端口 ${port} 已被服务器上的其他服务占用，请通过 --https-port / --http-port 指定其他 40000+ 空闲端口"
    fi
  done
}

prepare_config() {
  [[ ! -f "$ENV_FILE" ]] || fail "已有 .env 配置，请使用 ./install.sh upgrade，避免覆盖密钥"
  [[ -f "$ROOT/.env.example" ]] || fail "安装包缺少 .env.example"

  if [[ -n "$TLS_CERT_SRC" || -n "$TLS_KEY_SRC" ]]; then
    [[ -n "$TLS_CERT_SRC" && -n "$TLS_KEY_SRC" ]] || fail "--tls-cert 与 --tls-key 必须同时指定"
    [[ -r "$TLS_CERT_SRC" ]] || fail "无法读取证书文件：$TLS_CERT_SRC"
    [[ -r "$TLS_KEY_SRC" ]] || fail "无法读取私钥文件：$TLS_KEY_SRC"
  fi

  if [[ "$AUTO_IP" == true && -z "$IP_ADDR" && -z "$URL" ]]; then
    IP_ADDR="$(detect_host_ip || true)"
    [[ -n "$IP_ADDR" ]] || fail "无法自动探测服务器 IPv4，请通过 --ip <服务器IP> 显式指定"
    echo "已自动探测服务器 IP：${IP_ADDR}"
  fi

  if [[ -z "$URL" && -z "$IP_ADDR" && -t 0 ]]; then
    local detected_ip input_addr input_https_port
    detected_ip="$(detect_host_ip || true)"
    if [[ -n "$detected_ip" ]]; then
      read -r -p "请输入服务器 IP（直接回车使用 ${detected_ip}）或完整 HTTPS 域名：" input_addr
      input_addr="${input_addr:-$detected_ip}"
    else
      read -r -p "请输入服务器 IP（如 192.168.1.100）或完整 HTTPS 域名（如 https://crm.example.com:48443）：" input_addr
    fi
    if valid_ipv4 "$input_addr"; then
      IP_ADDR="$input_addr"
    else
      URL="$input_addr"
    fi
    if [[ "$HTTPS_PORT_SET" != true ]]; then
      read -r -p "请输入 HTTPS 访问端口（建议 40000+ 避免冲突，直接回车默认 ${HTTPS_PORT}）：" input_https_port
      if [[ -n "$input_https_port" ]]; then
        HTTPS_PORT="$input_https_port"
        HTTPS_PORT_SET=true
      fi
    fi
  fi

  [[ "$HTTP_PORT" =~ ^[0-9]+$ && "$HTTPS_PORT" =~ ^[0-9]+$ ]] || fail "端口必须为数字"
  (( HTTP_PORT >= 1 && HTTP_PORT <= 65535 && HTTPS_PORT >= 1 && HTTPS_PORT <= 65535 )) || fail "端口范围为 1-65535"
  if [[ "$HTTP_PORT" == "$HTTPS_PORT" && "$HTTP_PORT_SET" != true ]]; then
    if (( HTTPS_PORT > 1 )); then
      HTTP_PORT="$(( HTTPS_PORT - 1 ))"
    else
      HTTP_PORT="$(( HTTPS_PORT + 1 ))"
    fi
  fi
  (( HTTP_PORT != HTTPS_PORT )) || fail "HTTP 与 HTTPS 端口不能相同"

  if [[ -n "$IP_ADDR" ]]; then
    [[ -z "$URL" ]] || fail "--ip 与 --url 不能同时指定"
    valid_ipv4 "$IP_ADDR" || fail "--ip 格式不正确，需要有效的 IPv4 地址（例如 192.168.1.100）"
    if [[ "$HTTPS_PORT" == "443" ]]; then
      URL="https://${IP_ADDR}"
    else
      URL="https://${IP_ADDR}:${HTTPS_PORT}"
    fi
    SITE_ADDRESS="${SITE_ADDRESS:-:443}"
    TLS_MODE="local-cert"
    if [[ -z "$FILES_URL" ]]; then
      FILES_URL="$URL"
      FILES_SITE_ADDRESS="${FILES_SITE_ADDRESS:-:443}"
      FILES_PROXY_MODE="same-origin"
    fi
  fi

  [[ -n "$URL" ]] || fail "首次安装必须指定 --ip <服务器IP>、--auto-ip 或 --url <HTTPS地址>"
  validate_url "$URL" "$ALLOW_HTTP"

  if [[ "$URL" == https://* ]]; then
    local url_host
    url_host="$(extract_url_host "$URL")"
    if [[ -n "$TLS_CERT_SRC" || "$HTTPS_PORT" != "443" || "$TLS_MODE" == "local-cert" ]] || valid_ipv4 "$url_host"; then
      TLS_MODE="local-cert"
      if [[ -z "$FILES_URL" ]]; then
        FILES_URL="$URL"
        FILES_PROXY_MODE="same-origin"
      fi
      if [[ "$FILES_URL" == "$URL" ]]; then
        SITE_ADDRESS="${SITE_ADDRESS:-:443}"
        FILES_SITE_ADDRESS="${FILES_SITE_ADDRESS:-:443}"
        FILES_PROXY_MODE="same-origin"
      fi
    elif [[ -z "$FILES_URL" ]]; then
      fail "标准公网域名 HTTPS 安装必须通过 --files-url 配置文件域名，确保上传下载可用"
    else
      TLS_MODE="acme"
    fi
  else
    TLS_MODE="http-only"
  fi

  SITE_ADDRESS="${SITE_ADDRESS:-$URL}"
  valid_site "$SITE_ADDRESS" || fail "--site-address 格式不正确"
  [[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail "项目名只能包含小写字母、数字、下划线和连字符"

  check_ports_available

  cp "$ROOT/.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  set_env COMPOSE_PROJECT_NAME "$PROJECT_NAME"
  set_env PUBLIC_URL "$URL"
  set_env CADDY_SITE_ADDRESS "$SITE_ADDRESS"
  set_env HTTP_PORT "$HTTP_PORT"
  set_env HTTPS_PORT "$HTTPS_PORT"
  set_env TLS_MODE "$TLS_MODE"
  if [[ "$URL" == https://* ]]; then
    set_env COOKIE_SECURE true
  else
    set_env COOKIE_SECURE false
    echo "提示：当前为显式 HTTP 调试模式，请勿用于正式业务数据。"
  fi
  if [[ -n "$FILES_URL" ]]; then
    validate_url "$FILES_URL" "$ALLOW_HTTP"
    [[ "${FILES_URL%%:*}" == "${URL%%:*}" ]] || fail "文件地址与系统地址必须同为 HTTPS（或同为 HTTP）"
    if [[ "$FILES_URL" == "$URL" ]]; then
      FILES_PROXY_MODE="same-origin"
      FILES_SITE_ADDRESS="${FILES_SITE_ADDRESS:-$SITE_ADDRESS}"
    else
      FILES_PROXY_MODE="${FILES_PROXY_MODE:-separate}"
      FILES_SITE_ADDRESS="${FILES_SITE_ADDRESS:-$FILES_URL}"
    fi
    valid_site "$FILES_SITE_ADDRESS" || fail "--files-site-address 格式不正确"
    set_env FILES_ENABLED true
    set_env FILES_PROXY_MODE "$FILES_PROXY_MODE"
    set_env FILES_PUBLIC_ENDPOINT "$FILES_URL"
    set_env FILES_SITE_ADDRESS "$FILES_SITE_ADDRESS"
  else
    set_env FILES_PROXY_MODE separate
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
  HTTP_PORT="$(env_value HTTP_PORT)"
  HTTP_PORT="${HTTP_PORT:-48080}"
  HTTPS_PORT="$(env_value HTTPS_PORT)"
  HTTPS_PORT="${HTTPS_PORT:-48443}"
  TLS_MODE="$(env_value TLS_MODE)"
  [[ -n "$URL" ]] || fail "配置缺少 PUBLIC_URL"
  grep -q 'REPLACE_ON_INSTALL' "$ENV_FILE" && fail "请先替换 .env 中的安装占位符"
  return 0
}

ensure_tls_certs() {
  mkdir -p "$RUNTIME_DIR" "$CERTS_DIR"
  chmod 755 "$RUNTIME_DIR" "$CERTS_DIR"

  local effective_tls_mode
  effective_tls_mode="$(env_value TLS_MODE)"
  if [[ "$effective_tls_mode" != "local-cert" ]]; then
    return 0
  fi

  if [[ -n "$TLS_CERT_SRC" && -n "$TLS_KEY_SRC" ]]; then
    openssl x509 -in "$TLS_CERT_SRC" -noout >/dev/null 2>&1 || fail "无效的 SSL 证书文件：$TLS_CERT_SRC"
    openssl pkey -in "$TLS_KEY_SRC" -noout >/dev/null 2>&1 || fail "无效的 SSL 私钥文件：$TLS_KEY_SRC"
    cp "$TLS_CERT_SRC" "$CERTS_DIR/server.crt"
    cp "$TLS_KEY_SRC" "$CERTS_DIR/server.key"
    chmod 644 "$CERTS_DIR/server.crt"
    chmod 600 "$CERTS_DIR/server.key"
    rm -f "$CERTS_DIR/ca.crt" "$CERTS_DIR/ca.key"
    echo "已载入自定义 HTTPS 证书到 ${CERTS_DIR}"
  elif [[ ! -s "$CERTS_DIR/server.crt" || ! -s "$CERTS_DIR/server.key" ]]; then
    local host san_list tmp_dir
    host="$(extract_url_host "$(env_value PUBLIC_URL)")"
    if valid_ipv4 "$host"; then
      if [[ "$host" == "127.0.0.1" ]]; then
        san_list="IP:127.0.0.1,DNS:localhost"
      else
        san_list="IP:${host},IP:127.0.0.1,DNS:localhost"
      fi
    else
      san_list="DNS:${host},IP:127.0.0.1,DNS:localhost"
    fi

    tmp_dir="$(mktemp -d "$RUNTIME_DIR/certgen.XXXXXX")"
    cat > "$tmp_dir/ca.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = Whale CRM Local Root CA
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF
    cat > "$tmp_dir/server.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = ${host}
[v3_server]
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = ${san_list}
EOF
    if [[ ! -s "$CERTS_DIR/ca.crt" || ! -s "$CERTS_DIR/ca.key" ]]; then
      openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 \
        -config "$tmp_dir/ca.cnf" \
        -keyout "$CERTS_DIR/ca.key" -out "$CERTS_DIR/ca.crt" >/dev/null 2>&1 || { rm -rf "$tmp_dir"; fail "生成本地根证书失败"; }
      chmod 600 "$CERTS_DIR/ca.key"
      chmod 644 "$CERTS_DIR/ca.crt"
    fi
    openssl req -new -newkey rsa:2048 -nodes -sha256 \
      -config "$tmp_dir/server.cnf" \
      -keyout "$CERTS_DIR/server.key" -out "$tmp_dir/server.csr" >/dev/null 2>&1 || { rm -rf "$tmp_dir"; fail "生成服务器证书请求失败"; }
    openssl x509 -req -in "$tmp_dir/server.csr" \
      -CA "$CERTS_DIR/ca.crt" -CAkey "$CERTS_DIR/ca.key" -CAcreateserial -CAserial "$tmp_dir/ca.srl" \
      -out "$CERTS_DIR/server.crt" -days 825 -sha256 \
      -extfile "$tmp_dir/server.cnf" -extensions v3_server >/dev/null 2>&1 || { rm -rf "$tmp_dir"; fail "签发服务器 HTTPS 证书失败"; }
    openssl verify -CAfile "$CERTS_DIR/ca.crt" "$CERTS_DIR/server.crt" >/dev/null 2>&1 || { rm -rf "$tmp_dir"; fail "服务器 HTTPS 证书校验失败"; }
    rm -rf "$tmp_dir"
    chmod 644 "$CERTS_DIR/server.crt"
    chmod 600 "$CERTS_DIR/server.key"
    echo "已离线生成本地 HTTPS 证书（SAN: ${san_list}），根证书位于 ${CERTS_DIR}/ca.crt"
  fi

  if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce 2>/dev/null || true)" == "Enforcing" ]]; then
    chcon -Rt container_file_t "$CERTS_DIR" 2>/dev/null || true
  fi
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
  ensure_tls_certs
  local files_enabled proxy_mode public_url files_public
  files_enabled="$(env_value FILES_ENABLED)"
  proxy_mode="$(env_value FILES_PROXY_MODE)"
  public_url="$(env_value PUBLIC_URL)"
  files_public="$(env_value FILES_PUBLIC_ENDPOINT)"

  if [[ "$files_enabled" == true && ( "$proxy_mode" == "same-origin" || ( -n "$files_public" && "$files_public" == "$public_url" ) ) ]]; then
    [[ -f "$ROOT/Caddyfile.sameorigin" ]] || fail "安装包缺少 Caddyfile.sameorigin"
    cat "$ROOT/Caddyfile.sameorigin" > "$RUNTIME_DIR/Caddyfile"
  else
    cat "$ROOT/Caddyfile.core" > "$RUNTIME_DIR/Caddyfile"
    if [[ "$files_enabled" == true ]]; then
      [[ -n "$files_public" ]] || fail "启用文件功能时必须配置 FILES_PUBLIC_ENDPOINT"
      [[ -n "$(env_value FILES_SITE_ADDRESS)" ]] || fail "启用文件功能时必须配置 FILES_SITE_ADDRESS"
      cat "$ROOT/Caddyfile.files" >> "$RUNTIME_DIR/Caddyfile"
    fi
  fi
  chmod 644 "$RUNTIME_DIR/Caddyfile"
  if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce 2>/dev/null || true)" == "Enforcing" ]]; then
    chcon -t container_file_t "$RUNTIME_DIR/Caddyfile" 2>/dev/null || true
  fi
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
  chmod 755 "$RUNTIME_DIR"
  local output
  if compose run --help 2>&1 | grep -q -- '--env-from-file'; then
    BOOTSTRAP_ENV="$(mktemp "$RUNTIME_DIR/bootstrap.XXXXXX")"
    chmod 600 "$BOOTSTRAP_ENV"
    printf 'BOOTSTRAP_ADMIN_USERNAME=%s\nBOOTSTRAP_ADMIN_DISPLAY_NAME=%s\nBOOTSTRAP_ADMIN_PASSWORD=%s\n' \
      "$username" "$display_name" "$password" > "$BOOTSTRAP_ENV"
    if ! output="$(compose run --rm --no-deps -T --env-from-file "$BOOTSTRAP_ENV" \
      --entrypoint java crm-api \
      -cp /app/app.jar \
      -Dloader.main=com.whale.crm.platform.bootstrap.BootstrapAdminCommand \
      org.springframework.boot.loader.launch.PropertiesLauncher)"; then
      printf '%s\n' "$output" >&2
      return 1
    fi
    rm -f "$BOOTSTRAP_ENV"
    BOOTSTRAP_ENV=""
  else
    # 兼容 openEuler 上较旧版本的 Docker Compose v2（不含 --env-from-file），通过标准输入传递凭据不落命令行参数
    if ! output="$(printf '%s\n%s\n%s\n' "$username" "$display_name" "$password" | \
      compose run --rm --no-deps -T --entrypoint sh crm-api -c \
      'IFS= read -r BOOTSTRAP_ADMIN_USERNAME && IFS= read -r BOOTSTRAP_ADMIN_DISPLAY_NAME && IFS= read -r BOOTSTRAP_ADMIN_PASSWORD && export BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_DISPLAY_NAME BOOTSTRAP_ADMIN_PASSWORD && exec java -cp /app/app.jar -Dloader.main=com.whale.crm.platform.bootstrap.BootstrapAdminCommand org.springframework.boot.loader.launch.PropertiesLauncher')"; then
      printf '%s\n' "$output" >&2
      return 1
    fi
  fi
  printf '%s\n' "$output"
  if [[ "$output" == *"创建成功"* ]]; then
    printf 'username=%s\npassword=%s\n' "$username" "$password" > "$CREDENTIAL_FILE"
    chmod 600 "$CREDENTIAL_FILE"
    echo "管理员已初始化，凭据保存在 ${CREDENTIAL_FILE}（权限 600），请妥善保管。"
  else
    echo "管理员已存在，未修改账号，也未生成新的初始密码。"
  fi
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
      echo "HTTPS/访问检查通过：$URL/login"
      return 0
    fi
    if [[ ! "$URL" =~ ^https?://(127\.0\.0\.1|localhost)(:[0-9]+)?$ && "$attempt" -ge 3 ]]; then
      if probe_url "$local_fallback_url"; then
        echo "本机 HTTPS 回环检查通过：${local_fallback_url}（从本机直连 ${URL}/login 未通，常见于云主机弹性 IP 无回环或安全组策略，请从外部浏览器访问 ${URL}/login 验证）"
        return 0
      fi
    fi
    if (( attempt < 40 )); then sleep 5; fi
  done
  compose ps
  fail "服务已启动，但 $URL/login 暂不可访问。请检查证书、域名解析、firewalld 防火墙、安全组和 Caddy 日志"
}

configure_firewall() {
  [[ "$(uname -s)" == "Linux" ]] || return 0
  command -v firewall-cmd >/dev/null 2>&1 || return 0
  [[ "$(firewall-cmd --state 2>/dev/null || true)" == "running" ]] || return 0

  local ports_to_check=()
  if [[ "$URL" == https://* ]]; then
    ports_to_check+=("$HTTPS_PORT")
    if [[ "$(env_value TLS_MODE)" == "acme" && "$HTTP_PORT" == "80" ]]; then
      ports_to_check+=("$HTTP_PORT")
    fi
  else
    ports_to_check+=("$HTTP_PORT")
  fi

  local missing_ports=() p
  for p in "${ports_to_check[@]}"; do
    if ! firewall-cmd --query-port="${p}/tcp" >/dev/null 2>&1; then
      missing_ports+=("$p")
    fi
  done
  [[ ${#missing_ports[@]} -gt 0 ]] || return 0

  if [[ "$OPEN_FIREWALL" == true ]]; then
    if [[ "$(id -u)" -eq 0 ]]; then
      for p in "${missing_ports[@]}"; do
        firewall-cmd --permanent --add-port="${p}/tcp" >/dev/null
      done
      firewall-cmd --reload >/dev/null
      echo "已通过 openEuler firewalld 自动放行 HTTPS 端口：${missing_ports[*]}"
      return 0
    else
      echo "提示：已指定 --open-firewall 但当前非 root 用户，请手动执行以下放行命令：" >&2
    fi
  fi

  echo "提示：检测到 openEuler firewalld 正在运行，以下端口尚未放行：${missing_ports[*]}"
  for p in "${missing_ports[@]}"; do
    echo "  sudo firewall-cmd --permanent --add-port=${p}/tcp"
  done
  echo "  sudo firewall-cmd --reload"
}

print_install_summary() {
  local tls_note=""
  if [[ -s "$CERTS_DIR/ca.crt" ]]; then
    tls_note="HTTPS 证书说明：已自动生成离线本地 CA 证书（${CERTS_DIR}/ca.crt）。
               浏览器首次访问时点击「高级 → 继续前往」即可使用；也可在客户端导入 ca.crt，
               或重新部署时传入 --tls-cert / --tls-key 替换为商业/企业 SSL 证书。"
  elif [[ "$(env_value TLS_MODE)" == "local-cert" ]]; then
    tls_note="HTTPS 证书说明：已启用您提供的自定义 SSL 证书（${CERTS_DIR}/server.crt）。"
  fi
  cat <<EOF

============================================================
鲸选美学 CRM 安装完成（欧拉系统高位端口 HTTPS 隔离部署）
============================================================
HTTPS 访问地址：${URL}
宿主机占用端口：HTTPS=${HTTPS_PORT}（主入口），HTTP=${HTTP_PORT}（仅 308 跳转 HTTPS，对外防火墙只需放行 ${HTTPS_PORT}）
               （MySQL/Redis/Kafka/MinIO/ClamAV/API/Web 全部走容器内网，不占宿主机端口）
Compose 项目名：$(env_value COMPOSE_PROJECT_NAME)
初始管理员凭据：${CREDENTIAL_FILE}（权限 600，首次登录后请及时修改密码）
${tls_note}
日常维护命令：
  ./install.sh status        查看所有服务状态
  ./install.sh logs crm-api  查看后端日志
  ./install.sh backup        一键备份数据库、文件存储与密钥
  ./install.sh stop          停止服务（保留数据卷）
  ./install.sh start         重新启动服务
============================================================
EOF
}

backup_database() {
  local ts="${1:-$(date -u +%Y%m%dT%H%M%SZ)}"
  mkdir -p "$ROOT/backups"
  local destination="$ROOT/backups/crm-${ts}.sql.gz"
  if ! compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction --quick --routines --triggers --hex-blob -uroot medical_crm' | gzip > "$destination"; then
    rm -f "$destination"
    fail "数据库备份失败，未继续升级"
  fi
  [[ -s "$destination" ]] || { rm -f "$destination"; fail "数据库备份为空，未继续升级"; }
  chmod 600 "$destination"
  echo "数据库备份完成：$destination"
}

backup_minio() {
  local ts="${1:-$(date -u +%Y%m%dT%H%M%SZ)}"
  mkdir -p "$ROOT/backups"
  local destination="$ROOT/backups/crm-minio-${ts}.tar.gz"
  local project_name minio_cid volume_name
  project_name="$(env_value COMPOSE_PROJECT_NAME)"
  project_name="${project_name:-$PROJECT_NAME}"
  minio_cid="$(compose ps -q minio 2>/dev/null || true)"
  if [[ -n "$minio_cid" ]]; then
    if ! docker run --rm --volumes-from "$minio_cid" --entrypoint sh redis:7.4.2-alpine -c 'tar -C /data -cf - .' | gzip > "$destination"; then
      rm -f "$destination"
      fail "MinIO 对象存储备份失败"
    fi
  else
    volume_name="${project_name}_minio_data"
    if ! docker volume inspect "$volume_name" >/dev/null 2>&1; then
      fail "未找到 MinIO 容器或数据卷 ($volume_name)，无法备份对象存储"
    fi
    if ! docker run --rm -v "${volume_name}:/data:ro" --entrypoint sh redis:7.4.2-alpine -c 'tar -C /data -cf - .' | gzip > "$destination"; then
      rm -f "$destination"
      fail "MinIO 对象存储备份失败"
    fi
  fi
  [[ -s "$destination" ]] || { rm -f "$destination"; fail "MinIO 对象存储备份为空"; }
  gzip -t "$destination" || { rm -f "$destination"; fail "MinIO 备份 gzip 校验失败"; }
  chmod 600 "$destination"
  echo "MinIO 对象存储备份完成：$destination"
}

backup_env() {
  local ts="${1:-$(date -u +%Y%m%dT%H%M%SZ)}"
  mkdir -p "$ROOT/backups"
  if [[ -n "${BACKUP_ENCRYPTION_PASSPHRASE:-}" ]]; then
    local destination="$ROOT/backups/crm-env-${ts}.env.enc"
    if ! openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt \
      -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in "$ENV_FILE" -out "$destination"; then
      rm -f "$destination"
      fail ".env 密钥文件加密备份失败"
    fi
    chmod 600 "$destination"
    echo "环境密钥已加密备份：$destination"
  else
    local destination="$ROOT/backups/crm-env-${ts}.env"
    cp "$ENV_FILE" "$destination"
    chmod 600 "$destination"
    echo "警告：未设置 BACKUP_ENCRYPTION_PASSPHRASE，已以明文（权限 600）备份 .env 到 ${destination}；" >&2
    echo "      .env 包含 APP_AES_KEY_BASE64 与 APP_BLIND_INDEX_PEPPER 等核心密钥，建议设置 BACKUP_ENCRYPTION_PASSPHRASE 并将密钥备份与数据库备份分开异地保存。" >&2
    echo "环境密钥备份完成（未加密，建议尽快离线加密保存）：$destination"
  fi
}

backup_all() {
  local ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_database "$ts"
  backup_minio "$ts"
  backup_env "$ts"
}

case "$COMMAND" in
  install)
    require_tools
    check_host_arch
    check_linux_runtime_hints
    prepare_config
    load_images
    render_caddy
    start_stack
    create_admin
    check_access
    configure_firewall
    print_install_summary
    ;;
  upgrade)
    require_tools
    check_host_arch
    load_existing_config
    backup_all
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
    detect_compose || true
    load_existing_config
    compose down
    echo "服务已停止，数据卷保留。"
    ;;
  status)
    detect_compose || true
    load_existing_config
    compose ps
    ;;
  logs)
    detect_compose || true
    load_existing_config
    if [[ ${#POSITIONAL_ARGS[@]} -gt 0 ]]; then
      compose logs --tail "${LOG_LINES:-100}" "${POSITIONAL_ARGS[@]}"
    else
      compose logs --tail "${LOG_LINES:-100}"
    fi
    ;;
  backup)
    require_tools
    load_existing_config
    backup_all
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
