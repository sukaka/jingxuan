#!/usr/bin/env python3
"""Offline contract & regression tests for M2 DevSecOps & Infra hardening."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

TESTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = TESTS_DIR.parents[2]


class TestDevSecOpsContracts(unittest.TestCase):
    def test_caddyfile_files_h_sec_05(self) -> None:
        text = (REPO_ROOT / 'infra/production/Caddyfile.files').read_text(encoding='utf-8')
        self.assertIn('max_size 15MB', text)
        self.assertIn('@minio path /minio*', text)
        self.assertIn('respond @minio 403', text)
        self.assertIn('X-Content-Type-Options "nosniff"', text)
        self.assertIn('Content-Security-Policy "sandbox"', text)
        self.assertIn('-Server', text)
        self.assertIn('Strict-Transport-Security "max-age=31536000"', text)

    def test_crm_api_and_clamav_proxy_dockerfiles(self) -> None:
        api_df = (REPO_ROOT / 'apps/crm-api/Dockerfile').read_text(encoding='utf-8')
        self.assertIn('ENV SPRING_PROFILES_ACTIVE=prod', api_df)
        self.assertLess(api_df.index('ENV SPRING_PROFILES_ACTIVE=prod'), api_df.index('USER app'))
        self.assertIn('-XX:MaxRAMPercentage=50', api_df)
        self.assertNotIn('-XX:MaxRAMPercentage=75', api_df)

        proxy_df = (REPO_ROOT / 'infra/production/clamav-proxy/Dockerfile').read_text(encoding='utf-8')
        self.assertIn('max-children=16', proxy_df)

    def test_compose_yml_env_resources_networks_healthchecks_and_caps(self) -> None:
        compose_text = (REPO_ROOT / 'infra/production/compose.yml').read_text(encoding='utf-8')
        env_example = (REPO_ROOT / 'infra/production/.env.example').read_text(encoding='utf-8')

        # 1. All WECOM_*, NOTIFICATION_*, CLAMAV_* placeholders from application*.yml must be forwarded & documented
        app_yml = (REPO_ROOT / 'apps/crm-api/src/main/resources/application.yml').read_text(encoding='utf-8')
        app_prod = (REPO_ROOT / 'apps/crm-api/src/main/resources/application-prod.yml').read_text(encoding='utf-8')
        keys = set(re.findall(r'\$\{((?:WECOM|NOTIFICATION|CLAMAV)_[A-Z0-9_]+)', app_yml + '\n' + app_prod))
        self.assertTrue(keys)
        for k in sorted(keys):
            with self.subTest(env_key=k):
                self.assertRegex(compose_text, rf'(?m)^\s+{re.escape(k)}:')
                if not k.startswith('CLAMAV_'):
                    self.assertRegex(env_example, rf'(?m)^{re.escape(k)}=')

        # 2. M-INF-03 healthchecks and redis config (no CLI password flags)
        self.assertIn('MYSQL_PWD=\\"$$MYSQL_ROOT_PASSWORD\\" exec mysqladmin', compose_text)
        self.assertNotIn('-p$$MYSQL_ROOT_PASSWORD', compose_text)
        self.assertIn('REDISCLI_AUTH=\\"$$REDIS_PASSWORD\\"', compose_text)
        self.assertNotIn(' -a ', compose_text)
        self.assertNotIn('--requirepass', compose_text)
        self.assertIn('/tmp/redis.conf', compose_text)

        # 3. KAFKA_HEAP_OPTS
        self.assertIn('KAFKA_HEAP_OPTS:', compose_text)

        # 4. Validate service-level properties via compose config --format json (or python parser)
        # Check whether pyyaml is available or parse compose YAML via python in apps/ai-worker/.venv or basic checks
        services = ['mysql', 'redis', 'kafka', 'minio', 'clamav', 'crm-api', 'clamav-proxy', 'ai-worker', 'web', 'caddy']
        self.assertEqual(compose_text.count('cap_drop: ["ALL"]'), len(services))
        self.assertEqual(compose_text.count('security_opt: ["no-new-privileges:true"]'), len(services))
        self.assertEqual(len(re.findall(r'(?m)^\s+mem_limit:\s+\d+m$', compose_text)), len(services))
        self.assertEqual(len(re.findall(r'(?m)^\s+cpus:\s+"[0-9.]+"$', compose_text)), len(services))
        self.assertEqual(len(re.findall(r'(?m)^\s+pids_limit:\s+\d+$', compose_text)), len(services))

        total_mem = sum(int(m) for m in re.findall(r'(?m)^\s+mem_limit:\s+(\d+)m$', compose_text))
        self.assertLessEqual(total_mem, 8192)
        self.assertEqual(total_mem, 7872)

        # 5. Network isolation (clamav -> [internal], web -> [edge], crm-api -> [internal, edge])
        clamav_block = re.search(r'(?ms)^  clamav:\n(.*?)^  crm-api:', compose_text).group(1)
        self.assertIn('networks: [internal]', clamav_block)
        self.assertNotIn('edge', clamav_block)

        web_block = re.search(r'(?ms)^  web:\n(.*?)^  caddy:', compose_text).group(1)
        self.assertIn('networks: [edge]', web_block)
        self.assertNotIn('internal', web_block)

        proxy_block = re.search(r'(?ms)^  clamav-proxy:\n(.*?)^  ai-worker:', compose_text).group(1)
        self.assertIn('read_only: true', proxy_block)
        self.assertIn('network_mode: service:crm-api', proxy_block)

    def test_dockerignore_secret_exclusions_preserve_build_outputs(self) -> None:
        lines = [
            ln.strip()
            for ln in (REPO_ROOT / '.dockerignore').read_text(encoding='utf-8').splitlines()
            if ln.strip() and not ln.strip().startswith('#')
        ]
        for required in ('**/.env', '**/.env.*', '!**/.env.example', '**/*.pem', '**/*.key', '**/*.p12', '**/*.jks', '**/*.keystore', '**/id_rsa*', '**/id_ed25519*', '**/initial-admin.txt'):
            self.assertIn(required, lines)
        for forbidden in ('target', '**/target', 'dist', '**/dist'):
            self.assertNotIn(forbidden, lines)

    def test_ci_yml_has_minimal_permissions(self) -> None:
        ci_text = (REPO_ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertRegex(ci_text, r'(?m)^permissions:\n  contents: read$')

    def test_static_security_scan_includes_and_git_staging(self) -> None:
        scan_text = (REPO_ROOT / 'scripts/static_security_scan.sh').read_text(encoding='utf-8')
        for flag in ("--include='*.xml'", "--include='*.sh'", "--include='Dockerfile'", "--include='*.conf'"):
            self.assertIn(flag, scan_text)
        self.assertNotIn("--exclude='.env'", scan_text)
        self.assertNotIn("--exclude='*.pem'", scan_text)

        # Extract and verify the Python staging block against a temp git repo
        match = re.search(r"python3 - \"\$ROOT\" \"\$WORK/source\" <<'PY'\n(.*?)\nPY", scan_text, re.S)
        self.assertIsNotNone(match)
        staging_script = match.group(1)

        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            repo = tmp / 'repo'
            repo.mkdir()
            subprocess.run(['git', 'init'], cwd=repo, capture_output=True, check=True)
            subprocess.run(['git', 'config', 'user.email', 'test@example.com'], cwd=repo, check=True)
            subprocess.run(['git', 'config', 'user.name', 'Test'], cwd=repo, check=True)
            (repo / '.gitignore').write_text('.env\n.env.*\n!.env.example\n', encoding='utf-8')
            (repo / '.env.example').write_text('MYSQL_PASSWORD=REPLACE\n', encoding='utf-8')
            (repo / 'Dockerfile').write_text('FROM alpine:3.21\n', encoding='utf-8')
            (repo / '.env').write_text('MYSQL_PASSWORD=ignored-local\n', encoding='utf-8')
            subprocess.run(['git', 'add', '.gitignore', '.env.example', 'Dockerfile'], cwd=repo, check=True)
            subprocess.run(['git', 'commit', '-m', 'init'], cwd=repo, capture_output=True, check=True)

            # Case 1: Clean repo with gitignored .env -> passes and stages .env.example + Dockerfile, NOT .env
            dest1 = tmp / 'stage1'
            dest1.mkdir()
            r1 = subprocess.run([sys.executable, '-c', staging_script, str(repo), str(dest1)], capture_output=True, text=True)
            self.assertEqual(r1.returncode, 0, r1.stderr)
            self.assertTrue((dest1 / 'Dockerfile').is_file())
            self.assertTrue((dest1 / '.env.example').is_file())
            self.assertFalse((dest1 / '.env').exists())

            # Case 2: Untracked non-ignored private key -> caught and fails (case-insensitive extensions too)
            (repo / 'certs').mkdir(exist_ok=True)
            (repo / 'certs' / 'server.PEM').write_text('dummy-upper-pem', encoding='utf-8')
            dest2 = tmp / 'stage2'
            dest2.mkdir()
            r2 = subprocess.run([sys.executable, '-c', staging_script, str(repo), str(dest2)], capture_output=True, text=True)
            self.assertNotEqual(r2.returncode, 0)
            self.assertIn('certs/server.PEM', r2.stderr)
            (repo / 'certs' / 'server.PEM').unlink()

            (repo / 'certs' / 'tls.KEY').write_text('dummy-upper-key', encoding='utf-8')
            dest2b = tmp / 'stage2b'
            dest2b.mkdir()
            r2b = subprocess.run([sys.executable, '-c', staging_script, str(repo), str(dest2b)], capture_output=True, text=True)
            self.assertNotEqual(r2b.returncode, 0)
            self.assertIn('certs/tls.KEY', r2b.stderr)
            (repo / 'certs' / 'tls.KEY').unlink()

            # Case 2c: .ENV.EXAMPLE stays exempt under case-insensitive matching
            (repo / '.ENV.EXAMPLE').write_text('MYSQL_PASSWORD=REPLACE\n', encoding='utf-8')
            dest2c = tmp / 'stage2c'
            dest2c.mkdir()
            r2c = subprocess.run([sys.executable, '-c', staging_script, str(repo), str(dest2c)], capture_output=True, text=True)
            self.assertEqual(r2c.returncode, 0, r2c.stderr)
            (repo / '.ENV.EXAMPLE').unlink()

            # Case 3: Force-staged .env.prod -> caught and fails
            (repo / '.env.prod').write_text('JWT_SECRET=oops\n', encoding='utf-8')
            subprocess.run(['git', 'add', '-f', '.env.prod'], cwd=repo, check=True)
            dest3 = tmp / 'stage3'
            dest3.mkdir()
            r3 = subprocess.run([sys.executable, '-c', staging_script, str(repo), str(dest3)], capture_output=True, text=True)
            self.assertNotEqual(r3.returncode, 0)
            self.assertIn('.env.prod', r3.stderr)

    def test_m01_recovery_portability_and_secret_hygiene(self) -> None:
        text = (REPO_ROOT / 'scripts/m01-recovery.sh').read_text(encoding='utf-8')
        self.assertNotIn('JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"', text)
        self.assertNotIn("stat -f '%z'", text)
        self.assertNotIn('!= 23', text)
        self.assertNotIn('V23', text)
        self.assertNotIn('CAST(version AS UNSIGNED)', text)
        self.assertNotIn(r"re.match(r'^V(\d+)__'", text)
        self.assertNotIn('pathlib.Path("\'"$DUMP_FILE"\'")', text)
        self.assertNotIn('--password="$MYSQL_ROOT_PASSWORD"', text)
        self.assertNotIn('mc alias set', text)
        self.assertNotIn('--data \'{"username":"admin","password":"Dev@12345"}\'', text)
        self.assertIn('captcha_solver.py', text)
        self.assertIn('wc -c', text)

    def test_m01_recovery_flyway_version_tuple_comparison(self) -> None:
        text = (REPO_ROOT / 'scripts/m01-recovery.sh').read_text(encoding='utf-8')
        match = re.search(
            r'TARGET_FLYWAY_VERSION="\$\(python3 - "\$ROOT/apps/crm-api/src/main/resources/db/migration" "\$SOURCE_FLYWAY_VERSIONS" "\$TARGET_FLYWAY_VERSIONS" <<\'PY\'\n(.*?)\nPY\n\)"',
            text,
            re.S,
        )
        self.assertIsNotNone(match)
        flyway_script = match.group(1)

        with tempfile.TemporaryDirectory() as td:
            mig_dir = Path(td) / 'migration'
            mig_dir.mkdir()
            for fname in (
                'V3.1__a.sql',
                'V5_1__b.sql',
                'V51__c.sql',
                'V52__d.sql',
                'V52.1__e.sql',
                'V53_1__f.sql',
            ):
                (mig_dir / fname).write_text('-- stub\n', encoding='utf-8')

            # 1. All migrations applied including 53_1 (or 53.1) -> passes and prints human-readable max version
            applied_all = '3.1\n5.1\n51\n52\n52.1\n53_1\n'
            ok = subprocess.run(
                [sys.executable, '-c', flyway_script, str(mig_dir), applied_all, applied_all],
                capture_output=True,
                text=True,
            )
            self.assertEqual(ok.returncode, 0, ok.stderr)
            self.assertEqual(ok.stdout.strip(), '53_1')

            # 2. Target missing V52.1 even though V52 is present (when V52.1 is expected max) -> MUST fail
            (mig_dir / 'V53_1__f.sql').unlink()
            source_52_1 = '3.1\n5.1\n51\n52\n52.1\n'
            target_missing_52_1 = '3.1\n5.1\n51\n52\n'
            fail_missing = subprocess.run(
                [sys.executable, '-c', flyway_script, str(mig_dir), source_52_1, target_missing_52_1],
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(fail_missing.returncode, 0)
            self.assertIn('target=V52', fail_missing.stderr)
            self.assertIn('expected>=V52.1', fail_missing.stderr)

            # 3. Missing intermediate hotfix V52.1 in target even when V53_1 is present -> MUST fail
            (mig_dir / 'V53_1__f.sql').write_text('-- stub\n', encoding='utf-8')
            source_all = '3.1\n5.1\n51\n52\n52.1\n53.1\n'
            target_missing_hotfix = '3.1\n5.1\n51\n52\n53.1\n'
            fail_hotfix = subprocess.run(
                [sys.executable, '-c', flyway_script, str(mig_dir), source_all, target_missing_hotfix],
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(fail_hotfix.returncode, 0)

            # 4. Real repo migration dir contains V3.1, V5_1, V7_1 and at least V52 -> passes when matched
            real_mig_dir = REPO_ROOT / 'apps/crm-api/src/main/resources/db/migration'
            real_versions = '\n'.join(
                re.match(r'^V(\d+(?:[._]\d+)*)__', p.name).group(1)
                for p in sorted(real_mig_dir.glob('V*.sql'))
                if re.match(r'^V(\d+(?:[._]\d+)*)__', p.name)
            )
            real_ok = subprocess.run(
                [sys.executable, '-c', flyway_script, str(real_mig_dir), real_versions, real_versions],
                capture_output=True,
                text=True,
            )
            self.assertEqual(real_ok.returncode, 0, real_ok.stderr)

    def test_business_smoke_no_mysql_cli_password(self) -> None:
        text = (REPO_ROOT / 'infra/production/tests/business_smoke.py').read_text(encoding='utf-8')
        self.assertNotIn('-p"$MYSQL_ROOT_PASSWORD"', text)
        self.assertIn('MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', text)
        self.assertIn('login_with_captcha', text)

    def test_runtime_smoke_uses_captcha_solver(self) -> None:
        text = (REPO_ROOT / 'infra/production/tests/runtime_smoke.py').read_text(encoding='utf-8')
        self.assertIn('login_with_captcha', text)


if __name__ == '__main__':
    unittest.main()
