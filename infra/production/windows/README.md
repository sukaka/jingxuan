# 鲸选美学 CRM 离线一键安装包（Windows Docker Desktop · 默认 HTTPS 高位端口）

这是 **Windows 离线部署包**（适配 **Windows 10 / Windows 11 / Windows Server + Docker Desktop Linux 容器模式**），包内已内置全部应用与中间件 `linux/amd64` 镜像。安装与维护完全离线，**无需在 Windows 宿主机安装 OpenSSL、Java、Node.js、Python、Maven、npm、tar 或 gzip**，也不会联网拉取镜像。

## 1. 前置条件

- **操作系统**：64 位 Windows 10（2004 及以上）/ Windows 11 / Windows Server（x86_64 / amd64 架构，支持 WSL2 Linux 容器）。
- **Docker Desktop（必需）**：
  - 已安装并启动 **Docker Desktop**（内置 Docker Compose v2，支持 `docker compose` 或 `docker-compose` v2）。
  - **必须切换为 Linux 容器模式（WSL2 后端）**：在桌面右下角系统托盘右键点击 Docker Desktop 图标，确认菜单中显示的是「Switch to Windows containers...」（表示当前已在 Linux 容器模式）；如果显示「Switch to Linux containers...」，请先点击切换并等待重启完成。
  - 可在 PowerShell 中执行 `docker info --format '{{.OSType}}'` 确认返回 `linux`。
- **硬件与 WSL2 资源建议**：
  - 建议宿主机至少 **4 核 CPU、8 GB 以上可用内存、20 GB 以上可用磁盘空间**（业务数据与备份空间另计；若宿主机总内存为 16 GB 及以上，运行更充裕）。
  - 全部 10 个容器的内存上限总计约 7.8 GB，建议在 `%USERPROFILE%\.wslconfig` 中为 WSL2 分配至少 8 GB 内存（若文件不存在可新建）：
    ```ini
    [wsl2]
    memory=8GB
    processors=4
    swap=4GB
    ```
    保存后在 PowerShell 中执行 `wsl --shutdown`，然后重新启动 Docker Desktop 使配置生效。
- **零额外宿主机依赖**：无需在 Windows 宿主机安装 OpenSSL、Java、Node.js、Python、tar 或 gzip；密钥生成通过 .NET 原生加密随机数实现，离线证书签发、自定义证书校验、数据库/MinIO 备份和 `.env` 加密均通过包内已载入的离线容器镜像（`mysql:8.4.8` / `redis:7.4.2-alpine`）完成。

## 2. 默认 HTTPS 与隔离设计（零冲突）

针对 Windows 主机上可能已运行其他服务且要求强制 HTTPS 访问的场景，本安装包默认做了全面安全隔离：

- **默认强制 HTTPS + 40000+ 高位端口**：主入口默认 **HTTPS `48443`**（`COOKIE_SECURE=true` + HSTS），**不提供明文 HTTP 业务访问**；HTTP 端口默认 **`48080`** 仅做 `308` 强制跳转到 HTTPS（外部防火墙只需放行 `48443/tcp`）。可通过 `-HttpsPort` / `-HttpPort` 改为其他 40000+ 空闲端口。
- **开箱即用离线 HTTPS 证书**：
  - 未提供外部证书时，脚本会在本机**自动离线生成**本地 Root CA（`runtime\certs\ca.crt`，`CN=Whale CRM Local Root CA`）及带 SAN（包含本机 IP + `127.0.0.1` + `localhost`）的 825 天服务器 HTTPS 证书（私钥仅在本机生成，绝不打入安装包）。
  - 如有商业/企业 SSL 证书，可直接通过 `-TlsCert` / `-TlsKey` 一键校验并挂载，无需自签提示。
- **同源 HTTPS 文件上传下载**：文件上传、ClamAV 病毒扫描与预签名下载走同一 HTTPS 端口的 `/crm-private/*` 路径（自动剥离会话 Cookie、限制 15MB、`X-Content-Type-Options: nosniff`、`Content-Security-Policy: sandbox`、封禁 `/minio*`），无需额外证书或第二端口，浏览器内直接可用。
- **中间件零宿主机端口**：MySQL、Redis、Kafka、MinIO、ClamAV、crm-api、ai-worker、web 全部运行在独立 Docker 内部网络，**不占用 80、443、3306、6379、8080、9000、9092 等任何宿主机端口**。
- **Windows 安全加固与预检**：
  - 自动检查 Docker Desktop 运行状态、`OSType=linux`、`manifest.txt` 目标架构、WSL2 可用内存提示、本机 TCP 监听端口及 **Hyper-V / WinNAT 保留端口段**。
  - 自动使用 `icacls` 将 `.env`、`initial-admin.txt`、`runtime\certs\server.key`、`runtime\certs\ca.key` 和 `backups\*` 收紧为仅当前用户与 SYSTEM 可访问权限（等效于 Linux `chmod 600`）。

