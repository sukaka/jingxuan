#!/usr/bin/env python3
"""Verification test suite for the Windows offline HTTPS installer package."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tarfile
import unittest
import zipfile
import json

TESTS_DIR = Path(__file__).resolve().parent
PROD_DIR = TESTS_DIR.parent
WIN_DIR = PROD_DIR / 'windows'
REPO_ROOT = TESTS_DIR.parents[2]

INSTALL_PS1 = WIN_DIR / 'install.ps1'
INSTALL_BAT = WIN_DIR / 'install.bat'
MANAGE_BAT = WIN_DIR / 'manage.bat'
WIN_README = WIN_DIR / 'README.md'
BUILD_WIN_SH = REPO_ROOT / 'scripts' / 'build-windows-installer.sh'
BUILD_WIN_PS1 = REPO_ROOT / 'scripts' / 'build-windows-installer.ps1'
RELEASE_DIR = REPO_ROOT / 'release'


def _read_ps1_text(path: Path = INSTALL_PS1) -> str:
    return path.read_bytes().decode('utf-8-sig')


def _extract_ps_single_quoted_herestring(text: str, var_name: str) -> str:
    pattern = rf"\${re.escape(var_name)}\s*=\s*@'\r?\n(.*?)\r?\n'@"
    match = re.search(pattern, text, re.S)
    if not match:
        raise AssertionError(f"Could not find single-quoted here-string for ${var_name}")
    return match.group(1).replace('\r\n', '\n')


def _extract_ps_double_quoted_herestring(text: str, var_name: str) -> str:
    pattern = rf'\${re.escape(var_name)}\s*=\s*@"\r?\n(.*?)\r?\n"@'
    match = re.search(pattern, text, re.S)
    if not match:
        raise AssertionError(f"Could not find double-quoted here-string for ${var_name}")
    return match.group(1).replace('\r\n', '\n')


def _strip_ps_strings_and_comments(source: str) -> tuple[str, list[str]]:
    """Lex PowerShell source to validate balanced strings/comments/delimiters.

    Returns (code_only_text, errors). Code-only text replaces strings and comments
    with spaces (preserving newlines) so static checks can inspect host commands
    without false positives from container shell scripts or prose messages.
    """
    text = source.replace('\r\n', '\n')
    n = len(text)
    i = 0
    out: list[str] = []
    errors: list[str] = []
    stack: list[tuple[str, int]] = []
    line = 1
    matching = {'{': '}', '(': ')', '[': ']'}
    closers = {'}': '{', ')': '(', ']': '['}

    while i < n:
        ch = text[i]

        # Track newlines
        if ch == '\n':
            out.append('\n')
            line += 1
            i += 1
            continue

        # Block comment <# ... #>
        if ch == '<' and i + 1 < n and text[i + 1] == '#':
            start_line = line
            i += 2
            closed = False
            while i < n:
                if text[i] == '#' and i + 1 < n and text[i + 1] == '>':
                    i += 2
                    closed = True
                    break
                if text[i] == '\n':
                    out.append('\n')
                    line += 1
                i += 1
            if not closed:
                errors.append(f'Unclosed block comment starting at line {start_line}')
            out.append(' ')
            continue

        # Line comment # ...
        if ch == '#':
            while i < n and text[i] != '\n':
                i += 1
            out.append(' ')
            continue

        # Here-strings @' ... '@ or @" ... "@
        if ch == '@' and i + 1 < n and text[i + 1] in ("'", '"') and (i + 2 < n and text[i + 2] == '\n'):
            quote = text[i + 1]
            start_line = line
            i += 3
            out.append('\n')
            line += 1
            closed = False
            while i < n:
                # Closing terminator must be at column 0: '<quote>@' followed by newline/EOF/punctuation/space
                if text[i] == quote and i + 1 < n and text[i + 1] == '@' and (i == 0 or text[i - 1] == '\n'):
                    i += 2
                    closed = True
                    break
                if text[i] == '\n':
                    out.append('\n')
                    line += 1
                i += 1
            if not closed:
                errors.append(f'Unclosed here-string @{quote} starting at line {start_line}')
            out.append(' ')
            continue

        # Single-quoted string '...' (escaped via '')
        if ch == "'":
            start_line = line
            i += 1
            closed = False
            while i < n:
                if text[i] == "'":
                    if i + 1 < n and text[i + 1] == "'":
                        i += 2
                        continue
                    i += 1
                    closed = True
                    break
                if text[i] == '\n':
                    out.append('\n')
                    line += 1
                i += 1
            if not closed:
                errors.append(f"Unclosed single-quoted string starting at line {start_line}")
            out.append(' ')
            continue

        # Double-quoted string "..." (supports backtick escapes and $(...) subexpressions)
        if ch == '"':
            start_line = line
            i += 1
            closed = False
            sub_depth = 0
            while i < n:
                c = text[i]
                if c == '`' and i + 1 < n:
                    if text[i + 1] == '\n':
                        out.append('\n')
                        line += 1
                    i += 2
                    continue
                if c == '$' and i + 1 < n and text[i + 1] == '(':
                    sub_depth += 1
                    i += 2
                    continue
                if sub_depth > 0:
                    if c == '(':
                        sub_depth += 1
                    elif c == ')':
                        sub_depth -= 1
                    elif c == '\n':
                        out.append('\n')
                        line += 1
                    i += 1
                    continue
                if c == '"':
                    i += 1
                    closed = True
                    break
                if c == '\n':
                    out.append('\n')
                    line += 1
                i += 1
            if not closed:
                errors.append(f'Unclosed double-quoted string starting at line {start_line}')
            out.append(' ')
            continue

        # Delimiters
        if ch in matching:
            stack.append((ch, line))
        elif ch in closers:
            if not stack:
                errors.append(f"Unexpected closing '{ch}' at line {line}")
            else:
                top, top_line = stack.pop()
                if top != closers[ch]:
                    errors.append(
                        f"Mismatched delimiter '{ch}' at line {line} (opened '{top}' at line {top_line})"
                    )

        out.append(ch)
        i += 1

    for top, top_line in stack:
        errors.append(f"Unclosed delimiter '{top}' opened at line {top_line}")

    return ''.join(out), errors


def _docker_available() -> bool:
    if not shutil.which('docker'):
        return False
    res = subprocess.run(['docker', 'info'], capture_output=True, text=True)
    return res.returncode == 0


def _docker_image_present(image: str) -> bool:
    res = subprocess.run(['docker', 'image', 'inspect', image], capture_output=True, text=True)
    return res.returncode == 0


class TestWindowsEncodingAndLineEndings(unittest.TestCase):
    def test_install_ps1_utf8_bom_and_crlf(self) -> None:
        raw = INSTALL_PS1.read_bytes()
        self.assertTrue(raw.startswith(b'\xef\xbb\xbf'), 'install.ps1 must start with UTF-8 BOM (ef bb bf)')
        text = raw.decode('utf-8-sig')
        self.assertIn('鲸选美学 CRM', text)
        self.assertIn(b'\r\n', raw, 'install.ps1 must use CRLF line endings')
        self.assertEqual(raw.replace(b'\r\n', b'').count(b'\n'), 0, 'install.ps1 must not contain bare LF')
        self.assertEqual(raw.replace(b'\r\n', b'').count(b'\r'), 0, 'install.ps1 must not contain bare CR')

    def test_bat_files_crlf_ascii_bypass_and_safe_pause(self) -> None:
        for bat in (INSTALL_BAT, MANAGE_BAT):
            with self.subTest(file=bat.name):
                raw = bat.read_bytes()
                self.assertFalse(raw.startswith(b'\xef\xbb\xbf'), f'{bat.name} must not have UTF-8 BOM')
                self.assertTrue(all(b < 128 for b in raw), f'{bat.name} must be pure ASCII to avoid cmd.exe codepage garbling')
                self.assertIn(b'\r\n', raw, f'{bat.name} must use CRLF line endings')
                self.assertEqual(raw.replace(b'\r\n', b'').count(b'\n'), 0, f'{bat.name} must not contain bare LF')
                text = raw.decode('ascii')
                self.assertIn('-NoProfile -ExecutionPolicy Bypass -File', text)
                self.assertIn('install.ps1', text)
                self.assertIn('WHALE_CRM_NO_PAUSE', text)
                self.assertIn('pause', text.lower())
                # Regression guard: cmd.exe pipe subshells do NOT inherit EnableDelayedExpansion
                self.assertNotRegex(
                    text,
                    r'echo\(?![A-Za-z0-9_]+!\s*\|',
                    f'{bat.name} must not pipe !VAR! delayed expansion across | (breaks double-click pause in cmd.exe)',
                )
        self.assertIn('menu', MANAGE_BAT.read_text(encoding='ascii'))

    def test_runtime_files_written_as_utf8_no_bom_lf(self) -> None:
        text = _read_ps1_text()
        self.assertIn('New-Object System.Text.UTF8Encoding($false)', text)
        self.assertIn('function Write-Utf8NoBomLf', text)
        self.assertIn('-replace "`r`n", "`n"', text)
        # Ensure PS 5.1 BOM/UTF-16-producing file cmdlets are not used
        code_only, errors = _strip_ps_strings_and_comments(text)
        self.assertEqual(errors, [])
        self.assertNotRegex(code_only, r'\bOut-File\b')
        self.assertNotRegex(code_only, r'\bSet-Content\b')
        # Verify key runtime outputs go through Write-Utf8NoBomLf
        for target_var in ('$Script:EnvFile', '$caddyOut', '$Script:CredentialFile', '$bootstrapEnv', '$tmpCertPath', '$tmpKeyPath', '$serverCrt', '$serverKey'):
            self.assertRegex(
                text,
                rf'Write-Utf8NoBomLf\s+-Path\s+{re.escape(target_var)}\b',
                f'Expected {target_var} to be written via Write-Utf8NoBomLf',
            )


class TestWindowsInstallerSecurityAndParity(unittest.TestCase):
    def setUp(self) -> None:
        self.text = _read_ps1_text()
        self.code_only, self.lex_errors = _strip_ps_strings_and_comments(self.text)

    def test_defaults_https_cookie_secure_and_http_guard(self) -> None:
        self.assertRegex(self.text, r"\[string\]\$HttpPort\s*=\s*'48080'")
        self.assertRegex(self.text, r"\[string\]\$HttpsPort\s*=\s*'48443'")
        self.assertRegex(self.text, r"\[string\]\$ProjectName\s*=\s*'whale-crm'")
        self.assertIn("Set-EnvValue -Key 'COOKIE_SECURE' -Value 'true'", self.text)
        self.assertIn('AllowInsecureHttp', self.text)
        self.assertIn('生产环境必须使用 HTTPS', self.text)
        self.assertIn('Caddyfile.sameorigin', self.text)
        self.assertIn('Caddyfile.core', self.text)
        self.assertIn('Caddyfile.files', self.text)
        self.assertIn("FILES_PROXY_MODE", self.text)
        self.assertIn("same-origin", self.text)

    def test_zero_host_linux_dependencies_and_native_rng(self) -> None:
        self.assertIn('System.Security.Cryptography.RandomNumberGenerator', self.text)
        # Secret sizes must match install.sh
        expected_secrets = [
            ("MYSQL_ROOT_PASSWORD", "New-RandomHex -ByteCount 24"),
            ("MYSQL_PASSWORD", "New-RandomHex -ByteCount 24"),
            ("REDIS_PASSWORD", "New-RandomHex -ByteCount 24"),
            ("MINIO_ROOT_USER", "New-RandomHex -ByteCount 12"),
            ("MINIO_ROOT_PASSWORD", "New-RandomHex -ByteCount 32"),
            ("JWT_SECRET", "New-RandomHex -ByteCount 48"),
            ("APP_AES_KEY_BASE64", "New-RandomBase64 -ByteCount 32"),
            ("APP_BLIND_INDEX_PEPPER", "New-RandomHex -ByteCount 48"),
            ("AI_INTERNAL_TOKEN", "New-RandomHex -ByteCount 32"),
        ]
        for key, generator in expected_secrets:
            self.assertRegex(
                self.text,
                rf"Set-EnvValue\s+-Key\s+'{re.escape(key)}'\s+-Value\s+.*{re.escape(generator)}",
                f'Missing or mismatched native RNG secret generation for {key}',
            )
        # Host PowerShell code (outside container here-strings/comments) must never invoke Linux/host OpenSSL/tar/gzip/curl
        for forbidden_cmd in ('chmod', 'sed', 'awk', 'ss', 'lsof', 'firewall-cmd', 'chcon', 'getenforce', 'openssl', 'tar', 'gzip', 'curl', 'mysqldump'):
            self.assertNotRegex(
                self.code_only,
                rf'(?<![A-Za-z0-9_-]){re.escape(forbidden_cmd)}(?![A-Za-z0-9_-])',
                f'Host PowerShell code must not invoke {forbidden_cmd!r}',
            )

    def test_offline_container_openssl_certs_and_custom_cert_validation(self) -> None:
        self.assertIn("$Script:OpenSslImage = 'mysql:8.4.8'", self.text)
        self.assertIn('Assert-OfflineImagePresent', self.text)
        self.assertIn('openssl x509 -in /work/cert.pem -noout', self.text)
        self.assertIn('openssl pkey -in /work/key.pem -noout', self.text)
        self.assertIn('openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650', self.text)
        self.assertIn('openssl x509 -req -in /work/server.csr', self.text)
        self.assertIn('openssl verify -CAfile /certs/ca.crt /certs/server.crt', self.text)
        self.assertIn('CN = Whale CRM Local Root CA', self.text)
        self.assertIn('IP:127.0.0.1,DNS:localhost', self.text)

    def test_icacls_acl_hardening_on_all_sensitive_files(self) -> None:
        self.assertIn('icacls.exe', self.text)
        self.assertIn('/inheritance:r', self.text)
        self.assertIn('/grant:r', self.text)
        self.assertIn('*S-1-5-18:(F)', self.text)
        for secret_var in ('$Script:EnvFile', '$tmpEnv', '$Script:CredentialFile', '$bootstrapEnv', '$caKey', '$serverKey', '$tmpKeyPath', '$destination'):
            self.assertRegex(
                self.text,
                rf'Protect-SecretFile\s+-Path\s+{re.escape(secret_var)}\b',
                f'Expected Protect-SecretFile on {secret_var}',
            )

    def test_no_secret_stdout_or_cli_arg_leakage(self) -> None:
        # Admin bootstrap: --env-from-file + try/finally cleanup + stdin fallback, never password on CLI or stdout
        self.assertIn('--env-from-file', self.text)
        self.assertIn('IFS= read -r BOOTSTRAP_ADMIN_USERNAME', self.text)
        self.assertIn('BOOTSTRAP_ADMIN_PASSWORD', self.text)
        self.assertNotIn('-e BOOTSTRAP_ADMIN_PASSWORD=', self.text)
        self.assertNotRegex(self.text, r'Write-Host[^\r\n]*\$password\b')

        # MySQL dump: MYSQL_PWD passed via EnvironmentOverrides + -e MYSQL_PWD (no value on CLI)
        self.assertIn("'-e', 'MYSQL_PWD'", self.text)
        self.assertIn('EnvironmentOverrides @{ MYSQL_PWD = $mysqlRootPwd }', self.text)
        self.assertNotIn('-p"$MYSQL_ROOT_PASSWORD"', self.text)
        self.assertNotIn('--password=', self.text)

        # Env encryption: BACKUP_ENCRYPTION_PASSPHRASE passed via EnvironmentOverrides + -e without =value
        self.assertIn("'-e', 'BACKUP_ENCRYPTION_PASSPHRASE'", self.text)
        self.assertIn('EnvironmentOverrides @{ BACKUP_ENCRYPTION_PASSPHRASE = $passphrase }', self.text)
        self.assertIn('openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -pass env:BACKUP_ENCRYPTION_PASSPHRASE', self.text)
        self.assertNotIn('-pass pass:', self.text)
        self.assertIn('-AsSecureString', self.text)

    def test_binary_safe_container_backups_and_integrity_checks(self) -> None:
        # Backups mount $Script:BackupsDir directly into container and verify gzip -t inside container
        self.assertIn(':/backups', self.text)
        self.assertIn('mysqldump --single-transaction --quick --routines --triggers --hex-blob', self.text)
        self.assertIn('tar -C /data -czf "$out" .', self.text)
        self.assertGreaterEqual(self.text.count('gzip -t "$out"'), 2)
        # Never redirect docker stdout into .gz/.enc from PowerShell
        self.assertNotRegex(self.text, r'docker\b[^\r\n]*>\s*[^\r\n]*\.(sql\.gz|tar\.gz|env\.enc)')

    def test_windows_preflight_firewall_probe_and_subcommands(self) -> None:
        # Docker OSType == linux + arch + memory hint
        self.assertIn("'info', '--format', '{{.OSType}}'", self.text)
        self.assertIn("if ($osType -ne 'linux')", self.text)
        self.assertIn('Switch to Linux containers', self.text)
        self.assertIn('TARGET_PLATFORM=', self.text)
        self.assertIn('{{.MemTotal}}', self.text)

        # Active listeners + Hyper-V / WinNAT excluded port range check
        self.assertIn('Get-NetTCPConnection', self.text)
        self.assertIn('netstat.exe', self.text)
        self.assertIn("'excludedportrange', 'protocol=tcp'", self.text)
        self.assertIn('Hyper-V/WinNAT', self.text)

        # Proxy bypass + local CA validation + 127.0.0.1 fallback
        self.assertIn('req.Proxy = null;', self.text)
        self.assertIn('RemoteCertificateNameMismatch', self.text)
        self.assertIn('X509Chain', self.text)

        # Windows Defender Firewall + Trust CA
        self.assertIn('Get-NetFirewallRule', self.text)
        self.assertIn('New-NetFirewallRule', self.text)
        self.assertIn('X509Store', self.text)
        self.assertIn('StoreName]::Root', self.text)

        # All required subcommands
        for cmd in ('install', 'upgrade', 'start', 'stop', 'status', 'logs', 'backup', 'create-admin', 'trust-ca', 'menu'):
            self.assertRegex(self.text, rf"(?m)^\s*'{re.escape(cmd)}'\s*\{{", f'Missing subcommand handler: {cmd}')
        self.assertIn("'help'", self.text)

    def test_invoke_native_streaming_does_not_pollute_return_pipeline(self) -> None:
        match = re.search(r'function Invoke-NativeStreaming\s*\{(.*?)\r?\n\}', self.text, re.S)
        self.assertIsNotNone(match, 'Invoke-NativeStreaming function not found')
        body = match.group(1)
        # Must NOT emit raw `& $FileName @Arguments` stdout into return stream alongside `return $LASTEXITCODE`
        has_out_host = '| Out-Host' in body
        uses_process_inherit = 'System.Diagnostics.Process' in body and 'RedirectStandardOutput = $false' in body
        self.assertTrue(
            has_out_host or uses_process_inherit,
            'Invoke-NativeStreaming must pipe native stdout to Out-Host (or inherit console handles via Process) so $rc is strictly an integer exit code',
        )


class TestPowerShellStaticSyntax(unittest.TestCase):
    def test_install_ps1_syntax_structure_balanced(self) -> None:
        text = _read_ps1_text(INSTALL_PS1)
        code_only, errors = _strip_ps_strings_and_comments(text)
        self.assertEqual(errors, [], f'PowerShell syntax/balance errors in install.ps1: {errors}')
        self.assertRegex(code_only.lstrip(), r'^\[CmdletBinding\(\)\]\s*\nparam\s*\(')
        self.assertIn('Set-StrictMode -Version Latest', code_only)
        self.assertIn('$ErrorActionPreference =', code_only)

    def test_build_windows_ps1_syntax_structure_balanced(self) -> None:
        if not BUILD_WIN_PS1.is_file():
            self.skipTest('scripts/build-windows-installer.ps1 not present')
        raw = BUILD_WIN_PS1.read_bytes()
        self.assertTrue(raw.startswith(b'\xef\xbb\xbf'), 'build-windows-installer.ps1 must have UTF-8 BOM')
        text = raw.decode('utf-8-sig')
        code_only, errors = _strip_ps_strings_and_comments(text)
        self.assertEqual(errors, [], f'PowerShell syntax/balance errors in build-windows-installer.ps1: {errors}')
        self.assertIn('[CmdletBinding()]', code_only)


class TestWindowsDocumentationAlignment(unittest.TestCase):
    def test_readme_matches_filenames_subcommands_and_flags(self) -> None:
        ps1 = _read_ps1_text(INSTALL_PS1)
        readme = WIN_README.read_text(encoding='utf-8')

        # 1. Every full-installer .bat/.ps1 script under infra/production/windows/ must appear in README.md
        win_scripts = sorted(
            p.name for p in WIN_DIR.iterdir()
            if p.is_file() and p.suffix.lower() in ('.ps1', '.bat', '.cmd') and not p.name.startswith('apply-patch.')
        )
        self.assertEqual(win_scripts, ['install.bat', 'install.ps1', 'manage.bat'])
        for fname in win_scripts:
            self.assertIn(fname, readme, f'README.md missing script filename {fname}')

        # 2. Extract every CLI flag from install.ps1 param(...) block
        param_match = re.search(r'param\s*\((.*?)\r?\n\)', ps1, re.S)
        self.assertIsNotNone(param_match)
        param_block = param_match.group(1)
        ps1_flags = set(re.findall(r'\[(?:string|switch)\]\$([A-Za-z][A-Za-z0-9]*)', param_block))
        ps1_flags -= {'Command', 'ExtraArgs'}
        ps1_aliases = set(re.findall(r"\[Alias\('([^']+)'\)\]", param_block))
        all_valid_flags = ps1_flags | ps1_aliases

        for flag in sorted(ps1_flags):
            self.assertRegex(
                readme,
                rf'(?<![A-Za-z0-9-])-{re.escape(flag)}(?![A-Za-z0-9-])',
                f'README.md is missing CLI flag -{flag} defined in install.ps1',
            )

        # 3. Every -Flag used with .\install.ps1 (inline backticks or fenced PowerShell commands) must exist in install.ps1
        invocations: list[str] = []
        # Inline backtick spans like `.\install.ps1 install -Ip ...`
        for span in re.findall(r'`([^`\n]*install\.ps1[^`\n]*)`', readme):
            invocations.append(span)
        # Fenced PowerShell code blocks (joining PowerShell backtick line continuations)
        for block in re.findall(r'```powershell\r?\n(.*?)\r?\n```', readme, re.S):
            joined = re.sub(r'`\r?\n\s*', ' ', block)
            for line in joined.splitlines():
                stripped = line.strip()
                if stripped.startswith('#') or 'install.ps1' not in stripped:
                    continue
                invocations.append(stripped)

        self.assertTrue(invocations, 'Expected install.ps1 command examples in README.md')
        for cmd_line in invocations:
            after = cmd_line.split('install.ps1', 1)[1].split('#', 1)[0]
            for used_flag in re.findall(r'(?<!\S)-([A-Za-z][A-Za-z0-9]*)\b', after):
                if used_flag in ('NoProfile', 'ExecutionPolicy', 'File'):
                    continue
                self.assertIn(
                    used_flag,
                    all_valid_flags,
                    f'README.md references unknown install.ps1 flag -{used_flag} in command: {cmd_line}',
                )

        # 4. Every subcommand in install.ps1 must appear in README.md
        switch_match = re.search(r'switch\s*\(\$Command\)\s*\{(.*)\Z', ps1, re.S)
        self.assertIsNotNone(switch_match)
        subcommands = set(re.findall(r"(?m)^\s*'([a-z][a-z0-9-]*)'\s*\{", switch_match.group(1)))
        subcommands.add('help')
        for cmd in sorted(subcommands):
            self.assertRegex(
                readme,
                rf'(?<![A-Za-z0-9-]){re.escape(cmd)}(?![A-Za-z0-9-])',
                f'README.md missing subcommand {cmd}',
            )

        # 5. Core Windows operations & troubleshooting topics
        for topic in (
            'Docker Desktop',
            'OSType',
            '.wslconfig',
            '48443',
            'COOKIE_SECURE=true',
            'icacls',
            'Get-FileHash',
            'initial-admin.txt',
            'BACKUP_ENCRYPTION_PASSPHRASE',
            'excludedportrange',
            'New-NetFirewallRule',
            'runtime\\certs\\ca.crt',
        ):
            self.assertIn(topic, readme, f'README.md missing required Windows guidance topic: {topic}')


class TestWindowsPackagerScript(unittest.TestCase):
    def test_build_windows_installer_sh_syntax_and_contracts(self) -> None:
        self.assertTrue(BUILD_WIN_SH.is_file())
        proc = subprocess.run(['bash', '-n', str(BUILD_WIN_SH)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f'bash -n failed: {proc.stderr}')

        text = BUILD_WIN_SH.read_text(encoding='utf-8')
        self.assertIn('TARGET_OS=windows', text)
        self.assertIn('INSTALL_MODE=offline-images', text)
        self.assertIn('--skip-tests', text)
        self.assertIn('--reuse-images-from', text)
        self.assertIn(r'\xef\xbb\xbf', text)
        self.assertIn(r'\r\n', text)
        self.assertIn('ZIP_STORED', text)
        self.assertIn('ZIP_DEFLATED', text)
        self.assertIn('config --quiet', text)
        self.assertIn(':ro,z', text)

        # Verify normalization regex on real infra/production/compose.yml strips :ro,z -> :ro cleanly
        compose_src = (PROD_DIR / 'compose.yml').read_text(encoding='utf-8')
        self.assertIn(':ro,z', compose_src, 'Linux compose.yml should retain :ro,z untouched')
        compose_norm = re.sub(r':(ro|rw),[zZ](\b|$)', r':\1\2', compose_src, flags=re.MULTILINE)
        self.assertNotIn(':ro,z', compose_norm)
        self.assertNotIn(':ro,Z', compose_norm)
        self.assertIn('./runtime/Caddyfile:/etc/caddy/Caddyfile:ro', compose_norm)
        self.assertIn('./runtime/certs:/etc/caddy/certs:ro', compose_norm)


class TestOfflineDockerOpenSslSmoke(unittest.TestCase):
    def test_real_docker_openssl_certgen_validation_and_env_encryption(self) -> None:
        if not _docker_available() or not _docker_image_present('mysql:8.4.8'):
            self.skipTest('Docker or bundled mysql:8.4.8 image not available')

        ps1 = _read_ps1_text(INSTALL_PS1)
        cert_host = '192.168.10.25'
        san_list = f'IP:{cert_host},IP:127.0.0.1,DNS:localhost'

        ca_cnf = _extract_ps_double_quoted_herestring(ps1, 'caCnf')
        server_cnf = (
            _extract_ps_double_quoted_herestring(ps1, 'serverCnf')
            .replace('${certHost}', cert_host)
            .replace('${sanList}', san_list)
        )
        cert_gen_sh = _extract_ps_single_quoted_herestring(ps1, 'certGenScript')
        validate_sh = _extract_ps_single_quoted_herestring(ps1, 'validateScript')
        enc_sh = _extract_ps_single_quoted_herestring(ps1, 'encScript')

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            certs_dir = root / 'certs'
            work_dir = root / 'work'
            backups_dir = root / 'backups'
            certs_dir.mkdir()
            work_dir.mkdir()
            backups_dir.mkdir()

            (work_dir / 'ca.cnf').write_bytes((ca_cnf + '\n').encode('utf-8'))
            (work_dir / 'server.cnf').write_bytes((server_cnf + '\n').encode('utf-8'))

            # 1. Execute exact certGenScript inside bundled mysql:8.4.8
            gen = subprocess.run(
                [
                    'docker', 'run', '--rm',
                    '-v', f'{certs_dir}:/certs',
                    '-v', f'{work_dir}:/work',
                    '--entrypoint', 'sh',
                    'mysql:8.4.8',
                    '-c', cert_gen_sh,
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(gen.returncode, 0, f'certGenScript failed: {gen.stderr}\n{gen.stdout}')
            for fname in ('ca.crt', 'ca.key', 'server.crt', 'server.key'):
                fpath = certs_dir / fname
                self.assertTrue(fpath.is_file() and fpath.stat().st_size > 0, f'Missing generated {fname}')

            # Verify SAN + CA constraints + chain using container OpenSSL
            inspect_res = subprocess.run(
                [
                    'docker', 'run', '--rm',
                    '-v', f'{certs_dir}:/certs:ro',
                    '--entrypoint', 'sh',
                    'mysql:8.4.8',
                    '-c',
                    'set -eu; '
                    'openssl verify -CAfile /certs/ca.crt /certs/server.crt; '
                    'openssl x509 -in /certs/server.crt -noout -text; '
                    'echo "---CA---"; '
                    'openssl x509 -in /certs/ca.crt -noout -subject -text',
                ],
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertIn('OK', inspect_res.stdout)
            self.assertIn('IP Address:192.168.10.25', inspect_res.stdout)
            self.assertIn('IP Address:127.0.0.1', inspect_res.stdout)
            self.assertIn('DNS:localhost', inspect_res.stdout)
            self.assertIn('TLS Web Server Authentication', inspect_res.stdout)
            self.assertIn('Whale CRM Local Root CA', inspect_res.stdout)
            self.assertIn('CA:TRUE', inspect_res.stdout)

            # 2. Execute exact custom cert validateScript (valid, bad cert -> 11, bad key -> 12)
            check_dir = root / 'check'
            check_dir.mkdir()
            shutil.copy2(certs_dir / 'server.crt', check_dir / 'cert.pem')
            shutil.copy2(certs_dir / 'server.key', check_dir / 'key.pem')
            ok_val = subprocess.run(
                ['docker', 'run', '--rm', '-v', f'{check_dir}:/work:ro', '--entrypoint', 'sh', 'mysql:8.4.8', '-c', validate_sh],
                capture_output=True,
                text=True,
            )
            self.assertEqual(ok_val.returncode, 0, ok_val.stderr)

            (check_dir / 'cert.pem').write_text('not-a-cert\n', encoding='utf-8')
            bad_cert = subprocess.run(
                ['docker', 'run', '--rm', '-v', f'{check_dir}:/work:ro', '--entrypoint', 'sh', 'mysql:8.4.8', '-c', validate_sh],
                capture_output=True,
                text=True,
            )
            self.assertEqual(bad_cert.returncode, 11)

            shutil.copy2(certs_dir / 'server.crt', check_dir / 'cert.pem')
            (check_dir / 'key.pem').write_text('not-a-key\n', encoding='utf-8')
            bad_key = subprocess.run(
                ['docker', 'run', '--rm', '-v', f'{check_dir}:/work:ro', '--entrypoint', 'sh', 'mysql:8.4.8', '-c', validate_sh],
                capture_output=True,
                text=True,
            )
            self.assertEqual(bad_key.returncode, 12)

            # 3. Execute exact encScript for encrypted .env backup and verify decryption matches byte-for-byte
            sample_env = root / '.env'
            sample_content = 'PUBLIC_URL=https://192.168.10.25:48443\nJWT_SECRET=SYNTHETIC_JWT_SECRET_VALUE\n'
            sample_env.write_bytes(sample_content.encode('utf-8'))
            env_vars = os.environ.copy()
            env_vars['BACKUP_ENCRYPTION_PASSPHRASE'] = 'SyntheticWinBackupPass!2026'
            ts = '20261125T080000Z'
            enc_res = subprocess.run(
                [
                    'docker', 'run', '--rm',
                    '-e', 'BACKUP_ENCRYPTION_PASSPHRASE',
                    '-v', f'{sample_env}:/in/.env:ro',
                    '-v', f'{backups_dir}:/backups',
                    '--entrypoint', 'sh',
                    'mysql:8.4.8',
                    '-c', enc_sh,
                    '_', ts,
                ],
                env=env_vars,
                capture_output=True,
                text=True,
            )
            self.assertEqual(enc_res.returncode, 0, enc_res.stderr)
            enc_file = backups_dir / f'crm-env-{ts}.env.enc'
            self.assertTrue(enc_file.is_file() and enc_file.stat().st_size > 0)

            dec_res = subprocess.run(
                [
                    'docker', 'run', '--rm',
                    '-e', 'BACKUP_ENCRYPTION_PASSPHRASE',
                    '-v', f'{backups_dir}:/backups:ro',
                    '--entrypoint', 'sh',
                    'mysql:8.4.8',
                    '-c',
                    f'openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in /backups/{enc_file.name}',
                ],
                env=env_vars,
                capture_output=True,
                text=True,
                check=True,
            )
            self.assertEqual(dec_res.stdout, sample_content)


class TestBuiltWindowsZipDeliverable(unittest.TestCase):
    def test_release_windows_zip_checksum_contents_arch_and_compose(self) -> None:
        zips = sorted(
            p for p in RELEASE_DIR.glob('whale-crm-*-windows-*.zip')
            if not p.name.startswith('whale-crm-patch-')
        ) if RELEASE_DIR.is_dir() else []
        if not zips:
            if os.environ.get('WHALE_CRM_REQUIRE_ZIP') == '1':
                self.fail(f'No built Windows installer zip found in {RELEASE_DIR}')
            self.skipTest('No built Windows installer zip in release/ yet')

        zip_path = zips[-1]
        sha_path = zip_path.with_suffix('.zip.sha256')
        self.assertTrue(sha_path.is_file(), f'Missing SHA256 file: {sha_path}')

        # 1. Verify SHA256 file format and digest
        sha_line = sha_path.read_text(encoding='ascii').strip()
        parts = sha_line.split('  ')
        self.assertEqual(len(parts), 2, f'Expected standard sha256sum two-space format: {sha_line!r}')
        expected_hash, recorded_name = parts
        self.assertEqual(recorded_name, zip_path.name)
        h = hashlib.sha256()
        with zip_path.open('rb') as f:
            for chunk in iter(lambda: f.read(4 * 1024 * 1024), b''):
                h.update(chunk)
        self.assertEqual(h.hexdigest(), expected_hash.lower(), 'SHA256 mismatch on built Windows zip')

        # 2. Verify ZIP integrity, compression strategy, and no secrets
        with zipfile.ZipFile(zip_path, 'r') as zf:
            self.assertIsNone(zf.testzip(), 'ZIP CRC check failed')
            infos = zf.infolist()
            names = [info.filename for info in infos]
            top_dirs = {name.split('/', 1)[0] for name in names}
            self.assertEqual(len(top_dirs), 1, f'Expected single root dir in ZIP, got {top_dirs}')
            root_name = next(iter(top_dirs))

            for info in infos:
                rel = info.filename[len(root_name) + 1:]
                self.assertNotIn('..', Path(info.filename).parts)
                self.assertNotIn(rel, ('.env', 'initial-admin.txt'))
                self.assertFalse(rel.endswith(('.pem', '.key')), f'Sensitive key/pem bundled: {rel}')
                if rel.startswith('images/') and rel.endswith('.tar.gz'):
                    self.assertEqual(info.compress_type, zipfile.ZIP_STORED, f'{rel} should use ZIP_STORED')
                elif not info.is_dir():
                    self.assertEqual(info.compress_type, zipfile.ZIP_DEFLATED, f'{rel} should use ZIP_DEFLATED')

            # 3. Extract and inspect all required files, image tarballs, and compose.yml
            with tempfile.TemporaryDirectory() as td:
                zf.extractall(td)
                pkg = Path(td) / root_name

                required_files = [
                    'install.ps1', 'install.bat', 'manage.bat', 'README.md',
                    'compose.yml', '.env.example', 'Caddyfile.core', 'Caddyfile.files',
                    'Caddyfile.sameorigin', 'manifest.txt', 'images/images.txt',
                ]
                for rel in required_files:
                    self.assertTrue((pkg / rel).is_file(), f'Missing file in extracted zip: {rel}')

                # Encoding & freshness checks inside extracted package (must match repo sources byte-for-byte)
                for src_name in ('install.ps1', 'install.bat', 'manage.bat', 'README.md'):
                    self.assertEqual(
                        (pkg / src_name).read_bytes(),
                        (WIN_DIR / src_name).read_bytes(),
                        f'Packaged {src_name} differs from infra/production/windows/{src_name} (stale zip?)',
                    )
                for lf_name in ('.env.example', 'Caddyfile.core', 'Caddyfile.files', 'Caddyfile.sameorigin'):
                    lf_raw = (pkg / lf_name).read_bytes()
                    self.assertFalse(lf_raw.startswith(b'\xef\xbb\xbf'), f'Packaged {lf_name} must not have BOM')
                    self.assertNotIn(b'\r\n', lf_raw, f'Packaged {lf_name} must use LF line endings')

                # manifest.txt checks
                manifest_kv = dict(
                    line.split('=', 1)
                    for line in (pkg / 'manifest.txt').read_text(encoding='utf-8').splitlines()
                    if '=' in line
                )
                self.assertEqual(manifest_kv.get('TARGET_OS'), 'windows')
                self.assertEqual(manifest_kv.get('TARGET_PLATFORM'), 'linux/amd64')
                self.assertEqual(manifest_kv.get('INSTALL_MODE'), 'offline-images')
                release_ver = manifest_kv.get('RELEASE_VERSION', '')
                self.assertTrue(release_ver)
                self.assertIn(f'RELEASE_VERSION={release_ver}', (pkg / '.env.example').read_text(encoding='utf-8').splitlines())

                # compose.yml checks (no :ro,z, no BOM, LF only, passes docker compose config --quiet)
                compose_raw = (pkg / 'compose.yml').read_bytes()
                self.assertFalse(compose_raw.startswith(b'\xef\xbb\xbf'))
                self.assertNotIn(b'\r\n', compose_raw)
                compose_txt = compose_raw.decode('utf-8')
                self.assertNotIn(':ro,z', compose_txt)
                self.assertNotIn(':ro,Z', compose_txt)
                self.assertIn('./runtime/Caddyfile:/etc/caddy/Caddyfile:ro', compose_txt)
                self.assertIn('./runtime/certs:/etc/caddy/certs:ro', compose_txt)

                if _docker_available():
                    env_c = os.environ.copy()
                    env_c['RELEASE_VERSION'] = release_ver
                    cfg = subprocess.run(
                        [
                            'docker', 'compose',
                            '--env-file', str(TESTS_DIR / 'compose-test.properties'),
                            '-f', str(pkg / 'compose.yml'),
                            'config', '--quiet',
                        ],
                        env=env_c,
                        capture_output=True,
                        text=True,
                    )
                    self.assertEqual(cfg.returncode, 0, f'docker compose config failed: {cfg.stderr}')

                # Verify all 7 image tarballs exist, are linux/amd64, and cover all 10 images in images.txt
                expected_tarballs = {
                    'application.tar.gz', 'mysql.tar.gz', 'redis.tar.gz',
                    'kafka.tar.gz', 'minio.tar.gz', 'clamav.tar.gz', 'caddy.tar.gz',
                }
                actual_tarballs = {p.name for p in (pkg / 'images').glob('*.tar.gz')}
                self.assertEqual(actual_tarballs, expected_tarballs)

                expected_images = [
                    ln.strip()
                    for ln in (pkg / 'images' / 'images.txt').read_text(encoding='utf-8').splitlines()
                    if ln.strip()
                ]
                self.assertEqual(len(expected_images), 10)

                def _norm_ref(ref: str) -> str:
                    for prefix in ('docker.io/library/', 'docker.io/'):
                        if ref.startswith(prefix):
                            return ref[len(prefix):]
                    return ref

                found_tags: set[str] = set()
                for tb_name in sorted(expected_tarballs):
                    with tarfile.open(pkg / 'images' / tb_name, 'r:gz') as tf:
                        m = json.loads(tf.extractfile('manifest.json').read())
                        self.assertTrue(m, f'{tb_name}: empty manifest.json')
                        for entry in m:
                            cfg_json = json.loads(tf.extractfile(entry['Config']).read())
                            actual_plat = f"{cfg_json.get('os')}/{cfg_json.get('architecture')}"
                            self.assertEqual(actual_plat, 'linux/amd64', f'{tb_name} {entry.get("RepoTags")}: {actual_plat}')
                            for tag in entry.get('RepoTags') or []:
                                found_tags.add(_norm_ref(tag))

                missing = [img for img in expected_images if _norm_ref(img) not in found_tags]
                self.assertEqual(missing, [], f'Images listed in images.txt missing from tarballs: {missing}')


if __name__ == '__main__':
    unittest.main()
