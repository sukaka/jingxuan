# 11. 部署与运维设计

## 1. 服务划分

建议 gateway、crm-service、wecom-service、ai-service；P2 增加 archive-service、media-worker。第一阶段不要过度微服务化。

## 2. 基础设施

Nginx/API Gateway、Java Service、MySQL 8、Redis、Kafka、Object Storage、AI Service、Monitoring。

## 3. Redis Key

```text
wecom:access_token:{corpId}:{agentId}
wecom:jsapi_ticket:{corpId}
wecom:agent_ticket:{corpId}:{agentId}
wecom:oauth:state:{state}
sidebar:context:{userId}:{externalUserId}
quickreply:hot:{employeeId}
```

## 4. Token 管理

统一缓存、提前刷新、防并发击穿、分布式锁/single-flight、错误码触发刷新、Secret 不出服务端。

## 5. Kafka

`wecom.message.raw`、`wecom.message.normalized`、`crm.customer.event`、`ai.intent.request/result`、`ai.reply.request/result`。

## 6. 日志

记录 trace_id、tenant_id、employee_id、customer_id、api、result、latency。禁止打印 Secret、access_token、完整身份证、完整手机号、非必要原始敏感消息全文。

## 7. 监控

API QPS/P95、企业微信错误率、token 刷新失败、Redis 命中率、Kafka lag、archive cursor、AI 超时/失败率、话术发送成功率。

## 8. 降级

企业微信接口异常时展示 CRM 缓存数据；AI 异常不影响快捷话术；Kafka 异常不阻塞核心事务；会话存档异常保留 cursor 恢复点。


## 9. V47 H5 公开访问配置

H5 链接与内置封面 `/h5-card-cover.svg` 从 `WECOM_SIDEBAR_URL` 的 Origin 派生，并要求该 Origin 与 `WECOM_SIDEBAR_TRUSTED_ORIGINS` 一致。生产必须使用 HTTPS；本地仅允许明确的 localhost/127.0.0.1 HTTP。无需新增独立 H5 域名或 Redis 集群。

网关需将 `/h5/**` 指向前端静态应用，将 `/api/v1/public/h5-cards/**` 指向 CRM 后端。公开接口只读取 ACTIVE 项目分享快照，不开放内部 CRM 路径。配置变更后应分别验收员工预览、客户公开页、失效链接和企微 news 卡片；真实供应商环境未验收时不标记完成上线。


## 10. V48 官方会话归档 SDK 部署

使用企微[获取会话内容官方文档](https://developer.work.weixin.qq.com/document/path/91774)提供的 C SDK，生产 Compose 将 `wecom_archive_sdk` 以只读方式挂载到 `/opt/wecom-archive`。按服务器架构准备官方 `libWeWorkFinanceSdk_C.so`，并在 `private/` 中放置与公钥版本对应的 `private-{publickey_ver}.pem`；支持 PKCS1/PKCS8，RSA 模长至少 2048 位。

配置 `WECOM_ARCHIVE_ALLOWED_TENANT_ID`、`WECOM_ARCHIVE_CORP_ID`、`WECOM_ARCHIVE_SECRET`，可选代理使用 `WECOM_ARCHIVE_PROXY` 与 `WECOM_ARCHIVE_PROXY_PASSWORD`。SDK 路径和私钥目录分别由 `WECOM_ARCHIVE_SDK_LIBRARY_PATH`、`WECOM_ARCHIVE_PRIVATE_KEY_DIRECTORY` 指定，超时默认 15 秒。密钥仅通过部署环境注入，不提交仓库。

准备文件后需检查动态库架构和 OpenSSL 3 依赖，再由授权管理员在现有集成运行开关中启用真实归档。Mock 与真实游标分别为 `MOCK_ARCHIVE/OFFICIAL_ARCHIVE`，首次启用真实模式会从企业授权序号开始，不复用 Mock 位置。

V48 仅完成官方协议、RSA、权限与下游 MySQL/Kafka 的本地测试；V49 已扩展媒体分片与多类型，V50 已扩展撤回，V51 已扩展显式授权的群聊。未使用真实企业凭据调用供应商，不能把本地协议覆盖当成线上完整性、群成员同步或生产权限验收。


## 11. V49 媒体 Worker 部署

媒体复用 V48 官方 SDK 只读卷和私钥配置。生产设置 `WECOM_MEDIA_WORKER_ENABLED=true` 后，单实例 Worker 才按 MySQL 租约处理 PENDING/RETRY 资产；运行时的真实归档开关仍需单独启用。不需要 Redis 集群。

默认单文件上限 50MiB（`WECOM_MEDIA_MAX_FILE_BYTES=52428800`）、最多 200 分片（`WECOM_MEDIA_MAX_CHUNKS=200`），其他批大小、租约与重试参数在 `app.wecom.media` 配置。私有对象存储必须可用；媒体下载成功只更新资产状态，不改变消息或订单事实。

JNA 会释放桥接原生库到临时目录，因此 CRM API 的 `/tmp` 挂载需要 `exec` 权限，Compose 已配置 `/tmp:size=256m,exec`；根文件系统仍保持只读。上线前需使用真实媒体文件 ID 验收分片续传、MD5、大小限制和权限，不以本地协议测试替代供应商验收。


## 12. V53 后续本地 ClamAV 恢复

开发容器曾因 OOM 退出，而旧 Compose 没有自动重启策略。`infra/compose/docker-compose.dev.yml` 现为 ClamAV 设置 `restart: unless-stopped`，健康检查启动宽限期为 120 秒；继续使用既有病毒库卷和 `ConcurrentDatabaseReload=no`，不通过关闭完整性校验或减少安全能力换取启动成功。

该配置改善异常退出后的恢复，不代表消除了 Docker 宿主机的内存压力。生产资源限制、监控与告警仍需按实际容量配置；本地恢复不冒充生产高可用验收。最终以真实健康检查、clamd PING 和项目扫描客户端的 EICAR 结果为准。

实际恢复结果：容器 `running/healthy`，`clamdscan --ping 3` 返回 PONG；项目 `ClamAvScanner` 对标准 EICAR 测试样本返回 FOUND，对合成正常文本返回 CLEAN。测试临时文件已删除，未改变生产安全边界。
