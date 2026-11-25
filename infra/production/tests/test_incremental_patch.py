#!/usr/bin/env python3
"""Offline contract, functional sandbox, and deliverable tests for 2.0.3 incremental patch scripts."""

from __future__ import annotations

import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

TESTS_DIR = Path(__file__).resolve().parent
PROD_DIR = TESTS_DIR.parent
WIN_DIR = PROD_DIR / 'windows'
REPO_ROOT = TESTS_DIR.parents[2]
RELEASE_DIR = REPO_ROOT / 'release'

BUILD_PATCH_SH = REPO_ROOT / 'scripts' / 'build-incremental-patch.sh'
APPLY_PATCH_SH = PROD_DIR / 'apply-patch.sh'
WIN_APPLY_BAT = WIN_DIR / 'apply-patch.bat'
WIN_APPLY_PS1 = WIN_DIR / 'apply-patch.ps1'

sys.path.insert(0, str(TESTS_DIR))
import test_windows_installer as win_tests  # noqa: E402

APP_SERVICES = ['crm-api', 'ai-worker', 'web', 'clamav-proxy']
MIDDLEWARE_SERVICES = ['mysql', 'redis', 'kafka', 'minio', 'clamav', 'caddy']
MIDDLEWARE_IMAGE_PREFIXES = ('mysql:', 'redis:', 'apache/kafka:', 'minio/minio:', 'clamav/clamav:', 'caddy:')