## 3. 一键安装步骤

### 第一步：校验安装包 SHA256 并解压

在 PowerShell 中进入安装包所在目录，校验 `whale-crm-2.0.2-windows-amd64.zip` 的 SHA256（文件名以实际安装包为准）：

```powershell
Get-FileHash whale-crm-2.0.2-windows-amd64.zip -Algorithm SHA256
Get-Content whale-crm-2.0.2-windows-amd64.zip.sha256
```

确认两行输出的 64 位 SHA256 哈希值完全一致后（不区分大小写），将 `.zip` 解压到本地磁盘目录（推荐例如 `C:\whale-crm` 或 `D:\whale-crm`）：

```powershell
Expand-Archive -Path .\whale-crm-2.0.2-windows-amd64.zip -DestinationPath C:\whale-crm
```

> **解压路径注意事项**：
> - 请解压到**简短、无特殊符号的本地磁盘路径**（如 `C:\whale-crm`）。
> - **不要**解压到包含特殊符号（如 `&`、`%`、`!`、`#`、括号、空格等）、路径过深、OneDrive 同步目录或网络共享路径（UNC `\\server\share`）下，以免引发批处理解析异常或 Docker 卷挂载失败。

### 第二步：选择一种方式安装

#### 方式 A（推荐）：双击 `install.bat` 交互式一键安装

进入解压后的目录（例如 `C:\whale-crm\whale-crm-2.0.2-windows-amd64`），直接**双击 `install.bat`**。

- `install.bat` 会自动以 `powershell.exe -NoProfile -ExecutionPolicy Bypass` 启动 `install.ps1`，无需手动修改系统 PowerShell 执行策略，执行结束后窗口会暂停以便查看安装结果与错误提示（自动化场景可通过环境变量 `WHALE_CRM_NO_PAUSE=1` 关闭暂停）。
- 脚本会自动检测 Docker Desktop（Linux 容器模式）与可用端口，自动探测本机主局域网 IPv4（若仅本机单机测试可输入 `127.0.0.1`），并默认启用 **HTTPS `48443`** 端口。
- 按提示确认地址和端口（直接回车使用默认值），即可自动完成离线镜像载入、随机密钥生成、离线 HTTPS 证书签发、服务启动、管理员初始化与 HTTPS 连通性检查。

#### 方式 B：PowerShell 无人值守安装命令示例

打开 PowerShell 并切换到解压目录（如需自动放行 Windows Defender 防火墙或导入到本机计算机根证书库，请右键选择「**以管理员身份运行**」PowerShell）：

```powershell
cd C:\whale-crm\whale-crm-2.0.2-windows-amd64

# 1) 局域网 IP + 默认 48443 HTTPS 端口 + 自动放行 Windows Defender 防火墙（建议管理员 PowerShell）
.\install.ps1 install -Ip 192.168.1.100 -HttpsPort 48443 -OpenFirewall

# 2) 自动探测本机主局域网 IP 一键安装，并自动放行防火墙与信任本地根证书
.\install.ps1 install -AutoIp -HttpsPort 48443 -OpenFirewall -TrustCa

# 3) 本机单机测试（仅本机访问 127.0.0.1，自动导入本地根证书消除浏览器警告）
.\install.ps1 install -Ip 127.0.0.1 -HttpsPort 48443 -TrustCa

# 4) 使用自有商业/企业 SSL 证书（零浏览器安全提示）
.\install.ps1 install `
  -Ip 192.168.1.100 `
  -HttpsPort 48443 `
  -TlsCert C:\certs\fullchain.pem `
  -TlsKey C:\certs\privkey.pem `
  -OpenFirewall
