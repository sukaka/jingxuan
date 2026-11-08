# 鲸选美学 CRM 离线安装包

这是 **Linux 服务器部署包**，不是 Windows 桌面安装程序。包内包含应用与中间件镜像，安装时不需要 Java、Node.js、Python、Maven 或 npm，也不会从镜像仓库下载镜像。

## 1. 准备服务器与域名

- x86_64 Linux；建议至少 4 核 CPU、8 GB 内存和 20 GB 可用磁盘，业务数据与备份空间另计。
- Docker 27 或更新版本、Docker Compose v2.24 或更新版本，以及 `curl`、`openssl`、`tar`、`gzip`。
- 系统域名，例如 `https://crm.example.com`。
- 文件域名，例如 `https://files.example.com`。合同、素材、凭证和导出文件需要它；两个域名都解析到服务器。
- 公网 HTTPS 需要安全组/防火墙开放 80、443，且服务器能访问证书签发机构。Caddy 自动申请并续期证书；不在安装包内放置私钥。

这是单机部署基线，不等同于高可用、渗透测试、容量验收或正式供应商接入验收。MySQL、Kafka、Redis 不暴露宿主机端口；文件服务只接受带有效签名或凭据的请求。

## 2. 安装

校验压缩包后解压（文件名以实际下载版本为准）：

```bash
sha256sum -c whale-crm-2.0.1-linux-amd64.tar.gz.sha256
tar -xzf whale-crm-2.0.1-linux-amd64.tar.gz
cd whale-crm-2.0.1-linux-amd64
./install.sh install \
  --url https://crm.example.com \
  --files-url https://files.example.com
```

安装脚本会生成数据库、对象存储、JWT、字段加密及内部调用密钥，保存在当前目录的 `.env`（权限 600）。不会覆盖已有 `.env`，请将该目录限制为管理员可访问。

首次管理员账号默认为 `admin`，随机密码保存在 `initial-admin.txt`（权限 600），不会打印到终端或写入镜像。请把密码存入密码管理器，首次登录后修改初始密码并创建正式运营账号。开发账号 `admin / Dev@12345` 不会进入正式库。

查看服务：

```bash
./install.sh status
./install.sh logs
./install.sh logs crm-api
```

## 3. 日常维护与升级

```bash
./install.sh backup    # 备份到 backups/，权限 600
./install.sh stop      # 停止服务，保留数据卷
./install.sh start     # 使用原配置启动
```

将新版安装包解压到另一个目录，把原安装目录的 `.env` 复制到新目录后执行：

```bash
./install.sh upgrade
```

升级会先备份当前数据库，再载入镜像并执行 Flyway 向前迁移。不会重置管理员、覆盖密钥或清空数据。应用回滚不等于数据库回滚，不能直接把旧镜像启动在无法兼容的新库上；升级前仍须核对变更说明并演练恢复。

`backups/` 仅在当前服务器，必须另做异地备份。恢复时应使用隔离环境先验证，不要直接覆盖正式库。

## 4. 本地隔离验证

仅用于本机验收，不用于真实业务数据：

```bash
./install.sh install \
  --url http://127.0.0.1:18080 \
  --site-address http://127.0.0.1 \
  --http-port 18080 \
  --https-port 18443 \
  --project-name whale-crm-local
```

本地验证不配置文件域名时，文件功能保持关闭。生产 HTTPS 安装必须配置文件域名，避免交付缺少上传下载的系统。非本机 HTTP 只有显式传入 `--allow-insecure-http` 才会继续，不建议这样部署真实业务。

## 5. 外部服务与上线边界

有赞、企业微信、真实 AI 写回和真实 Dify 默认关闭。需要启用时，先在 `.env` 中配置相应授权，再执行 `./install.sh stop` 和 `./install.sh start`；不要修改容器内配置，也不要把真实密钥提交到代码仓库。

- 内网中的 MySQL 连接默认不使用 TLS，仅适用于本机专用部署；外置数据库必须改为受保护的连接并调整 `DB_JDBC_URL`。
- ClamAV 通过应用容器的本机代理访问，原生明文 TCP 不暴露到网络；仅允许其出站访问 `database.clamav.net` 更新病毒库，不开放宿主机端口。离线镜像可启动，持续更新仍需互联网；严格离线环境应定期导入新病毒库。为降低更新时的内存峰值，病毒库重载期间会短暂阻塞新扫描，不关闭病毒库完整性校验。
- 单机 Kafka 为单副本，不是生产高可用集群；正式流量前应评估外置数据库、Kafka 复制、对象存储冗余和监控告警。
- 上线前还要完成真实权限/账务抽样、备份恢复、容量与安全验收。安装成功不代表这些验收已经完成。
