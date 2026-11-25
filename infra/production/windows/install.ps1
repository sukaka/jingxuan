[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Command = 'install',

    [string]$Ip = '',
    [switch]$AutoIp,
    [string]$Url = '',
    [string]$SiteAddress = '',
    [string]$FilesUrl = '',
    [string]$FilesSiteAddress = '',
    [string]$HttpPort = '48080',
    [string]$HttpsPort = '48443',
    [string]$TlsCert = '',
    [string]$TlsKey = '',
    [string]$ProjectName = 'whale-crm',
    [switch]$OpenFirewall,
    [switch]$AllowInsecureHttp,
    [switch]$SkipPortCheck,
    [switch]$TrustCa,
    [Alias('h')]
    [switch]$Help,

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Ensure UTF-8 console decoding for native container stdout (e.g. Chinese messages from crm-api)
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

$Script:Root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
Set-Location -LiteralPath $Script:Root

$Script:EnvFile = Join-Path $Script:Root '.env'
$Script:ComposeFile = Join-Path $Script:Root 'compose.yml'
$Script:RuntimeDir = Join-Path $Script:Root 'runtime'
$Script:CertsDir = Join-Path $Script:RuntimeDir 'certs'
$Script:CredentialFile = Join-Path $Script:Root 'initial-admin.txt'
$Script:BackupsDir = Join-Path $Script:Root 'backups'

# Bundled offline images verified to contain openssl/tar/gzip (zero host dependency)
$Script:OpenSslImage = 'mysql:8.4.8'
$Script:BackupTarImage = 'redis:7.4.2-alpine'

$Script:UseStandaloneCompose = $false
$Script:HttpPortSet = $PSBoundParameters.ContainsKey('HttpPort')
$Script:HttpsPortSet = $PSBoundParameters.ContainsKey('HttpsPort')
$Script:FilesProxyMode = ''
$Script:TlsMode = ''
$Script:EffectiveUrl = $Url.Trim()
$Script:EffectiveSiteAddress = $SiteAddress.Trim()
$Script:EffectiveFilesUrl = $FilesUrl.Trim()
$Script:EffectiveFilesSiteAddress = $FilesSiteAddress.Trim()
$Script:EffectiveIp = $Ip.Trim()
$Script:EffectiveHttpPort = $HttpPort.Trim()
$Script:EffectiveHttpsPort = $HttpsPort.Trim()
$Script:EffectiveTlsCert = $TlsCert.Trim()
$Script:EffectiveTlsKey = $TlsKey.Trim()
$Script:EffectiveProjectName = $ProjectName.Trim()

function Show-Usage {
    $usageText = @'
用法：
  # Windows（Docker Desktop Linux 容器模式）高位端口默认 HTTPS 一键安装（默认 HTTPS 48443）
  .\install.ps1
  .\install.ps1 install -Ip <本机局域网IP> [-HttpsPort 48443] [-HttpPort 48080] [-OpenFirewall] [-TrustCa]
  .\install.ps1 install -AutoIp [-HttpsPort 48443] [-OpenFirewall] [-TrustCa]

  # 使用自有商业/企业 SSL 证书部署高位端口 HTTPS
  .\install.ps1 install -Ip <本机局域网IP> -HttpsPort 48443 -TlsCert C:\certs\fullchain.pem -TlsKey C:\certs\privkey.pem
  .\install.ps1 install -Url https://crm.example.com:48443 -HttpsPort 48443 -TlsCert C:\certs\fullchain.pem -TlsKey C:\certs\privkey.pem

  # 公网标准域名 HTTPS 安装（使用独立系统域名与文件域名，Caddy 自动签发证书）
  .\install.ps1 install -Url https://crm.example.com -FilesUrl https://files.example.com -HttpPort 80 -HttpsPort 443

  # 日常维护、升级与根证书信任
  .\install.ps1 upgrade
  .\install.ps1 start | stop | status | backup | create-admin | trust-ca | menu
  .\install.ps1 logs [服务名 ...]
  .\install.ps1 help

首次安装参数：
  -Ip IP                   本机 IPv4（一键启用 https://<IP>:<HTTPS_PORT> 与同源 HTTPS 文件上传下载）
  -AutoIp                  自动探测本机主局域网 IPv4 并以 HTTPS 一键安装（适合无人值守）
  -Url URL                 系统访问地址，默认必须使用 HTTPS
  -SiteAddress URL         Caddy 监听站点，默认与系统地址相同（高位端口同源 HTTPS 模式默认 :443）
  -FilesUrl URL            文件公开访问地址，配置后启用上传下载（高位端口/IP 模式自动同源 HTTPS）
  -FilesSiteAddress URL    文件站点监听地址，默认与文件地址相同
  -HttpsPort PORT          宿主机 HTTPS 访问端口，默认 48443（40000+ 高位端口，避免冲突）
  -HttpPort PORT           宿主机 HTTP 重定向端口，默认 48080（仅 308 跳转至 HTTPS，不提供明文业务访问）
  -TlsCert FILE            自定义 SSL 证书文件路径（PEM/fullchain）；未指定时离线自动生成本地 CA+SAN 证书
  -TlsKey FILE             自定义 SSL 私钥文件路径（PEM，与 -TlsCert 成对使用）
  -ProjectName NAME        Docker Compose 项目名，默认 whale-crm（隔离容器/卷/网络）
  -OpenFirewall            自动添加 Windows Defender 防火墙入站 TCP 放行规则（需管理员权限，非管理员则输出可复制命令）
  -TrustCa                 安装完成后自动将本地根证书（runtime\certs\ca.crt）导入 Windows 受信任根证书存储
  -AllowInsecureHttp       仅限显式本机/隔离调试允许 HTTP（默认禁止）
  -SkipPortCheck           跳过安装前宿主机端口占用与 Hyper-V 保留端口段预检

端口与隔离说明：
  系统默认仅通过 HTTPS（默认 48443）对外提供访问，COOKIE_SECURE 强制为 true，HTTP 端口仅作 HTTPS 跳转。
  MySQL、Redis、Kafka、MinIO、ClamAV、crm-api、ai-worker、web 全部运行在独立 Docker 内部网络，不占用宿主机端口。
  全过程不依赖宿主机安装 OpenSSL / tar / gzip / curl。

备份说明（backup / upgrade）：
  同时备份 MySQL 数据库（backups\crm-*.sql.gz）、MinIO 对象存储（backups\crm-minio-*.tar.gz）
  及 .env 密钥文件（设置环境变量 BACKUP_ENCRYPTION_PASSPHRASE 时通过容器 OpenSSL 加密为 .env.enc）。

管理员密码只在 initial-admin.txt 中保存（并通过 icacls 限制访问权限），不会打印到终端。
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
        [switch]$UseSystemEncoding
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FileName
    $escapedArgs = @()
    foreach ($a in @($Arguments)) {
        $escapedArgs += (Format-WindowsArgument -Arg ([string]$a))
    }
    $psi.Arguments = ($escapedArgs -join ' ')
    $psi.WorkingDirectory = $Script:Root
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
        # Strip inherited ACLs and grant full control exclusively to current user SID and SYSTEM (S-1-5-18)
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

function New-RandomBytes {
    param([Parameter(Mandatory = $true)][int]$Count)
    $bytes = New-Object byte[] $Count
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return ,$bytes
}

function New-RandomHex {
    param([Parameter(Mandatory = $true)][int]$ByteCount)
    $bytes = New-RandomBytes -Count $ByteCount
    return ([System.BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}

function New-RandomBase64 {
    param([Parameter(Mandatory = $true)][int]$ByteCount)
    $bytes = New-RandomBytes -Count $ByteCount
    return [System.Convert]::ToBase64String($bytes)
}

function Test-IsAdministrator {
    try {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
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
    if ($Script:UseStandaloneCompose) {
        $allArgs = @('--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
        return (Invoke-NativeStreaming -FileName 'docker-compose' -Arguments $allArgs)
    }
    $allArgs = @('compose', '--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
    return (Invoke-NativeStreaming -FileName 'docker' -Arguments $allArgs)
}

function Invoke-ComposeCapture {
    param(
        [string[]]$ComposeArgs = @(),
        [string]$StandardInputText = $null,
        [hashtable]$EnvironmentOverrides = $null
    )
    if ($Script:UseStandaloneCompose) {
        $allArgs = @('--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
        return (Invoke-ProcessCapture -FileName 'docker-compose' -Arguments $allArgs -StandardInputText $StandardInputText -EnvironmentOverrides $EnvironmentOverrides)
    }
    $allArgs = @('compose', '--env-file', $Script:EnvFile, '-f', $Script:ComposeFile) + @($ComposeArgs)
    return (Invoke-ProcessCapture -FileName 'docker' -Arguments $allArgs -StandardInputText $StandardInputText -EnvironmentOverrides $EnvironmentOverrides)
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
        Fail '无法连接 Docker 引擎。请先启动 Docker Desktop，等待左下角状态显示 Engine running 后重试。'
    }
    $osType = $infoRes.StdOut.Trim().ToLowerInvariant()
    if ($osType -ne 'linux') {
        Fail "检测到当前 Docker Desktop 处于 Windows 容器模式（OSType=${osType}），本系统要求 Linux 容器模式（WSL2）。请在任务栏右下角右键点击 Docker Desktop 图标，选择「Switch to Linux containers...」，等待切换完成后重试。"
    }
}

function Assert-HostArchitecture {
    $manifestPath = Join-Path $Script:Root 'manifest.txt'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        return
    }
    $targetPlatform = ''
    foreach ($line in (Read-Utf8Text -Path $manifestPath) -split "`r?`n") {
        if ($line.StartsWith('TARGET_PLATFORM=', [System.StringComparison]::Ordinal)) {
            $targetPlatform = $line.Substring('TARGET_PLATFORM='.Length).Trim()
        }
    }
    if ([string]::IsNullOrWhiteSpace($targetPlatform)) {
        return
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
        'x86_64' { 'linux/amd64' }
        'amd64'  { 'linux/amd64' }
        'aarch64' { 'linux/arm64' }
        'arm64'  { 'linux/arm64' }
        default  { '' }
    }
    if (-not [string]::IsNullOrWhiteSpace($normalizedActual) -and $normalizedActual -ne $targetPlatform) {
        Fail "安装包目标架构为 ${targetPlatform}，当前 Docker/宿主机架构为 ${actualArch}（${normalizedActual}），请使用匹配架构的安装包。"
    }
}

function Show-WindowsRuntimeHints {
    try {
        $memRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('info', '--format', '{{.MemTotal}}')
        if ($memRes.ExitCode -eq 0) {
            $memBytes = 0L
            if ([long]::TryParse($memRes.StdOut.Trim(), [ref]$memBytes) -and $memBytes -gt 0) {
                $memMb = [math]::Floor($memBytes / 1048576)
                if ($memMb -lt 6144) {
                    Write-Warn "提示：当前 Docker Desktop（WSL2）可用内存约 ${memMb} MB，建议在 %USERPROFILE%\.wslconfig 或 Docker Desktop 设置中分配至少 8 GB 内存，以保障 MySQL/Kafka/ClamAV 稳定运行。"
                }
            }
        }
    } catch {
        # Best-effort memory hint
    }
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
    # Preserve trailing newline cleanly without duplicating empty tail element
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

function Test-ValidIpv4 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Address)
    if ($Address -notmatch '^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$') {
        return $false
    }
    $o1 = [int]$Matches[1]
    $o2 = [int]$Matches[2]
    $o3 = [int]$Matches[3]
    $o4 = [int]$Matches[4]
    return ($o1 -ge 1 -and $o1 -le 255 -and $o2 -ge 0 -and $o2 -le 255 -and $o3 -ge 0 -and $o3 -le 255 -and $o4 -ge 0 -and $o4 -le 255)
}

function Test-IsVirtualAdapterName {
    param([AllowEmptyString()][string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) {
        return $false
    }
    return ($Name -match '(?i)(vEthernet|WSL|Hyper-V|Docker|Loopback|VirtualBox|VMware|Tailscale|ZeroTier|Teredo|isatap|Bluetooth|Npcap|WireGuard|OpenVPN|TAP-Windows|Wintun)')
}

function Test-IsUsableLanIpv4 {
    param([AllowEmptyString()][string]$IpAddress)
    if ([string]::IsNullOrWhiteSpace($IpAddress)) {
        return $false
    }
    if (-not (Test-ValidIpv4 -Address $IpAddress)) {
        return $false
    }
    if ($IpAddress -match '^(127\.|169\.254\.|0\.|172\.17\.)') {
        return $false
    }
    return $true
}

function Get-HostLanIp {
    # 1. Prefer IPv4 on the primary physical/non-virtual adapter with lowest default route metric
    try {
        if ($null -ne (Get-Command 'Get-NetRoute' -ErrorAction SilentlyContinue) -and
            $null -ne (Get-Command 'Get-NetIPAddress' -ErrorAction SilentlyContinue)) {
            $adapterByIdx = @{}
            if ($null -ne (Get-Command 'Get-NetAdapter' -ErrorAction SilentlyContinue)) {
                foreach ($ad in @(Get-NetAdapter -ErrorAction SilentlyContinue)) {
                    $adapterByIdx[$ad.ifIndex] = $ad
                }
            }

            $routes = @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
                Sort-Object { $_.RouteMetric + $_.InterfaceMetric })
            foreach ($route in $routes) {
                $idx = $route.InterfaceIndex
                $alias = [string]$route.InterfaceAlias
                $desc = ''
                if ($adapterByIdx.ContainsKey($idx)) {
                    $desc = [string]$adapterByIdx[$idx].InterfaceDescription
                    if ($adapterByIdx[$idx].Status -ne 'Up') {
                        continue
                    }
                }
                if ((Test-IsVirtualAdapterName -Name $alias) -or (Test-IsVirtualAdapterName -Name $desc)) {
                    continue
                }
                $addrs = @(Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $idx -ErrorAction SilentlyContinue |
                    Where-Object { $_.AddressState -eq 'Preferred' })
                foreach ($a in $addrs) {
                    if (Test-IsUsableLanIpv4 -IpAddress $a.IPAddress) {
                        return $a.IPAddress
                    }
                }
            }

            # 2. Fallback for air-gapped LAN without a default gateway: scan active non-virtual IPv4 adapters
            $candidates = New-Object System.Collections.Generic.List[string]
            foreach ($a in @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.AddressState -eq 'Preferred' })) {
                $idx = $a.InterfaceIndex
                $alias = [string]$a.InterfaceAlias
                $desc = ''
                if ($adapterByIdx.ContainsKey($idx)) {
                    $desc = [string]$adapterByIdx[$idx].InterfaceDescription
                    if ($adapterByIdx[$idx].Status -ne 'Up') {
                        continue
                    }
                }
                if ((Test-IsVirtualAdapterName -Name $alias) -or (Test-IsVirtualAdapterName -Name $desc)) {
                    continue
                }
                if (Test-IsUsableLanIpv4 -IpAddress $a.IPAddress) {
                    $candidates.Add($a.IPAddress)
                }
            }
            foreach ($cand in $candidates) {
                if ($cand -match '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)') {
                    return $cand
                }
            }
            if ($candidates.Count -gt 0) {
                return $candidates[0]
            }
        }
    } catch {
        # Fallback to .NET NetworkInterface below
    }

    # 3. .NET NetworkInterface fallback
    try {
        foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) {
                continue
            }
            if ($nic.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback -or
                $nic.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Tunnel) {
                continue
            }
            if ((Test-IsVirtualAdapterName -Name $nic.Name) -or (Test-IsVirtualAdapterName -Name $nic.Description)) {
                continue
            }
            foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
                if ($ua.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) {
                    $ipStr = $ua.Address.IPAddressToString
                    if (Test-IsUsableLanIpv4 -IpAddress $ipStr) {
                        return $ipStr
                    }
                }
            }
        }
    } catch {
        # Return empty string if detection fails
    }
    return ''
}