```

> 若系统 PowerShell 执行策略禁止直接运行 `.\install.ps1`，可改用 `install.bat` 或显式绕过策略调用：
> ```powershell
> powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 install -AutoIp -HttpsPort 48443 -OpenFirewall -TrustCa
> ```

**安装参数速查（`.\install.ps1`）**：

| 参数 | 说明 |
| --- | --- |
| `-Ip <IPv4>` | 本机 IPv4（如 `192.168.1.100` 或 `127.0.0.1`），一键启用 `https://<IP>:<HttpsPort>` 与同源 HTTPS 文件上传下载 |
| `-AutoIp` | 自动探测本机主局域网 IPv4 并以 HTTPS 一键安装（适合无人值守） |
| `-Url <URL>` | 完整系统访问地址（默认必须为 `https://...`） |
| `-SiteAddress <地址>` | 自定义 Caddy 监听站点地址（高位端口同源 HTTPS 模式默认 `:443`） |
| `-FilesUrl <URL>` | 独立文件公开访问地址（高位端口 / IP 模式自动同源 HTTPS，无需手动指定） |
| `-FilesSiteAddress <地址>` | 自定义文件站点 Caddy 监听地址 |
| `-HttpsPort <端口>` | 宿主机 HTTPS 访问端口，默认 `48443`（40000+ 高位端口，避免冲突） |
| `-HttpPort <端口>` | 宿主机 HTTP 重定向端口，默认 `48080`（仅 308 跳转至 HTTPS，不提供明文业务访问） |
| `-TlsCert <路径>` | 自定义 SSL 证书文件路径（PEM / fullchain），未指定时离线自动生成本地 CA + SAN 证书 |
| `-TlsKey <路径>` | 自定义 SSL 私钥文件路径（PEM，与 `-TlsCert` 成对使用） |
| `-ProjectName <名称>` | Docker Compose 项目名，默认 `whale-crm`（隔离容器、数据卷与网络） |
| `-OpenFirewall` | 自动添加 Windows Defender 防火墙入站 TCP 放行规则（需管理员权限；非管理员时会输出可复制命令） |
| `-TrustCa` | 安装完成后自动将本地根证书（`runtime\certs\ca.crt`）导入 Windows 受信任根证书存储 |
| `-AllowInsecureHttp` | 仅限显式本机/隔离调试允许明文 HTTP（生产环境默认禁止） |
| `-SkipPortCheck` | 跳过安装前宿主机端口占用与 Hyper-V 保留端口段预检 |
| `-Help` / `-h` | 查看完整命令行用法与参数说明 |

**公网标准域名 HTTPS 安装（可选）**：若服务器开放 80/443 且具备正式系统域名与独立文件域名（由 Caddy 自动申请并续期 ACME 证书）：

```powershell
.\install.ps1 install `
  -Url https://crm.example.com `
  -FilesUrl https://files.example.com `
  -HttpPort 80 `
  -HttpsPort 443 `
  -OpenFirewall
```

## 4. 首次访问、管理员凭据与消除浏览器证书提示

安装完成后，在浏览器访问：**`https://<本机IP>:48443`**（单机测试为 **`https://127.0.0.1:48443`**）。

### 查看初始管理员凭据

安装脚本会随机生成数据库、对象存储、JWT、字段加密及内部调用密钥，保存在当前目录的 `.env` 中（已通过 `icacls` 限制为仅当前用户与 SYSTEM 可访问），不会覆盖已有 `.env`。

首次管理员账号默认为 `admin`，随机初始密码保存在安装目录下的 **`initial-admin.txt`**（不会打印到终端或写入镜像）。在 PowerShell 中查看：

```powershell
Get-Content .\initial-admin.txt
```

也可以直接用记事本打开 `initial-admin.txt` 查看。**首次登录后请及时修改初始密码**并创建正式运营账号，随后妥善保管或移除该凭据文件。开发账号 `admin / Dev@12345` 不会进入正式库。

### 消除浏览器安全证书提示

若安装时未传入商业/企业 SSL 证书（`-TlsCert` / `-TlsKey`），系统使用的是本机离线生成的本地 Root CA 签发证书：

