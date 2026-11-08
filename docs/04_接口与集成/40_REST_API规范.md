# 40 REST API 契约与接口清单

## 1. 全局约定
所有路径前缀 `/api/v1`；JSON UTF-8；业务 code 为字符串稳定标识；金额字段为 `_fen` 整数；时间 ISO8601 (UTC)，分页 `page,size` 或 cursor 二选一并在团队内统一。请求带 `X-Request-Id`, `Idempotency-Key`（资金/核销/关键创建），认证采用安全 Token/Cookie 与后端 RBAC+ABAC。

统一响应：
```json
{"code":"OK","message":"success","data":{},"requestId":"...","timestamp":"2026-10-23T00:00:00Z"}
```
错误：
```json
{"code":"ENTITLEMENT_INSUFFICIENT","message":"权益余额不足","details":[{"field":"count","reason":"current balance is 0"}],"requestId":"trace"}
```
建议将标准 HTTP 状态与业务错误一一映射：400 校验；401未认证；403越权；404不可见；409并发/状态冲突；422领域规则失败；429限流；5xx内部错误。

## 2. API 清单（按领域拆 Controller/Service）
| 业务域 | 方法与路径 | 操作与关键检查 |
|---|---|---|
| Auth | `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | 认证/个人权限与菜单，演示登录禁用 |
| 用户/角色 | `GET /users`, `POST /users`, `PATCH /users/{id}`, `PUT /users/{id}/roles` | 管理员专属，审计 |
| 字典 | `GET /dictionaries/{code}/options`, `POST /dictionary-items`, `PATCH /dictionary-items/{id}`, `POST /dictionary-items/{id}/disable` | 管理员；停用不破历史 |
| 客户 | `GET /customers`, `POST /customers`, `GET /customers/{id}`, `PATCH /customers/{id}` | data scope 强制 |
| 标签/积分 | `PUT /customers/{id}/tags`, `GET /customers/{id}/points`, `POST /customers/{id}/points-adjustments` | 积分账本，不直接 SET balance |
| 合并/归属 | `GET /customer-merge-candidates`, `POST /customer-merges`, `POST /customers/{id}/ownership-transfers`, `GET /customers/{id}/ownership-history` | 必须审批/审计 |
| 跟进/任务 | `GET/POST /followups`, `PATCH /followups/{id}`, `GET/POST /tasks`, `PATCH /tasks/{id}` | 本人/团队权限 |
| 产品/套餐 | `GET/POST /projects`, `PATCH /projects/{id}`, `GET/POST /packages`, `POST /packages/{id}/versions` | 版本管理；不能影响旧订单 |
| SKU/活动 | `GET/POST /skus`, `PATCH /skus/{id}`, `GET/POST /campaigns`, `GET/POST /campaign-batches` | 编码唯一 |
| 机构 | `GET/POST /institutions`, `GET/PATCH /institutions/{id}`, `GET /institutions/directory` | 字段分权限 |
| 合同资质 | `GET/POST /institutions/{id}/contracts`, `GET/POST /institutions/{id}/qualifications` | 文件隐私权限 |
| 机构审批 | `POST /institution-change-requests`, `GET /institution-change-requests`, `POST /institution-change-requests/{id}/approve`, `POST /institution-change-requests/{id}/reject` | 按审批步骤与 base version |
| 订单 | `GET/POST /orders`, `GET /orders/{id}`, `PATCH /orders/{id}/draft`, `POST /orders/{id}/payment-confirmations` | 不可跳过支付核验 |
| 权益 | `GET /customers/{id}/entitlements`, `GET /entitlements/{id}/ledger` | 账本与快照 |
| 预约 | `GET/POST /appointments`, `POST /appointments/{id}/confirm`, `POST /appointments/{id}/check-in`, `POST /appointments/{id}/cancel` | 医院数据范围 |
| 核销 | `POST /redemptions`, `POST /redemptions/{id}/post`, `POST /redemptions/{id}/reversal-requests`, `GET /redemptions` | 最终确认仅机构授权 |
| 退款 | `POST /refunds`, `POST /refunds/{id}/approve`, `POST /refunds/{id}/execute`, `GET /refunds` | 财务执行、真实支付结果 |
| 结算 | `GET /settlements`, `POST /settlement-batches`, `POST /settlement-batches/{id}/confirm` | 财务权限与业务规则 |
| 有赞 | `GET /integrations/youzan/orders`, `POST /integrations/youzan/sync`, `POST /integrations/youzan/reconcile`, `POST /webhooks/youzan` | 回调签名，幂等 |
| 企微 | `GET /wecom/conversations`, `GET /wecom/conversations/{id}/messages`, `GET /wecom/archive-health` | 权限/同意/范围 |
| AI | `POST /ai/insights/jobs`, `GET /ai/insights`, `POST /ai/insights/{id}/confirm`, `POST /ai/reply-drafts` | 人工确认 |
| 素材文件 | `POST /files/upload-init`, `POST /files/{id}/complete`, `GET /files/{id}/download-url`, `GET/POST /materials` | 短效 URL / ACL |
| 报表/导出 | `GET /reports/operations`, `GET /reports/campaigns`, `POST /exports`, `GET /exports/{id}` | 分权、日志、异步 |
| 审计 | `GET /audit-logs` | 授权筛选、审计自身不可编辑 |

## 3. 核销示例
```
POST /api/v1/redemptions
Idempotency-Key: 7b5e...nonce
{"appointmentId":1001,"entitlementId":5001,"projectId":12,"count":1,"serviceAt":"2026-10-23T10:00:00+08:00","voucherFileId":9001}
```
HTTP 201 响应 `status=PENDING_INSTITUTION`。授权医院 `POST /redemptions/{id}/post` 后才得到 `POSTED` 与新的 available count。

## 4. 列表筛选与数据权限
`GET /customers?ownerId=...` 的 ownerId 只能**收窄**当前登录人的可访问范围，不允许用户自己指定扩大范围；机构类 API 不接受未经权限校验的 institutionId。所有 SQL 添加租户与权限数据范围，导出 worker 也必须独立复验。

## 5. 幂等和并发
`POST /orders/{id}/payment-confirmations`, `POST /redemptions/...`, `POST /refunds/...` 必须带业务幂等键；后端在 MySQL 保存 request hash 与 resource ID；同键不同请求体返回409，回放同键同请求返回首次结果。所有可编辑资源 PATCH 支持 `If-Match: <version>` 或请求 body.version 校验。

## 6. 发布规则
每个阶段将相应接口写到 `contracts/openapi`（本包附有最小 YAML），生成前端 TS client；不得靠前端猜测 JSON 字段。正式接有赞/企微时另定义供应商适配 DTO，与内部订单 API/状态分离。
