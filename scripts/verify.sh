#!/usr/bin/env bash
# 统一验证入口：后端 verify + 前端 typecheck/lint/test/build + ai-worker pytest。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

step() { echo; echo "===== $* ====="; }

step "crm-api: mvn verify（含 Testcontainers，需 Docker）"
./mvnw -B -pl apps/crm-api -am verify

step "crm-web: typecheck"
npm --prefix apps/crm-web run typecheck
step "crm-web: lint"
npm --prefix apps/crm-web run lint
step "crm-web: test"
npm --prefix apps/crm-web run test
step "crm-web: build"
npm --prefix apps/crm-web run build

step "ai-worker: pytest"
if [ -x apps/ai-worker/.venv/bin/python ]; then
  apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests -v
else
  echo "apps/ai-worker/.venv 不存在，先执行：python3 -m venv apps/ai-worker/.venv && apps/ai-worker/.venv/bin/pip install -r apps/ai-worker/requirements.txt" >&2
  exit 1
fi

echo
echo "===== 全部通过 ====="