function Get-UrlHost {
    param([Parameter(Mandatory = $true)][string]$RawUrl)
    $clean = $RawUrl -replace '^https?://', ''
    $colonIdx = $clean.IndexOf(':')
    if ($colonIdx -ge 0) {
        return $clean.Substring(0, $colonIdx)
    }
    return $clean
}

function Test-ValidUrl {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    return ($Value -cmatch '^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$')
}

function Test-ValidSite {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    if ($Value -in @(':443', 'https://:443', ':80', 'http://:80')) {
        return $true
    }
    return (Test-ValidUrl -Value $Value)
}

function Assert-ValidUrl {
    param(
        [Parameter(Mandatory = $true)][string]$TargetUrl,
        [bool]$AllowHttp
    )
    if (-not (Test-ValidUrl -Value $TargetUrl)) {
        Fail "地址必须为不带路径的 https://域名或IP[:端口]，当前为 $TargetUrl"
    }
    if ($TargetUrl.StartsWith('http://', [System.StringComparison]::Ordinal) -and
        (-not $AllowHttp) -and
        ($TargetUrl -notmatch '^http://(127\.0\.0\.1|localhost|[A-Za-z0-9.-]*\.localhost)(:[0-9]+)?$')) {
        Fail '生产环境必须使用 HTTPS（不允许明文 HTTP）；请使用 -Ip <本机IP> 或 https:// 地址'
    }
}

function Test-PortListening {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        if ($null -ne (Get-Command 'Get-NetTCPConnection' -ErrorAction SilentlyContinue)) {
            $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
            if ($listeners.Count -gt 0) {
                return $true
            }
            return $false
        }
    } catch {
        # Fallback to netstat below
    }
    try {
        $netstatExe = Join-Path $env:SystemRoot 'System32\netstat.exe'
        if (-not (Test-Path -LiteralPath $netstatExe -PathType Leaf)) {
            $netstatExe = 'netstat.exe'
        }
        $res = Invoke-ProcessCapture -FileName $netstatExe -Arguments @('-ano', '-p', 'TCP') -UseSystemEncoding
        if ($res.ExitCode -eq 0) {
            foreach ($line in ($res.StdOut -split "`r?`n")) {
                if ($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\b') {
                    if ([int]$Matches[1] -eq $Port) {
                        return $true
                    }
                }
            }
        }
    } catch {
        # Best-effort listener check
    }
    return $false
}

