[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$TargetDir = '',

    [switch]$Rollback,
    [string]$ToVersion = '',
    [switch]$SkipBackup,
    [Alias('h')]
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Ensure UTF-8 console decoding for native container stdout
$Script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
try {
    [Console]::OutputEncoding = $Script:Utf8NoBom
    $OutputEncoding = $Script:Utf8NoBom
} catch {
    # Ignore when running without an attached console
}

# Enable TLS 1.2 (and TLS 1.3 when supported) on Windows PowerShell 5.1 / .NET Framework
try {
    $secProto = [System.Net.ServicePointManager]::SecurityProtocol
    $secProto = $secProto -bor [System.Net.SecurityProtocolType]::Tls12
    try {
        $tls13 = [Enum]::Parse([System.Net.SecurityProtocolType], 'Tls13')
        $secProto = $secProto -bor $tls13
    } catch {
        # .NET Framework 4.x on older Windows may not define Tls13
    }
    [System.Net.ServicePointManager]::SecurityProtocol = $secProto
} catch {
    # Ignore if runtime manages TLS defaults automatically
}

$Script:PatchDir = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$Script:TargetRoot = ''
$Script:EnvFile = ''
$Script:ComposeFile = ''
$Script:RuntimeDir = ''
$Script:CertsDir = ''
$Script:BackupsDir = ''
$Script:LastVersionFile = ''
$Script:UseStandaloneCompose = $false
$Script:OpenSslImage = 'mysql:8.4.8'
$Script:AppServices = @('crm-api', 'ai-worker', 'web', 'clamav-proxy')
$Script:BaseInfraServices = @('mysql', 'redis', 'kafka', 'minio', 'clamav')

$Script:EffectiveUrl = ''
$Script:EffectiveHttpPort = '48080'
$Script:EffectiveHttpsPort = '48443'
$Script:CurrentVersion = 'unknown'
$Script:PatchVersion = ''
$Script:BackupSqlFile = ''
$Script:BackupEnvFile = ''

function Show-Usage {
    $usageText = @'
用法：
  # 离线增量升级（仅更新 4 个应用容器，不重启 MySQL/Redis/Kafka/MinIO/ClamAV/Caddy，不覆盖 .env 密钥与证书）
  apply-patch.bat <现有安装目录>
  .\apply-patch.ps1 <现有安装目录> [-SkipBackup]
  .\apply-patch.ps1 -TargetDir C:\whale-crm\whale-crm-2.0.2-windows-amd64
  .\apply-patch.ps1                          # 自动探测当前目录/父目录，或在交互模式下提示输入目录

  # 一键回滚应用版本（需本地保留旧版 4 个应用镜像）
  apply-patch.bat -Rollback <现有安装目录>
  .\apply-patch.ps1 -Rollback -TargetDir C:\whale-crm\whale-crm-2.0.2-windows-amd64 [-ToVersion 2.0.2] [-SkipBackup]

参数说明：
  -TargetDir DIR     已部署的鲸选美学 CRM 目录（含 .env 与 compose.yml），也可作为首个位置参数传入
  -SkipBackup        跳过升级前自动备份 MySQL 与 .env（不建议生产使用）
  -Rollback          回滚应用容器至上一版本（读取 <安装目录>\runtime\last-release-version）
  -ToVersion VERSION 配合 -Rollback 使用，显式指定回滚目标版本号（例如 2.0.2）
  -Help / -h         显示本帮助信息

环境变量：
  STARTUP_TIMEOUT              容器健康等待超时秒数，默认 600
  BACKUP_ENCRYPTION_PASSPHRASE 设置后通过容器 OpenSSL 使用 AES-256-CBC 加密备份 .env（输出 .env.enc）

说明：
  1. 本脚本仅修改目标目录 .env 中的 RELEASE_VERSION，绝不覆盖任何密钥、端口、证书或访问地址配置。
  2. 全过程不依赖宿主机安装 OpenSSL、tar、gzip、curl 或 mysqldump。
  3. 如增量版本含数据库迁移，Flyway 迁移仅向前执行，应用回滚不等同于数据库结构回滚。
'@
    Write-Host $usageText
}

function Fail {
    param([Parameter(Mandatory = $true)][string]$Message)
    [Console]::Error.WriteLine("错误：$Message")
    exit 1
}

function Write-Warn {
    param([Parameter(Mandatory = $true)][string]$Message)
    [Console]::Error.WriteLine($Message)
}

function Write-Utf8NoBomLf {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )
    $normalized = $Content -replace "`r`n", "`n" -replace "`r", "`n"
    [System.IO.File]::WriteAllText($Path, $normalized, $Script:Utf8NoBom)
}

