# 08. 后端 API 设计

## 1. 企业微信

```http
GET  /api/wecom/oauth/authorize
GET  /api/wecom/oauth/callback
POST /api/wecom/jssdk/signature
GET  /api/wecom/sidebar/context?externalUserId=...
POST /api/wecom/customer/sync
```

## 2. Sidebar Context 返回示例

```json
{
  "employee": {"id": 1028, "name": "张三"},
  "customer": {"id": 100028, "name": "王女士", "mobileMasked": "138****1234"},
  "summary": {"totalOrders": 3, "remainingPackages": 2, "lastVisitDate": "2026-11-02"}
}
```

## 3. 快捷话术

V44–V46 已实现以下接口，统一使用 `/api/v1` 前缀：

```http
GET    /api/v1/quick-replies?scene=...
POST   /api/v1/quick-replies
PATCH  /api/v1/quick-replies/{id}
POST   /api/v1/quick-replies/{id}/disable
POST   /api/v1/quick-replies/{id}/preview
POST   /api/v1/quick-replies/{id}/usage
PUT    /api/v1/quick-replies/{id}/favorite
DELETE /api/v1/quick-replies/{id}/favorite
GET    /api/v1/quick-replies/categories
POST   /api/v1/quick-replies/categories
PATCH  /api/v1/quick-replies/categories/{id}
POST   /api/v1/quick-replies/categories/{id}/disable
GET    /api/v1/quick-replies/variables
GET    /api/v1/quick-replies/statistics?from=...&to=...&employeeId=...
GET    /api/v1/quick-replies/usages
```

预览返回 `templateId、content、variables、missingVariables、canSend、allowEdit`。管理写接口要求 `quickreply:manage`、CSRF、幂等键、版本和理由；员工侧读取与使用要求 `quickreply:view`，普通员工统计仅限本人。

后台管理页面位于 `/settings/quick-replies`，话术继承分类作用域，可插入白名单变量并按真实 CRM 客户预览。统计区分复制、SDK 成功、失败、未知与编辑数；成功率不把复制或客户已读计入。

## 4. AI

V46 已实现以下员工侧接口，项目统一使用 `/api/v1` 前缀：

```http
POST /api/v1/ai/replies/generate
GET  /api/v1/ai/replies?customerId=...&size=20
GET  /api/v1/ai/replies/{id}
POST /api/v1/ai/replies/{id}/decision
```

生成请求只传 `customerId`、`templateId` 和选填 `instruction`。模板必须启用 `aiEnabled` 且在当前员工作用域内；`instruction` 只是不可信的措辞要求。后端通过白名单 Resolver 加载真实事实，不接受前端传入金额、余额、次数、日期、订单状态或机构作为可信事实。

生成与处理写接口均需 CSRF 和 `Idempotency-Key`。状态仅允许 `PROPOSED → ACCEPTED/REJECTED`，处理时校验 `version`；原模型回复不可覆盖，采纳可另存编辑后的 `finalContent`。只有当前员工针对当前客户已采纳的候选，才能通过 `aiSuggestionId` 写入 `/api/v1/quick-replies/{templateId}/usage`，且模板、场景和最终文本必须一致。

模型未启用、调用失败或输出未通过结构/事实/安全校验均返回明确错误，不生成伪成功记录。`provider=mock` 会在员工页面明确标识。AI 采纳不等于已复制或已发送，SDK 成功也不等于客户已读。

## 5. 业务接口

复用现有 CRM；必要时增加聚合：

```http
GET  /api/customers/{customerId}/packages/summary
POST /api/customers/{customerId}/followups
POST /api/customers/{customerId}/appointments
POST /api/customers/{customerId}/todos
```

## 6. 统一要求

鉴权、数据权限、trace_id、统一错误码、统一分页、敏感数据脱敏、不暴露 Secret/token。

## 7. V43 已实现的侧边栏基础接口

项目统一使用 `/api/v1` 前缀，当前实现如下：

```http
GET  /api/v1/wecom/oauth/authorize
GET  /api/v1/wecom/oauth/callback?code=...&state=...
POST /api/v1/wecom/jssdk/signature
GET  /api/v1/wecom/sidebar/context?externalUserid=...
```