function Get-ExcludedPortRangeMatch {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        $netshExe = Join-Path $env:SystemRoot 'System32\netsh.exe'
        if (-not (Test-Path -LiteralPath $netshExe -PathType Leaf)) {
            $netshExe = 'netsh.exe'
        }
        foreach ($family in @('ipv4', 'ipv6')) {
            $res = Invoke-ProcessCapture -FileName $netshExe -Arguments @('interface', $family, 'show', 'excludedportrange', 'protocol=tcp') -UseSystemEncoding
            if ($res.ExitCode -ne 0) {
                continue
            }
            foreach ($line in ($res.StdOut -split "`r?`n")) {
                if ($line -match '^\s*(\d+)\s+(\d+)(?:\s+\*)?\s*$') {
                    $startPort = [int]$Matches[1]
                    $endPort = [int]$Matches[2]
                    if ($Port -ge $startPort -and $Port -le $endPort) {
                        return "${startPort}-${endPort}"
                    }
                }
            }
        }
    } catch {
        # Best-effort excluded port check
    }
    return ''
}

function Assert-PortsAvailable {
    if ($SkipPortCheck) {
        return
    }
    $checks = @(
        [pscustomobject]@{ Label = 'HTTPS'; Port = [int]$Script:EffectiveHttpsPort },
        [pscustomobject]@{ Label = 'HTTP';  Port = [int]$Script:EffectiveHttpPort }
    )
    foreach ($item in $checks) {
        if (Test-PortListening -Port $item.Port) {
            Fail "宿主机 $($item.Label) 端口 $($item.Port) 已被本机其他服务占用，请通过 -HttpsPort / -HttpPort 指定其他 40000+ 空闲端口。"
        }
        $excludedRange = Get-ExcludedPortRangeMatch -Port $item.Port
        if (-not [string]::IsNullOrWhiteSpace($excludedRange)) {
            Fail "宿主机 $($item.Label) 端口 $($item.Port) 位于 Windows Hyper-V/WinNAT 保留端口段（${excludedRange}）内，Docker 无法绑定该端口。请通过 -HttpsPort / -HttpPort 指定其他空闲端口（例如 49443 / 49080，或运行 netsh interface ipv4 show excludedportrange protocol=tcp 查看保留段）。"
        }
    }
}

function Initialize-Config {
    if (Test-Path -LiteralPath $Script:EnvFile -PathType Leaf) {
        Fail '已有 .env 配置，请使用 .\install.ps1 upgrade，避免覆盖密钥。'
    }
    $envExample = Join-Path $Script:Root '.env.example'
    if (-not (Test-Path -LiteralPath $envExample -PathType Leaf)) {
        Fail '安装包缺少 .env.example'
    }

    if (-not [string]::IsNullOrWhiteSpace($Script:EffectiveTlsCert) -or -not [string]::IsNullOrWhiteSpace($Script:EffectiveTlsKey)) {
        if ([string]::IsNullOrWhiteSpace($Script:EffectiveTlsCert) -or [string]::IsNullOrWhiteSpace($Script:EffectiveTlsKey)) {
            Fail '-TlsCert 与 -TlsKey 必须同时指定'
        }
        if (-not (Test-Path -LiteralPath $Script:EffectiveTlsCert -PathType Leaf)) {
            Fail "无法读取证书文件：$($Script:EffectiveTlsCert)"
        }
        if (-not (Test-Path -LiteralPath $Script:EffectiveTlsKey -PathType Leaf)) {
            Fail "无法读取私钥文件：$($Script:EffectiveTlsKey)"
        }
        $Script:EffectiveTlsCert = (Resolve-Path -LiteralPath $Script:EffectiveTlsCert).Path
        $Script:EffectiveTlsKey = (Resolve-Path -LiteralPath $Script:EffectiveTlsKey).Path
    }

    if ($AutoIp -and [string]::IsNullOrWhiteSpace($Script:EffectiveIp) -and [string]::IsNullOrWhiteSpace($Script:EffectiveUrl)) {
        $detected = Get-HostLanIp
        if ([string]::IsNullOrWhiteSpace($detected)) {
            Fail '无法自动探测本机局域网 IPv4，请通过 -Ip <本机IP> 或 -Ip 127.0.0.1 显式指定'
        }
        $Script:EffectiveIp = $detected
        Write-Host "已自动探测本机 IP：$($Script:EffectiveIp)"
    }

    $isInteractive = $true
    try {
        $isInteractive = (-not [Console]::IsInputRedirected)
    } catch {
        $isInteractive = $false
    }

    if ([string]::IsNullOrWhiteSpace($Script:EffectiveUrl) -and [string]::IsNullOrWhiteSpace($Script:EffectiveIp) -and $isInteractive) {
        $detectedIp = Get-HostLanIp
        if (-not [string]::IsNullOrWhiteSpace($detectedIp)) {
            $inputAddr = (Read-Host "请输入本机局域网 IP（直接回车使用 ${detectedIp}，或输入 127.0.0.1 仅本机测试，或输入完整 HTTPS 域名）").Trim()
            if ([string]::IsNullOrWhiteSpace($inputAddr)) {
                $inputAddr = $detectedIp
            }
        } else {
            $inputAddr = (Read-Host '未检测到局域网 IP，请输入本机 IP（直接回车默认 127.0.0.1 仅本机测试）或完整 HTTPS 域名（如 https://crm.example.com:48443）').Trim()
            if ([string]::IsNullOrWhiteSpace($inputAddr)) {
                $inputAddr = '127.0.0.1'
            }
        }
        if (Test-ValidIpv4 -Address $inputAddr) {
            $Script:EffectiveIp = $inputAddr
        } else {
            $Script:EffectiveUrl = $inputAddr
        }
        if (-not $Script:HttpsPortSet) {
            $inputHttpsPort = (Read-Host "请输入 HTTPS 访问端口（建议 40000+ 避免冲突，直接回车默认 $($Script:EffectiveHttpsPort)）").Trim()
            if (-not [string]::IsNullOrWhiteSpace($inputHttpsPort)) {
                $Script:EffectiveHttpsPort = $inputHttpsPort
                $Script:HttpsPortSet = $true
            }
        }
    }

    if ($Script:EffectiveHttpPort -notmatch '^\d+$' -or $Script:EffectiveHttpsPort -notmatch '^\d+$') {
        Fail '端口必须为数字'
    }
    $httpPortNum = [int]$Script:EffectiveHttpPort
    $httpsPortNum = [int]$Script:EffectiveHttpsPort
    if ($httpPortNum -lt 1 -or $httpPortNum -gt 65535 -or $httpsPortNum -lt 1 -or $httpsPortNum -gt 65535) {
        Fail '端口范围为 1-65535'
    }
    if ($httpPortNum -eq $httpsPortNum -and (-not $Script:HttpPortSet)) {
        if ($httpsPortNum -gt 1) {
            $httpPortNum = $httpsPortNum - 1
        } else {
            $httpPortNum = $httpsPortNum + 1
        }
        $Script:EffectiveHttpPort = [string]$httpPortNum
    }
    if ($httpPortNum -eq $httpsPortNum) {
        Fail 'HTTP 与 HTTPS 端口不能相同'
    }

    if (-not [string]::IsNullOrWhiteSpace($Script:EffectiveIp)) {
        if (-not [string]::IsNullOrWhiteSpace($Script:EffectiveUrl)) {
            Fail '-Ip 与 -Url 不能同时指定'
        }
        if (-not (Test-ValidIpv4 -Address $Script:EffectiveIp)) {
            Fail '-Ip 格式不正确，需要有效的 IPv4 地址（例如 192.168.1.100 或 127.0.0.1）'
        }
        if ($Script:EffectiveHttpsPort -eq '443') {
            $Script:EffectiveUrl = "https://$($Script:EffectiveIp)"
        } else {
            $Script:EffectiveUrl = "https://$($Script:EffectiveIp):$($Script:EffectiveHttpsPort)"
        }
        if ([string]::IsNullOrWhiteSpace($Script:EffectiveSiteAddress)) {
            $Script:EffectiveSiteAddress = ':443'
        }
        $Script:TlsMode = 'local-cert'
        if ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesUrl)) {
            $Script:EffectiveFilesUrl = $Script:EffectiveUrl
            if ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesSiteAddress)) {
                $Script:EffectiveFilesSiteAddress = ':443'
            }
            $Script:FilesProxyMode = 'same-origin'
        }
    }

    if ([string]::IsNullOrWhiteSpace($Script:EffectiveUrl)) {
        Fail '首次安装必须指定 -Ip <本机IP>、-AutoIp 或 -Url <HTTPS地址>'
    }
    Assert-ValidUrl -TargetUrl $Script:EffectiveUrl -AllowHttp ([bool]$AllowInsecureHttp)

    if ($Script:EffectiveUrl.StartsWith('https://', [System.StringComparison]::Ordinal)) {
        $urlHost = Get-UrlHost -RawUrl $Script:EffectiveUrl
        if ((-not [string]::IsNullOrWhiteSpace($Script:EffectiveTlsCert)) -or
            ($Script:EffectiveHttpsPort -ne '443') -or
            ($Script:TlsMode -eq 'local-cert') -or
            (Test-ValidIpv4 -Address $urlHost)) {
            $Script:TlsMode = 'local-cert'
            if ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesUrl)) {
                $Script:EffectiveFilesUrl = $Script:EffectiveUrl
                $Script:FilesProxyMode = 'same-origin'
            }
            if ($Script:EffectiveFilesUrl -eq $Script:EffectiveUrl) {
                if ([string]::IsNullOrWhiteSpace($Script:EffectiveSiteAddress)) {
                    $Script:EffectiveSiteAddress = ':443'
                }
                if ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesSiteAddress)) {
                    $Script:EffectiveFilesSiteAddress = ':443'
                }
                $Script:FilesProxyMode = 'same-origin'
            }
        } elseif ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesUrl)) {
            Fail '标准公网域名 HTTPS 安装必须通过 -FilesUrl 配置文件域名，确保上传下载可用'
        } else {
            $Script:TlsMode = 'acme'
        }
    } else {
        $Script:TlsMode = 'http-only'
    }

    if ([string]::IsNullOrWhiteSpace($Script:EffectiveSiteAddress)) {
        $Script:EffectiveSiteAddress = $Script:EffectiveUrl
    }
    if (-not (Test-ValidSite -Value $Script:EffectiveSiteAddress)) {
        Fail '-SiteAddress 格式不正确'
    }
    if ($Script:EffectiveProjectName -cnotmatch '^[a-z0-9][a-z0-9_-]*$') {
        Fail '项目名只能包含小写字母、数字、下划线和连字符'
    }

    Assert-PortsAvailable

    # Copy .env.example as UTF-8 without BOM + LF and harden ACL immediately
    Write-Utf8NoBomLf -Path $Script:EnvFile -Content (Read-Utf8Text -Path $envExample)
    Protect-SecretFile -Path $Script:EnvFile

    Set-EnvValue -Key 'COMPOSE_PROJECT_NAME' -Value $Script:EffectiveProjectName
    Set-EnvValue -Key 'PUBLIC_URL' -Value $Script:EffectiveUrl
    Set-EnvValue -Key 'CADDY_SITE_ADDRESS' -Value $Script:EffectiveSiteAddress
    Set-EnvValue -Key 'HTTP_PORT' -Value $Script:EffectiveHttpPort
    Set-EnvValue -Key 'HTTPS_PORT' -Value $Script:EffectiveHttpsPort
    Set-EnvValue -Key 'TLS_MODE' -Value $Script:TlsMode

    if ($Script:EffectiveUrl.StartsWith('https://', [System.StringComparison]::Ordinal)) {
        Set-EnvValue -Key 'COOKIE_SECURE' -Value 'true'
    } else {
        Set-EnvValue -Key 'COOKIE_SECURE' -Value 'false'
        Write-Host '提示：当前为显式 HTTP 调试模式，请勿用于正式业务数据。'
    }

    if (-not [string]::IsNullOrWhiteSpace($Script:EffectiveFilesUrl)) {
        Assert-ValidUrl -TargetUrl $Script:EffectiveFilesUrl -AllowHttp ([bool]$AllowInsecureHttp)
        $urlScheme = ($Script:EffectiveUrl -split ':')[0]
        $filesScheme = ($Script:EffectiveFilesUrl -split ':')[0]
        if ($filesScheme -ne $urlScheme) {
            Fail '文件地址与系统地址必须同为 HTTPS（或同为 HTTP）'
        }
        if ($Script:EffectiveFilesUrl -eq $Script:EffectiveUrl) {
            $Script:FilesProxyMode = 'same-origin'
            if ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesSiteAddress)) {
                $Script:EffectiveFilesSiteAddress = $Script:EffectiveSiteAddress
            }
        } else {
            if ([string]::IsNullOrWhiteSpace($Script:FilesProxyMode)) {
                $Script:FilesProxyMode = 'separate'
            }
            if ([string]::IsNullOrWhiteSpace($Script:EffectiveFilesSiteAddress)) {
                $Script:EffectiveFilesSiteAddress = $Script:EffectiveFilesUrl
            }
        }
        if (-not (Test-ValidSite -Value $Script:EffectiveFilesSiteAddress)) {
            Fail '-FilesSiteAddress 格式不正确'
        }
        Set-EnvValue -Key 'FILES_ENABLED' -Value 'true'
        Set-EnvValue -Key 'FILES_PROXY_MODE' -Value $Script:FilesProxyMode
        Set-EnvValue -Key 'FILES_PUBLIC_ENDPOINT' -Value $Script:EffectiveFilesUrl
        Set-EnvValue -Key 'FILES_SITE_ADDRESS' -Value $Script:EffectiveFilesSiteAddress
    } else {
        Set-EnvValue -Key 'FILES_PROXY_MODE' -Value 'separate'
    }

    # Generate all cryptographic secrets natively using .NET RandomNumberGenerator
    Set-EnvValue -Key 'MYSQL_ROOT_PASSWORD' -Value (New-RandomHex -ByteCount 24)
    Set-EnvValue -Key 'MYSQL_PASSWORD' -Value (New-RandomHex -ByteCount 24)
    Set-EnvValue -Key 'REDIS_PASSWORD' -Value (New-RandomHex -ByteCount 24)
    Set-EnvValue -Key 'MINIO_ROOT_USER' -Value ("crm_" + (New-RandomHex -ByteCount 12))
    Set-EnvValue -Key 'MINIO_ROOT_PASSWORD' -Value (New-RandomHex -ByteCount 32)
    Set-EnvValue -Key 'JWT_SECRET' -Value (New-RandomHex -ByteCount 48)
    Set-EnvValue -Key 'APP_AES_KEY_BASE64' -Value (New-RandomBase64 -ByteCount 32)
    Set-EnvValue -Key 'APP_BLIND_INDEX_PEPPER' -Value (New-RandomHex -ByteCount 48)
    Set-EnvValue -Key 'AI_INTERNAL_TOKEN' -Value (New-RandomHex -ByteCount 32)

    $envText = Read-Utf8Text -Path $Script:EnvFile
    if ($envText -notmatch '(?m)^RELEASE_VERSION=') {
        Remove-Item -LiteralPath $Script:EnvFile -Force -ErrorAction SilentlyContinue
        Fail '配置缺少 RELEASE_VERSION'
    }
    if ($envText.Contains('REPLACE_ON_INSTALL')) {
        Remove-Item -LiteralPath $Script:EnvFile -Force -ErrorAction SilentlyContinue
        Fail '密钥生成不完整，未启动任何服务'
    }
}

