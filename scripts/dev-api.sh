#!/usr/bin/env bash
# 本地后端：独立运行副本启动，避免 Maven 构建覆盖正在使用的 JAR。仅限本地开发。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT/apps/crm-api/target/runtime"
BUILD_JAR="$ROOT/apps/crm-api/target/crm-api-0.1.1-SNAPSHOT.jar"
PID_FILE="$RUNTIME_DIR/local-api.pid"
HEALTH_URL="${API_HEALTH_URL:-http://localhost:8080/actuator/health}"
mkdir -p "$RUNTIME_DIR"

pid_running() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid
  pid="$(<"$PID_FILE")"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

assert_managed_process() {
  local pid command_line
  pid="$(<"$PID_FILE")"
  command_line="$(ps -p "$pid" -o args=)"
  if [[ "$command_line" != *"apps/crm-api/target/runtime/crm-api-"*".jar"* ]]; then
    echo "PID $pid 不是本脚本管理的后端进程，拒绝停止。请人工检查 $PID_FILE" >&2
    return 1
  fi
}

stop_api() {
  if ! pid_running; then
    rm -f "$PID_FILE"
    return 0
  fi
  assert_managed_process
  local pid
  pid="$(<"$PID_FILE")"
  kill -TERM "$pid"
  for _ in {1..100}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      return 0
    fi
    sleep 0.2
  done
  echo "旧后端 PID $pid 未在 20 秒内退出，不强制终止。请检查 $RUNTIME_DIR 中的日志。" >&2
  return 1
}

if [[ "${1:-}" == "stop" ]]; then
  stop_api
  echo "本地后端已停止"
  exit 0
fi
if [[ "${1:-}" == "status" ]]; then
  curl --fail --silent --show-error "$HEALTH_URL"
  exit 0
fi
if ! command -v lsof >/dev/null 2>&1; then
  echo "缺少 lsof，无法确认 8080 端口是否被其他进程占用" >&2
  exit 1
fi
if pid_running; then
  assert_managed_process
elif lsof -tiTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "8080 已被非本脚本管理的进程占用。请确认后停止该进程，再运行 $0" >&2
  exit 1
fi
if [[ "${API_SKIP_BUILD:-0}" != "1" ]]; then
  "$ROOT/mvnw" -f "$ROOT/pom.xml" -pl apps/crm-api -DskipTests -Dspotbugs.skip=true package
fi
[[ -f "$BUILD_JAR" ]] || { echo "构建产物不存在：$BUILD_JAR" >&2; exit 1; }
ARTIFACT="$RUNTIME_DIR/crm-api-$(date +%Y%m%d%H%M%S)-$$.jar"
LOG_FILE="${ARTIFACT%.jar}.log"
cp "$BUILD_JAR" "$ARTIFACT"
stop_api

echo "启动独立运行副本：$ARTIFACT"
nohup java -jar "$ARTIFACT" "$@" > "$LOG_FILE" 2>&1 &
API_PID=$!
echo "$API_PID" > "$PID_FILE"
for _ in {1..60}; do
  if curl --fail --silent --show-error "$HEALTH_URL" >/dev/null 2>&1; then
    printf '本地后端已启动，PID=%s，健康检查通过\n' "$API_PID"
    echo "日志：$LOG_FILE"
    exit 0
  fi
  if ! kill -0 "$API_PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "后端启动失败，请检查日志：$LOG_FILE" >&2
    exit 1
  fi
  sleep 1
done
echo "后端在 60 秒内未通过健康检查，将停止本次启动的进程。日志：$LOG_FILE" >&2
stop_api
exit 1