class TestIncrementalPatchBuilderContracts(unittest.TestCase):
    def test_build_script_exists_executable_syntax_and_contracts(self) -> None:
        self.assertTrue(BUILD_PATCH_SH.is_file())
        mode = BUILD_PATCH_SH.stat().st_mode
        self.assertTrue(bool(mode & stat.S_IXUSR), 'scripts/build-incremental-patch.sh must be executable')

        proc = subprocess.run(['bash', '-n', str(BUILD_PATCH_SH)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f'bash -n failed: {proc.stderr}')

        text = BUILD_PATCH_SH.read_text(encoding='utf-8')
        self.assertIn('CRM_PATCH_VERSION:-2.0.3', text)
        self.assertIn('CRM_TARGET_PLATFORM:-linux/amd64', text)
        self.assertIn('--skip-tests', text)
        self.assertIn('--platform', text)
        self.assertIn('manifest.json', text)
        self.assertIn('INSTALL_MODE=incremental-patch', text)
        self.assertIn('PATCH_VERSION=$VERSION', text)
        self.assertIn('GIT_COMMIT=$GIT_COMMIT', text)
        self.assertIn('BUILD_TIME_UTC=$BUILD_TIME_UTC', text)
        self.assertIn('whale-crm-patch-${VERSION}-openeuler-${PLATFORM_SLUG}', text)
        self.assertIn('whale-crm-patch-${VERSION}-windows-${ARCH}', text)
        self.assertIn('ZIP_STORED', text)
        self.assertIn('ZIP_DEFLATED', text)

        # Single docker build / single docker save (excluding --help probe) for 4 app images only
        save_calls = re.findall(r'(?m)^\s*docker save (?!.*--help)', text)
        self.assertEqual(len(save_calls), 1, f'Must run docker save once and share across Linux + Windows, found: {save_calls}')
        app_images_block = re.search(r'APP_IMAGES=\((.*?)\)', text, re.S)
        self.assertIsNotNone(app_images_block)
        entries = re.findall(r'"([^"]+)"', app_images_block.group(1))
        self.assertEqual(
            entries,
            [
                'whale-crm/crm-api:${VERSION}',
                'whale-crm/web:${VERSION}',
                'whale-crm/ai-worker:${VERSION}',
                'whale-crm/clamav-proxy:${VERSION}',
            ],
        )
        for prefix in MIDDLEWARE_IMAGE_PREFIXES:
            self.assertFalse(any(e.startswith(prefix) for e in entries))

    def test_build_script_rejects_invalid_args_and_platform(self) -> None:
        bad_arg = subprocess.run(['bash', str(BUILD_PATCH_SH), '--unknown-flag'], capture_output=True, text=True)
        self.assertEqual(bad_arg.returncode, 2)

        env = os.environ.copy()
        env['CRM_TARGET_PLATFORM'] = 'linux/386'
        bad_plat = subprocess.run(['bash', str(BUILD_PATCH_SH), '--skip-tests'], env=env, capture_output=True, text=True)
        self.assertEqual(bad_plat.returncode, 2)

        env['CRM_TARGET_PLATFORM'] = 'linux/amd64'
        env['CRM_PATCH_VERSION'] = '../bad'
        bad_ver = subprocess.run(['bash', str(BUILD_PATCH_SH), '--skip-tests'], env=env, capture_output=True, text=True)
        self.assertEqual(bad_ver.returncode, 2)


class TestLinuxApplyPatchContractsAndFlows(unittest.TestCase):
    def test_apply_patch_sh_executable_syntax_and_security_contracts(self) -> None:
        self.assertTrue(APPLY_PATCH_SH.is_file())
        mode = APPLY_PATCH_SH.stat().st_mode
        self.assertTrue(bool(mode & stat.S_IXUSR), 'infra/production/apply-patch.sh must be executable')

        proc = subprocess.run(['bash', '-n', str(APPLY_PATCH_SH)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f'bash -n failed: {proc.stderr}')

        text = APPLY_PATCH_SH.read_text(encoding='utf-8')
        self.assertNotIn('sed -i', text)
        self.assertIn('ENVIRON["ENV_VAL"]', text)
        self.assertNotIn('-p"$MYSQL_ROOT_PASSWORD"', text)
        self.assertIn('MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', text)
        self.assertIn('gzip -t', text)
        self.assertIn('docker-compose', text)
        self.assertIn('--target', text)
        self.assertIn('--skip-backup', text)
        self.assertIn('--rollback', text)
        self.assertIn('--to-version', text)
        self.assertIn('last-release-version', text)
        self.assertIn('up -d --no-deps --wait --wait-timeout', text)
        self.assertIn('ca.crt', text)
        self.assertIn('127.0.0.1', text)

    def _setup_sandbox(
        self,
        tmp: Path,
        *,
        current_version: str = '2.0.2',
        patch_version: str = '2.0.3',
        stopped_services: set[str] | None = None,
        missing_images: set[str] | None = None,
        standalone_compose_only: bool = False,
        placeholder_env: bool = False,
    ) -> tuple[Path, Path, Path, Path, Path]:
        stopped = set(stopped_services or set())
        missing = set(missing_images or set())

        target_dir = tmp / 'existing-install'
        target_dir.mkdir()
        (target_dir / 'runtime' / 'certs').mkdir(parents=True)
        (target_dir / 'runtime' / 'certs' / 'ca.crt').write_text('SYNTHETIC-CA-CERT\n', encoding='utf-8')
        (target_dir / 'runtime' / 'certs' / 'server.key').write_text('SYNTHETIC-SERVER-KEY\n', encoding='utf-8')
        os.chmod(target_dir / 'runtime' / 'certs' / 'server.key', 0o600)
        shutil.copy2(PROD_DIR / 'compose.yml', target_dir / 'compose.yml')

        env_lines = [
            '# Custom production configuration comment',
            'COMPOSE_PROJECT_NAME=whale-crm',
            f'RELEASE_VERSION={current_version}',
            'PUBLIC_URL=https://192.168.10.25:49443',
            'CADDY_SITE_ADDRESS=:443',
            'HTTP_PORT=49080',
            'HTTPS_PORT=49443',
            'TLS_MODE=local-cert',
            'COOKIE_SECURE=true',
            'FILES_ENABLED=true',
            'FILES_PROXY_MODE=same-origin',
            'FILES_PUBLIC_ENDPOINT=https://192.168.10.25:49443',
            'MYSQL_ROOT_PASSWORD=REPLACE_ON_INSTALL' if placeholder_env else 'MYSQL_ROOT_PASSWORD=SYNTHETIC_MYSQL_ROOT_SECRET',
            'MYSQL_PASSWORD=SYNTHETIC_MYSQL_USER_SECRET',
            'REDIS_PASSWORD=SYNTHETIC_REDIS_SECRET',
            'JWT_SECRET=SYNTHETIC_JWT_SECRET_WITH_SPECIAL&CHARS\\HERE',
            'APP_AES_KEY_BASE64=SYNTHETIC_AES_BASE64==',
            'APP_BLIND_INDEX_PEPPER=SYNTHETIC_PEPPER_HEX',
            'AI_INTERNAL_TOKEN=SYNTHETIC_AI_TOKEN',
        ]
        env_file = target_dir / '.env'
        env_file.write_text('\n'.join(env_lines) + '\n', encoding='utf-8')
        os.chmod(env_file, 0o600)

        patch_dir = tmp / f'whale-crm-patch-{patch_version}-openeuler-linux-amd64'
        (patch_dir / 'images').mkdir(parents=True)
        shutil.copy2(APPLY_PATCH_SH, patch_dir / 'apply-patch.sh')
        os.chmod(patch_dir / 'apply-patch.sh', 0o755)
        (patch_dir / 'manifest.txt').write_text(
            f'PATCH_VERSION={patch_version}\n'
            f'RELEASE_VERSION={patch_version}\n'
            'TARGET_PLATFORM=linux/amd64\n'
            'TARGET_OS=linux\n'
            'GIT_COMMIT=abcdef1234567890\n'
            'BUILD_TIME_UTC=2026-11-25T12:00:00Z\n'
            'INSTALL_MODE=incremental-patch\n',
            encoding='utf-8',
        )
        app_imgs = [f'whale-crm/{svc}:{patch_version}' for svc in ('crm-api', 'web', 'ai-worker', 'clamav-proxy')]
        (patch_dir / 'images' / 'images.txt').write_text('\n'.join(app_imgs) + '\n', encoding='utf-8')
        (patch_dir / 'images' / 'application.tar.gz').write_bytes(b'stub-app-images')

        bin_dir = tmp / 'bin'
        bin_dir.mkdir()
        docker_log = tmp / 'docker-calls.jsonl'
        curl_log = tmp / 'curl-calls.jsonl'

        stub_docker_py = f"""#!/usr/bin/env python3
import json, os, sys
log_path = {str(docker_log)!r}
stopped = set({sorted(stopped)!r})
missing_images = set({sorted(missing)!r})
standalone_only = {standalone_compose_only!r}
prog = os.path.basename(sys.argv[0])
args = sys.argv[1:]
with open(log_path, 'a', encoding='utf-8') as f:
    f.write(json.dumps([prog] + args) + '\\n')

if prog == 'docker-compose':
    if args == ['version']:
        print('Docker Compose version v2.29.0')
        sys.exit(0)
    compose_args = args
else:
    if args == ['compose', 'version']:
        if standalone_only:
            sys.exit(1)
        print('Docker Compose version v2.38.1')
        sys.exit(0)
    if args == ['info']:
        sys.exit(0)
    if args[:1] == ['load']:
        sys.exit(0)
    if args[:2] == ['image', 'inspect']:
        img = args[2]
        sys.exit(1 if img in missing_images else 0)
    if args[:1] == ['inspect']:
        cid = args[-1]
        svc = cid.removeprefix('cid-')
        print('false' if svc in stopped else 'true')
        sys.exit(0)
    if args[:1] == ['compose']:
        compose_args = args[1:]
    else:
        sys.exit(0)

if 'ps' in compose_args and '-q' in compose_args:
    svc = compose_args[-1]
    if svc not in stopped:
        print(f'cid-{{svc}}')
    sys.exit(0)
if 'exec' in compose_args and 'mysql' in compose_args:
    sys.stdout.write('-- stub mysqldump\\nCREATE TABLE t(id INT);\\n')
    sys.exit(0)
sys.exit(0)
"""
        stub_docker = bin_dir / 'docker'
        stub_docker.write_text(stub_docker_py, encoding='utf-8')
        os.chmod(stub_docker, 0o755)
        if standalone_compose_only:
            stub_dc = bin_dir / 'docker-compose'
            stub_dc.write_text(stub_docker_py, encoding='utf-8')
            os.chmod(stub_dc, 0o755)

        stub_curl = bin_dir / 'curl'
        stub_curl.write_text(
            f"""#!/usr/bin/env python3
import json, sys
with open({str(curl_log)!r}, 'a', encoding='utf-8') as f:
    f.write(json.dumps(sys.argv[1:]) + '\\n')
sys.exit(0)
""",
            encoding='utf-8',
        )
        os.chmod(stub_curl, 0o755)

        return target_dir, patch_dir, bin_dir, docker_log, curl_log

    def test_upgrade_updates_only_release_version_and_app_containers(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            target_dir, patch_dir, bin_dir, docker_log, curl_log = self._setup_sandbox(Path(td))
            orig_env_lines = (target_dir / '.env').read_text(encoding='utf-8').splitlines()
            orig_ca = (target_dir / 'runtime' / 'certs' / 'ca.crt').read_text(encoding='utf-8')
            orig_key = (target_dir / 'runtime' / 'certs' / 'server.key').read_text(encoding='utf-8')

            env = os.environ.copy()
            env['PATH'] = f'{bin_dir}:{env["PATH"]}'

            proc = subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), str(target_dir)],
                cwd=patch_dir,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertIn('2.0.2 -> 2.0.3', proc.stdout)
            self.assertIn('--rollback', proc.stdout)

            # 1. .env changed ONLY RELEASE_VERSION, preserving every other line and 600 mode
            new_env_lines = (target_dir / '.env').read_text(encoding='utf-8').splitlines()
            expected_env_lines = [
                'RELEASE_VERSION=2.0.3' if ln == 'RELEASE_VERSION=2.0.2' else ln
                for ln in orig_env_lines
            ]
            self.assertEqual(new_env_lines, expected_env_lines)
            self.assertEqual(stat.S_IMODE((target_dir / '.env').stat().st_mode), 0o600)

            # 2. TLS certs completely untouched
            self.assertEqual((target_dir / 'runtime' / 'certs' / 'ca.crt').read_text(encoding='utf-8'), orig_ca)
            self.assertEqual((target_dir / 'runtime' / 'certs' / 'server.key').read_text(encoding='utf-8'), orig_key)

            # 3. last-release-version recorded
            self.assertEqual((target_dir / 'runtime' / 'last-release-version').read_text(encoding='utf-8').strip(), '2.0.2')

            # 4. Backups created with mode 600 and valid gzip
            sql_backups = list((target_dir / 'backups').glob('crm-pre-patch-*.sql.gz'))
            env_backups = list((target_dir / 'backups').glob('crm-env-pre-patch-*.env'))
            self.assertEqual(len(sql_backups), 1)
            self.assertEqual(len(env_backups), 1)
            self.assertEqual(stat.S_IMODE(sql_backups[0].stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(env_backups[0].stat().st_mode), 0o600)
            self.assertIn('CREATE TABLE', gzip.decompress(sql_backups[0].read_bytes()).decode('utf-8'))

            # 5. Docker calls: loaded application.tar.gz, ONLY ran compose up on 4 app services, zero middleware restarts, zero secret leaks
            calls = [json.loads(ln) for ln in docker_log.read_text(encoding='utf-8').splitlines()]
            flattened = '\n'.join(' '.join(c) for c in calls)
            self.assertNotIn('SYNTHETIC_MYSQL_ROOT_SECRET', flattened)
            self.assertNotIn('SYNTHETIC_JWT_SECRET', flattened)
            self.assertIn(['docker', 'load', '-i', str(patch_dir / 'images' / 'application.tar.gz')], calls)

            up_calls = [c for c in calls if 'up' in c]
            self.assertEqual(len(up_calls), 1, f'Expected exactly one compose up call, got: {up_calls}')
            self.assertEqual(
                up_calls[0],
                [
                    'docker', 'compose', '--env-file', str(target_dir / '.env'),
                    '-f', str(target_dir / 'compose.yml'),
                    'up', '-d', '--no-deps', '--wait', '--wait-timeout', '600',
                    *APP_SERVICES,
                ],
            )
            for mw in MIDDLEWARE_SERVICES:
                self.assertNotIn(mw, up_calls[0])

            # 6. Curl probe used local ca.crt and --noproxy *
            curl_calls = [json.loads(ln) for ln in curl_log.read_text(encoding='utf-8').splitlines()]
            self.assertTrue(curl_calls)
            self.assertIn('--noproxy', curl_calls[0])
            self.assertIn('--cacert', curl_calls[0])
            self.assertIn(str(target_dir / 'runtime' / 'certs' / 'ca.crt'), curl_calls[0])
            self.assertIn('https://192.168.10.25:49443/login', curl_calls[0])

    def test_auto_detect_parent_dir_skip_backup_and_retry_preserves_last_version(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            target_dir, patch_dir, bin_dir, _, _ = self._setup_sandbox(Path(td))
            nested = target_dir / 'subdir'
            nested.mkdir()
            env = os.environ.copy()
            env['PATH'] = f'{bin_dir}:{env["PATH"]}'

            # Run from child directory without passing target_dir + --skip-backup
            subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), '--skip-backup'],
                cwd=nested,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertFalse((target_dir / 'backups').exists())
            self.assertIn('RELEASE_VERSION=2.0.3', (target_dir / '.env').read_text(encoding='utf-8').splitlines())
            self.assertEqual((target_dir / 'runtime' / 'last-release-version').read_text(encoding='utf-8').strip(), '2.0.2')

            # Re-running 2.0.3 patch on already-2.0.3 must NOT overwrite last-release-version with 2.0.3
            subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), '--skip-backup', '--target', str(target_dir)],
                cwd=patch_dir,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual((target_dir / 'runtime' / 'last-release-version').read_text(encoding='utf-8').strip(), '2.0.2')

    def test_rollback_restores_previous_version_and_guards_missing_images(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            target_dir, patch_dir, bin_dir, docker_log, _ = self._setup_sandbox(
                Path(td), current_version='2.0.3', patch_version='2.0.3'
            )
            (target_dir / 'runtime' / 'last-release-version').write_text('2.0.2\n', encoding='utf-8')
            env = os.environ.copy()
            env['PATH'] = f'{bin_dir}:{env["PATH"]}'

            proc = subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), '--rollback', str(target_dir)],
                cwd=patch_dir,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertIn('2.0.3 -> 2.0.2', proc.stdout)
            self.assertIn('RELEASE_VERSION=2.0.2', (target_dir / '.env').read_text(encoding='utf-8').splitlines())
            calls = [json.loads(ln) for ln in docker_log.read_text(encoding='utf-8').splitlines()]
            up_calls = [c for c in calls if 'up' in c]
            self.assertEqual(len(up_calls), 1)
            self.assertEqual(up_calls[0][-4:], APP_SERVICES)

        # Missing image guard: must abort before mutating .env
        with tempfile.TemporaryDirectory() as td:
            target_dir, patch_dir, bin_dir, _, _ = self._setup_sandbox(
                Path(td),
                current_version='2.0.3',
                patch_version='2.0.3',
                missing_images={'whale-crm/web:2.0.2'},
            )
            (target_dir / 'runtime' / 'last-release-version').write_text('2.0.2\n', encoding='utf-8')
            env = os.environ.copy()
            env['PATH'] = f'{bin_dir}:{env["PATH"]}'

            proc = subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), '--rollback', '--target', str(target_dir)],
                cwd=patch_dir,
                env=env,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn('whale-crm/web:2.0.2', proc.stderr)
            self.assertIn('RELEASE_VERSION=2.0.3', (target_dir / '.env').read_text(encoding='utf-8').splitlines())

    def test_encrypted_backup_standalone_compose_placeholder_guard_and_stopped_infra_start(self) -> None:
        # 1. Placeholder .env rejected before touching docker
        with tempfile.TemporaryDirectory() as td:
            target_dir, patch_dir, bin_dir, docker_log, _ = self._setup_sandbox(Path(td), placeholder_env=True)
            env = os.environ.copy()
            env['PATH'] = f'{bin_dir}:{env["PATH"]}'
            proc = subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), str(target_dir)],
                cwd=patch_dir,
                env=env,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn('REPLACE_ON_INSTALL', proc.stderr)
            calls = [json.loads(ln) for ln in docker_log.read_text(encoding='utf-8').splitlines()]
            self.assertFalse(any('load' in c or 'up' in c for c in calls))

        # 2. Standalone docker-compose v2 + encrypted backup + starting only stopped infra service (redis)
        with tempfile.TemporaryDirectory() as td:
            target_dir, patch_dir, bin_dir, docker_log, _ = self._setup_sandbox(
                Path(td),
                standalone_compose_only=True,
                stopped_services={'redis'},
            )
            env = os.environ.copy()
            env['PATH'] = f'{bin_dir}:{env["PATH"]}'
            env['BACKUP_ENCRYPTION_PASSPHRASE'] = 'SyntheticPatchPass!2026'

            subprocess.run(
                ['bash', str(patch_dir / 'apply-patch.sh'), str(target_dir)],
                cwd=patch_dir,
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
            enc_files = list((target_dir / 'backups').glob('crm-env-pre-patch-*.env.enc'))
            self.assertEqual(len(enc_files), 1)
            self.assertEqual(stat.S_IMODE(enc_files[0].stat().st_mode), 0o600)

            calls = [json.loads(ln) for ln in docker_log.read_text(encoding='utf-8').splitlines()]
            flattened = '\n'.join(' '.join(c) for c in calls)
            self.assertNotIn('SyntheticPatchPass!2026', flattened)
            up_calls = [c for c in calls if 'up' in c]
            self.assertEqual(len(up_calls), 2)
            # First starts only stopped redis with --no-recreate, via docker-compose
            self.assertEqual(up_calls[0][0], 'docker-compose')
            self.assertIn('--no-recreate', up_calls[0])
            self.assertEqual(up_calls[0][-1], 'redis')
            self.assertNotIn('mysql', up_calls[0])
            # Second rolls out only the 4 app services
            self.assertEqual(up_calls[1][0], 'docker-compose')
            self.assertEqual(up_calls[1][-4:], APP_SERVICES)


class TestWindowsApplyPatchContracts(unittest.TestCase):
    def test_apply_patch_bat_encoding_ascii_crlf_and_safe_pause(self) -> None:
        self.assertTrue(WIN_APPLY_BAT.is_file())
        raw = WIN_APPLY_BAT.read_bytes()
        self.assertFalse(raw.startswith(b'\xef\xbb\xbf'), 'apply-patch.bat must not have UTF-8 BOM')
        self.assertTrue(all(b < 128 for b in raw), 'apply-patch.bat must be pure ASCII')
        self.assertIn(b'\r\n', raw, 'apply-patch.bat must use CRLF')
        self.assertEqual(raw.replace(b'\r\n', b'').count(b'\n'), 0, 'apply-patch.bat must not contain bare LF')
        text = raw.decode('ascii')
        self.assertIn('-NoProfile -ExecutionPolicy Bypass -File', text)
        self.assertIn('apply-patch.ps1', text)
        self.assertIn('WHALE_CRM_NO_PAUSE', text)
        self.assertIn('pause', text.lower())
        self.assertNotRegex(text, r'echo\(?![A-Za-z0-9_]+!\s*\|')

    def test_apply_patch_ps1_bom_crlf_syntax_security_and_patch_flow(self) -> None:
        self.assertTrue(WIN_APPLY_PS1.is_file())
        raw = WIN_APPLY_PS1.read_bytes()
        self.assertTrue(raw.startswith(b'\xef\xbb\xbf'), 'apply-patch.ps1 must start with UTF-8 BOM')
        self.assertIn(b'\r\n', raw, 'apply-patch.ps1 must use CRLF')
        self.assertEqual(raw.replace(b'\r\n', b'').count(b'\n'), 0, 'apply-patch.ps1 must not contain bare LF')
        self.assertEqual(raw.replace(b'\r\n', b'').count(b'\r'), 0, 'apply-patch.ps1 must not contain bare CR')

        text = raw.decode('utf-8-sig')
        code_only, errors = win_tests._strip_ps_strings_and_comments(text)
        self.assertEqual(errors, [], f'PowerShell lexer errors in apply-patch.ps1: {errors}')
        self.assertRegex(code_only.lstrip(), r'^\[CmdletBinding\(\)\]\s*\nparam\s*\(')
        self.assertIn('Set-StrictMode -Version Latest', code_only)
        self.assertNotRegex(code_only, r'\bOut-File\b')
        self.assertNotRegex(code_only, r'\bSet-Content\b')

        # Host PowerShell code must not invoke Linux CLI commands directly
        for forbidden_cmd in ('chmod', 'sed', 'awk', 'openssl', 'tar', 'gzip', 'curl', 'mysqldump'):
            self.assertNotRegex(
                code_only,
                rf'(?<![A-Za-z0-9_-]){re.escape(forbidden_cmd)}(?![A-Za-z0-9_-])',
                f'Host PowerShell code must not invoke {forbidden_cmd!r}',
            )

        # Key contracts: params, encoding, ACLs, binary-safe container backup, rollout, rollback, probe
        self.assertIn('[string]$TargetDir', text)
        self.assertIn('[switch]$Rollback', text)
        self.assertIn('[string]$ToVersion', text)
        self.assertIn('[switch]$SkipBackup', text)
        self.assertIn('Write-Utf8NoBomLf', text)
        self.assertIn('Protect-SecretFile', text)
        self.assertIn('icacls.exe', text)
        self.assertIn('| Out-Host', text)
        self.assertIn('EnvironmentOverrides @{ MYSQL_PWD = $mysqlRootPwd }', text)
        self.assertIn('EnvironmentOverrides @{ BACKUP_ENCRYPTION_PASSPHRASE = $passphrase }', text)
        self.assertIn('gzip -t "$out"', text)
        self.assertNotIn('-p"$MYSQL_ROOT_PASSWORD"', text)
        self.assertIn('last-release-version', text)
        self.assertIn("Set-EnvValue -Key 'RELEASE_VERSION'", text)
        self.assertIn("@('crm-api', 'ai-worker', 'web', 'clamav-proxy')", text)
        self.assertIn("'up', '-d', '--no-deps', '--wait', '--wait-timeout'", text)
        self.assertIn('WhaleCrm.HttpProbe', text)
        self.assertIn('req.Proxy = null;', text)
        self.assertIn('RemoteCertificateNameMismatch', text)
        self.assertIn('127.0.0.1', text)


class TestBuiltIncrementalPatchDeliverables(unittest.TestCase):
    def _verify_sha256_file(self, archive_path: Path) -> None:
        sha_path = Path(str(archive_path) + '.sha256')
        self.assertTrue(sha_path.is_file(), f'Missing SHA-256 file: {sha_path}')
        sha_line = sha_path.read_text(encoding='ascii').strip()
        parts = sha_line.split('  ')
        self.assertEqual(len(parts), 2, f'Expected two-space sha256sum format: {sha_line!r}')
        expected_hash, recorded_name = parts
        self.assertEqual(recorded_name, archive_path.name)
        h = hashlib.sha256()
        with archive_path.open('rb') as f:
            for chunk in iter(lambda: f.read(4 * 1024 * 1024), b''):
                h.update(chunk)
        self.assertEqual(h.hexdigest(), expected_hash.lower(), f'SHA-256 mismatch on {archive_path.name}')

    def _verify_app_images_archive(self, pkg_dir: Path, expected_version: str) -> None:
        images_dir = pkg_dir / 'images'
        tarballs = sorted(p.name for p in images_dir.glob('*.tar.gz'))
        self.assertEqual(tarballs, ['application.tar.gz'], f'Incremental patch must contain only application.tar.gz, got {tarballs}')

        images_list = [
            ln.strip()
            for ln in (images_dir / 'images.txt').read_text(encoding='utf-8').splitlines()
            if ln.strip()
        ]
        expected_images = [
            f'whale-crm/crm-api:{expected_version}',
            f'whale-crm/web:{expected_version}',
            f'whale-crm/ai-worker:{expected_version}',
            f'whale-crm/clamav-proxy:{expected_version}',
        ]
        self.assertEqual(images_list, expected_images)

        def _norm(ref: str) -> str:
            for prefix in ('docker.io/library/', 'docker.io/'):
                if ref.startswith(prefix):
                    return ref[len(prefix):]
            return ref

        found_tags: set[str] = set()
        with tarfile.open(images_dir / 'application.tar.gz', 'r:gz') as tf:
            manifest = json.loads(tf.extractfile('manifest.json').read())
            self.assertEqual(len(manifest), 4, f'Expected exactly 4 images in application.tar.gz, got {len(manifest)}')
            for entry in manifest:
                cfg = json.loads(tf.extractfile(entry['Config']).read())
                actual_plat = f"{cfg.get('os')}/{cfg.get('architecture')}"
                self.assertEqual(actual_plat, 'linux/amd64', f'{entry.get("RepoTags")}: {actual_plat}')
                for tag in entry.get('RepoTags') or []:
                    norm = _norm(tag)
                    for mw_prefix in MIDDLEWARE_IMAGE_PREFIXES:
                        self.assertFalse(norm.startswith(mw_prefix), f'Middleware image leaked into patch: {norm}')
                    found_tags.add(norm)
        self.assertEqual(found_tags, {_norm(ref) for ref in expected_images})

    def test_release_patch_archives_when_present(self) -> None:
        linux_tars = sorted(RELEASE_DIR.glob('whale-crm-patch-*-openeuler-*.tar.gz')) if RELEASE_DIR.is_dir() else []
        win_zips = sorted(RELEASE_DIR.glob('whale-crm-patch-*-windows-*.zip')) if RELEASE_DIR.is_dir() else []
        if not linux_tars and not win_zips:
            if os.environ.get('WHALE_CRM_REQUIRE_PATCH') == '1':
                self.fail(f'No built incremental patch packages found in {RELEASE_DIR}')
            self.skipTest('No built incremental patch packages in release/ yet')

        self.assertTrue(linux_tars, 'Missing openEuler incremental patch .tar.gz')
        self.assertTrue(win_zips, 'Missing Windows incremental patch .zip')

        linux_tar = linux_tars[-1]
        win_zip = win_zips[-1]
        self._verify_sha256_file(linux_tar)
        self._verify_sha256_file(win_zip)

        # Verify Linux tar.gz
        with tempfile.TemporaryDirectory() as td:
            with tarfile.open(linux_tar, 'r:gz') as tf:
                names = tf.getnames()
                top_dirs = {n.split('/', 1)[0] for n in names}
                self.assertEqual(len(top_dirs), 1)
                root_name = next(iter(top_dirs))
                for m in tf.getmembers():
                    parts = Path(m.name).parts
                    self.assertNotIn('..', parts)
                    base = Path(m.name).name.lower()
                    self.assertNotIn(base, ('.env', 'initial-admin.txt'))
                    self.assertFalse(base.endswith(('.pem', '.key', '.p12', '.jks')))
                tf.extractall(td)
            pkg = Path(td) / root_name
            for req in ('apply-patch.sh', 'PATCH_README.md', 'manifest.txt', 'images/images.txt', 'images/application.tar.gz'):
                self.assertTrue((pkg / req).is_file(), f'Linux patch missing {req}')
            proc = subprocess.run(['bash', '-n', str(pkg / 'apply-patch.sh')], capture_output=True, text=True)
            self.assertEqual(proc.returncode, 0, proc.stderr)
            mkv = dict(ln.split('=', 1) for ln in (pkg / 'manifest.txt').read_text(encoding='utf-8').splitlines() if '=' in ln)
            self.assertEqual(mkv.get('INSTALL_MODE'), 'incremental-patch')
            self.assertEqual(mkv.get('TARGET_PLATFORM'), 'linux/amd64')
            self.assertTrue(mkv.get('PATCH_VERSION'))
            self.assertTrue(mkv.get('GIT_COMMIT'))
            self.assertTrue(mkv.get('BUILD_TIME_UTC'))
            self._verify_app_images_archive(pkg, mkv['PATCH_VERSION'])

        # Verify Windows zip
        with zipfile.ZipFile(win_zip, 'r') as zf:
            self.assertIsNone(zf.testzip())
            infos = zf.infolist()
            top_dirs = {i.filename.split('/', 1)[0] for i in infos}
            self.assertEqual(len(top_dirs), 1)
            root_name = next(iter(top_dirs))
            for info in infos:
                rel = info.filename[len(root_name) + 1:]
                self.assertNotIn('..', Path(info.filename).parts)
                self.assertNotIn(rel, ('.env', 'initial-admin.txt'))
                self.assertFalse(rel.lower().endswith(('.pem', '.key', '.p12', '.jks')))
                if rel == 'images/application.tar.gz':
                    self.assertEqual(info.compress_type, zipfile.ZIP_STORED)
                elif not info.is_dir():
                    self.assertEqual(info.compress_type, zipfile.ZIP_DEFLATED)
            with tempfile.TemporaryDirectory() as td:
                zf.extractall(td)
                pkg = Path(td) / root_name
                for req in ('apply-patch.bat', 'apply-patch.ps1', 'PATCH_README.md', 'manifest.txt', 'images/images.txt', 'images/application.tar.gz'):
                    self.assertTrue((pkg / req).is_file(), f'Windows patch missing {req}')
                self.assertEqual((pkg / 'apply-patch.bat').read_bytes(), WIN_APPLY_BAT.read_bytes())
                self.assertEqual((pkg / 'apply-patch.ps1').read_bytes(), WIN_APPLY_PS1.read_bytes())
                mkv = dict(ln.split('=', 1) for ln in (pkg / 'manifest.txt').read_text(encoding='utf-8').splitlines() if '=' in ln)
                self.assertEqual(mkv.get('INSTALL_MODE'), 'incremental-patch')
                self.assertEqual(mkv.get('TARGET_PLATFORM'), 'linux/amd64')
                self.assertEqual(mkv.get('TARGET_OS'), 'windows')
                self.assertTrue(mkv.get('PATCH_VERSION'))
                self._verify_app_images_archive(pkg, mkv['PATCH_VERSION'])


if __name__ == '__main__':
    unittest.main()
