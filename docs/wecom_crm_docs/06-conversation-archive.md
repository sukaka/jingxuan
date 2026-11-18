# 06. 会话内容存档设计

## 1. 定位

P2 功能。未开通前，CRM 可以识别当前客户、显示业务数据、使用快捷话术，但不能假设能自动读取完整普通聊天内容。

## 2. 原则

需要企业微信侧开通、配置存档成员/Secret/加密公钥，使用官方 SDK 拉取加密消息并解密，媒体单独下载；客户同意与可存档范围按官方当前规则执行。

## 3. 架构

```text
WeCom Archive SDK -> archive-puller -> decrypt -> message-normalizer -> Kafka
                                                     ├-> DB
                                                     ├-> AI
                                                     └-> Media Worker
```

## 4. Topic

`wecom.message.raw`、`wecom.message.normalized`、`wecom.customer.changed`、`crm.customer.event`、`ai.intent.request/result`、`ai.reply.request/result`、`crm.todo.created`。

## 5. 幂等

使用 message_id/event_id/seq/cursor，数据库唯一约束；重试不得重复生成客户事件或待办。

## 6. 规范化结构

```json
{
  "messageId": "...",
  "from": "...",
  "toList": [],
  "roomId": null,
  "messageType": "text",
  "content": {},
  "sendTime": "...",
  "externalUserId": "...",
  "employeeUserId": "..."
}
```

## 7. 媒体

媒体元数据入库 -> media-worker 下载 -> 对象存储 -> 保存对象 Key。不要在数据库保存大二进制。

## 8. 异常

客户不同意、员工不在范围、SDK 失败、cursor 重复、消息乱序、媒体失败、Kafka 重试、AI 失败均需覆盖。


## 9. V48 官方 SDK 文本归档实现

- `OfficialWecomArchiveAdapter` 通过 JNA 调用企微官方 C SDK，不使用第三方仿冒 SDK。每次拉取创建独立 SDK 会话，严格递增 seq、消息 ID 去重、响应大小限制及 finally 释放资源；非 0 返回码不推进成功游标。
- 官方响应的 `encrypt_random_key` 按 `publickey_ver` 使用 `private-{version}.pem` 解密，支持 PKCS1/PKCS8 RSA 私钥和 RSA PKCS1 随机密钥解密；再调用官方 `DecryptData` 得到正文。Secret、代理密码、私钥和随机密钥不写日志。
- V48 当时只处理身份明确的单聊文本：通过已启用的 `crm_wecom_staff_account` 映射识别员工，external_userid 使用企微约定的 `wo/wm` 前缀。群聊、撤回、媒体/其他类型及身份歧义分别记录 `ROOM_CONTEXT_UNSUPPORTED/ACTION_NOT_SUPPORTED/MEDIA_OR_TYPE_PENDING/IDENTITY_UNRESOLVED` 缺口，不保存正文。
- 适配器不从空数据伪造联系人同意；联系人同意与可采成员继续由现有映射、回调及归档成员配置提供。同意后仍需授权范围校验，消息正文经 AES-GCM 后写入私有对象存储，并通过现有 Outbox 投递。
- 真实模式使用独立 `OFFICIAL_ARCHIVE` 游标，Mock 使用 `MOCK_ARCHIVE`，禁止切换模式时复用序号。`allowedTenantId + corpId` 与现有运行时开关共同限制真实采集，非授权租户仍返回禁用适配器。

V48 当时仅完成官方单聊文本链路和协议测试；V49 补媒体与多类型，V50 补撤回，V51 补群聊身份和授权。真实企业凭据、SDK 原生库兼容性和线上权限仍需供应商验收，不能以本地功能覆盖替代。


## 10. V49 媒体分片与多类型消息

V48 初始版本仅接收单聊文本；V49 起扩展为身份明确的单聊多类型消息，文本、Markdown、链接、位置、名片、文件、会议和混合/聊天记录等保留真实可读摘要，不再以 MEDIA_OR_TYPE_PENDING 阻止所有非文本消息。群聊与撤回仍分别有 ROOM_CONTEXT_UNSUPPORTED/ACTION_NOT_SUPPORTED 缺口，不改变既有历史事实。

