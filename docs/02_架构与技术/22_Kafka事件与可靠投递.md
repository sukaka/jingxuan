# 22 Kafka Topics、事件契约与可靠性

## 1. 为什么用 Kafka
有赞订单/退款、企微采集、AI 分析、消息提醒、导出/报表都是大量异步任务；用户操作同步完成关键 MySQL 事务，再可靠发布事件，避免对外平台慢导致业务页面卡死。**Kafka 不参与银行金额或核销余额的事务最终判定。**

## 2. Topic（示例名称与分区需压测决策）
| Topic | 分区 Key | Producer | Consumer | 主要负载 | 默认分类 |
|---|---|---|---|---|---|
| `crm.customer.events.v1` | customerId | CRM outbox | analytics/task/AI | 建档、转交、合并、标签变更 | INTERNAL |
| `crm.order.events.v1` | orderId | CRM outbox | entitlement/analytics | 订单支付确认、取消、退款意图 | SENSITIVE |
| `crm.entitlement.events.v1` | entitlementId | CRM outbox | report/cache | 发放、核销、回收、冲正 | SENSITIVE |
| `crm.refund.events.v1` | refundId | CRM outbox | report/cache | 已确认退款成功事实，不含申请/审批意图 | SENSITIVE |
| `crm.settlement.events.v1` | settlementBatchId | CRM outbox | report/cache | 已登记机构结算付款事实，不冒充银行回调 | RESTRICTED |
| `crm.appointment.events.v1` | appointmentId | CRM outbox | notification/institution | 预约创建/确认/到院 | SENSITIVE |
| `crm.institution.events.v1` | institutionId | CRM outbox | cache/notification | 合同/资质/资料审批 | RESTRICTED |
| `crm.youzan.raw.v1` | shopId:externalOrderId | adapter | mapper | 已验证的外部订单通知 ID/引用 | RESTRICTED |
| `crm.wecom.message.ref.v1` | conversationId | archive adapter | ai/monitor | 归档消息 ID/存储引用；不直接传大段聊天明文 | RESTRICTED |
| `crm.ai.insight.v1` | customerId | ai-worker | crm/task | 意向/摘要/证据引用/待确认 | SENSITIVE |
| `crm.knowledge.sync.v1` | knowledgeDocumentId | CRM outbox | knowledge-sync | 资料发布任务 ID；不传正文或密钥 | INTERNAL |
| `crm.notification.request.v1` | recipientId | domain worker | notifier | 回访、审批、医院预约提醒 | INTERNAL |
| `crm.dlq.*` | originalKey | consumer | ops | 失败事件/排障引用（脱敏） | RESTRICTED |

P1-4 成功事实使用以下确定映射，完整 payload 以 `contracts/events/event-envelope.schema.json` 为准：

| event_type | aggregate_type | Topic | 最小 payload 与事实边界 |
|---|---|---|---|
| `refund.succeeded.v1` | `refund` | `crm.refund.events.v1` | refundId、orderId、customerId、amountFen、externalRefundNo、verificationMethod；仅人工凭证复核或验签回调确认成功后发布 |
| `entitlement.refund_revoked.v1` | `entitlement` | `crm.entitlement.events.v1` | refundId、entitlementId、count、allocatedFen、externalRefundNo；每个权益批次一条，与 REFUND_REVOKE 负流水同事务写入 |
| `settlement.payment.recorded.v1` | `settlement` | `crm.settlement.events.v1` | settlementBatchId、settlementPaymentId、institutionId、amountFen、paidAt；仅表示财务已登记付款，不表示收到银行回调 |

三类事件均只发布真实成功事实。申请、批准、执行中、失败回调和待核实结算不得产生上述事件；payload 不含银行账号、凭证正文或完整外部报文。

P2-KB-2 使用 `knowledge.sync.requested.v1`（aggregate_type=`knowledge_document`，topic=`crm.knowledge.sync.v1`）表示发布任务已持久化，不表示 Dify 已创建文档或索引成功。payload 仅含 `jobId`、`documentId`，分区键为 documentId；素材正文、Dataset/App Key、客户资料和业务写操作不得进入事件。