function Read-ExistingConfig {
    if (-not (Test-Path -LiteralPath $Script:EnvFile -PathType Leaf)) {
        Fail '未找到 .env，请先执行 .\install.ps1 install'
    }
    $Script:EffectiveUrl = Get-EnvValue -Key 'PUBLIC_URL'
    $Script:EffectiveFilesUrl = Get-EnvValue -Key 'FILES_PUBLIC_ENDPOINT'
    $Script:EffectiveHttpPort = Get-EnvValue -Key 'HTTP_PORT'
    if ([string]::IsNullOrWhiteSpace($Script:EffectiveHttpPort)) {
        $Script:EffectiveHttpPort = '48080'
    }
    $Script:EffectiveHttpsPort = Get-EnvValue -Key 'HTTPS_PORT'
    if ([string]::IsNullOrWhiteSpace($Script:EffectiveHttpsPort)) {
        $Script:EffectiveHttpsPort = '48443'
    }
    $Script:TlsMode = Get-EnvValue -Key 'TLS_MODE'
    if ([string]::IsNullOrWhiteSpace($Script:EffectiveUrl)) {
        Fail '配置缺少 PUBLIC_URL'
    }
    if ((Read-Utf8Text -Path $Script:EnvFile).Contains('REPLACE_ON_INSTALL')) {
        Fail '请先替换 .env 中的安装占位符'
    }
}

function Assert-OfflineImagePresent {
    param([Parameter(Mandatory = $true)][string]$ImageName)
    $inspectRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('image', 'inspect', $ImageName)
    if ($inspectRes.ExitCode -ne 0) {
        Fail "本地缺少离线工具镜像 ${ImageName}，请先完成镜像载入（images/*.tar.gz）。"
    }
}