递归提取所有含 `sdkfileid` 的对象，包括混合消息和聊天内容字符串中的嵌套 JSON；每个对象注册为 `crm_wecom_media_asset` 的独立 PENDING 资产。消息事务同时保存元数据，不把大二进制放 MySQL，也不因注册成功就宣称媒体下载成功。

Worker 使用 MySQL 租约和版本执行 `PENDING → DOWNLOADING → STORED/RETRY/FAILED`，通过官方 `GetMediaData` 依次拉取分片，校验游标推进、最大文件/分片数、声明大小与 MD5，再写私有对象并计算 SHA256。租约过期可恢复，旧租约不能覆盖新结果；重试不改变原消息事实。

授权会话页返回媒体资产状态，STORED 才显示下载入口。下载接口复用当前同意、成员覆盖和客户数据范围，使用附件响应，不暴露 sdkfileid、对象 Key 或永久公开 URL。真实媒体文件 ID 和供应商下载仍需企业环境验收。


## 11. V50 撤回同步与乱序补全

官方 `action=recall` 与 `msgtype=revoke` 通过 `revoke.pre_msgid` 关联原消息，不再作为普通文本入库或 ACTION_NOT_SUPPORTED 跳过。群聊仍因 ROOM_CONTEXT_UNSUPPORTED 独立阻断，不与单聊撤回混用。

撤回处理追加 `crm_wecom_recall_event`，并校验原消息的员工、external_userid 与 sender_type。生效时只更新原消息的 recalled_at、recall_message_id 与 version，不删除正文、私有对象、媒体资产或历史记录。内容响应明确返回 recalledAt，页面提示原文仅用于当前授权审计。

原消息尚未到达时，控制事件为 PENDING_ORIGINAL 并产生 RECALL_ORIGINAL_PENDING 缺口；原消息合法入库后自动补全为 APPLIED 并关闭缺口。重复事件按企业 + 控制消息 ID 幂等，重复补全不重复发布。实际生效追加 `wecom.message.recalled.v1`，topic 为 `crm.wecom.events.v1`，不含正文。

本地覆盖协议、身份不一致、重复、乱序与真实 MySQL 补全测试；真实供应商撤回时序与权限仍需企业环境验收。


## 12. V51 群聊身份、授权与归档

官方消息存在非空 `roomid` 时，适配器规范化为 `conversationType=ROOM`，保存真实发送人的企微 ID；不再直接以 ROOM_CONTEXT_UNSUPPORTED 阻断，也不从 `tolist` 猜测完整群成员。群与成员必须通过 `PUT /api/v1/integrations/wecom/rooms/{roomId}` 由有权管理员按可追溯的完整清单确认，该接口不是供应商群成员同步接口。

- `crm_wecom_room` 的 `ACTIVE/DISABLED` 只控制新归档，停用不删除历史。完整清单使用 version、幂等键、CSRF 和审计；未列出的旧成员改为 `REMOVED`，不物理删除。
- 归档前必须存在至少一位当前外部参与人、至少一位当前有效的已映射可采员工，发送人必须在当前清单内，且全部当前外部参与人与群内可采员工的联系授权均为 GRANTED。条件不足只记录缺口，不创建正文对象。
- 群消息的 `customer_id/external_userid/staff_user_id` 为空，不能用任一参与人代表整个群；`sender_wecom_userid/sender_staff_user_id` 记录实际发送方，`room_roster_version` 记录采集时清单版本。群聊不能进入单聊 AI 托管、投诉客户归因或单聊 AI 任务。
- 查询和数据范围在 SQL 中通过当前可采群参与人执行 ALL/TEAM/SELF 判断，同时复查群启用之外的历史读取条件。当前成员移除后，其新消息拒绝入库；已有消息仍可被其他有权群成员读取。已知历史外部参与人撤回同意后，即使已退出，也阻止继续读取历史内容。
- 群聊复用 V49 媒体和 V50 撤回；撤回除单聊身份外还校验 roomid 和实际发送人，乱序补全、私有正文、媒体权限与 Outbox 幂等规则不变。补存成功会关闭相同 seq 的群上下文/授权缺口，已恢复历史不删除。

真实企业的群成员清单来源、存档许可与线上 SDK 行为仍需供应商环境验收；管理员手工确认不冒充真实官方同步。
