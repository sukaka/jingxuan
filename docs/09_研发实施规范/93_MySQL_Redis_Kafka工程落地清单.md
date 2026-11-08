# 93 MySQL / Redis / Kafka 工程落地与防事故检查清单

本页用于架构评审和大模型提交 PR 时逐项勾选；本地 Compose 只是开发环境，不能据此宣称生产高可用。

## MySQL 8.4：系统唯一业务事实源

| 事项 | 强制要求 | 验收方式 |
|---|---|---|
| 引擎/编码 | InnoDB/utf8mb4，业务时刻存 UTC | 环境/DDL 自动检查 |
| 交易金额 | BIGINT 存整数分，税/佣金用 DECIMAL 的规则快照，不用 double | 金额分摊、四舍五入/余数测试 |
| 事务 | 支付确认、权益发放、核销记账与 Outbox 在同一事务 | 故障注入 + DB 回滚检查 |
| 并发 | 余额扣减 SELECT FOR UPDATE 或安全条件 UPDATE，防超卖 | 余额1并发双核销仅1成功 |
| 幂等 | 外部交易号、支付事件ID、核销申请幂等键、退款回执业务唯一约束 | 同事件重放100次结果唯一 |
| 租户/权限 | 每个表 `tenant_id`，每个查询带租户和数据范围；跨租户的 FK/ID 必须服务端验证 | 双租户/跨机构拒绝用例 |
| 历史 | 已支付订单、核销、退款、审批生效记录使用追加流水/冲正 | 无 UPDATE 直接覆盖操作 |
| DDL | Flyway 版本化迁移；每表有索引、唯一约束、必要字段长度和审计 | 测试环境从空库顺序 migrate |
| 日期 | DATETIME(3) 明确 UTC；日期型权益到期以机构当地日界为规则配置 | 跨时区、闰日、月底测试 |
| 隐私 | 手机、银行卡、合同按字段密文/对象存储私有引用；可检索用 HMAC 盲索引 | 数据库转储无明文 |
| 对账 | 账本 sum 与余额快照逐笔核对，差异冻结并开 risk_case | 模拟篡改/恢复用例 |
| 备份 | 全量+binlog/PITR计划、定期异地备份和恢复验证 | 实际恢复演练报告 |

**注意**：基线示例 SQL 是蓝图，不代表所有联合外键、软删除唯一键和 CHECK/触发器已获审计。尤其 `tenant_id` 与自增主键组合的跨租户外键关联需在正式设计中补充分区级或复合约束/服务校验；不要误把统一 `tenant_id` 字段当成租户隔离已经自动成立。

## Redis：只做可丢弃的派生状态

建议 Key（按租户+领域隔离）：

```text
crm:{tenant}:session:{sessionId}            TTL 2h/按登录策略
crm:{tenant}:dict:{dictType}:v{version}     TTL 30m + 更新主动失效
crm:{tenant}:customer:summary:{id}          TTL 5m，回填需权限校验
crm:{tenant}:institution:profile:{id}       TTL 10m，敏感值不入缓存
crm:{tenant}:rate:{api}:{subject}           TTL 1m
crm:{tenant}:task:due:yyyyMMdd              可重建的工作台提示
```

- 严禁在 Redis 中单独扣客户消费次数或累计实付，把缓存当账本。
- `DEL`/缓存丢失不改变正式订单或权益；缓存击穿用 singleflight/逻辑互斥，缓存失效用版本事件/明确TTL。
- Redis 分布式锁不是跨服务金融事务保证，不能代替 MySQL 唯一约束和行锁。
- 生产建议按运维要求评估 Sentinel/Cluster、TLS、ACL、AOF、故障转移和失效预算，容量压测后决定。

## Kafka：异步事件，至少一次投递+业务幂等

最小事件交换：

```text
MySQL正式事务
    ├─支付/权益/核销/退款业务表
    └─outbox_event(PENDING)
          ↓ Relay轮询/批量
Kafka topic（按业务 aggregate_id 分区）
          ↓ consumer
inbox_event唯一(event_id, consumer_group)
          ↓ 订阅者处理+本地事务提交
报表/通知/AI待办等投影
```

建议 Topic 见 `22_Kafka事件与可靠投递.md`，每个事件必须有 `event_id,event_version,tenant_id,aggregate_id,occurred_at,correlation_id`。

| 故障 | 系统预期行为 |
|---|---|
| Kafka 不可用 | 业务 MySQL 事务可提交；Outbox 堆积报警，待恢复后发送 |
| 发布成功但标记失败 | 允许重复投递；Inbox 唯一处理，不能重复生成权益 |
| 消费到未知版本 | 暂停/死信处理，不无视新增敏感字段 |
| 事件乱序 | 根据 aggregateVersion 拦截旧版本，缺口等待重试 |
| 消费者失败 | 有上限的重试，DLQ，告警，经过审批可回放 |
| 积压显著 | lag、oldest_outbox_age 监控和业务服务降级说明 |
| 企微消息含私密内容 | Kafka 尽量传加密存储引用，不发全文/附件二进制 |

注意顺序：消费者数据库事务成功**后**提交 Kafka offset；不能先 ACK 再写账。不能仅依赖 Kafka EOS 来声称 MySQL 侧恰好一次。

## 容量与 SLA 基准

在未知真实日订单量、历史客户量和会话消息量前，**不设伪精确容量**。上线前收集：客户数、日新增、峰值订单/核销QPS、聊天消息峰值、单消息媒体均值、活动最大峰值、预期保存年限。以 `52_非功能需求与SLO.md` 设置候选值并执行真实压测；对超标服务保留扩容和降级策略。

## 数据/事件命名基准

- 数据表：`crm_<domain>`，库内不要混杂业务金额单位。
- API 业务码：`ORDER_STATE_CONFLICT / ENTITLEMENT_INSUFFICIENT / INSTITUTION_SCOPE_DENIED`。
- Domain Event：过去时 `order.paid.v1 / entitlement.redeemed.v1`，事件 contract 独立版本；可兼容扩展，不随意修改已发布语义。
- ID：数据库BIGINT/雪花ID皆可但全项目统一；JavaScript 大整数从 REST 返回时建议采用字符串，避免 2^53 精度丢失。
- 时间：DB UTC，展示按用户/机构明确时区；金额币种区分 `CNY`/分，避免多币种扩展时隐形兼容问题。