KnowledgeSyncConsumer 在 `KNOWLEDGE_SYNC_KAFKA_ENABLED=true` 时消费该 topic，校验必填信封、occurred_at 时间格式及 `correlation_id=event_id`，拒绝未知信封字段和 `jobId/documentId` 之外的 payload 字段。合法事件在同一 MySQL 事务插入 `crm_inbox_event` 并将未停用资料的 WAITING 任务推进为 PENDING；`tenant_id + consumer_group + event_id` 唯一约束保证重复事件不重复推进。晚到的合法事件若对应 DISABLED 资料，只记录 Inbox 而不重新排队，数据库任务状态更新也会再次检查文档未停用。事务成功后才提交 offset，有限重试后进入 `crm.dlq.knowledge.sync.v1`。同步 Worker 由独立 `KNOWLEDGE_SYNC_WORKER_ENABLED` 控制，默认均关闭。

2026-10-28 真实 Broker E2E 已验证：发布合成素材后 Outbox 为 SENT，Inbox 记录 processed_at，任务只执行一次并 SUCCEEDED，随后文档停用。该结果仅证明 Mock Dataset 链路，不代表真实 Dify 已联调。

2026-10-28 P3-K01 使用独立 MySQL 8.4 与 Kafka 4.1.2 容器验证：同一 Outbox 重放两次仍只有一条 Inbox、一次 Worker 执行，文档保持 ACTIVE；缺少 occurred_at/correlation_id 的非法事件进入真实 DLQ，保留原始 key/value、topic/partition/offset 与异常头，不写 Inbox、不改变业务状态。MinIO 与 Dify 使用测试适配；本结果不外推到其他 Topic、生产审批回放或生产部署。

## 3. 所有事件强制信封
```json
{
  "event_id":"uuid",
  "event_type":"entitlement.redeemed",
  "event_version":1,
  "tenant_id":1,
  "aggregate_type":"entitlement",
  "aggregate_id":"12345",
  "occurred_at":"2026-10-23T08:00:00Z",
  "correlation_id":"trace-id",
  "causation_id":"request-id",
  "actor_id":"abc",
  "payload":{"redemption_id":"54321","entitlement_id":"12345","count":1}
}
```
资金/机构银行信息事件只放 ID/最小必要片段，详细记录由授权消费方查询数据库/安全 API 获取；禁止把会话全文发到通用 topic。

## 4. Outbox 事务写入
领域操作同一 MySQL 事务内写 `business tables` + `outbox_event(status=NEW)`；Relay 按主键批量 SELECT 使用 SKIP LOCKED（结合 DB 能力和分页策略），发布 Kafka 成功后标记 `SENT`。若发送成功但更新 DB 状态失败，会重复发送，因此消费者必须幂等。

## 5. Inbox 与消费机制
`inbox_event(group_name, event_id)` 唯一索引。消费顺序：校验 schema/event version→核对权限/来源→DB 事务中尝试插入 Inbox→处理业务→提交 DB→提交 Kafka offset。处理失败重试：指数退避+抖动，超过次数入 DLQ。DLQ 回放需运维审批并保留原因/操作人。按 aggregate ID 做 partition key 减少乱序。

## 6. 可靠性参数建议
生产 `acks=all`, `enable.idempotence=true`, 压测再定 retries；Kafka 事务若使用遵守 Kafka 文档，但**Kafka exactly-once 不自动涵盖 MySQL 外部副作用**。生产 topic 复制因子>=3、min ISR>=2需有 >=3 broker；开发单机副本1只用于测试。

## 7. 顺序、重放、失败边界
- 消费收到旧 `aggregate_version`：丢弃已应用版本或标记重放；缺版本需重试并报警。
- 订单支付先于客户匹配：进入异常队列而不是创造无依据的客户权益。
- 有赞/企微接入网络失败：轮询补偿 + checkpoint + 告警；不要靠 Kafka 自动补齐未取到的外部数据。
- 监控：producer error、outbox_age、topic lag、consumer retry、DLQ count、处理延迟、事件总量与 DB 对账差异。

## 8. 官方参考
https://kafka.apache.org/quickstart/ 与 https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html
