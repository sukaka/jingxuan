﻿#Requires -Version 5.1
<#
.SYNOPSIS
    在 Windows 主机（Docker Desktop Linux 容器模式）上构建鲸选美学 CRM Windows 离线安装包 (.zip + .zip.sha256)。
.EXAMPLE
    .\scripts\build-windows-installer.ps1 -SkipTests
    .\scripts\build-windows-installer.ps1 -SkipTests -ReuseImagesFrom release\whale-crm-2.0.2-linux-amd64.tar.gz
#>
[CmdletBinding()]
param(
    [string]$Version = $(if ($env:CRM_RELEASE_VERSION) { $env:CRM_RELEASE_VERSION } else { '2.0.2' }),
    [ValidateSet('linux/amd64', 'linux/arm64')]
    [string]$Platform = $(if ($env:CRM_TARGET_PLATFORM) { $env:CRM_TARGET_PLATFORM } else { 'linux/amd64' }),
    [switch]$SkipTests,
    [string]$ReuseImagesFrom = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^[A-Za-z0-9._-]+$') {
    throw "版本号格式不正确：$Version"
}

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $Root
try {
    foreach ($tool in @('docker', 'python')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            if ($tool -eq 'python' -and (Get-Command 'python3' -ErrorAction SilentlyContinue)) {
                continue
            }
            throw "未找到必需命令：$tool"
        }
    }
    $Python = if (Get-Command 'python3' -ErrorAction SilentlyContinue) { 'python3' } else { 'python' }

    $Arch = $Platform -replace '^linux/', ''
    $Name = "whale-crm-$Version-windows-$Arch"
    $OutputDir = Join-Path $Root 'release'
    $StageDir = Join-Path (Join-Path $OutputDir 'build') $Name
    $ZipPath = Join-Path $OutputDir "$Name.zip"
    $ShaPath = Join-Path $OutputDir "$Name.zip.sha256"
    $WinSrc = Join-Path $Root 'infra\production\windows'

    foreach ($req in @('install.ps1', 'install.bat', 'manage.bat', 'README.md')) {
        $reqPath = Join-Path $WinSrc $req
        if (-not (Test-Path -LiteralPath $reqPath -PathType Leaf)) {
            throw "缺少 Windows 安装包源文件：$reqPath"
        }
    }

    if (Test-Path -LiteralPath $StageDir) {
        Remove-Item -LiteralPath $StageDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Join-Path $StageDir 'images') -Force | Out-Null

    if ($SkipTests) {
        Write-Host '明确跳过测试；仅在已单独完成验证时使用。'
    } else {
        & (Join-Path $Root 'mvnw.cmd') -B -pl apps/crm-api -am verify
        if ($LASTEXITCODE -ne 0) { throw '后端验证失败' }
        & npm --prefix apps/crm-web run test
        if ($LASTEXITCODE -ne 0) { throw '前端测试失败' }
    }

    foreach ($fname in @('install.ps1', 'install.bat', 'manage.bat', 'README.md')) {
        Copy-Item -LiteralPath (Join-Path $WinSrc $fname) -Destination (Join-Path $StageDir $fname) -Force
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    foreach ($cf in @('Caddyfile.core', 'Caddyfile.files', 'Caddyfile.sameorigin')) {
        $cfText = [System.IO.File]::ReadAllText((Join-Path $Root "infra\production\$cf"), $utf8NoBom).TrimStart([char]0xFEFF) -replace "`r`n", "`n"
        [System.IO.File]::WriteAllText((Join-Path $StageDir $cf), $cfText, $utf8NoBom)
    }
    $composeSrc = [System.IO.File]::ReadAllText((Join-Path $Root 'infra\production\compose.yml'), $utf8NoBom) -replace "`r`n", "`n"
    $composeNorm = [System.Text.RegularExpressions.Regex]::Replace($composeSrc, ':(ro|rw),[zZ](\b|$)', ':$1$2', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($composeNorm -match ':(ro|rw),[zZ]') {
        throw 'Windows compose.yml 仍残留 SELinux 挂载选项'
    }
    [System.IO.File]::WriteAllText((Join-Path $StageDir 'compose.yml'), $composeNorm, $utf8NoBom)

    $envLines = ([System.IO.File]::ReadAllText((Join-Path $Root 'infra\production\.env.example'), $utf8NoBom) -replace "`r`n", "`n").Split("`n")
    $replaced = $false
    for ($i = 0; $i -lt $envLines.Count; $i++) {
        if ($envLines[$i].StartsWith('RELEASE_VERSION=')) {
            $envLines[$i] = "RELEASE_VERSION=$Version"
            $replaced = $true
        }
    }
    if (-not $replaced) { throw '.env.example 中未找到 RELEASE_VERSION=' }
    [System.IO.File]::WriteAllText((Join-Path $StageDir '.env.example'), ($envLines -join "`n"), $utf8NoBom)

    # 编码检查：install.ps1 必须 UTF-8 BOM，.bat 必须 CRLF
    $ps1Bytes = [System.IO.File]::ReadAllBytes((Join-Path $StageDir 'install.ps1'))
    if ($ps1Bytes.Length -lt 3 -or $ps1Bytes[0] -ne 0xEF -or $ps1Bytes[1] -ne 0xBB -or $ps1Bytes[2] -ne 0xBF) {
        throw 'install.ps1 必须以 UTF-8 BOM (ef bb bf) 开头'
    }
    Get-ChildItem -Path $StageDir -Filter '*.bat' -File | ForEach-Object {
        $raw = [System.IO.File]::ReadAllText($_.FullName, $utf8NoBom)
        if ($raw.Replace("`r`n", '').Contains("`n")) {
            throw "$($_.Name) 必须使用 CRLF 行尾"
        }
    }

    $prevVer = $env:RELEASE_VERSION
    try {
        $env:RELEASE_VERSION = $Version
        & docker compose --env-file (Join-Path $Root 'infra\production\tests\compose-test.properties') -f (Join-Path $StageDir 'compose.yml') config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'docker compose config 校验失败' }
    } finally {
        $env:RELEASE_VERSION = $prevVer
    }

    $Images = @(
        "whale-crm/crm-api:$Version",
        "whale-crm/web:$Version",
        "whale-crm/ai-worker:$Version",
        "whale-crm/clamav-proxy:$Version",
        'mysql:8.4.8',
        'redis:7.4.2-alpine',
        'apache/kafka:4.1.2',
        'minio/minio:RELEASE.2025-09-07T16-13-09Z',
        'clamav/clamav:1.4.3',
        'caddy:2.11.4-alpine'
    )

    $ArchiveGroups = @(
        @{ Name = 'application'; Images = $Images[0..3] },
        @{ Name = 'mysql';       Images = @($Images[4]) },
        @{ Name = 'redis';       Images = @($Images[5]) },
        @{ Name = 'kafka';       Images = @($Images[6]) },
        @{ Name = 'minio';       Images = @($Images[7]) },
        @{ Name = 'clamav';      Images = @($Images[8]) },
        @{ Name = 'caddy';       Images = @($Images[9]) }
    )

    $VerifyArchivePy = @'
import json, sys, tarfile
archive_path, expected_platform, *expected_refs = sys.argv[1:]
def norm_ref(ref):
    for prefix in ("docker.io/library/", "docker.io/"):
        if ref.startswith(prefix):
            return ref[len(prefix):]
    return ref
with tarfile.open(archive_path, "r:gz") as tf:
    manifest = json.loads(tf.extractfile("manifest.json").read())
    assert manifest, f"{archive_path}: empty manifest.json"
    found = set()
    for entry in manifest:
        cfg = json.loads(tf.extractfile(entry["Config"]).read())
        actual = f"{cfg.get('os')}/{cfg.get('architecture')}"
        if actual != expected_platform:
            raise SystemExit(f"错误：{archive_path} 架构为 {actual}，预期 {expected_platform}")
        for tag in entry.get("RepoTags") or []:
            found.add(norm_ref(tag))
    missing = [r for r in expected_refs if norm_ref(r) not in found]
    if missing:
        raise SystemExit(f"错误：{archive_path} 缺少镜像标签 {missing}")
'@

    if ($ReuseImagesFrom) {
        $ReuseScript = @'
from pathlib import Path
import shutil, sys, tarfile, zipfile
source = Path(sys.argv[1])
images_out = Path(sys.argv[2])
expected_images = sys.argv[3:]
expected_archives = {"application.tar.gz","mysql.tar.gz","redis.tar.gz","kafka.tar.gz","minio.tar.gz","clamav.tar.gz","caddy.tar.gz"}
needed = expected_archives | {"images.txt"}
if not source.exists():
    raise SystemExit(f"错误：路径不存在：{source}")
if source.is_dir():
    img_dir = source / "images" if (source / "images" / "images.txt").is_file() else source
    for fname in sorted(needed):
        f = img_dir / fname
        if not f.is_file():
            raise SystemExit(f"错误：缺少 {f}")
        shutil.copy2(f, images_out / fname)
elif source.name.endswith((".tar.gz", ".tgz")):
    extracted = set()
    with tarfile.open(source, "r:gz") as tf:
        for m in tf.getmembers():
            if not m.isfile(): continue
            parts = Path(m.name).parts
            if any(p in ("..", "") or p.startswith("/") for p in parts):
                raise SystemExit(f"非法路径：{m.name}")
            if len(parts) >= 2 and parts[-2] == "images" and parts[-1] in needed:
                with tf.extractfile(m) as src_f, open(images_out / parts[-1], "wb") as out_f:
                    shutil.copyfileobj(src_f, out_f, 1024 * 1024)
                extracted.add(parts[-1])
    if needed - extracted:
        raise SystemExit(f"缺少镜像文件：{sorted(needed - extracted)}")
elif source.name.endswith(".zip"):
    extracted = set()
    with zipfile.ZipFile(source, "r") as zf:
        for info in zf.infolist():
            if info.is_dir(): continue
            parts = Path(info.filename).parts
            if any(p in ("..", "") or p.startswith("/") for p in parts):
                raise SystemExit(f"非法路径：{info.filename}")
            if len(parts) >= 2 and parts[-2] == "images" and parts[-1] in needed:
                with zf.open(info, "r") as src_f, open(images_out / parts[-1], "wb") as out_f:
                    shutil.copyfileobj(src_f, out_f, 1024 * 1024)
                extracted.add(parts[-1])
    if needed - extracted:
        raise SystemExit(f"缺少镜像文件：{sorted(needed - extracted)}")
else:
    raise SystemExit(f"不支持的复用源：{source}")
actual = [ln.strip() for ln in (images_out / "images.txt").read_text(encoding="utf-8-sig").splitlines() if ln.strip()]
if actual != expected_images:
    raise SystemExit(f"images.txt 不匹配：预期 {expected_images}，实际 {actual}")
'@
        & $Python -c $ReuseScript $ReuseImagesFrom (Join-Path $StageDir 'images') @Images
        if ($LASTEXITCODE -ne 0) { throw '复用镜像校验失败' }
        foreach ($g in $ArchiveGroups) {
            $archivePath = Join-Path (Join-Path $StageDir 'images') "$($g.Name).tar.gz"
            & $Python -c $VerifyArchivePy $archivePath $Platform @($g.Images)
            if ($LASTEXITCODE -ne 0) { throw "复用镜像包架构/标签校验失败：$archivePath" }
        }
    } else {
        & (Join-Path $Root 'mvnw.cmd') -B -pl apps/crm-api -am -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'crm-api 打包失败' }
        & npm --prefix apps/crm-web ci
        if ($LASTEXITCODE -ne 0) { throw 'crm-web 依赖安装失败' }
        & npm --prefix apps/crm-web run build
        if ($LASTEXITCODE -ne 0) { throw 'crm-web 构建失败' }

        & docker build --platform $Platform --build-arg JAR_FILE=apps/crm-api/target/crm-api-0.1.1-SNAPSHOT.jar -t $Images[0] -f apps/crm-api/Dockerfile $Root
        if ($LASTEXITCODE -ne 0) { throw 'crm-api 镜像构建失败' }
        & docker build --platform $Platform -t $Images[1] -f apps/crm-web/Dockerfile $Root
        if ($LASTEXITCODE -ne 0) { throw 'web 镜像构建失败' }
        & docker build --platform $Platform -t $Images[2] -f apps/ai-worker/Dockerfile $Root
        if ($LASTEXITCODE -ne 0) { throw 'ai-worker 镜像构建失败' }
        & docker build --platform $Platform -t $Images[3] -f infra/production/clamav-proxy/Dockerfile infra/production/clamav-proxy
        if ($LASTEXITCODE -ne 0) { throw 'clamav-proxy 镜像构建失败' }

        foreach ($img in $Images[4..9]) {
            $localArch = (& docker image inspect --format '{{.Os}}/{{.Architecture}}' $img 2>$null)
            if ($env:CRM_PULL_IMAGES -ne 'always' -and $localArch -eq $Platform) {
                Write-Host "复用本地同架构镜像：$img ($localArch)"
            } else {
                & docker pull --platform $Platform $img
                if ($LASTEXITCODE -ne 0) { throw "拉取镜像失败：$img" }
            }
        }

        $saveHelp = (& docker save --help 2>&1) -join "`n"
        $usePlatformFlag = $saveHelp -match '--platform'
        $SavePy = @'
import gzip, subprocess, sys
out_path, use_plat, plat, *imgs = sys.argv[1:]
cmd = ["docker", "save"]
if use_plat == "1":
    cmd += ["--platform", plat]
cmd += imgs
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
with gzip.open(out_path, "wb", compresslevel=1) as gz:
    while True:
        chunk = proc.stdout.read(1024 * 1024)
        if not chunk:
            break
        gz.write(chunk)
rc = proc.wait()
if rc != 0:
    raise SystemExit(rc)
'@
        foreach ($g in $ArchiveGroups) {
            Write-Host "打包镜像：$($g.Name) ($Platform)"
            $archivePath = Join-Path (Join-Path $StageDir 'images') "$($g.Name).tar.gz"
            & $Python -c $SavePy $archivePath ($(if ($usePlatformFlag) { '1' } else { '0' })) $Platform @($g.Images)
            if ($LASTEXITCODE -ne 0) { throw "导出镜像失败：$($g.Name)" }
            & $Python -c $VerifyArchivePy $archivePath $Platform @($g.Images)
            if ($LASTEXITCODE -ne 0) { throw "校验镜像包失败：$archivePath" }
        }
    }

    [System.IO.File]::WriteAllText((Join-Path (Join-Path $StageDir 'images') 'images.txt'), (($Images -join "`n") + "`n"), $utf8NoBom)

    $buildTimeUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $manifestText = @(
        "RELEASE_VERSION=$Version"
        "TARGET_PLATFORM=$Platform"
        "TARGET_OS=windows"
        "BUILD_TIME_UTC=$buildTimeUtc"
        "INSTALL_MODE=offline-images"
    ) -join "`n"
    [System.IO.File]::WriteAllText((Join-Path $StageDir 'manifest.txt'), ($manifestText + "`n"), $utf8NoBom)

    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    $ZipPy = @'
