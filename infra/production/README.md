# 鲸选美学 CRM 离线一键安装包（欧拉系统 · 默认 HTTPS 高位端口）

这是 **Linux x86_64 服务器离线部署包**（适配 **openEuler 欧拉系统 / 兆芯 KY5288G1 等 x86_64 服务器**），包内已内置全部应用与中间件 `linux/amd64` 镜像，安装时不需要 Java、Node.js、Python、Maven 或 npm，也不会联网拉取镜像。

## 1. 默认 HTTPS 与多系统共存设计（零冲突）

针对服务器上**已经安装了其他业务系统**且**强制使用 HTTPS 访问**的场景，本安装包默认做了全面安全隔离：

- **默认强制 HTTPS + 40000+ 高位端口**：主入口默认 **HTTPS `48443`**（`COOKIE_SECURE=true` + HSTS），**不提供明文 HTTP 业务访问**；HTTP 端口默认 **`48080`** 仅做 `308` 强制跳转到 HTTPS（外部防火墙只需放行 `48443/tcp`）。可通过 `--https-port` / `--http-port` 改为任意 40000+ 空闲端口。
- **开箱即用离线 HTTPS 证书**：
  - 未提供外部证书时，脚本会在本机**自动离线生成**本地 Root CA（`runtime/certs/ca.crt`）及带 SAN（包含服务器 IP + `127.0.0.1` + `localhost`）的 825 天服务器 HTTPS 证书（私钥不打入安装包，仅在本机生成）。
  - 如有商业/企业 SSL 证书，可直接通过 `--tls-cert` / `--tls-key` 一键挂载，无需自签提示。
- **同源 HTTPS 文件上传下载**：文件上传、ClamAV 病毒扫描与预签名下载走同一 HTTPS 端口的 `/crm-private/*` 路径（自动剥离会话 Cookie、限制 15MB、`X-Content-Type-Options: nosniff`、`Content-Security-Policy: sandbox`、封禁 `/minio*`），无需额外证书或第二端口，浏览器内直接可用。
- **中间件零宿主机端口**：MySQL、Redis、Kafka、MinIO、ClamAV、crm-api、ai-worker、web 全部运行在独立 Docker 内部网络，**不占用 80、443、3306、6379、8080、9000、9092 等任何宿主机端口**。
- **欧拉系统兼容**：自动校验 `x86_64` 架构与端口占用，兼容 openEuler **SELinux（`:ro,z` + `chcon`）**、**`firewalld` 自动放行（`--open-firewall`）** 以及 **较旧版本 Docker Compose v2**。

## 2. 服务器前置条件

- **架构与系统**：x86_64 Linux（openEuler 20.03/22.03/24.03 LTS，兆芯 KY5288G1 等 x86_64 处理器）；建议至少 4 核 CPU、8 GB 内存和 20 GB 可用磁盘（业务数据与备份空间另计）。
- **运行环境**：已安装 Docker 与 Docker Compose v2（`docker compose` 插件或 `docker-compose` v2），以及 `curl`、`openssl`、`tar`、`gzip`。

## 3. 一键安装（默认 HTTPS + 48443 高位端口）

校验压缩包并解压（文件名以实际安装包为准）：

```bash
sha256sum -c whale-crm-2.0.2-openeuler-linux-amd64.tar.gz.sha256
tar -xzf whale-crm-2.0.2-openeuler-linux-amd64.tar.gz
cd whale-crm-2.0.2-openeuler-linux-amd64
```

### 方式 A：交互式一键安装（直接回车即可）

```bash
./install.sh
```

脚本会自动探测服务器 IP 并默认启用 **HTTPS `48443`** 端口，按两次回车即可自动完成镜像载入、随机密钥生成、离线 HTTPS 证书签发、服务启动与管理员初始化。

### 方式 B：一条命令无人值守安装（推荐）

