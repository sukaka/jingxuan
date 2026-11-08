#!/usr/bin/env python3
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
API = 'https://api.osv.dev/v1'
WORK = Path(os.environ.get('P3_AUDIT_WORK', ROOT / 'tmp' / f'p3-dependency-audit-{int(time.time())}-{uuid.uuid4().hex[:10]}'))


def run(command):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    (WORK / (Path(command[0]).name + '.log')).write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f'依赖清单命令失败：{Path(command[0]).name}')
    return result.stdout


def inventory():
    maven_text = run([str(ROOT / 'mvnw'), '-B', '-pl', 'apps/crm-api', '-am', 'dependency:list',
                      f'-DoutputFile={WORK / "maven-dependencies.txt"}'])
    (WORK / 'maven-list.log').write_text(maven_text)
    entries = {}
    for line in (WORK / 'maven-dependencies.txt').read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('The following files have been resolved:'):
            continue
        if not re.match(r'^[^\s:]+:[^\s:]+:', line):
            raise ValueError(f'无法解析 Maven 依赖：{line}')
        parts = line.split('--', 1)[0].strip().split(':')
        if len(parts) < 5:
            raise ValueError(f'无法解析 Maven 依赖：{line}')
        name = f'{parts[0]}:{parts[1]}'
        if name == 'com.whale.crm:crm-api':
            continue
        entries[('Maven', name, parts[-2])] = {
            'ecosystem': 'Maven', 'name': name, 'version': parts[-2], 'scope': parts[-1],
        }
    pip = ROOT / 'apps/ai-worker/.venv/bin/python'
    if not pip.is_file():
        raise RuntimeError('AI Worker 虚拟环境不存在，不能改用宿主机 Python 冒充其依赖审计')
    python_text = run([str(pip), '-m', 'pip', 'list', '--format=json', '--disable-pip-version-check'])
    for item in json.loads(python_text):
        name = re.sub(r'[-_.]+', '-', item['name']).lower()
        entries[('PyPI', name, item['version'])] = {
            'ecosystem': 'PyPI', 'name': name, 'version': item['version'], 'scope': 'ai-worker-venv',
        }
    if not entries:
        raise RuntimeError('没有可审计的依赖')
    return sorted(entries.values(), key=lambda item: (item['ecosystem'], item['name'], item['version']))


def post(endpoint, payload):
    request = Request(API + endpoint, data=json.dumps(payload).encode(),
                      headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
    for attempt in range(2):
        try:
            with urlopen(request, timeout=30) as response:
                if response.status != 200:
                    raise RuntimeError(f'OSV 返回 HTTP {response.status}')
                return json.load(response)
        except HTTPError as error:
            if error.code != 429 and error.code < 500:
                raise
            if attempt == 1:
                raise
            time.sleep(1)
        except URLError:
            if attempt == 1:
                raise
            time.sleep(1)
    raise RuntimeError('OSV 请求未完成')


def all_vulnerabilities(package):
    payload = {'package': {'ecosystem': package['ecosystem'], 'name': package['name']},
               'version': package['version']}
    vulnerabilities = []
    while True:
        response = post('/query', payload)
        vulnerabilities.extend(response.get('vulns', []))
        token = response.get('next_page_token')
        if not token:
            return vulnerabilities
        payload['page_token'] = token
        if len(vulnerabilities) > 5000:
            raise RuntimeError(f'{package["name"]} 的漏洞结果超过安全上限')


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    WORK.chmod(0o700)
    packages = inventory()
    (WORK / 'inventory.json').write_text(json.dumps(packages, ensure_ascii=False, indent=2) + '\n')
    findings = {}
    batches = []
    for start in range(0, len(packages), 100):
        chunk = packages[start:start + 100]
        queries = [{'package': {'ecosystem': item['ecosystem'], 'name': item['name']},
                    'version': item['version']} for item in chunk]
        response = post('/querybatch', {'queries': queries})
        results = response.get('results')
        if not isinstance(results, list) or len(results) != len(chunk):
            raise RuntimeError('OSV 批量响应数量不一致')
        batches.append({'queries': queries, 'results': results})
        (WORK / 'osv-batch-results.json').write_text(json.dumps(batches, ensure_ascii=False, indent=2) + '\n')
        for package, result in zip(chunk, results):
            if result.get('vulns'):
                key = (package['ecosystem'], package['name'], package['version'])
                findings[key] = all_vulnerabilities(package)
    serializable_findings = [
        {'package': {'ecosystem': key[0], 'name': key[1], 'version': key[2]}, 'vulnerabilities': vulnerabilities}
        for key, vulnerabilities in findings.items()
    ]
    (WORK / 'osv-findings.json').write_text(json.dumps(serializable_findings, ensure_ascii=False, indent=2) + '\n')
    affected = []
    for package in packages:
        key = (package['ecosystem'], package['name'], package['version'])
        vulnerabilities = findings.get(key, [])
        if vulnerabilities:
            affected.append({**package, 'vulnerabilities': [
                {'id': item['id'], 'summary': item.get('summary'), 'modified': item.get('modified'),
                 'reference': f'https://osv.dev/vulnerability/{item["id"]}'}
                for item in vulnerabilities
            ]})
    counts = {
        ecosystem: sum(item['ecosystem'] == ecosystem for item in packages)
        for ecosystem in ('Maven', 'PyPI')
    }
    report = {
        'status': 'FAIL' if affected else 'PASS',
        'source': 'OSV API v1 querybatch/query',
        'queriedAt': datetime.now(timezone.utc).isoformat(),
        'inventoryCounts': counts,
        'inventorySha256': hashlib.sha256((WORK / 'inventory.json').read_bytes()).hexdigest(),
        'vulnerablePackageCount': len(affected),
        'vulnerabilityCount': sum(len(item['vulnerabilities']) for item in affected),
        'affectedPackages': affected,
        'scope': 'Maven 解析依赖（含 test scope）与 AI Worker 虚拟环境安装包',
        'limitations': '公开漏洞版本匹配，不覆盖源码 SAST、secret 扫描、恶意代码、渗透或生产环境验收',
    }
    (WORK / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'affectedPackages'},
                     ensure_ascii=False, indent=2))
    for package in affected:
        print(package['ecosystem'], package['name'], package['version'],
              ','.join(item['id'] for item in package['vulnerabilities']))
    print('证据目录:', WORK)
    return 1 if affected else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        WORK.mkdir(parents=True, exist_ok=True)
        (WORK / 'error.json').write_text(json.dumps({
            'status': 'ERROR', 'type': type(error).__name__, 'message': str(error),
        }, ensure_ascii=False, indent=2) + '\n')
        print(f'依赖审计未完成：{type(error).__name__}: {error}', file=sys.stderr)
        print('诊断目录:', WORK, file=sys.stderr)
        sys.exit(2)