function Read-Utf8Text {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Format-WindowsArgument {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Arg)
    if ($Arg.Length -eq 0) {
        return '""'
    }
    if ($Arg -notmatch '[\s"]') {
        return $Arg
    }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $backslashes = 0
    for ($i = 0; $i -lt $Arg.Length; $i++) {
        $ch = $Arg[$i]
        if ($ch -eq '\') {
            $backslashes++
        } elseif ($ch -eq '"') {
            [void]$sb.Append('\', ($backslashes * 2) + 1)
            [void]$sb.Append('"')
            $backslashes = 0
        } else {
            if ($backslashes -gt 0) {
                [void]$sb.Append('\', $backslashes)
                $backslashes = 0
            }
            [void]$sb.Append($ch)
        }
    }
    if ($backslashes -gt 0) {
        [void]$sb.Append('\', $backslashes * 2)
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Invoke-ProcessCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [string[]]$Arguments = @(),
        [string]$StandardInputText = $null,
        [hashtable]$EnvironmentOverrides = $null,
        [string]$WorkingDirectory = '',
        [switch]$UseSystemEncoding
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FileName
    $escapedArgs = @()
    foreach ($a in @($Arguments)) {
        $escapedArgs += (Format-WindowsArgument -Arg ([string]$a))
    }
    $psi.Arguments = ($escapedArgs -join ' ')
    $psi.WorkingDirectory = if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory)) { $WorkingDirectory } elseif (-not [string]::IsNullOrWhiteSpace($Script:TargetRoot)) { $Script:TargetRoot } else { $Script:PatchDir }
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    if ($null -ne $StandardInputText) {
        $psi.RedirectStandardInput = $true
    }
    if (-not $UseSystemEncoding) {
        $psi.StandardOutputEncoding = $Script:Utf8NoBom
        $psi.StandardErrorEncoding = $Script:Utf8NoBom
    }
    if ($null -ne $EnvironmentOverrides) {
        foreach ($key in $EnvironmentOverrides.Keys) {
            $psi.EnvironmentVariables[[string]$key] = [string]$EnvironmentOverrides[$key]
        }
    }

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    try {
        [void]$proc.Start()
        if ($null -ne $StandardInputText) {
            $stdinWriter = New-Object System.IO.StreamWriter($proc.StandardInput.BaseStream, $Script:Utf8NoBom)
            try {
                $stdinWriter.Write($StandardInputText)
                $stdinWriter.Flush()
            } finally {
                $stdinWriter.Close()
            }
        }
        $stderrTask = $proc.StandardError.ReadToEndAsync()
        $stdoutText = $proc.StandardOutput.ReadToEnd()
        $proc.WaitForExit()
        $stderrText = $stderrTask.GetAwaiter().GetResult()
        return [pscustomobject]@{
            ExitCode = $proc.ExitCode
            StdOut   = $stdoutText
            StdErr   = $stderrText
        }
    } finally {
        $proc.Dispose()
    }
}

function Invoke-NativeStreaming {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [string[]]$Arguments = @()
    )
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $global:LASTEXITCODE = 0
        & $FileName @Arguments | Out-Host
        if ($null -eq $LASTEXITCODE) {
            return 0
        }
        return [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

function Protect-SecretFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return
    }
    try {
        $userSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $icaclsExe = Join-Path $env:SystemRoot 'System32\icacls.exe'
        if (-not (Test-Path -LiteralPath $icaclsExe -PathType Leaf)) {
            $icaclsExe = 'icacls.exe'
        }
        $res = Invoke-ProcessCapture -FileName $icaclsExe -Arguments @(
            $Path,
            '/inheritance:r',
            '/grant:r', "*${userSid}:(F)",
            '/grant:r', '*S-1-5-18:(F)'
        ) -UseSystemEncoding
        if ($res.ExitCode -ne 0) {
            Write-Warn "警告：无法通过 icacls 收紧敏感文件权限（可能位于非 NTFS 分区）：$Path"
        }
    } catch {
        Write-Warn "警告：无法通过 icacls 收紧敏感文件权限：$Path（$($_.Exception.Message)）"
    }
}

function Find-Compose {
    $res = Invoke-ProcessCapture -FileName 'docker' -Arguments @('compose', 'version')
    if ($res.ExitCode -eq 0) {
        $Script:UseStandaloneCompose = $false
        return $true
    }
    $standalone = Get-Command 'docker-compose' -ErrorAction SilentlyContinue
    if ($null -ne $standalone) {
        $res2 = Invoke-ProcessCapture -FileName 'docker-compose' -Arguments @('version')
        if ($res2.ExitCode -eq 0 -and ($res2.StdOut + $res2.StdErr) -match '(^|\s)v?2\.') {
            $Script:UseStandaloneCompose = $true
            return $true
        }
    }
    return $false
}

function Invoke-ComposeStreaming {
    param([string[]]$ComposeArgs = @())
    Push-Location -LiteralPath $Script:TargetRoot
    try {
        if ($Script:UseStandaloneCompose) {
            $allArgs = @('--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
            return (Invoke-NativeStreaming -FileName 'docker-compose' -Arguments $allArgs)
        }
        $allArgs = @('compose', '--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
        return (Invoke-NativeStreaming -FileName 'docker' -Arguments $allArgs)
    } finally {
        Pop-Location
    }
}

function Invoke-ComposeCapture {
    param(
        [string[]]$ComposeArgs = @(),
        [string]$StandardInputText = $null,
        [hashtable]$EnvironmentOverrides = $null
    )
    if ($Script:UseStandaloneCompose) {
        $allArgs = @('--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
        return (Invoke-ProcessCapture -FileName 'docker-compose' -Arguments $allArgs -StandardInputText $StandardInputText -EnvironmentOverrides $EnvironmentOverrides -WorkingDirectory $Script:TargetRoot)
    }
    $allArgs = @('compose', '--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
    return (Invoke-ProcessCapture -FileName 'docker' -Arguments $allArgs -StandardInputText $StandardInputText -EnvironmentOverrides $EnvironmentOverrides -WorkingDirectory $Script:TargetRoot)
}

function Assert-Prerequisites {
    $dockerCmd = Get-Command 'docker' -ErrorAction SilentlyContinue
    if ($null -eq $dockerCmd) {
        Fail '未检测到 docker 命令。请先安装并启动 Docker Desktop（开启 WSL2 / Linux 容器模式）后重试。'
    }
    if (-not (Find-Compose)) {
        Fail '需要 Docker Compose v2（docker compose 插件或 docker-compose v2）。请升级 Docker Desktop 后重试。'
    }
    $infoRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('info', '--format', '{{.OSType}}')
    if ($infoRes.ExitCode -ne 0) {
        Fail '无法连接 Docker 引擎。请先启动 Docker Desktop，等待状态显示 Engine running 后重试。'
    }
    $osType = $infoRes.StdOut.Trim().ToLowerInvariant()
    if ($osType -ne 'linux') {
        Fail "检测到当前 Docker Desktop 处于 Windows 容器模式（OSType=${osType}），本系统要求 Linux 容器模式（WSL2）。请切换为 Linux 容器模式后重试。"
    }
}

function Assert-HostArchitecture {
    $manifestPath = Join-Path $Script:PatchDir 'manifest.txt'
    $targetPlatform = 'linux/amd64'
    if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
        foreach ($line in (Read-Utf8Text -Path $manifestPath) -split "`r?`n") {
            if ($line.StartsWith('TARGET_PLATFORM=', [System.StringComparison]::Ordinal)) {
                $parsed = $line.Substring('TARGET_PLATFORM='.Length).Trim()
                if (-not [string]::IsNullOrWhiteSpace($parsed)) {
                    $targetPlatform = $parsed
                }
            }
        }
    }

    $actualArch = ''
    $archRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('info', '--format', '{{.Architecture}}')
    if ($archRes.ExitCode -eq 0) {
        $actualArch = $archRes.StdOut.Trim().ToLowerInvariant()
    }
    if ([string]::IsNullOrWhiteSpace($actualArch)) {
        $actualArch = ([string]$env:PROCESSOR_ARCHITECTURE).Trim().ToLowerInvariant()
    }

    $normalizedActual = switch ($actualArch) {
        'x86_64'  { 'linux/amd64' }
        'amd64'   { 'linux/amd64' }
        'aarch64' { 'linux/arm64' }
        'arm64'   { 'linux/arm64' }
        default   { '' }
    }
    if (-not [string]::IsNullOrWhiteSpace($normalizedActual) -and $normalizedActual -ne $targetPlatform) {
        Fail "增量包目标架构为 ${targetPlatform}，当前 Docker/宿主机架构为 ${actualArch}（${normalizedActual}），请使用匹配架构的增量更新包。"
    }
}

function Test-InstallDirectory {
    param([string]$Candidate)
    if ([string]::IsNullOrWhiteSpace($Candidate)) {
        return $false
    }
    if (-not (Test-Path -LiteralPath $Candidate -PathType Container)) {
        return $false
    }
    return ((Test-Path -LiteralPath (Join-Path $Candidate '.env') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $Candidate 'compose.yml') -PathType Leaf))
}

function Resolve-TargetDirectory {
    $candidate = $TargetDir.Trim()
    if (-not [string]::IsNullOrWhiteSpace($candidate)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
            Fail "指定的安装目录不存在：$candidate"
        }
        $Script:TargetRoot = (Resolve-Path -LiteralPath $candidate).Path
        return
    }

    $cwd = (Get-Location).Path
    $cwdParent = Split-Path -Path $cwd -Parent
    $patchParent = Split-Path -Path $Script:PatchDir -Parent
    foreach ($probe in @($cwd, $cwdParent, $patchParent)) {
        if (Test-InstallDirectory -Candidate $probe) {
            $Script:TargetRoot = (Resolve-Path -LiteralPath $probe).Path
            Write-Host "已自动探测现有安装目录：$($Script:TargetRoot)"
            return
        }
    }

    $isInteractive = $true
    try {
        $isInteractive = (-not [Console]::IsInputRedirected)
    } catch {
        $isInteractive = $false
    }
    if ($isInteractive) {
        $inputDir = (Read-Host '请输入现有鲸选美学 CRM 安装目录路径（含 .env 与 compose.yml，例如 C:\whale-crm\whale-crm-2.0.2-windows-amd64）').Trim().Trim('"')
        if (-not [string]::IsNullOrWhiteSpace($inputDir) -and (Test-Path -LiteralPath $inputDir -PathType Container)) {
            $Script:TargetRoot = (Resolve-Path -LiteralPath $inputDir).Path
            return
        }
    }

    Fail '未在当前目录或父目录检测到 .env 与 compose.yml，请通过参数传入现有安装目录：apply-patch.bat <现有安装目录>'
}

function Get-EnvValue {
    param([Parameter(Mandatory = $true)][string]$Key)
    if (-not (Test-Path -LiteralPath $Script:EnvFile -PathType Leaf)) {
        return ''
    }
    $prefix = "${Key}="
    $found = ''
    foreach ($line in (Read-Utf8Text -Path $Script:EnvFile) -split "`r?`n") {
        if ($line.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
            $found = $line.Substring($prefix.Length)
        }
    }
    return $found
}

function Set-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value
    )
    if ($Value.Contains("`n") -or $Value.Contains("`r")) {
        Fail "$Key 不能包含换行"
    }
    $prefix = "${Key}="
    $existingContent = Read-Utf8Text -Path $Script:EnvFile
    $lines = @($existingContent -split "`r?`n")
    if ($lines.Count -gt 0 -and $lines[$lines.Count - 1] -eq '' -and $existingContent.EndsWith("`n")) {
        if ($lines.Count -eq 1) {
            $lines = @()
        } else {
            $lines = @($lines[0..($lines.Count - 2)])
        }
    }
    $outLines = New-Object System.Collections.Generic.List[string]
    $replaced = $false
    foreach ($line in $lines) {
        if (-not $replaced -and $line.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
            $outLines.Add("${prefix}${Value}")
            $replaced = $true
        } else {
            $outLines.Add($line)
        }
    }
    if (-not $replaced) {
        $outLines.Add("${prefix}${Value}")
    }
    $tmpEnv = "$($Script:EnvFile).tmp.$([Guid]::NewGuid().ToString('N'))"
    try {
        Write-Utf8NoBomLf -Path $tmpEnv -Content (($outLines -join "`n") + "`n")
        Protect-SecretFile -Path $tmpEnv
        [System.IO.File]::Copy($tmpEnv, $Script:EnvFile, $true)
        Protect-SecretFile -Path $Script:EnvFile
    } finally {
        if (Test-Path -LiteralPath $tmpEnv -PathType Leaf) {
            Remove-Item -LiteralPath $tmpEnv -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-TargetConfig {
    $Script:EnvFile = Join-Path $Script:TargetRoot '.env'
    $Script:ComposeFile = Join-Path $Script:TargetRoot 'compose.yml'
    $Script:RuntimeDir = Join-Path $Script:TargetRoot 'runtime'
    $Script:CertsDir = Join-Path $Script:RuntimeDir 'certs'
    $Script:BackupsDir = Join-Path $Script:TargetRoot 'backups'
    $Script:LastVersionFile = Join-Path $Script:RuntimeDir 'last-release-version'

    if (-not (Test-Path -LiteralPath $Script:EnvFile -PathType Leaf)) {
        Fail "目标目录缺少 .env：$($Script:EnvFile)"
    }
    if (-not (Test-Path -LiteralPath $Script:ComposeFile -PathType Leaf)) {
        Fail "目标目录缺少 compose.yml：$($Script:ComposeFile)"
    }
    if ((Read-Utf8Text -Path $Script:EnvFile).Contains('REPLACE_ON_INSTALL')) {
        Fail '目标目录 .env 仍包含 REPLACE_ON_INSTALL 占位符，请先完成首次安装后再执行增量更新。'
    }

    $Script:EffectiveUrl = Get-EnvValue -Key 'PUBLIC_URL'
    if ([string]::IsNullOrWhiteSpace($Script:EffectiveUrl)) {
        Fail '目标目录 .env 缺少 PUBLIC_URL 配置'
    }
    $Script:EffectiveHttpPort = Get-EnvValue -Key 'HTTP_PORT'
    if ([string]::IsNullOrWhiteSpace($Script:EffectiveHttpPort)) {
        $Script:EffectiveHttpPort = '48080'
    }
    $Script:EffectiveHttpsPort = Get-EnvValue -Key 'HTTPS_PORT'
    if ([string]::IsNullOrWhiteSpace($Script:EffectiveHttpsPort)) {
        $Script:EffectiveHttpsPort = '48443'
    }
    $ver = Get-EnvValue -Key 'RELEASE_VERSION'
    $Script:CurrentVersion = if ([string]::IsNullOrWhiteSpace($ver)) { 'unknown' } else { $ver.Trim() }
}

function Read-PatchVersion {
    $manifestPath = Join-Path $Script:PatchDir 'manifest.txt'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        Fail "增量包缺少 manifest.txt：$manifestPath"
    }
    $patchVer = ''
    $relVer = ''
    foreach ($line in (Read-Utf8Text -Path $manifestPath) -split "`r?`n") {
        if ($line.StartsWith('PATCH_VERSION=', [System.StringComparison]::Ordinal)) {
            $patchVer = $line.Substring('PATCH_VERSION='.Length).Trim()
        } elseif ($line.StartsWith('RELEASE_VERSION=', [System.StringComparison]::Ordinal)) {
            $relVer = $line.Substring('RELEASE_VERSION='.Length).Trim()
        }
    }
    $chosen = if (-not [string]::IsNullOrWhiteSpace($patchVer)) { $patchVer } else { $relVer }
    if ($chosen -cnotmatch '^[A-Za-z0-9._-]+$') {
        Fail "manifest.txt 中的版本号格式不正确：$chosen"
    }
    $Script:PatchVersion = $chosen
}

function Test-ServiceRunning {
    param([Parameter(Mandatory = $true)][string]$ServiceName)
    $cidRes = Invoke-ComposeCapture -ComposeArgs @('ps', '-q', $ServiceName)
    if ($cidRes.ExitCode -ne 0) {
        return $false
    }
    $cid = ($cidRes.StdOut -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($cid)) {
        return $false
    }
    $insRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('inspect', '--format', '{{.State.Running}}', $cid.Trim())
    return ($insRes.ExitCode -eq 0 -and $insRes.StdOut.Trim().ToLowerInvariant() -eq 'true')
}

function Ensure-BaseInfraRunning {
    $stopped = New-Object System.Collections.Generic.List[string]
    foreach ($svc in $Script:BaseInfraServices) {
        if (-not (Test-ServiceRunning -ServiceName $svc)) {
            $stopped.Add($svc)
        }
    }
    if ($stopped.Count -gt 0) {
        $startupTimeout = if ([string]::IsNullOrWhiteSpace($env:STARTUP_TIMEOUT)) { '600' } else { $env:STARTUP_TIMEOUT }
        Write-Host "检测到以下基础中间件容器尚未运行，先启动以保障应用依赖健康（不重建既有容器）：$($stopped -join ' ')"
        $rc = Invoke-ComposeStreaming -ComposeArgs (@('up', '-d', '--no-recreate', '--wait', '--wait-timeout', $startupTimeout) + $stopped.ToArray())
        if ($rc -ne 0) {
            Fail "启动基础中间件容器失败：$($stopped -join ' ')"
        }
    }
}

function Ensure-CaddyRunning {
    if (-not (Test-ServiceRunning -ServiceName 'caddy')) {
        $startupTimeout = if ([string]::IsNullOrWhiteSpace($env:STARTUP_TIMEOUT)) { '600' } else { $env:STARTUP_TIMEOUT }
        Write-Host '检测到反向代理容器 caddy 尚未运行，正在启动（不重建既有配置）...'
        $rc = Invoke-ComposeStreaming -ComposeArgs @('up', '-d', '--no-deps', '--no-recreate', '--wait', '--wait-timeout', $startupTimeout, 'caddy')
        if ($rc -ne 0) {
            Fail '启动 caddy 容器失败'
        }
    }
}

function Assert-OfflineImagePresent {
    param([Parameter(Mandatory = $true)][string]$ImageName)
    $inspectRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('image', 'inspect', $ImageName)
    if ($inspectRes.ExitCode -ne 0) {
        Fail "本地缺少应用镜像：${ImageName}"
    }
}

function Assert-AppImagesPresent {
    param([Parameter(Mandatory = $true)][string]$Version)
    $requiredImages = @(
        "whale-crm/crm-api:${Version}",
        "whale-crm/web:${Version}",
        "whale-crm/ai-worker:${Version}",
        "whale-crm/clamav-proxy:${Version}"
    )
    foreach ($img in $requiredImages) {
        Assert-OfflineImagePresent -ImageName $img
    }
}

function Backup-Database {
    param([Parameter(Mandatory = $true)][string]$Timestamp)
    if (-not (Test-Path -LiteralPath $Script:BackupsDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:BackupsDir -Force)
    }
    $destination = Join-Path $Script:BackupsDir "crm-pre-patch-${Timestamp}.sql.gz"
    $cidRes = Invoke-ComposeCapture -ComposeArgs @('ps', '-q', 'mysql')
    $mysqlCid = $cidRes.StdOut.Trim()
    if ($cidRes.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($mysqlCid)) {
        Fail '未找到运行中的 MySQL 容器，无法备份数据库（请先启动服务或使用 -SkipBackup）'
    }

    $netRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('inspect', '--format', '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}', $mysqlCid)
    $mysqlNetwork = (($netRes.StdOut.Trim() -split '\s+') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($mysqlNetwork)) {
        $proj = Get-EnvValue -Key 'COMPOSE_PROJECT_NAME'
        if ([string]::IsNullOrWhiteSpace($proj)) { $proj = 'whale-crm' }
        $mysqlNetwork = "${proj}_internal"
    }

    $imgRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('inspect', '--format', '{{.Config.Image}}', $mysqlCid)
    $mysqlImg = if ($imgRes.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($imgRes.StdOut.Trim())) { $imgRes.StdOut.Trim() } else { $Script:OpenSslImage }

    $mysqlRootPwd = Get-EnvValue -Key 'MYSQL_ROOT_PASSWORD'
    if ([string]::IsNullOrWhiteSpace($mysqlRootPwd)) {
        Fail '配置缺少 MYSQL_ROOT_PASSWORD，无法备份数据库'
    }

    $mountBackups = "$($Script:BackupsDir.TrimEnd('\')):/backups"
    $dumpScript = @'
set -eu
umask 077
ts="$1"
tmp="/backups/.crm-pre-patch-${ts}.sql.tmp"
out="/backups/crm-pre-patch-${ts}.sql.gz"
trap 'rm -f "$tmp"' EXIT
mysqldump --single-transaction --quick --routines --triggers --hex-blob -h mysql -uroot medical_crm > "$tmp"
test -s "$tmp"
gzip -c "$tmp" > "$out"
rm -f "$tmp"
test -s "$out"
gzip -t "$out"
'@
    $res = Invoke-ProcessCapture -FileName 'docker' -Arguments @(
        'run', '--rm',
        '--network', $mysqlNetwork,
        '-e', 'MYSQL_PWD',
        '-v', $mountBackups,
        '--entrypoint', 'sh',
        $mysqlImg,
        '-c', ($dumpScript -replace "`r`n", "`n"),
        '_', $Timestamp
    ) -EnvironmentOverrides @{ MYSQL_PWD = $mysqlRootPwd }

    if ($res.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-Item -LiteralPath $destination).Length -eq 0) {
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
        }
        Fail "数据库备份失败，已中止升级（未修改 .env 或容器）：$($res.StdErr.Trim())"
    }
    Protect-SecretFile -Path $destination
    $Script:BackupSqlFile = $destination
    Write-Host "数据库备份完成：$destination"
}