```bash
# 1) 使用服务器 IP + 默认 48443 HTTPS 端口，并自动放行欧拉 firewalld 防火墙（需 sudo/root）
sudo ./install.sh install --ip 192.168.1.100 --https-port 48443 --open-firewall

# 2) 自动探测本机 IP 一键安装（HTTPS 48443）
sudo ./install.sh install --auto-ip --https-port 48443 --open-firewall

# 3) 使用自有商业/企业 SSL 证书（零浏览器安全提示）
sudo ./install.sh install \
  --ip 192.168.1.100 \
  --https-port 48443 \
  --tls-cert /path/to/fullchain.pem \
  --tls-key /path/to/privkey.pem \
  --open-firewall
```

安装完成后直接访问：**`https://<服务器IP>:48443`**

> **关于离线自动生成证书的浏览器提示**：若未传入 `--tls-cert`，首次用浏览器打开 `https://<IP>:48443` 时点击「高级 → 继续前往」即可正常使用（所有流量仍全程 TLS 加密，Cookie 保持 `Secure`）。如需团队电脑彻底无提示，可将安装目录下的 `runtime/certs/ca.crt` 导入系统受信任根证书，或替换为商业/企业 SSL 证书。

> **如果未使用 `--open-firewall` 且外部无法访问**：在欧拉服务器上只需放行 HTTPS 端口（若在云主机上还需放行安全组）：
> ```bash
> sudo firewall-cmd --permanent --add-port=48443/tcp
> sudo firewall-cmd --reload
> ```

> **如果后续需要更换服务器访问 IP**：编辑 `.env` 中的 `PUBLIC_URL` 和 `FILES_PUBLIC_ENDPOINT` 为新地址（如 `https://新IP:48443`），删除 `runtime/certs/server.crt` 与 `runtime/certs/server.key`，再执行 `./install.sh stop && ./install.sh start` 即可自动重签新 IP 证书并生效。

安装脚本会随机生成数据库、对象存储、JWT、字段加密及内部调用密钥，保存在当前目录的 `.env`（权限 600），不会覆盖已有 `.env`。

首次管理员账号默认为 `admin`，随机密码保存在 `initial-admin.txt`（权限 600），不会打印到终端或写入镜像。查看初始凭据：

```bash
cat initial-admin.txt
```

首次登录后请及时修改初始密码并创建正式运营账号。开发账号 `admin / Dev@12345` 不会进入正式库。

## 4. 公网标准域名 HTTPS 安装（可选）

若后续服务器开放 80/443 且具备正式系统域名和文件域名（Caddy 自动申请并续期 ACME 证书）：

```bash
./install.sh install \
  --url https://crm.example.com \
  --files-url https://files.example.com \
  --http-port 80 \
  --https-port 443
```

## 5. 日常维护与升级

```bash
./install.sh status        # 查看容器健康状态
./install.sh logs          # 查看全部日志
./install.sh logs crm-api  # 查看后端日志
./install.sh backup        # 备份数据库 + MinIO 文件 + .env 到 backups/（权限 600）
./install.sh stop          # 停止服务，保留数据卷
./install.sh start         # 使用原配置启动
```

升级时将新版安装包解压到新目录，把原安装目录的 `.env`（以及 `runtime/certs/` 目录以保留已信任证书）复制到新目录后执行：

```bash
./install.sh upgrade
```

升级会先备份当前数据库、MinIO 对象存储和 `.env`，再载入新版镜像并执行 Flyway 向前迁移；不会重置管理员、覆盖密钥或清空数据。

## 6. 外部服务与上线边界

有赞、企业微信、真实 AI 写回和真实 Dify 默认关闭。需要启用时，先在 `.env` 中配置相应授权，再执行 `./install.sh stop` 和 `./install.sh start`；不要修改容器内配置，也不要把真实密钥提交到代码仓库。

- 内网中的 MySQL 连接默认不使用 TLS，仅适用于本机容器内网部署；外置数据库必须改为受保护的连接并调整 `DB_JDBC_URL`。
- ClamAV 镜像已内置基础病毒库（`main.cvd`、`daily.cvd`、`bytecode.cvd`），完全离线环境可直接启动扫描；经应用容器本机代理访问，不暴露宿主机端口。长期离线环境应定期导入新病毒库。
- 单机 Kafka 为单副本，不是生产高可用集群；正式大流量前应评估外置数据库、高可用、异地备份恢复演练及容量/安全验收。