function Ensure-TlsCerts {
    if (-not (Test-Path -LiteralPath $Script:RuntimeDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:RuntimeDir -Force)
    }
    if (-not (Test-Path -LiteralPath $Script:CertsDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:CertsDir -Force)
    }

    $effectiveTlsMode = Get-EnvValue -Key 'TLS_MODE'
    if ($effectiveTlsMode -ne 'local-cert') {
        return
    }

    $serverCrt = Join-Path $Script:CertsDir 'server.crt'
    $serverKey = Join-Path $Script:CertsDir 'server.key'
    $caCrt = Join-Path $Script:CertsDir 'ca.crt'
    $caKey = Join-Path $Script:CertsDir 'ca.key'

    if (-not [string]::IsNullOrWhiteSpace($Script:EffectiveTlsCert) -and -not [string]::IsNullOrWhiteSpace($Script:EffectiveTlsKey)) {
        Assert-OfflineImagePresent -ImageName $Script:OpenSslImage
        $tmpCheckDir = Join-Path $Script:RuntimeDir ("certcheck." + [Guid]::NewGuid().ToString('N'))
        [void](New-Item -ItemType Directory -Path $tmpCheckDir -Force)
        try {
            $tmpCertPath = Join-Path $tmpCheckDir 'cert.pem'
            $tmpKeyPath = Join-Path $tmpCheckDir 'key.pem'
            Write-Utf8NoBomLf -Path $tmpCertPath -Content (Read-Utf8Text -Path $Script:EffectiveTlsCert)
            Write-Utf8NoBomLf -Path $tmpKeyPath -Content (Read-Utf8Text -Path $Script:EffectiveTlsKey)
            Protect-SecretFile -Path $tmpKeyPath

            $mountWork = "$($tmpCheckDir.TrimEnd('\')):/work:ro"
            $validateScript = @'
set -eu
openssl x509 -in /work/cert.pem -noout >/dev/null 2>&1 || { echo "INVALID_CERT" >&2; exit 11; }
openssl pkey -in /work/key.pem -noout >/dev/null 2>&1 || { echo "INVALID_KEY" >&2; exit 12; }
'@
            $valRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @(
                'run', '--rm',
                '-v', $mountWork,
                '--entrypoint', 'sh',
                $Script:OpenSslImage,
                '-c', ($validateScript -replace "`r`n", "`n")
            )
            if ($valRes.ExitCode -eq 11) {
                Fail "无效的 SSL 证书文件：$($Script:EffectiveTlsCert)"
            }
            if ($valRes.ExitCode -eq 12) {
                Fail "无效的 SSL 私钥文件：$($Script:EffectiveTlsKey)"
            }
            if ($valRes.ExitCode -ne 0) {
                Fail "校验自定义 SSL 证书/私钥失败：$($valRes.StdErr.Trim())"
            }

            Write-Utf8NoBomLf -Path $serverCrt -Content (Read-Utf8Text -Path $tmpCertPath)
            Write-Utf8NoBomLf -Path $serverKey -Content (Read-Utf8Text -Path $tmpKeyPath)
            Protect-SecretFile -Path $serverKey
            if (Test-Path -LiteralPath $caCrt -PathType Leaf) { Remove-Item -LiteralPath $caCrt -Force -ErrorAction SilentlyContinue }
            if (Test-Path -LiteralPath $caKey -PathType Leaf) { Remove-Item -LiteralPath $caKey -Force -ErrorAction SilentlyContinue }
            Write-Host "已载入自定义 HTTPS 证书到 $($Script:CertsDir)"
        } finally {
            if (Test-Path -LiteralPath $tmpCheckDir -PathType Container) {
                Remove-Item -LiteralPath $tmpCheckDir -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        return
    }

    $hasServerCrt = (Test-Path -LiteralPath $serverCrt -PathType Leaf) -and ((Get-Item -LiteralPath $serverCrt).Length -gt 0)
    $hasServerKey = (Test-Path -LiteralPath $serverKey -PathType Leaf) -and ((Get-Item -LiteralPath $serverKey).Length -gt 0)
    if (-not $hasServerCrt -or -not $hasServerKey) {
        Assert-OfflineImagePresent -ImageName $Script:OpenSslImage
        $certHost = Get-UrlHost -RawUrl (Get-EnvValue -Key 'PUBLIC_URL')
        if (Test-ValidIpv4 -Address $certHost) {
            if ($certHost -eq '127.0.0.1') {
                $sanList = 'IP:127.0.0.1,DNS:localhost'
            } else {
                $sanList = "IP:${certHost},IP:127.0.0.1,DNS:localhost"
            }
        } else {
            $sanList = "DNS:${certHost},IP:127.0.0.1,DNS:localhost"
        }

        $tmpDir = Join-Path $Script:RuntimeDir ("certgen." + [Guid]::NewGuid().ToString('N'))
        [void](New-Item -ItemType Directory -Path $tmpDir -Force)
        try {
            $caCnf = @"
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = Whale CRM Local Root CA
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
"@
            $serverCnf = @"
[req]
distinguished_name = dn
prompt = no
[dn]
CN = ${certHost}
[v3_server]
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = ${sanList}
"@
            Write-Utf8NoBomLf -Path (Join-Path $tmpDir 'ca.cnf') -Content ($caCnf + "`n")
            Write-Utf8NoBomLf -Path (Join-Path $tmpDir 'server.cnf') -Content ($serverCnf + "`n")

            $mountCerts = "$($Script:CertsDir.TrimEnd('\')):/certs"
            $mountWork = "$($tmpDir.TrimEnd('\')):/work"
            $certGenScript = @'
set -eu
umask 077
if [ ! -s /certs/ca.crt ] || [ ! -s /certs/ca.key ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 3650 \
    -config /work/ca.cnf \
    -keyout /certs/ca.key -out /certs/ca.crt
fi
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -config /work/server.cnf \
  -keyout /certs/server.key -out /work/server.csr
openssl x509 -req -in /work/server.csr \
  -CA /certs/ca.crt -CAkey /certs/ca.key -CAcreateserial -CAserial /work/ca.srl \
  -out /certs/server.crt -days 825 -sha256 \
  -extfile /work/server.cnf -extensions v3_server
openssl verify -CAfile /certs/ca.crt /certs/server.crt
'@
            $genRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @(
                'run', '--rm',
                '-v', $mountCerts,
                '-v', $mountWork,
                '--entrypoint', 'sh',
                $Script:OpenSslImage,
                '-c', ($certGenScript -replace "`r`n", "`n")
            )
            if ($genRes.ExitCode -ne 0) {
                Fail "通过离线镜像（$($Script:OpenSslImage)）生成本地 HTTPS 证书失败：$($genRes.StdErr.Trim())"
            }
            Protect-SecretFile -Path $caKey
            Protect-SecretFile -Path $serverKey
            Write-Host "已离线生成本地 HTTPS 证书（SAN: ${sanList}），根证书位于 ${caCrt}"
        } finally {
            if (Test-Path -LiteralPath $tmpDir -PathType Container) {
                Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

function Import-OfflineImages {
    $imagesDir = Join-Path $Script:Root 'images'
    $imagesTxt = Join-Path $imagesDir 'images.txt'
    if (-not (Test-Path -LiteralPath $imagesTxt -PathType Leaf)) {
        Fail '安装包缺少 images\images.txt，不能静默联网拉取镜像'
    }
    $archives = @(Get-ChildItem -LiteralPath $imagesDir -Filter '*.tar.gz' -File -ErrorAction SilentlyContinue | Sort-Object Name)
    if ($archives.Count -eq 0) {
        Fail '安装包缺少镜像归档（images\*.tar.gz）'
    }
    foreach ($archive in $archives) {
        Write-Host "载入镜像 $($archive.Name)"
        $rc = Invoke-NativeStreaming -FileName 'docker' -Arguments @('load', '--input', $archive.FullName)
        if ($rc -ne 0) {
            Fail "镜像载入失败：$($archive.Name)"
        }
    }
    foreach ($line in (Read-Utf8Text -Path $imagesTxt) -split "`r?`n") {
        $img = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($img)) {
            continue
        }
        $res = Invoke-ProcessCapture -FileName 'docker' -Arguments @('image', 'inspect', $img)
        if ($res.ExitCode -ne 0) {
            Fail "镜像未正确载入：$img"
        }
    }
}

function Write-CaddyConfig {
    Ensure-TlsCerts
    $filesEnabled = Get-EnvValue -Key 'FILES_ENABLED'
    $proxyMode = Get-EnvValue -Key 'FILES_PROXY_MODE'
    $publicUrl = Get-EnvValue -Key 'PUBLIC_URL'
    $filesPublic = Get-EnvValue -Key 'FILES_PUBLIC_ENDPOINT'
    $caddyOut = Join-Path $Script:RuntimeDir 'Caddyfile'

    if ($filesEnabled -eq 'true' -and ($proxyMode -eq 'same-origin' -or (-not [string]::IsNullOrWhiteSpace($filesPublic) -and $filesPublic -eq $publicUrl))) {
        $sameOriginPath = Join-Path $Script:Root 'Caddyfile.sameorigin'
        if (-not (Test-Path -LiteralPath $sameOriginPath -PathType Leaf)) {
            Fail '安装包缺少 Caddyfile.sameorigin'
        }
        Write-Utf8NoBomLf -Path $caddyOut -Content (Read-Utf8Text -Path $sameOriginPath)
    } else {
        $corePath = Join-Path $Script:Root 'Caddyfile.core'
        if (-not (Test-Path -LiteralPath $corePath -PathType Leaf)) {
            Fail '安装包缺少 Caddyfile.core'
        }
        $content = Read-Utf8Text -Path $corePath
        if ($filesEnabled -eq 'true') {
            if ([string]::IsNullOrWhiteSpace($filesPublic)) {
                Fail '启用文件功能时必须配置 FILES_PUBLIC_ENDPOINT'
            }
            if ([string]::IsNullOrWhiteSpace((Get-EnvValue -Key 'FILES_SITE_ADDRESS'))) {
                Fail '启用文件功能时必须配置 FILES_SITE_ADDRESS'
            }
            $filesPath = Join-Path $Script:Root 'Caddyfile.files'
            if (-not (Test-Path -LiteralPath $filesPath -PathType Leaf)) {
                Fail '安装包缺少 Caddyfile.files'
            }
            if (-not $content.EndsWith("`n")) {
                $content += "`n"
            }
            $content += (Read-Utf8Text -Path $filesPath)
        }
        Write-Utf8NoBomLf -Path $caddyOut -Content $content
    }
}

function Start-Stack {
    $cfgRc = Invoke-ComposeStreaming -ComposeArgs @('config', '--quiet')
    if ($cfgRc -ne 0) {
        Fail 'compose.yml 配置校验失败，请检查 .env 与 compose.yml'
    }
    $startupTimeout = if ([string]::IsNullOrWhiteSpace($env:STARTUP_TIMEOUT)) { '600' } else { $env:STARTUP_TIMEOUT }
    $upRc = Invoke-ComposeStreaming -ComposeArgs @('up', '-d', '--remove-orphans', '--wait', '--wait-timeout', $startupTimeout)
    if ($upRc -ne 0) {
        [void](Invoke-ComposeStreaming -ComposeArgs @('ps'))
        Fail '启动服务失败，请执行 .\install.ps1 logs 查看容器日志'
    }
    [void](Invoke-ComposeStreaming -ComposeArgs @('ps'))
}

function Initialize-Admin {
    if (Test-Path -LiteralPath $Script:CredentialFile -PathType Leaf) {
        Write-Host '管理员凭据文件已存在，不重复创建或覆盖账号。'
        return
    }
    $username = if ([string]::IsNullOrWhiteSpace($env:CRM_ADMIN_USERNAME)) { 'admin' } else { $env:CRM_ADMIN_USERNAME }
    $displayName = if ([string]::IsNullOrWhiteSpace($env:CRM_ADMIN_DISPLAY_NAME)) { '系统管理员' } else { $env:CRM_ADMIN_DISPLAY_NAME }
    $password = if ([string]::IsNullOrWhiteSpace($env:CRM_ADMIN_PASSWORD)) { New-RandomHex -ByteCount 18 } else { $env:CRM_ADMIN_PASSWORD }

    if ($username -cnotmatch '^[A-Za-z0-9_.-]{3,32}$') {
        Fail '管理员登录名格式不正确'
    }
    if ($password -cnotmatch '^[A-Za-z0-9@._-]{12,64}$') {
        Fail '管理员密码需为 12-64 位字母、数字或 @._-'
    }
    if ($displayName.Contains("`n") -or $displayName.Contains("`r")) {
        Fail '管理员显示名不能换行'
    }

    if (-not (Test-Path -LiteralPath $Script:RuntimeDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:RuntimeDir -Force)
    }

    $helpRes = Invoke-ComposeCapture -ComposeArgs @('run', '--help')
    $supportsEnvFromFile = (($helpRes.StdOut + $helpRes.StdErr) -like '*--env-from-file*')

    $runRes = $null
    if ($supportsEnvFromFile) {
        $bootstrapEnv = Join-Path $Script:RuntimeDir ("bootstrap." + [Guid]::NewGuid().ToString('N'))
        try {
            $envPayload = "BOOTSTRAP_ADMIN_USERNAME=${username}`nBOOTSTRAP_ADMIN_DISPLAY_NAME=${displayName}`nBOOTSTRAP_ADMIN_PASSWORD=${password}`n"
            Write-Utf8NoBomLf -Path $bootstrapEnv -Content $envPayload
            Protect-SecretFile -Path $bootstrapEnv
            $runRes = Invoke-ComposeCapture -ComposeArgs @(
                'run', '--rm', '--no-deps', '-T',
                '--env-from-file', $bootstrapEnv,
                '--entrypoint', 'java',
                'crm-api',
                '-cp', '/app/app.jar',
                '-Dloader.main=com.whale.crm.platform.bootstrap.BootstrapAdminCommand',
                'org.springframework.boot.loader.launch.PropertiesLauncher'
            )
        } finally {
            if (Test-Path -LiteralPath $bootstrapEnv -PathType Leaf) {
                Remove-Item -LiteralPath $bootstrapEnv -Force -ErrorAction SilentlyContinue
            }
        }
    } else {
        # Stdin fallback for older Docker Compose v2 without --env-from-file (never passes secret in CLI args)
        $stdinPayload = "${username}`n${displayName}`n${password}`n"
        $bootstrapSh = 'IFS= read -r BOOTSTRAP_ADMIN_USERNAME && IFS= read -r BOOTSTRAP_ADMIN_DISPLAY_NAME && IFS= read -r BOOTSTRAP_ADMIN_PASSWORD && export BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_DISPLAY_NAME BOOTSTRAP_ADMIN_PASSWORD && exec java -cp /app/app.jar -Dloader.main=com.whale.crm.platform.bootstrap.BootstrapAdminCommand org.springframework.boot.loader.launch.PropertiesLauncher'
        $runRes = Invoke-ComposeCapture -ComposeArgs @(
            'run', '--rm', '--no-deps', '-T',
            '--entrypoint', 'sh',
            'crm-api',
            '-c', $bootstrapSh
        ) -StandardInputText $stdinPayload
    }

    $combinedOutput = ($runRes.StdOut + $runRes.StdErr).Trim()
    if ($runRes.ExitCode -ne 0) {
        if (-not [string]::IsNullOrWhiteSpace($combinedOutput)) {
            Write-Warn $combinedOutput
        }
        Fail '初始化管理员失败'
    }
    if (-not [string]::IsNullOrWhiteSpace($runRes.StdOut.Trim())) {
        Write-Host $runRes.StdOut.Trim()
    }
    if ($runRes.StdOut.Contains('创建成功')) {
        Write-Utf8NoBomLf -Path $Script:CredentialFile -Content "username=${username}`npassword=${password}`n"
        Protect-SecretFile -Path $Script:CredentialFile
        Write-Host "管理员已初始化，凭据保存在 $($Script:CredentialFile)（已通过 icacls 限制仅当前用户与 SYSTEM 可访问），请妥善保管。"
    } else {
        Write-Host '管理员已存在，未修改账号，也未生成新的初始密码。'
    }
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
    # Fallback when Add-Type is unavailable (no proxy, best-effort Invoke-WebRequest)
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
            Write-Host "HTTPS/访问检查通过：$primaryUrl"
            return
        }
        if ((-not $isLoopbackPrimary) -and ($attempt -ge 3)) {
            if (Test-HttpEndpoint -TargetUrl $localFallbackUrl -CaCertPath $useCaPath -TimeoutSeconds 10) {
                Write-Host "本机 HTTPS 回环检查通过：${localFallbackUrl}（从本机直连 ${primaryUrl} 未通，常见于防火墙策略或路由隔离，请确认 Windows 防火墙已放行端口后从外部浏览器访问 ${primaryUrl}）"
                return
            }
        }
        if ($attempt -lt 40) {
            Start-Sleep -Seconds 5
        }
    }

    [void](Invoke-ComposeStreaming -ComposeArgs @('ps'))
    Fail "服务已启动，但 $primaryUrl 暂不可访问。请检查证书、Windows Defender 防火墙、端口冲突和 Caddy 日志（.\install.ps1 logs caddy）。"
}

function Test-FirewallPortAllowed {
    param([Parameter(Mandatory = $true)][string]$Port)
    try {
        if ($null -eq (Get-Command 'Get-NetFirewallRule' -ErrorAction SilentlyContinue)) {
            return $false
        }
        $rules = @(Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction SilentlyContinue)
        foreach ($r in $rules) {
            $pf = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $r -ErrorAction SilentlyContinue
            if ($null -ne $pf -and $pf.Protocol -eq 'TCP') {
                $localPorts = @($pf.LocalPort)
                if ($localPorts -contains $Port) {
                    return $true
                }
            }
        }
    } catch {
        return $false
    }
    return $false
}

function Set-WindowsFirewall {
    $urlHost = Get-UrlHost -RawUrl $Script:EffectiveUrl
    if ((-not $OpenFirewall) -and ($urlHost -eq '127.0.0.1' -or $urlHost -eq 'localhost')) {
        return
    }

    $portsToCheck = New-Object System.Collections.Generic.List[string]
    if ($Script:EffectiveUrl.StartsWith('https://', [System.StringComparison]::Ordinal)) {
        $portsToCheck.Add($Script:EffectiveHttpsPort)
        if ((Get-EnvValue -Key 'TLS_MODE') -eq 'acme' -and $Script:EffectiveHttpPort -eq '80') {
            $portsToCheck.Add($Script:EffectiveHttpPort)
        }
    } else {
        $portsToCheck.Add($Script:EffectiveHttpPort)
    }

    $missingPorts = New-Object System.Collections.Generic.List[string]
    foreach ($p in $portsToCheck) {
        if (-not (Test-FirewallPortAllowed -Port $p)) {
            $missingPorts.Add($p)
        }
    }
    if ($missingPorts.Count -eq 0) {
        return
    }

    if ($OpenFirewall) {
        if (Test-IsAdministrator) {
            try {
                foreach ($p in $missingPorts) {
                    [void](New-NetFirewallRule -DisplayName "Whale CRM TCP $p" -Direction Inbound -Action Allow -Protocol TCP -LocalPort $p -Profile Any -ErrorAction Stop)
                }
                Write-Host "已通过 Windows Defender 防火墙自动放行入站 TCP 端口：$($missingPorts -join ', ')"
                return
            } catch {
                Write-Warn "警告：自动添加 Windows 防火墙规则失败（$($_.Exception.Message)），请以管理员身份手动执行以下命令："
            }
        } else {
            Write-Warn '提示：已指定 -OpenFirewall，但当前窗口非管理员权限。请以管理员身份打开 PowerShell 并执行以下命令放行入站端口：'
        }
    } else {
        Write-Host "提示：如需局域网内其他电脑访问，请以管理员身份打开 PowerShell 放行以下 Windows 防火墙入站 TCP 端口（$($missingPorts -join ', ')）："
    }

    foreach ($p in $missingPorts) {
        Write-Host "  New-NetFirewallRule -DisplayName `"Whale CRM TCP $p`" -Direction Inbound -Action Allow -Protocol TCP -LocalPort $p -Profile Any"
    }
}

function Import-LocalRootCa {
    $caCertPath = Join-Path $Script:CertsDir 'ca.crt'
    if (-not (Test-Path -LiteralPath $caCertPath -PathType Leaf) -or (Get-Item -LiteralPath $caCertPath).Length -eq 0) {
        Fail "未找到本地根证书 ${caCertPath}。请先完成安装（如使用自有商业 SSL 证书则无需导入本地根证书）。"
    }

    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($caCertPath)
    try {
        $thumbprint = $cert.Thumbprint
        foreach ($loc in @([System.Security.Cryptography.X509Certificates.StoreLocation]::LocalMachine, [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser)) {
            $checkStore = New-Object System.Security.Cryptography.X509Certificates.X509Store([System.Security.Cryptography.X509Certificates.StoreName]::Root, $loc)
            try {
                $checkStore.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
                $existing = $checkStore.Certificates.Find([System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint, $thumbprint, $false)
                if ($existing.Count -gt 0) {
                    Write-Host "本地根证书（CN=Whale CRM Local Root CA，指纹 ${thumbprint}）已存在于受信任的根证书颁发机构（$loc），无需重复导入。"
                    return
                }
            } finally {
                $checkStore.Close()
            }
        }

        $isAdmin = Test-IsAdministrator
        $targetLocation = if ($isAdmin) {
            [System.Security.Cryptography.X509Certificates.StoreLocation]::LocalMachine
        } else {
            [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser
        }
        $targetLabel = if ($isAdmin) { '本地计算机（LocalMachine\Root）' } else { '当前用户（CurrentUser\Root）' }

        if (-not $isAdmin) {
            Write-Host '即将导入本地根证书到当前用户的「受信任的根证书颁发机构」，如弹出 Windows 安全警告请点击「是(Y)」确认。'
        }

        $store = New-Object System.Security.Cryptography.X509Certificates.X509Store([System.Security.Cryptography.X509Certificates.StoreName]::Root, $targetLocation)
        try {
            $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
            $store.Add($cert)
        } catch {
            Fail "导入本地根证书失败或被用户取消：$($_.Exception.Message)"
        } finally {
            $store.Close()
        }

        $displayUrl = if (-not [string]::IsNullOrWhiteSpace($Script:EffectiveUrl)) { $Script:EffectiveUrl } else { Get-EnvValue -Key 'PUBLIC_URL' }
        Write-Host "已将本地根证书导入 Windows 受信任的根证书颁发机构（${targetLabel}），指纹：${thumbprint}。"
        if (-not [string]::IsNullOrWhiteSpace($displayUrl)) {
            Write-Host "请完全关闭并重启 Edge / Chrome 浏览器后访问 ${displayUrl}，即可消除证书安全提示。"
        } else {
            Write-Host '请完全关闭并重启 Edge / Chrome 浏览器，即可消除证书安全提示。'
        }
    } finally {
        $cert.Dispose()
    }
}

function Show-InstallSummary {
    $caCrt = Join-Path $Script:CertsDir 'ca.crt'
    $serverCrt = Join-Path $Script:CertsDir 'server.crt'
    $tlsNote = ''
    if ((Test-Path -LiteralPath $caCrt -PathType Leaf) -and ((Get-Item -LiteralPath $caCrt).Length -gt 0)) {
        $tlsNote = @"
HTTPS 证书说明：已自动生成离线本地 CA 证书（${caCrt}）。
               消除浏览器警告：执行 .\install.ps1 trust-ca（或在 manage.bat 中选择导入根证书），
               也可双击 ca.crt 导入「受信任的根证书颁发机构」，或使用 -TlsCert / -TlsKey 替换为自有 SSL 证书。
"@
    } elseif ((Get-EnvValue -Key 'TLS_MODE') -eq 'local-cert') {
        $tlsNote = "HTTPS 证书说明：已启用您提供的自定义 SSL 证书（${serverCrt}）。"
    }

    $projectVal = Get-EnvValue -Key 'COMPOSE_PROJECT_NAME'
    Write-Host @"

============================================================
鲸选美学 CRM 安装完成（Windows Docker Desktop 高位端口 HTTPS 隔离部署）
============================================================
HTTPS 访问地址：$($Script:EffectiveUrl)
宿主机占用端口：HTTPS=$($Script:EffectiveHttpsPort)（主入口），HTTP=$($Script:EffectiveHttpPort)（仅 308 跳转 HTTPS，对外防火墙只需放行 $($Script:EffectiveHttpsPort)）
               （MySQL/Redis/Kafka/MinIO/ClamAV/API/Web 全部走容器内网，不占宿主机端口）
Compose 项目名：${projectVal}
初始管理员凭据：$($Script:CredentialFile)（已通过 icacls 限制权限，首次登录后请及时修改密码）
${tlsNote}
日常维护方式：
  双击 manage.bat             打开交互式运维菜单
  .\install.ps1 status        查看所有服务状态
  .\install.ps1 logs crm-api  查看后端日志
  .\install.ps1 backup        一键备份数据库、文件存储与密钥
  .\install.ps1 trust-ca      导入并信任本地 HTTPS 根证书
  .\install.ps1 stop          停止服务（保留数据卷）
  .\install.ps1 start         重新启动服务
============================================================
"@
}

function Backup-Database {
    param([Parameter(Mandatory = $true)][string]$Timestamp)
    if (-not (Test-Path -LiteralPath $Script:BackupsDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:BackupsDir -Force)
    }
    $destination = Join-Path $Script:BackupsDir "crm-${Timestamp}.sql.gz"
    $cidRes = Invoke-ComposeCapture -ComposeArgs @('ps', '-q', 'mysql')
    $mysqlCid = $cidRes.StdOut.Trim()
    if ($cidRes.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($mysqlCid)) {
        Fail '未找到运行中的 MySQL 容器，无法备份数据库（请先启动服务）'
    }

    $netRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('inspect', '--format', '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}', $mysqlCid)
    $mysqlNetwork = (($netRes.StdOut.Trim() -split '\s+') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($mysqlNetwork)) {
        $proj = Get-EnvValue -Key 'COMPOSE_PROJECT_NAME'
        if ([string]::IsNullOrWhiteSpace($proj)) { $proj = $Script:EffectiveProjectName }
        $mysqlNetwork = "${proj}_internal"
    }

    $imgRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('inspect', '--format', '{{.Config.Image}}', $mysqlCid)
    $mysqlImg = if ($imgRes.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($imgRes.StdOut.Trim())) { $imgRes.StdOut.Trim() } else { $Script:OpenSslImage }

    $mysqlRootPwd = Get-EnvValue -Key 'MYSQL_ROOT_PASSWORD'
    if ([string]::IsNullOrWhiteSpace($mysqlRootPwd)) {
        Fail '配置缺少 MYSQL_ROOT_PASSWORD，无法备份数据库'
    }

    # Mount backups dir directly into container so binary .sql.gz never passes through PowerShell text pipelines
    $mountBackups = "$($Script:BackupsDir.TrimEnd('\')):/backups"
    $dumpScript = @'
set -eu
umask 077
ts="$1"
tmp="/backups/.crm-${ts}.sql.tmp"
out="/backups/crm-${ts}.sql.gz"
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
        Fail "数据库备份失败，未继续升级：$($res.StdErr.Trim())"
    }
    Protect-SecretFile -Path $destination
    Write-Host "数据库备份完成：$destination"
}

function Backup-Minio {
    param([Parameter(Mandatory = $true)][string]$Timestamp)
    if (-not (Test-Path -LiteralPath $Script:BackupsDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:BackupsDir -Force)
    }
    $destination = Join-Path $Script:BackupsDir "crm-minio-${Timestamp}.tar.gz"
    $projectVal = Get-EnvValue -Key 'COMPOSE_PROJECT_NAME'
    if ([string]::IsNullOrWhiteSpace($projectVal)) {
        $projectVal = $Script:EffectiveProjectName
    }

    $tarImage = $Script:BackupTarImage
    $inspectTar = Invoke-ProcessCapture -FileName 'docker' -Arguments @('image', 'inspect', $tarImage)
    if ($inspectTar.ExitCode -ne 0) {
        $tarImage = $Script:OpenSslImage
        Assert-OfflineImagePresent -ImageName $tarImage
    }

    $mountBackups = "$($Script:BackupsDir.TrimEnd('\')):/backups"
    $tarScript = @'
set -eu
umask 077
ts="$1"
out="/backups/crm-minio-${ts}.tar.gz"
tar -C /data -czf "$out" .
test -s "$out"
gzip -t "$out"
'@

    $cidRes = Invoke-ComposeCapture -ComposeArgs @('ps', '-q', 'minio')
    $minioCid = if ($cidRes.ExitCode -eq 0) { $cidRes.StdOut.Trim() } else { '' }
    if (-not [string]::IsNullOrWhiteSpace($minioCid)) {
        $res = Invoke-ProcessCapture -FileName 'docker' -Arguments @(
            'run', '--rm',
            '--volumes-from', $minioCid,
            '-v', $mountBackups,
            '--entrypoint', 'sh',
            $tarImage,
            '-c', ($tarScript -replace "`r`n", "`n"),
            '_', $Timestamp
        )
    } else {
        $volumeName = "${projectVal}_minio_data"
        $volRes = Invoke-ProcessCapture -FileName 'docker' -Arguments @('volume', 'inspect', $volumeName)
        if ($volRes.ExitCode -ne 0) {
            Fail "未找到 MinIO 容器或数据卷（$volumeName），无法备份对象存储"
        }
        $res = Invoke-ProcessCapture -FileName 'docker' -Arguments @(
            'run', '--rm',
            '-v', "${volumeName}:/data:ro",
            '-v', $mountBackups,
            '--entrypoint', 'sh',
            $tarImage,
            '-c', ($tarScript -replace "`r`n", "`n"),
            '_', $Timestamp
        )
    }

    if ($res.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-Item -LiteralPath $destination).Length -eq 0) {
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
        }
        Fail "MinIO 对象存储备份失败：$($res.StdErr.Trim())"
    }
    Protect-SecretFile -Path $destination
    Write-Host "MinIO 对象存储备份完成：$destination"
}

function Backup-Env {
    param([Parameter(Mandatory = $true)][string]$Timestamp)
    if (-not (Test-Path -LiteralPath $Script:BackupsDir -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $Script:BackupsDir -Force)
    }
    $passphrase = [string]$env:BACKUP_ENCRYPTION_PASSPHRASE
    if (-not [string]::IsNullOrWhiteSpace($passphrase)) {
        Assert-OfflineImagePresent -ImageName $Script:OpenSslImage
        $destination = Join-Path $Script:BackupsDir "crm-env-${Timestamp}.env.enc"
        $mountBackups = "$($Script:BackupsDir.TrimEnd('\')):/backups"
        $mountEnv = "$($Script:EnvFile):/in/.env:ro"
        $encScript = @'
set -eu
umask 077
ts="$1"
out="/backups/crm-env-${ts}.env.enc"
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
            Fail ".env 密钥文件加密备份失败：$($res.StdErr.Trim())"
        }
        Protect-SecretFile -Path $destination
        Write-Host "环境密钥已加密备份：$destination"
    } else {
        $destination = Join-Path $Script:BackupsDir "crm-env-${Timestamp}.env"
        [System.IO.File]::Copy($Script:EnvFile, $destination, $true)
        Protect-SecretFile -Path $destination
        Write-Warn "警告：未设置 BACKUP_ENCRYPTION_PASSPHRASE，已以明文（已通过 icacls 限制权限）备份 .env 到 ${destination}；"
        Write-Warn '      .env 包含 APP_AES_KEY_BASE64 与 APP_BLIND_INDEX_PEPPER 等核心密钥，建议设置 BACKUP_ENCRYPTION_PASSPHRASE 并将密钥备份与数据库备份分开异地保存。'
        Write-Host "环境密钥备份完成（未加密，建议尽快离线加密保存）：$destination"
    }
}

