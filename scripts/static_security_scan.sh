#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for tool in docker tar python3; do
  command -v "$tool" >/dev/null
done
GITLEAKS_IMAGE='zricethezav/gitleaks:v8.30.1'
SEMGREP_IMAGE='semgrep/semgrep:1.181.1'
for image in "$GITLEAKS_IMAGE" "$SEMGREP_IMAGE"; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker pull "$image"
  fi
done
if [ -n "${P3_SECURITY_WORK:-}" ]; then
  case "$P3_SECURITY_WORK" in
    tmp/*) WORK="$ROOT/$P3_SECURITY_WORK" ;;
    *) echo 'P3_SECURITY_WORK 只能是 tmp/ 下的新审计目录' >&2; exit 2 ;;
  esac
else
  WORK="$ROOT/tmp/p3-static-security-$(date +%s)-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
fi
if [ -e "$WORK" ]; then
  echo '审计目录已存在，拒绝覆盖历史证据' >&2
  exit 2
fi
mkdir -p "$WORK/source" "$WORK/reports" "$WORK/selftest"
chmod 700 "$WORK"

tar --exclude='*/.env' --exclude='*/.env.*' --exclude='.env' --exclude='.env.*' \
  --exclude='*.pem' --exclude='*.key' --exclude='*.p12' --exclude='*.jks' --exclude='*.keystore' \
  --exclude='id_rsa*' --exclude='id_ed25519*' --exclude='node_modules' --exclude='target' --exclude='.venv' \
  -cf - apps/crm-api/src apps/crm-api/pom.xml apps/crm-web/src apps/crm-web/package.json \
  apps/crm-web/package-lock.json apps/ai-worker/app apps/ai-worker/requirements.txt \
  scripts contracts infra docs .github README.md AGENTS.md pom.xml gitleaks.toml | tar -xf - -C "$WORK/source"
python3 - "$WORK/selftest/fixture.txt" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).write_text('Synthetic scanner fixture only, not a real credential: ' + 'AKIA' + 'QWERTYUIOPASDFGH' + '\n')
PY

set +e
docker run --rm -v "$WORK/source:/scan:ro" -v "$WORK/reports:/report" \
  -v "$ROOT/gitleaks.toml:/etc/gitleaks.toml:ro" "$GITLEAKS_IMAGE" \
  detect --source /scan --config /etc/gitleaks.toml --no-git --redact --exit-code 1 \
  --report-format json --report-path /report/gitleaks.json
GITLEAKS_STATUS=$?
docker run --rm -v "$WORK/source:/scan:ro" -v "$WORK/reports:/report" "$SEMGREP_IMAGE" \
  semgrep scan --config p/owasp-top-ten --metrics=off \
  --include='*.java' --include='*.py' --include='*.ts' --include='*.vue' --include='*.js' \
  --include='*.yml' --include='*.yaml' --json --output /report/semgrep.json --error /scan
SEMGREP_STATUS=$?
docker run --rm -v "$WORK/selftest:/scan:ro" -v "$WORK/reports:/report" \
  -v "$ROOT/gitleaks.toml:/etc/gitleaks.toml:ro" "$GITLEAKS_IMAGE" \
  detect --source /scan --config /etc/gitleaks.toml --no-git --redact --exit-code 1 \
  --report-format json --report-path /report/gitleaks-selftest.json
SELFTEST_STATUS=$?
set -e

python3 - "$WORK" "$GITLEAKS_STATUS" "$SEMGREP_STATUS" "$SELFTEST_STATUS" <<'PY'
from pathlib import Path
from datetime import datetime, timezone
import json,sys
root=Path(sys.argv[1])
gitleaks_status,semgrep_status,selftest_status=map(int,sys.argv[2:])

def findings(path,key):
    if not path.is_file():
        return None
    data=json.loads(path.read_text())
    return len(data if key is None else data[key])
leaks=findings(root/'reports/gitleaks.json',None)
sast=findings(root/'reports/semgrep.json','results')
selftest=findings(root/'reports/gitleaks-selftest.json',None)
selftest_detected=selftest_status==1 and selftest is not None and selftest>0
complete=all(value is not None for value in (leaks,sast,selftest))
passed=gitleaks_status==0 and semgrep_status==0 and selftest_detected and complete
summary={
    'status':'PASS' if passed else 'FAIL',
    'createdAt':datetime.now(timezone.utc).isoformat(),
    'gitleaks':{
        'image':'zricethezav/gitleaks:v8.30.1','exitCode':gitleaks_status,
        'findings':leaks,'syntheticFixtureDetected':selftest_detected,
    },
    'semgrep':{
        'image':'semgrep/semgrep:1.181.1','exitCode':semgrep_status,
        'findings':sast,'rules':'p/owasp-top-ten','metrics':'off',
    },
    'sourceExclusions':['.env*','private keys','node_modules','target','.venv','tmp'],
    'limitations':'本机限定源码扫描，不是完整渗透测试、生产安全验收或秘密文件读取授权',
}
(root/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(summary,ensure_ascii=False,indent=2))
print('证据目录:',root)
sys.exit(0 if passed else 1)
PY