- **临时快速访问**：首次打开 `https://<本机IP>:48443` 时，在浏览器提示页点击「**高级 → 继续前往**」即可正常使用（所有流量仍全程 TLS 加密，Cookie 保持 `Secure`）。
- **本机永久消除提示（方式 1：命令或菜单一键导入）**：
  - 安装时直接追加 `-TrustCa`，或安装完成后执行：
    ```powershell
    .\install.ps1 trust-ca
    ```
  - 也可以双击 `manage.bat` 选择菜单 `[8] 导入并信任本地 HTTPS 根证书 (trust-ca)`。
  - 普通用户权限会导入到当前用户「受信任的根证书颁发机构」（`CurrentUser\Root`，如弹出 Windows 安全警告请点击「**是(Y)**」确认）；管理员权限会导入到本机计算机（`LocalMachine\Root`）。导入完成后**完全关闭并重启 Edge / Chrome 浏览器**即可消除证书安全提示。
- **本机或局域网其他电脑消除提示（方式 2：双击导入 `runtime\certs\ca.crt`）**：
  1. 双击安装目录下的 **`runtime\certs\ca.crt`**（局域网其他办公电脑只需复制 `ca.crt`，**严禁分发或泄露 `runtime\certs\ca.key` 与 `server.key`**）。
  2. 点击「**安装证书**」→ 选择「**当前用户**」（或管理员选择「**本地计算机**」）→ 下一步。
  3. 选择「**将所有的证书都放入下列存储**」→ 点击「**浏览**」→ 选择「**受信任的根证书颁发机构**」→ 确定 → 完成并确认安全提示。
  4. 完全关闭并重启浏览器即可。

## 5. 日常维护与升级

### 方式 A：双击 `manage.bat`（交互式运维菜单）

双击安装目录下的 **`manage.bat`**（内部调用 `.\install.ps1 menu`），按数字键 `1`–`9` 即可完成：

- `[1]` 查看服务状态（`status`）
- `[2]` 查看服务日志（`logs`，支持输入指定服务名如 `crm-api caddy` 或直接回车查看全部）
- `[3]` 备份数据库、MinIO 文件与密钥（`backup`，支持交互式隐式输入 `.env` 加密口令）
- `[4]` 启动服务（`start`）
- `[5]` 停止服务（保留数据卷）（`stop`）
- `[6]` 离线升级（`upgrade`）
- `[7]` 初始化/检查管理员账号（`create-admin`）
- `[8]` 导入并信任本地 HTTPS 根证书（`trust-ca`）
- `[9]` 查看帮助与命令行参数（`help`）

也可在命令提示符直接传参调用，例如 `manage.bat status`、`manage.bat logs crm-api`。

### 方式 B：PowerShell 子命令（`.\install.ps1`）

```powershell
.\install.ps1 status         # 查看所有容器健康状态
.\install.ps1 logs           # 查看全部服务最近日志（默认 100 行，可通过 $env:LOG_LINES 调整）
.\install.ps1 logs crm-api   # 查看指定服务（如后端）日志
.\install.ps1 backup         # 备份 MySQL 数据库 + MinIO 文件 + .env 到 backups\
.\install.ps1 stop           # 停止服务（保留数据卷）
.\install.ps1 start          # 使用现有 .env 配置启动服务
.\install.ps1 upgrade        # 先全量备份，再载入新版镜像并向前迁移升级
.\install.ps1 create-admin   # 若尚未初始化管理员账号，执行初始化
.\install.ps1 trust-ca       # 将 runtime\certs\ca.crt 导入 Windows 受信任根证书存储
.\install.ps1 menu           # 打开交互式日常运维菜单
.\install.ps1 help           # 查看完整帮助信息
```

> 可选环境变量：`STARTUP_TIMEOUT`（启动等待超时秒数，默认 `600`）、`LOG_LINES`（`logs` 默认输出行数，默认 `100`）、`BACKUP_ENCRYPTION_PASSPHRASE`（`.env` 备份加密口令）、`WHALE_CRM_NO_PAUSE`（设为非空时关闭 `.bat` 退出暂停）。

### 备份与加密说明（`BACKUP_ENCRYPTION_PASSPHRASE`）