function Invoke-BackupAll {
    $ts = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    Backup-Database -Timestamp $ts
    Backup-Minio -Timestamp $ts
    Backup-Env -Timestamp $ts
}

function Invoke-InteractiveMenu {
    while ($true) {
        Write-Host ''
        Write-Host '============================================================'
        Write-Host '鲸选美学 CRM — Windows 日常运维菜单'
        Write-Host '============================================================'
        Write-Host '  [1] 查看服务状态 (status)'
        Write-Host '  [2] 查看服务日志 (logs)'
        Write-Host '  [3] 备份数据库、MinIO 文件与密钥 (backup)'
        Write-Host '  [4] 启动服务 (start)'
        Write-Host '  [5] 停止服务（保留数据卷） (stop)'
        Write-Host '  [6] 离线升级 (upgrade)'
        Write-Host '  [7] 初始化/检查管理员账号 (create-admin)'
        Write-Host '  [8] 导入并信任本地 HTTPS 根证书 (trust-ca)'
        Write-Host '  [9] 查看帮助与命令行参数 (help)'
        Write-Host '  [0] 退出'
        Write-Host '============================================================'
        $choice = (Read-Host '请选择操作编号 [0-9]').Trim()
        if ($choice -eq '0' -or [string]::IsNullOrWhiteSpace($choice)) {
            if ($choice -eq '0') {
                return
            }
            continue
        }

        $subArgs = @()
        $tempPassSet = $false
        $savedPass = $env:BACKUP_ENCRYPTION_PASSPHRASE
        try {
            switch ($choice) {
                '1' { $subArgs = @('status') }
                '2' {
                    $svcInput = (Read-Host '请输入服务名（如 crm-api caddy，直接回车查看全部服务日志）').Trim()
                    $subArgs = @('logs')
                    if (-not [string]::IsNullOrWhiteSpace($svcInput)) {
                        $subArgs += ($svcInput -split '\s+')
                    }
                }
                '3' {
                    if ([string]::IsNullOrWhiteSpace($env:BACKUP_ENCRYPTION_PASSPHRASE)) {
                        $secPass = Read-Host '如需加密备份 .env，请输入加密口令（直接回车跳过加密，明文受限权限备份）' -AsSecureString
                        if ($null -ne $secPass -and $secPass.Length -gt 0) {
                            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPass)
                            try {
                                $env:BACKUP_ENCRYPTION_PASSPHRASE = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
                                $tempPassSet = $true
                            } finally {
                                [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
                            }
                        }
                    }
                    $subArgs = @('backup')
                }
                '4' { $subArgs = @('start') }
                '5' { $subArgs = @('stop') }
                '6' { $subArgs = @('upgrade') }
                '7' { $subArgs = @('create-admin') }
                '8' { $subArgs = @('trust-ca') }
                '9' { $subArgs = @('help') }
                default {
                    Write-Warn "无效选项：$choice"
                    continue
                }
            }

            $psExe = (Get-Process -Id $PID).Path
            $rc = Invoke-NativeStreaming -FileName $psExe -Arguments (@('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath) + $subArgs)
            if ($rc -ne 0) {
                Write-Warn "操作未完成（退出码 $rc），请查看上方提示信息。"
            }
        } finally {
            if ($tempPassSet) {
                if ($null -eq $savedPass) {
                    Remove-Item Env:BACKUP_ENCRYPTION_PASSPHRASE -ErrorAction SilentlyContinue
                } else {
                    $env:BACKUP_ENCRYPTION_PASSPHRASE = $savedPass
                }
            }
        }

        [void](Read-Host '按回车键返回运维菜单')
    }
}

# --- Argument Validation & Subcommand Dispatch ---

$normalizedExtra = @($ExtraArgs | Where-Object { $null -ne $_ -and $_ -ne '' })
if ($Help -or $Command -in @('help', '-h', '--help', '/?') -or ($normalizedExtra -contains '--help') -or ($normalizedExtra -contains '-h') -or ($normalizedExtra -contains '/?')) {
    Show-Usage
    exit 0
}
if ($Command -eq 'logs') {
    foreach ($svc in $normalizedExtra) {
        if ($svc -cnotmatch '^[a-z0-9][a-z0-9_-]*$') {
            Fail "非法的服务名：$svc"
        }
    }
} else {
    if ($normalizedExtra.Count -gt 0) {
        Fail "命令 $Command 不接受额外位置参数：$($normalizedExtra -join ' ')"
    }
}

switch ($Command) {
    'install' {
        Assert-Prerequisites
        Assert-HostArchitecture
        Show-WindowsRuntimeHints
        Initialize-Config
        Import-OfflineImages
        Write-CaddyConfig
        Start-Stack
        Initialize-Admin
        Test-Access
        Set-WindowsFirewall
        if ($TrustCa) {
            $caPath = Join-Path $Script:CertsDir 'ca.crt'
            if ((Test-Path -LiteralPath $caPath -PathType Leaf) -and ((Get-Item -LiteralPath $caPath).Length -gt 0)) {
                Import-LocalRootCa
            }
        }
        Show-InstallSummary
    }
    'upgrade' {
        Assert-Prerequisites
        Assert-HostArchitecture
        Read-ExistingConfig
        Invoke-BackupAll
        Import-OfflineImages
        Write-CaddyConfig
        Start-Stack
        Test-Access
        Write-Host '升级完成。数据库迁移只向前执行，应用回滚不等于数据库回滚。'
    }
    'start' {
        Assert-Prerequisites
        Read-ExistingConfig
        Write-CaddyConfig
        Start-Stack
        Initialize-Admin
        Test-Access
    }
    'stop' {
        if (-not (Find-Compose)) {
            Fail '需要 Docker Compose v2（docker compose 插件或 docker-compose v2）'
        }
        Read-ExistingConfig
        $rc = Invoke-ComposeStreaming -ComposeArgs @('down')
        if ($rc -ne 0) {
            Fail '停止服务失败'
        }
        Write-Host '服务已停止，数据卷保留。'
    }
    'status' {
        if (-not (Find-Compose)) {
            Fail '需要 Docker Compose v2（docker compose 插件或 docker-compose v2）'
        }
        Read-ExistingConfig
        $rc = Invoke-ComposeStreaming -ComposeArgs @('ps')
        if ($rc -ne 0) {
            Fail '查询服务状态失败'
        }
    }
    'logs' {
        if (-not (Find-Compose)) {
            Fail '需要 Docker Compose v2（docker compose 插件或 docker-compose v2）'
        }
        Read-ExistingConfig
        $logLines = if ([string]::IsNullOrWhiteSpace($env:LOG_LINES)) { '100' } else { $env:LOG_LINES }
        $logArgs = @('logs', '--tail', $logLines) + $normalizedExtra
        $rc = Invoke-ComposeStreaming -ComposeArgs $logArgs
        if ($rc -ne 0) {
            Fail '读取日志失败'
        }
    }
    'backup' {
        Assert-Prerequisites
        Read-ExistingConfig
        Invoke-BackupAll
    }
    'create-admin' {
        Assert-Prerequisites
        Read-ExistingConfig
        Initialize-Admin
    }
    'trust-ca' {
        if (Test-Path -LiteralPath $Script:EnvFile -PathType Leaf) {
            $Script:EffectiveUrl = Get-EnvValue -Key 'PUBLIC_URL'
        }
        Import-LocalRootCa
    }
    'menu' {
        Invoke-InteractiveMenu
    }
    default {
        Fail "未知命令 $Command，使用 .\install.ps1 help 查看帮助"
    }
}
