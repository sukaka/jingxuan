# 稳定错误码字典（contracts/errors）

所有 API 错误响应结构：`{"code","message","details","requestId","timestamp"}`。
`code` 为稳定字符串标识，客户端只允许基于 code 做分支，不得解析 message。

| code | HTTP | 含义 | 备注 |
|---|---|---|---|
| OK | 200/201 | 成功 | 成功响应 envelope 的 code |
| VALIDATION_FAILED | 400 | 字段类型/格式无效 | details 携带 field/reason 列表 |
| UNAUTHORIZED | 401 | 未登录或凭证失效 | 不泄露资源是否存在 |
| FORBIDDEN | 403 | 已认证但无权限 | 数据范围越权同此码 |
| CSRF_TOKEN_INVALID | 403 | 写请求缺少/不匹配 X-CSRF-Token | 双提交校验：须等于 CRM_CSRF Cookie 值 |
| NOT_FOUND | 404 | 资源不存在或不可见 | 敏感资源优先 404 防枚举 |
| CONFLICT | 409 | 乐观锁失败/状态冲突/幂等键冲突 | 客户端刷新后重试，禁止盲目重放写操作 |
| PENDING_BUSINESS_RULE | 422 | 依赖未确认业务规则（Qxx） | details 必须携带 Qxx 编号 |
| RATE_LIMITED | 429 | 超出限流 | 响应头 Retry-After |
| AUTH_PROTECTION_UNAVAILABLE | 503 | 登录保护存储暂不可用 | 验证码/失败计数不可用，不能静默跳过登录保护 |
| AI_PROVIDER_DISABLED | 503 | AI 模型服务未启用 | 默认安全关闭，启用前需完成供应商审批与配置 |
| AI_PROVIDER_NOT_CONFIGURED | 503 | AI 模型供应商配置不完整 | 不发送任何聊天内容 |
| AI_PROVIDER_FAILED | 502 | AI 模型服务暂时不可用 | Java 任务进入有界退避重试 |
| AI_OUTPUT_INVALID | 502 | AI 输出未通过结构、证据或安全校验 | 不保存洞察、不生成业务待办 |
| NOT_IMPLEMENTED | 501 | 该能力属于后续阶段 | 仅用于明确的未实现能力，不再用于 AI 抽取接口 |
| INTERNAL_ERROR | 500 | 未预期内部错误 | 不向前端暴露堆栈 |

领域错误码在各阶段 PR 中追加到本表（如 P0-3 的 `ENTITLEMENT_INSUFFICIENT`）。

P1-4 退款与结算复用上述稳定码：自审/越权为 `FORBIDDEN`，回调验签失败为 `UNAUTHORIZED`，非法状态、超额回收、待核实付款、超额付款、重复核销/银行流水及幂等冲突为 `CONFLICT`，字段、金额分摊和 `grossFen-commissionFen=netPayableFen` 等输入错误为 `VALIDATION_FAILED`。不得通过新增细分码绕过客户端对稳定通用码的处理；错误消息不得包含凭证正文或银行敏感信息。

P2-KB-1 Dify 知识配置复用现有 AI 错误码：`AI_PROVIDER_DISABLED` 包含 `FEATURE_REAL_DIFY` 未开启，`AI_PROVIDER_NOT_CONFIGURED` 包含 Dify 地址/Key 不完整，`AI_PROVIDER_FAILED` 表示上游或网络失败，`AI_OUTPUT_INVALID` 表示 blocking 响应或引用结构不符合内部契约。连接测试由 CRM 将供应商失败落为 `connectionStatus=FAILED` 并返回脱敏视图，不向前端透传上游报文；知识连接测试不自动重试，避免重复费用。