- OAuth state 为一次性随机值，存于单实例 Redis；回调只接受唯一映射的 ACTIVE CRM 员工，并复用现有 JWT + 服务端会话 Cookie。
- JS-SDK 同时返回企业 `config` 与应用 `agentConfig` 签名。票据按 CorpID 和类型缓存，URL 不含 `#`，非本地必须 HTTPS 且 Origin 受信。
- 上下文状态为 `READY / CUSTOMER_UNLINKED / WAITING_CONTEXT`；多个客户映射时拒绝猜测。订单和权益摘要分别受 `order:read`、`entitlement:read` 与客户数据范围控制。
- `remainingPackages` 是可用权益数，`remainingCount` 是剩余服务次数之和，两者不混用。
- 真实企业微信环境验收仍需有效凭据、可信域名和已授权应用；本地测试不代表供应商联调通过。


## 8. V47 H5 分享卡片接口

```http
POST /api/v1/h5/cards
GET  /api/v1/h5/cards?customerId=...&size=20
POST /api/v1/h5/cards/{id}/disable
POST /api/v1/h5/cards/{id}/usage
GET  /api/v1/public/h5-cards/{shareKey}
```

生成需要 `wecom:sidebar:view + product:read`、CSRF 和幂等键，后端校验客户范围、企微映射及 ACTIVE 项目。卡片保存标题、摘要和公开正文的不可变快照；停用需要 version、理由、CSRF 和幂等键。

使用记录支持 `COPIED/SDK_SUCCESS/SDK_FAILED/SDK_UNKNOWN`，只追加且不代表客户已读。公开接口免登录，凭高随机 shareKey 仅读取 ACTIVE 卡片，只返回项目名称与公开内容，不返回内部 ID、客户、员工或企微身份。


## 9. V49 媒体读取接口

```http
GET /api/v1/integrations/wecom/media/{id}/content
```

`id` 是内部媒体资产 ID。接口要求 `wecom:read`，每次复查消息当前同意、归档成员和客户数据范围；仅 `STORED` 返回私有对象附件流。`PENDING/DOWNLOADING/RETRY/FAILED` 返回 422，未授权返回 404。

`GET /api/v1/integrations/wecom/messages/{id}/content` 额外返回 `mediaAssets`，包含资产 ID、类型、脱敏文件名、大小、状态与 `available`；不包含 sdkfileid、对象 Key 或永久公开地址。媒体下载与消息正文读取分别写审计。


## 10. V50 撤回字段与事件

`POST /api/v1/integrations/wecom/sync` 新增响应字段 `recalled`，表示本次新接收的撤回控制事件数，不与普通消息 `stored` 或授权 `skipped` 混用。

消息列表新增 `recalledAt/recallMessageId`；消息内容响应也返回 `recalledAt`。撤回后原正文不删除，只有授权范围、当前同意与归档成员均满足时才可读取，页面明确提示其审计属性。

实际补全发布 `wecom.message.recalled.v1` 到 `crm.wecom.events.v1`，仅包含 `messageRef、recalledAt、recallMessageId`，不含正文。PENDING_ORIGINAL 撤回在原消息补全前不发布生效事件。


## 11. V51 群聊授权与消息查询

```http
GET /api/v1/integrations/wecom/rooms?corpId=...
PUT /api/v1/integrations/wecom/rooms/{roomId}
GET /api/v1/integrations/wecom/messages?customerId=...&conversationId=...&page=1&size=20
```

群聊读取要求 `wecom:read` 及当前员工数据范围；登记/更新要求 `wecom:monitor`、CSRF、`Idempotency-Key`、version 和操作理由。请求体字段为 `corpId、displayName、enabled、memberUserids、sourceEvidenceRef、version、reason`。`memberUserids` 是完整当前成员清单，不是追加补丁；新群 version=0，已有群必须匹配当前版本。

启用前校验员工映射/在职/归档覆盖、至少一位外部参与人及当前外部参与人的同意。失败时不保留半份配置；相同幂等键重放返回同一资源结果。停用只阻止新归档，退群只追加状态变化，不删除消息或媒体。