function Backup-Env {
    param([Parameter(Mandatory = $true)][string]$Timestamp)
    if (-not (Test-Path -LiteralPath $Script:BackupsDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:BackupsDir -Force)
    }
    $passphrase = [string]$env:BACKUP_ENCRYPTION_PASSPHRASE
    if (-not [string]::IsNullOrWhiteSpace($passphrase)) {
        Assert-OfflineImagePresent -ImageName $Script:OpenSslImage
        $destination = Join-Path $Script:BackupsDir "crm-env-pre-patch-${Timestamp}.env.enc"
        $mountBackups = "$($Script:BackupsDir.TrimEnd('\')):/backups"
        $mountEnv = "$($Script:EnvFile):/in/.env:ro"
        $encScript = @'
set -eu
umask 077
ts="$1"
out="/backups/crm-env-pre-patch-${ts}.env.enc"
openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in /in/.env -out "$out"
test -s "$out"
'@
        $res = Invoke-ProcessCapture -FileName 'docker' -Arguments @(
            'run', '--rm',
            '-e', 'BACKUP_ENCRYPTION_PASSPHRASE',
            '-v', $mountEnv,
            '-v', $mountBackups,
            '--entrypoint', 'sh',
            $Script:OpenSslImage,
            '-c', ($encScript -replace "`r`n", "`n"),
            '_', $Timestamp
        ) -EnvironmentOverrides @{ BACKUP_ENCRYPTION_PASSPHRASE = $passphrase }

        if ($res.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-Item -LiteralPath $destination).Length -eq 0) {
            if (Test-Path -LiteralPath $destination -PathType Leaf) {
                Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
            }
            Fail ".env 密钥文件加密备份失败，已中止升级：$($res.StdErr.Trim())"
        }
        Protect-SecretFile -Path $destination
        $Script:BackupEnvFile = $destination
        Write-Host "环境配置已加密备份：$destination"
    } else {
        $destination = Join-Path $Script:BackupsDir "crm-env-pre-patch-${Timestamp}.env"
        [System.IO.File]::Copy($Script:EnvFile, $destination, $true)
        Protect-SecretFile -Path $destination
        $Script:BackupEnvFile = $destination
        Write-Warn "警告：未设置 BACKUP_ENCRYPTION_PASSPHRASE，已以明文（已通过 icacls 限制权限）备份 .env 到 ${destination}。"
        Write-Host "环境配置备份完成：$destination"
    }
}

