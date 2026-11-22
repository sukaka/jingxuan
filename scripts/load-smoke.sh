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
for tool in curl python3; do
  command -v "$tool" >/dev/null
done
RUN_ID="$(date +%s)-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
WORK="$ROOT/tmp/p3-load-$RUN_ID"
mkdir -p "$WORK"
chmod 700 "$WORK"
cleanup() {
  rm -f "$WORK/cookies.txt" "$WORK/login.json"
}
trap cleanup EXIT

curl --fail --silent --show-error --max-time 10 "$BASE_URL/actuator/health" > "$WORK/health.json"
python3 - "$WORK/health.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body.get('status')=='UP',body
PY
export CRM_LOAD_USERNAME="$USERNAME"
export CRM_LOAD_PASSWORD="$PASSWORD"
python3 "$ROOT/infra/production/tests/captcha_solver.py" \
  --base-url "$BASE_URL" \
  --username-env CRM_LOAD_USERNAME \
  --password-env CRM_LOAD_PASSWORD \
  --cookie-jar "$WORK/cookies.txt" \
  --out-json "$WORK/login.json"
python3 - "$WORK/login.json" "$WORK/cookies.txt" <<'PY'
import http.cookiejar,json,sys
body=json.load(open(sys.argv[1]))
assert body.get('code')=='OK' and body['data']['userId']>0,body
jar=http.cookiejar.MozillaCookieJar(sys.argv[2])
jar.load(ignore_discard=True,ignore_expires=True)
names={c.name for c in jar}
assert {'CRM_TOKEN','CRM_CSRF'}.issubset(names),f'登录响应缺少认证 Cookie: {names}'
PY
for endpoint in customers reconciliation; do
  case "$endpoint" in
    customers) PATH_PART='/api/v1/customers?page=1&size=20' ;;
    reconciliation) PATH_PART='/api/v1/settlements/reconciliation' ;;
  esac
  curl --fail --silent --show-error --max-time 10 -b "$WORK/cookies.txt" "$BASE_URL$PATH_PART" > "$WORK/$endpoint-preflight.json"
  python3 - "$WORK/$endpoint-preflight.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body.get('code')=='OK',body
PY
done
python3 - "$WORK" "$WORK/cookies.txt" <<'PY'
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlparse
import http.client
import http.cookiejar
import json
import math
import os
import queue
import sys
import time

root = Path(sys.argv[1])
cookie_path = Path(sys.argv[2])
base_url = os.environ['LOAD_BASE_URL'].rstrip('/')
parsed = urlparse(base_url)
total_requests = int(os.environ['LOAD_REQUESTS'])
concurrency = int(os.environ['LOAD_CONCURRENCY'])
p95_limit = int(os.environ['LOAD_P95_LIMIT'])

jar = http.cookiejar.MozillaCookieJar(str(cookie_path))
jar.load(ignore_discard=True, ignore_expires=True)
cookie_header = '; '.join(f'{c.name}={c.value}' for c in jar if c.name in ('CRM_TOKEN', 'CRM_CSRF'))
assert 'CRM_TOKEN=' in cookie_header and 'CRM_CSRF=' in cookie_header, 'Cookie file missing auth cookies'

endpoints = [
    ('customers', '/api/v1/customers?page=1&size=20'),
    ('reconciliation', '/api/v1/settlements/reconciliation'),
]

def run_endpoint(name: str, path_part: str) -> dict:
    req_queue: queue.Queue[int] = queue.Queue()
    for idx in range(total_requests):
        req_queue.put(idx)

    host = parsed.hostname or '127.0.0.1'
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    full_path = (parsed.path.rstrip('/') + path_part) if parsed.path else path_part
    headers = {
        'Accept': 'application/json',
        'Cookie': cookie_header,
        'Connection': 'keep-alive',
    }

    def worker() -> tuple[list[float], int, int]:
        latencies_ms: list[float] = []
        transport_errs = 0
        non_2xx = 0
        conn = None

        def open_conn():
            if parsed.scheme == 'https':
                return http.client.HTTPSConnection(host, port, timeout=10)
            return http.client.HTTPConnection(host, port, timeout=10)

        try:
            conn = open_conn()
            while True:
                try:
                    req_queue.get_nowait()
                except queue.Empty:
                    break
                started = time.perf_counter()
                try:
                    conn.request('GET', full_path, headers=headers)
                    resp = conn.getresponse()
                    resp.read()
                    elapsed_ms = (time.perf_counter() - started) * 1000.0
                    latencies_ms.append(elapsed_ms)
                    if not (200 <= resp.status < 300):
                        non_2xx += 1
                    if resp.getheader('Connection', '').lower() == 'close':
                        conn.close()
                        conn = open_conn()
                except Exception:
                    transport_errs += 1
                    try:
                        conn.close()
                    except Exception:
                        pass
                    conn = open_conn()
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
        return latencies_ms, transport_errs, non_2xx

    wall_start = time.perf_counter()
    all_latencies: list[float] = []
    transport_failures = 0
    non_2xx_responses = 0
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker) for _ in range(concurrency)]
        for fut in futures:
            lats, terr, n2xx = fut.result()
            all_latencies.extend(lats)
            transport_failures += terr
            non_2xx_responses += n2xx
    wall_elapsed = max(1e-6, time.perf_counter() - wall_start)

    complete = len(all_latencies)
    failed = transport_failures + non_2xx_responses
    sorted_lats = sorted(all_latencies)
    p95 = int(math.ceil(sorted_lats[min(len(sorted_lats) - 1, math.ceil(0.95 * len(sorted_lats)) - 1)])) if sorted_lats else 0
    rps = round(complete / wall_elapsed, 2)

    result = {
        'endpoint': name,
        'completeRequests': complete,
        'failedRequests': failed,
        'transportFailures': transport_failures,
        'lengthDifferingResponses': 0,
        'non2xxResponses': non_2xx_responses,
        'p95Ms': p95,
        'p95LimitMs': p95_limit,
        'requestsPerSecond': rps,
    }
    assert complete == total_requests, result
    assert transport_failures == 0 and non_2xx_responses == 0, result
    assert failed == 0, result
    assert p95 <= p95_limit, result
    return result

results = [run_endpoint(name, path_part) for name, path_part in endpoints]
summary = {
    'baseUrl': base_url,
    'concurrency': concurrency,
    'results': results,
    'boundary': '本机只读负载冒烟，不是生产容量、SLA、渗透或上线验收',
}
(root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
print('证据目录:', root)
PY