执行 `.\install.ps1 backup`（或 `.\install.ps1 upgrade` 的前置备份）时，脚本会通过离线容器将备份直接写入 `backups\` 目录并执行 `gzip -t` 完整性校验（避免 PowerShell 文本管道破坏二进制归档），同时自动收紧 ACL：

- `backups\crm-<时间戳>.sql.gz`：MySQL `medical_crm` 逻辑备份
- `backups\crm-minio-<时间戳>.tar.gz`：MinIO 对象存储归档
- `backups\crm-env-<时间戳>.env` 或 `backups\crm-env-<时间戳>.env.enc`：`.env` 环境密钥备份

由于 `.env` 包含 `APP_AES_KEY_BASE64` 与 `APP_BLIND_INDEX_PEPPER` 等核心密钥，**强烈建议在执行 `backup` / `upgrade` 前设置 `BACKUP_ENCRYPTION_PASSPHRASE`**，通过包内 `mysql:8.4.8` 镜像中的 OpenSSL 使用 AES-256-CBC（PBKDF2 100000 次迭代）离线加密为 `.env.enc`，完成后清除环境变量，并将密钥备份与数据库备份分开异地保存：

```powershell
$env:BACKUP_ENCRYPTION_PASSPHRASE = "请替换为高强度备份口令"
.\install.ps1 backup
Remove-Item Env:BACKUP_ENCRYPTION_PASSPHRASE
```

未设置 `BACKUP_ENCRYPTION_PASSPHRASE` 时，脚本会输出警告并以明文（通过 `icacls` 限制访问权限）备份 `.env`，请尽快离线加密保存。

如需在灾备恢复时离线解密 `.env.enc`（同样无需宿主机安装 OpenSSL）：

```powershell
$env:BACKUP_ENCRYPTION_PASSPHRASE = "备份时使用的口令"
docker run --rm -e BACKUP_ENCRYPTION_PASSPHRASE -v "${PWD}\backups:/backups" --entrypoint sh mysql:8.4.8 -c 'openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -pass env:BACKUP_ENCRYPTION_PASSPHRASE -in /backups/crm-env-<时间戳>.env.enc -out /backups/restored.env'
Remove-Item Env:BACKUP_ENCRYPTION_PASSPHRASE
```

### 版本升级步骤

1. **保持旧版本服务处于运行状态**（不要提前删除数据卷，且确保 MySQL 容器在线以便升级前自动完成全量备份）。
2. 将新版 `whale-crm-<版本>-windows-amd64.zip` 校验 SHA256 后解压到**新目录**。
3. 把原安装目录下的 **`.env`** 文件以及 **`runtime\certs\`** 整个目录复制到新目录的对应位置（保留同一 `COMPOSE_PROJECT_NAME`、密钥及已受信任的本地 CA 与服务器证书）。
4. 在新目录中执行升级（建议先设置 `$env:BACKUP_ENCRYPTION_PASSPHRASE`）：
   ```powershell
   .\install.ps1 upgrade
   ```
5. 升级会先备份当前数据库、MinIO 对象存储和 `.env`，再载入新版镜像并执行 Flyway 向前迁移；**不会重置管理员、覆盖密钥或清空数据**。

> 注意：数据库迁移只向前执行，应用回滚不等于数据库回滚。

## 6. Windows 常见问题排查

### 1) Docker Desktop 未运行或处于 Windows 容器模式

- **现象**：脚本报错无法连接 Docker，或提示 `检测到当前 Docker Desktop 处于 Windows 容器模式（OSType=windows）`。
- **处理**：
  1. 启动 Docker Desktop，等待左下角状态显示为 **Engine running**。
  2. 在任务栏右下角右键点击 Docker Desktop 图标，若看到 **「Switch to Linux containers...」**，点击切换并等待重启完成。
  3. 在 PowerShell 执行 `docker info --format '{{.OSType}}'` 确认输出为 `linux` 后重试。

### 2) Hyper-V / WinNAT 保留端口冲突

- **现象**：安装预检提示端口位于 Windows Hyper-V/WinNAT 保留端口段，或启动容器时报错 `Ports are not available` / `bind: An attempt was made to access a socket in a way forbidden by its access permissions`。
- **原因**：开启 WSL2 / Hyper-V 后，Windows WinNAT 会动态保留若干 TCP 端口区间（有时会覆盖 `48080` 或 `48443`）。
- **排查**：在 PowerShell 中查看当前系统保留端口段：
  ```powershell
  netsh interface ipv4 show excludedportrange protocol=tcp
  netsh interface ipv6 show excludedportrange protocol=tcp
  ```
- **改端口方法**：
  - **首次安装时换用空闲端口**：避开上述保留区间，通过 `-HttpsPort` / `-HttpPort` 指定其他 40000+ 空闲端口，例如：
    ```powershell
    .\install.ps1 install -Ip 192.168.1.100 -HttpsPort 49443 -HttpPort 49080 -OpenFirewall
    ```
  - **已安装后修改端口**：编辑 `.env` 中的 `HTTPS_PORT`、`HTTP_PORT`、`PUBLIC_URL` 及 `FILES_PUBLIC_ENDPOINT`（保持端口号一致），再执行：
    ```powershell
    .\install.ps1 stop
    .\install.ps1 start
    ```
    如局域网其他电脑需要访问，记得同步放行新的 HTTPS 防火墙端口。

### 3) Windows Defender 防火墙放行

- **现象**：本机访问 `https://127.0.0.1:48443` 正常，但局域网其他电脑访问 `https://<本机IP>:48443` 超时或无法连接。
- **处理**：
  - 安装时以**管理员身份运行** PowerShell 并追加 `-OpenFirewall`。
  - 或手动在管理员 PowerShell 中放行 HTTPS 入站端口（对外只需放行 HTTPS 端口，默认 `48443/tcp`）：
    ```powershell
    New-NetFirewallRule -DisplayName "Whale CRM TCP 48443" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 48443 -Profile Any
    ```
  - 同时确认本机第三方安全软件防火墙以及局域网路由器/交换机未拦截该端口。