function Import-PatchImages {
    $imagesDir = Join-Path $Script:PatchDir 'images'
    $archive = Join-Path $imagesDir 'application.tar.gz'
    $imagesTxt = Join-Path $imagesDir 'images.txt'
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        Fail "增量包缺少应用镜像归档：$archive"
    }
    if (-not (Test-Path -LiteralPath $imagesTxt -PathType Leaf)) {
        Fail "增量包缺少镜像清单：$imagesTxt"
    }

    Write-Host '正在载入应用镜像：application.tar.gz'
    $rc = Invoke-NativeStreaming -FileName 'docker' -Arguments @('load', '-i', $archive)
    if ($rc -ne 0) {
        Fail '载入应用镜像失败：application.tar.gz'
    }

    foreach ($line in (Read-Utf8Text -Path $imagesTxt) -split "`r?`n") {
        $img = $line.Trim()
        if (-not [string]::IsNullOrWhiteSpace($img)) {
            Assert-OfflineImagePresent -ImageName $img
        }
    }
    Assert-AppImagesPresent -Version $Script:PatchVersion
}

function Save-LastVersion {
    param(
        [Parameter(Mandatory = $true)][string]$PreviousVersion,
        [Parameter(Mandatory = $true)][string]$TargetVersion
    )
    if ([string]::IsNullOrWhiteSpace($PreviousVersion) -or $PreviousVersion -eq 'unknown') {
        return
    }
    if (-not (Test-Path -LiteralPath $Script:RuntimeDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:RuntimeDir -Force)
    }
    $hasExisting = (Test-Path -LiteralPath $Script:LastVersionFile -PathType Leaf) -and ((Get-Item -LiteralPath $Script:LastVersionFile).Length -gt 0)
    if ($PreviousVersion -ne $TargetVersion -or (-not $hasExisting)) {
        Write-Utf8NoBomLf -Path $Script:LastVersionFile -Content "${PreviousVersion}`n"
    }
}