消息响应新增 `conversationType、senderWecomUserid、senderStaffUserId、roomRosterVersion`；ROOM 的 `customerId、externalUserid、staffUserId` 始终为空。可按 conversationId 查询某个授权群，但列表和正文均仍受当前成员、同意和 ALL/TEAM/SELF 范围约束。`wecom.message.archived.v1` 同步增加 conversationType 和可空 roomRosterVersion，不包含正文。


## 12. V52 AI 意图响应契约

既有 `/api/v1/ai/jobs` 和 `/api/v1/ai/jobs/{id}` 的 `insight.insightType` 扩展为 13 类：`PACKAGE_QUERY、APPOINTMENT_INTENT、VISIT_INTENT、AFTERCARE_FEEDBACK、PRICE_INQUIRY、REFUND_INTENT、COMPLAINT、REPURCHASE_INTENT、FOLLOWUP_REQUIRED、RISK_EVENT、SERVICE_EXPERIENCE、SERVICE_COMPLETION_CLAIM、GENERAL`。

`proposedActions` 继续只允许 `VERIFY_BALANCE、ASK_PREFERRED_INSTITUTION、CREATE_FOLLOWUP_DRAFT、VERIFY_SERVICE_RECORD、OFFER_HUMAN_SUPPORT、VERIFY_PAYMENT_RECORD`。高风险分类要求对应风险标记和人工动作；`evidenceMessageIds` 必须是当前授权输入内的真实消息。未知类型、伪造证据、事实状态或金融写标志不合法时终止，不保存候选。

响应扩展只增加枚举值，不改变任务状态机、请求字段、权限或事件 Topic；不新增自动写业务事实的接口。完整类型定义见 `05-ai-design.md`，OpenAPI 以 `contracts/openapi/openapi.v1.yaml` 为准。


## 13. V53 企微运营分析接口

```http
GET /api/v1/reports/wecom-analytics?from=2026-11-10&to=2026-11-11&employeeId=2
```

需要 `report:read`；`employeeId` 可选，SELF 只能查询本人，TEAM/ALL 仍受客户数据范围约束，机构账号拒绝读取。日期默认最近 30 个企业自然日，最长 366 天，返回 from/to、UTC 半开区间、scopeType、generatedAt 和 `metricVersion=P3_FACT_V1`。只读现有事实，不创建快照或写业务表。

### 指标口径

- `quickReply`：使用日志、复制、SDK 成功/失败/未知、编辑后使用和 AI 关联使用。`sdkSuccessRate` 的分母仅为有 SDK 结果的记录，不把复制计入，也不代表已读。
- `aiReply`：期内生成候选的当前 ACCEPTED/REJECTED/PROPOSED 状态、编辑采纳、Mock 数、候选复制与 SDK 结果。`cohortAcceptanceRate=acceptedCount/generatedCount`，无生成候选时为 null。采纳不等于复制或发送。
- `conversion.confirmedAppointmentCount`：期内有真实 CONFIRMED 状态历史的不同预约；`checkedInFromConfirmedCount` 为同批次中在确认后、统计期末前实际 CHECKED_IN 的数量，二者同口径后才计算到院率。
- `conversion.paidOrderCount`：期内有 CONFIRMED 支付流水且订单为有效已支付状态的不同订单，同订单多流水只算一笔。`repeatPaidCustomerCount` 为期内至少两笔不同已支付订单的客户数，`repeatCustomerRate=repeatPaidCustomerCount/paidCustomerCount`，无付费客户时为 null。
- 员工筛选对候选/使用记录使用实际操作员工，对预约使用创建时归属快照，对订单使用成交归属快照；客户范围按当前有效客户档案判断，不修改历史事实。

`unsupportedMetrics` 显式列出 `MESSAGE_READ_RATE、H5_OPEN_RATE、CHAT_CAUSAL_ATTRIBUTION、QUICK_REPLY_BUSINESS_OUTCOME`。当前没有客户已读、H5 打开和聊天对业务结果的因果证据，不能将这些指标填成 0 或用同客户时间窗臆造归因。Mock 候选数量单列，不用于证明真实模型效果。