### 4) 更换本机 IP 后重签 HTTPS 证书

- **现象**：DHCP 重新分配了本机局域网 IP，导致旧地址无法访问或浏览器提示证书 IP 不匹配。
- **建议**：为部署主机配置局域网静态 IP（或在路由器绑定 MAC 地址）。
- **重签步骤**：
  1. 编辑 `.env` 中的 `PUBLIC_URL` 和 `FILES_PUBLIC_ENDPOINT` 为新地址（如 `https://192.168.1.120:48443`）。
  2. 删除 `runtime\certs\server.crt` 与 `runtime\certs\server.key`（**保留 `runtime\certs\ca.crt` 与 `runtime\certs\ca.key`**，已导入根证书的终端无需重复导入）。
  3. 执行以下命令自动重签新 IP 的 SAN 证书并重启生效：
     ```powershell
     .\install.ps1 stop
     .\install.ps1 start
     ```

### 5) WSL2 内存不足

- **现象**：安装启动超时，或 `.\install.ps1 status` 显示 `clamav`、`mysql`、`kafka`、`crm-api` 不健康或反复重启（`Exit 137`）。
- **处理**：
  1. 编辑 `%USERPROFILE%\.wslconfig`，将 `[wsl2]` 下的 `memory` 提高至 `8GB`（宿主机内存 ≥ 16 GB 时建议 `10GB`），并配置 `swap=4GB`。
  2. 在 PowerShell 中执行 `wsl --shutdown`，然后重新启动 Docker Desktop。
  3. 回到安装目录执行 `.\install.ps1 start`，必要时可临时设置 `$env:STARTUP_TIMEOUT = "900"` 并通过 `.\install.ps1 logs clamav` / `.\install.ps1 logs crm-api` 排查具体服务日志。

### 6) PowerShell 执行策略禁止运行 `.ps1`

- **现象**：在 PowerShell 中直接执行 `.\install.ps1` 时提示「因为在此系统上禁止运行脚本」。
- **处理**：直接双击 `install.bat` / `manage.bat`，或在命令行使用：
  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 status
  ```

## 7. 外部服务与上线边界

有赞、企业微信、真实 AI 写回和真实 Dify 默认关闭。需要启用时，先在 `.env` 中配置相应授权，再执行 `.\install.ps1 stop` 和 `.\install.ps1 start`；不要修改容器内配置，也不要把真实密钥提交到代码仓库。

- 内网中的 MySQL 连接默认不使用 TLS，仅适用于本机容器内网部署；外置数据库必须改为受保护的连接并调整 `DB_JDBC_URL`。
- ClamAV 镜像已内置基础病毒库（`main.cvd`、`daily.cvd`、`bytecode.cvd`），完全离线环境可直接启动扫描；经应用容器本机代理访问，不暴露宿主机端口。长期离线环境应定期导入新病毒库。
- 单机 Kafka 为单副本，不是生产高可用集群；Windows Docker Desktop 适合内网中小规模部署或单机交付，正式大流量生产上线前应评估 Linux 服务器部署、外置数据库、高可用、异地备份恢复演练及容量/安全验收。