function Invoke-AppRollout {
    $cfgRc = Invoke-ComposeStreaming -ComposeArgs @('config', '--quiet')
    if ($cfgRc -ne 0) {
        Fail 'compose.yml 配置校验失败，请检查目标目录 .env 与 compose.yml'
    }
    $startupTimeout = if ([string]::IsNullOrWhiteSpace($env:STARTUP_TIMEOUT)) { '600' } else { $env:STARTUP_TIMEOUT }
    Write-Host "正在滚动更新 4 个应用容器（不重启 MySQL/Redis/Kafka/MinIO/ClamAV/Caddy）：$($Script:AppServices -join ' ')"
    $upArgs = @('up', '-d', '--no-deps', '--wait', '--wait-timeout', $startupTimeout) + $Script:AppServices
    $upRc = Invoke-ComposeStreaming -ComposeArgs $upArgs
    if ($upRc -ne 0) {
        [void](Invoke-ComposeStreaming -ComposeArgs @('ps'))
        Fail "滚动更新应用容器失败，请检查容器日志，或执行回滚：apply-patch.bat -Rollback `"$($Script:TargetRoot)`""
    }
    Ensure-CaddyRunning
    [void](Invoke-ComposeStreaming -ComposeArgs @('ps'))
}

$Script:HttpProbeInitialized = $false
$Script:HttpProbeAvailable = $false

function Initialize-HttpProbeType {
    if ($Script:HttpProbeInitialized) {
        return $Script:HttpProbeAvailable
    }
    $Script:HttpProbeInitialized = $true
    if ([System.Management.Automation.PSTypeName]'WhaleCrm.HttpProbe'.Type) {
        $Script:HttpProbeAvailable = $true
        return $true
    }
    $code = @'
using System;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;

namespace WhaleCrm {
    public static class HttpProbe {
        public static bool Check(string targetUrl, string caCertPath, int timeoutSeconds) {
            X509Certificate2 caCert = null;
            try {
                if (!string.IsNullOrEmpty(caCertPath)) {
                    caCert = new X509Certificate2(caCertPath);
                }
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(targetUrl);
                req.Method = "GET";
                req.Timeout = timeoutSeconds * 1000;
                req.ReadWriteTimeout = timeoutSeconds * 1000;
                req.AllowAutoRedirect = true;
                req.Proxy = null;

                if (caCert != null) {
                    string expectedThumbprint = caCert.Thumbprint;
                    byte[] caRaw = caCert.RawData;
                    req.ServerCertificateValidationCallback = delegate(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors sslErrors) {
                        if (sslErrors == SslPolicyErrors.None) {
                            return true;
                        }
                        if ((sslErrors & SslPolicyErrors.RemoteCertificateNameMismatch) != 0) {
                            return false;
                        }
                        if (cert == null) {
                            return false;
                        }
                        using (X509Chain customChain = new X509Chain())
                        using (X509Certificate2 localCa = new X509Certificate2(caRaw))
                        using (X509Certificate2 serverCert = new X509Certificate2(cert)) {
                            customChain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
                            customChain.ChainPolicy.VerificationFlags = X509VerificationFlags.AllowUnknownCertificateAuthority;
                            customChain.ChainPolicy.ExtraStore.Add(localCa);
                            if (!customChain.Build(serverCert)) {
                                return false;
                            }
                            X509ChainElement root = customChain.ChainElements[customChain.ChainElements.Count - 1];
                            return string.Equals(root.Certificate.Thumbprint, expectedThumbprint, StringComparison.OrdinalIgnoreCase);
                        }
                    };
                }

                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse()) {
                    int code = (int)resp.StatusCode;
                    return code >= 200 && code < 400;
                }
            } catch {
                return false;
            } finally {
                if (caCert != null) {
                    caCert.Dispose();
                }
            }
        }
    }
}
'@
    try {
        Add-Type -TypeDefinition $code -Language CSharp -ErrorAction Stop
        $Script:HttpProbeAvailable = $true
    } catch {
        $Script:HttpProbeAvailable = $false
    }
    return $Script:HttpProbeAvailable
}

function Test-HttpEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$TargetUrl,
        [string]$CaCertPath = '',
        [int]$TimeoutSeconds = 15
    )
    $effectiveCaPath = ''
    if (-not [string]::IsNullOrWhiteSpace($CaCertPath) -and (Test-Path -LiteralPath $CaCertPath -PathType Leaf)) {
        $effectiveCaPath = $CaCertPath
    }
    if (Initialize-HttpProbeType) {
        return [WhaleCrm.HttpProbe]::Check($TargetUrl, $effectiveCaPath, $TimeoutSeconds)
    }
    try {
        $iwrParams = @{
            Uri         = $TargetUrl
            Method      = 'Get'
            TimeoutSec  = $TimeoutSeconds
            ErrorAction = 'Stop'
        }
        $iwrCmd = Get-Command 'Invoke-WebRequest' -ErrorAction Stop
        if ($iwrCmd.Parameters.ContainsKey('NoProxy')) {
            $iwrParams['NoProxy'] = $true
        }
        if ($iwrCmd.Parameters.ContainsKey('UseBasicParsing')) {
            $iwrParams['UseBasicParsing'] = $true
        }
        $resp = Invoke-WebRequest @iwrParams
        return ([int]$resp.StatusCode -ge 200 -and [int]$resp.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Test-Access {
    $caCertPath = Join-Path $Script:CertsDir 'ca.crt'
    $useCaPath = ''
    if ($Script:EffectiveUrl.StartsWith('https://', [System.StringComparison]::Ordinal) -and
        (Test-Path -LiteralPath $caCertPath -PathType Leaf) -and
        ((Get-Item -LiteralPath $caCertPath).Length -gt 0)) {
        $useCaPath = $caCertPath
    }

    if ($Script:EffectiveUrl.StartsWith('https://', [System.StringComparison]::Ordinal)) {
        $localFallbackUrl = "https://127.0.0.1:$($Script:EffectiveHttpsPort)/login"
    } else {
        $localFallbackUrl = "http://127.0.0.1:$($Script:EffectiveHttpPort)/login"
    }
    $primaryUrl = "$($Script:EffectiveUrl)/login"
    $isLoopbackPrimary = ($Script:EffectiveUrl -match '^https?://(127\.0\.0\.1|localhost)(:[0-9]+)?$')

    for ($attempt = 1; $attempt -le 40; $attempt++) {
        if (Test-HttpEndpoint -TargetUrl $primaryUrl -CaCertPath $useCaPath -TimeoutSeconds 15) {
            Write-Host "HTTPS/访问探活通过：$primaryUrl"
            return
        }
        if ((-not $isLoopbackPrimary) -and ($attempt -ge 3)) {
            if (Test-HttpEndpoint -TargetUrl $localFallbackUrl -CaCertPath $useCaPath -TimeoutSeconds 10) {
                Write-Host "本机 HTTPS 回环探活通过：${localFallbackUrl}（从本机直连 ${primaryUrl} 未通，常见于防火墙策略或路由隔离，请从外部浏览器访问 ${primaryUrl} 验证）"
                return
            }
        }
        if ($attempt -lt 40) {
            Start-Sleep -Seconds 5
        }
    }

    [void](Invoke-ComposeStreaming -ComposeArgs @('ps'))
    Fail "应用容器已更新，但 $primaryUrl 暂不可访问。请检查应用与 Caddy 日志，或执行回滚：apply-patch.bat -Rollback `"$($Script:TargetRoot)`""
}

# --- Main Dispatch ---

if ($Help -or $TargetDir -in @('help', '-h', '--help', '/?')) {
    Show-Usage
    exit 0
}

if (-not [string]::IsNullOrWhiteSpace($ToVersion) -and (-not $Rollback)) {
    Fail '-ToVersion 仅可配合 -Rollback 使用'
}

if ($Rollback) {
    Assert-Prerequisites
    Resolve-TargetDirectory
    Read-TargetConfig

    $rollbackVer = $ToVersion.Trim()
    if ([string]::IsNullOrWhiteSpace($rollbackVer)) {
        if (-not (Test-Path -LiteralPath $Script:LastVersionFile -PathType Leaf)) {
            Fail "未找到上一版本记录（$($Script:LastVersionFile)），请通过 -ToVersion <版本号> 显式指定回滚目标版本。"
        }
        $rollbackVer = (Read-Utf8Text -Path $Script:LastVersionFile).Trim()
    }
    if ($rollbackVer -cnotmatch '^[A-Za-z0-9._-]+$') {
        Fail "回滚版本号格式不正确：$rollbackVer"
    }

    Write-Host '============================================================'
    Write-Host "鲸选美学 CRM 应用版本回滚：$($Script:CurrentVersion) -> ${rollbackVer}"
    Write-Host "目标安装目录：$($Script:TargetRoot)"
    Write-Host '============================================================'

    Assert-AppImagesPresent -Version $rollbackVer
    Ensure-BaseInfraRunning

    if (-not $SkipBackup) {
        $ts = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
        Backup-Env -Timestamp $ts
    }

    Write-Host "正在恢复 $($Script:EnvFile) 中的 RELEASE_VERSION=${rollbackVer}（其余配置保持不变）"
    Set-EnvValue -Key 'RELEASE_VERSION' -Value $rollbackVer

    Invoke-AppRollout
    Test-Access

    Write-Host @"

============================================================
鲸选美学 CRM 应用版本回滚完成
============================================================
安装目录：$($Script:TargetRoot)
版本回滚：$($Script:CurrentVersion) -> ${rollbackVer}
已重启服务：$($Script:AppServices -join ' ')（中间件容器均未重启）
访问地址：$($Script:EffectiveUrl)/login
提示：应用回滚仅切换应用镜像版本，不会回滚已执行的 Flyway 数据库迁移。
============================================================
"@
} else {
    Assert-Prerequisites
    Assert-HostArchitecture
    Resolve-TargetDirectory
    Read-TargetConfig
    Read-PatchVersion

    Write-Host '============================================================'
    Write-Host "鲸选美学 CRM 离线增量升级：$($Script:CurrentVersion) -> $($Script:PatchVersion)"
    Write-Host "目标安装目录：$($Script:TargetRoot)"
    Write-Host '============================================================'

    Ensure-BaseInfraRunning

    if ($SkipBackup) {
        Write-Host '提示：已指定 -SkipBackup，跳过 MySQL 与 .env 升级前备份。'
    } else {
        $ts = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
        Backup-Database -Timestamp $ts
        Backup-Env -Timestamp $ts
    }

    Import-PatchImages
    Save-LastVersion -PreviousVersion $Script:CurrentVersion -TargetVersion $Script:PatchVersion

    Write-Host "仅原地更新 $($Script:EnvFile) 中的 RELEASE_VERSION=$($Script:PatchVersion)（其余密钥、端口与证书保持不变）"
    Set-EnvValue -Key 'RELEASE_VERSION' -Value $Script:PatchVersion

    Invoke-AppRollout
    Test-Access

    Write-Host @"

============================================================
鲸选美学 CRM 离线增量升级完成
============================================================
安装目录：$($Script:TargetRoot)
版本变更：$($Script:CurrentVersion) -> $($Script:PatchVersion)
已更新服务：$($Script:AppServices -join ' ')（MySQL/Redis/Kafka/MinIO/ClamAV/Caddy 均未重启）
访问地址：$($Script:EffectiveUrl)/login
上一版本记录：$($Script:LastVersionFile)
"@
    if (-not [string]::IsNullOrWhiteSpace($Script:BackupSqlFile)) {
        Write-Host "数据库备份：$($Script:BackupSqlFile)"
    }
    if (-not [string]::IsNullOrWhiteSpace($Script:BackupEnvFile)) {
        Write-Host "环境配置备份：$($Script:BackupEnvFile)"
    }
    Write-Host @"
一键回滚命令：
  apply-patch.bat -Rollback "$($Script:TargetRoot)"
提示：若版本包含数据库迁移，Flyway 迁移仅向前执行，应用回滚不等同于数据库回滚。
============================================================
"@
}
