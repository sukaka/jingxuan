#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for tool in docker python3 git; do
  command -v "$tool" >/dev/null
done
git rev-parse --is-inside-work-tree >/dev/null
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

python3 - "$ROOT" "$WORK/source" <<'PY'
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys

repo_root = Path(sys.argv[1])
dest_root = Path(sys.argv[2])
exclude_dirs = {'node_modules', 'target', '.venv', 'tmp', 'release', '.git'}
secret_name_re = re.compile(
    r'(^|/)(?:\.env(?:\..+)?|.*\.(?:pem|key|p12|jks|keystore)|id_(?:rsa|ed25519).*)$',
    re.IGNORECASE,
)

raw = subprocess.check_output(
    ['git', '-C', str(repo_root), 'ls-files', '-z', '--cached', '--others', '--exclude-standard']
)
entries = [item.decode('utf-8', errors='surrogateescape') for item in raw.split(b'\x00') if item]

flagged_secrets = []
staged_count = 0
for rel in entries:
    parts = Path(rel).parts
    if any(part in exclude_dirs for part in parts):
        continue
    src = repo_root / rel
    if src.is_symlink() or not src.is_file():
        continue
    if secret_name_re.search(rel) and Path(rel).name.lower() != '.env.example':
        flagged_secrets.append(rel)
    dst = dest_root / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    staged_count += 1

if flagged_secrets:
    print('检测到被跟踪或未忽略的敏感凭据/密钥文件：', ', '.join(sorted(flagged_secrets)), file=sys.stderr)
    sys.exit(1)
print(f'已暂存 {staged_count} 个受管/未忽略源码文件用于静态安全扫描')
PY
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
  --include='*.yml' --include='*.yaml' --include='*.xml' --include='*.sh' \
  --include='Dockerfile' --include='*.conf' --json --output /report/semgrep.json --error /scan
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
    'sourceExclusions':['gitignored untracked files','node_modules','target','.venv','tmp','release'],
    'limitations':'本机限定源码扫描，不是完整渗透测试、生产安全验收或秘密文件读取授权',
}
(root/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(summary,ensure_ascii=False,indent=2))
print('证据目录:',root)
sys.exit(0 if passed else 1)
PY
