#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
BASE_URL="${CRM_LOAD_BASE_URL:-http://127.0.0.1:8080}"
REQUESTS="${CRM_LOAD_REQUESTS:-200}"
CONCURRENCY="${CRM_LOAD_CONCURRENCY:-5}"
P95_LIMIT="${CRM_LOAD_P95_LIMIT_MS:-500}"
USERNAME="${CRM_LOAD_USERNAME:-admin}"
PASSWORD="${CRM_LOAD_PASSWORD:-Dev@12345}"
export LOAD_BASE_URL="$BASE_URL" LOAD_REQUESTS="$REQUESTS" LOAD_CONCURRENCY="$CONCURRENCY" LOAD_P95_LIMIT="$P95_LIMIT"

python3 - <<'PY'
import os
from urllib.parse import urlparse
url=urlparse(os.environ['LOAD_BASE_URL'])
assert url.scheme in ('http','https') and url.hostname in ('127.0.0.1','localhost','::1'), '只允许本机负载目标'
for name,minimum,maximum in [('LOAD_REQUESTS',1,100000),('LOAD_CONCURRENCY',1,64),('LOAD_P95_LIMIT',1,60000)]:
    value=int(os.environ[name])
    assert minimum<=value<=maximum,(name,value)
assert int(os.environ['LOAD_CONCURRENCY'])<=int(os.environ['LOAD_REQUESTS'])
PY
for tool in curl ab python3; do
  command -v "$tool" >/dev/null
done
RUN_ID="$(date +%s)-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
WORK="$ROOT/tmp/p3-load-$RUN_ID"
mkdir -p "$WORK"
chmod 700 "$WORK"
cleanup() {
  rm -f "$WORK/cookies.txt" "$WORK/login.headers"
}
trap cleanup EXIT

curl --fail --silent --show-error --max-time 10 "$BASE_URL/actuator/health" > "$WORK/health.json"
python3 - "$WORK/health.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body.get('status')=='UP',body
PY
PAYLOAD="$(python3 -c 'import json,sys; print(json.dumps({"username":sys.argv[1],"password":sys.argv[2]}))' "$USERNAME" "$PASSWORD")"
curl --fail --silent --show-error --max-time 10 -D "$WORK/login.headers" -c "$WORK/cookies.txt" \
  -H 'Content-Type: application/json' --data "$PAYLOAD" "$BASE_URL/api/v1/auth/login" > "$WORK/login.json"
python3 - "$WORK/login.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body.get('code')=='OK' and body['data']['userId']>0,body
PY
AUTH_COOKIE="$(python3 - "$WORK/login.headers" <<'PY'
import sys
cookies=[]
for line in open(sys.argv[1]):
    if line.lower().startswith('set-cookie:'):
        cookie=line.split(':',1)[1].strip().split(';',1)[0]
        if cookie.startswith(('CRM_TOKEN=','CRM_CSRF=')):
            cookies.append(cookie)
assert len(cookies)==2,'登录响应缺少认证 Cookie'
print('; '.join(cookies))
PY
)"
for endpoint in customers reconciliation; do
  case "$endpoint" in
    customers) PATH_PART='/api/v1/customers?page=1&size=20' ;;
    reconciliation) PATH_PART='/api/v1/settlements/reconciliation' ;;
  esac
  curl --fail --silent --show-error --max-time 10 -H "Cookie: $AUTH_COOKIE" "$BASE_URL$PATH_PART" > "$WORK/$endpoint-preflight.json"
  python3 - "$WORK/$endpoint-preflight.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body.get('code')=='OK',body
PY
  ab -k -s 10 -n "$REQUESTS" -c "$CONCURRENCY" -H 'Accept: application/json' \
    -H "Cookie: $AUTH_COOKIE" "$BASE_URL$PATH_PART" > "$WORK/$endpoint.ab.txt"
done
python3 - "$WORK" <<'PY'
from pathlib import Path
import json,os,re,sys
root=Path(sys.argv[1])
results=[]
for name in ('customers','reconciliation'):
    text=(root/f'{name}.ab.txt').read_text()
    def number(label,default=None):
        match=re.search(rf'^{re.escape(label)}\s+(\d+(?:\.\d+)?)',text,re.M)
        if not match:
            if default is not None:
                return default
            raise AssertionError((name,label))
        return float(match.group(1))
    p95_match=re.search(r'^\s*95%\s+(\d+)',text,re.M)
    assert p95_match,(name,'缺少 p95')
    p95=int(p95_match.group(1))
    complete=int(number('Complete requests:'))
    failed=int(number('Failed requests:'))
    non_2xx=int(number('Non-2xx responses:',0))
    breakdown=re.search(r'\(Connect:\s*(\d+), Receive:\s*(\d+), Length:\s*(\d+), Exceptions:\s*(\d+)\)',text)
    if breakdown:
        connect,receive,length_variants,exceptions=map(int,breakdown.groups())
    else:
        assert failed==0,(name,'非零失败时缺少请求失败分类')
        connect=receive=length_variants=exceptions=0
    transport_failures=connect+receive+exceptions
    limit=int(os.environ['LOAD_P95_LIMIT'])
    result={
        'endpoint':name,
        'completeRequests':complete,
        'failedRequests':failed,
        'transportFailures':transport_failures,
        'lengthDifferingResponses':length_variants,
        'non2xxResponses':non_2xx,
        'p95Ms':p95,
        'p95LimitMs':limit,
        'requestsPerSecond':number('Requests per second:'),
    }
    assert complete==int(os.environ['LOAD_REQUESTS']),result
    assert transport_failures==0 and non_2xx==0,result
    assert failed==transport_failures+length_variants,result
    assert p95<=limit,result
    results.append(result)
summary={
    'baseUrl':os.environ['LOAD_BASE_URL'],
    'concurrency':int(os.environ['LOAD_CONCURRENCY']),
    'results':results,
    'boundary':'本机只读负载冒烟，不是生产容量、SLA、渗透或上线验收',
}
(root/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(summary,ensure_ascii=False,indent=2))
print('证据目录:',root)
PY