import hashlib, os, sys, zipfile
from pathlib import Path
stage, root_name, zip_path, sha_path = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4])
tmp_zip = zip_path.with_suffix(".zip.tmp")
if tmp_zip.exists():
    tmp_zip.unlink()
with zipfile.ZipFile(tmp_zip, mode="w", allowZip64=True) as zf:
    for p in sorted(f for f in stage.rglob("*") if f.is_file()):
        rel = p.relative_to(stage).as_posix()
        arcname = f"{root_name}/{rel}"
        if rel.startswith("images/") and p.name.endswith(".tar.gz"):
            zf.write(p, arcname=arcname, compress_type=zipfile.ZIP_STORED)
        else:
            zf.write(p, arcname=arcname, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
with zipfile.ZipFile(tmp_zip, mode="r") as zf:
    bad = zf.testzip()
    if bad is not None:
        raise SystemExit(f"ZIP 校验失败：{bad}")
os.replace(tmp_zip, zip_path)
h = hashlib.sha256()
with open(zip_path, "rb") as f:
    for chunk in iter(lambda: f.read(4 * 1024 * 1024), b""):
        h.update(chunk)
sha_line = f"{h.hexdigest()}  {zip_path.name}\n"
sha_path.write_bytes(sha_line.encode("ascii"))
print(f"SHA256: {sha_line.strip()}")
'@
    & $Python -c $ZipPy $StageDir $Name $ZipPath $ShaPath
    if ($LASTEXITCODE -ne 0) { throw '生成 ZIP 或 SHA256 失败' }

    Remove-Item -LiteralPath $StageDir -Recurse -Force
    Get-Item -LiteralPath $ZipPath, $ShaPath | Format-Table Name, Length, LastWriteTime -AutoSize
} finally {
    Pop-Location
}
