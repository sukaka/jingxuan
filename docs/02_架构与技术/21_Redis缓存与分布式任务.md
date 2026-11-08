# 21 Redis 设计规范

## Redis 用途和边界
仅作为**加速与临时协调**，不以 Redis 值决定“成功退款/正式核销/余额”。Redis 丢失后可以从 MySQL 重建或重新登录；生产高可用按实际吞吐与运维条件选 Sentinel/Cluster，优先托管或专门团队运维。

| Key 示例 | 类型 | TTL（设计建议） | 数据源/失效策略 |
|---|---|---|---|
| `crm:{tenant}:dict:{code}:v{version}` | JSON string | 10~30min | MySQL 字典；修改后提升版本并广播失效 |
| `crm:{tenant}:institution:summary:{id}` | JSON | 5min | MySQL 机构公开字段；审批生效后删除 |
| `crm:{tenant}:product:sku:{id}` | JSON | 10min | MySQL SKU，有订单版本快照 |
| `crm:{tenant}:rate:api:{uid}:{minute}` | counter | 2min | 限流窗口；失败关闭或降级 |
| `crm:{tenant}:session:{sid}` | hash | 短会话 | 账号权限变更失效；生产不存明文密码 |
| `crm:{tenant}:lock:wecom-sync:{corp}` | string UUID | 30s按任务刷新 | 仅采集协调，最终游标写 MySQL |
| `crm:{tenant}:job:{jobId}:progress` | hash | 24h | 进度临时值，正式作业状态仍在 MySQL |
| `crm:{tenant}:ai:rate:{model}` | counter | 1min | 模型限流；不得泄露聊天内容 |

## 一致性策略
- Cache-aside：查缓存失败→DB→写短 TTL；写库成功后删缓存；无需尝试将业务写操作直接当 Redis 原子写。
- 防击穿：单飞加载/singleflight + 小随机 TTL（防雪崩），Redis 节点不可用时查询 DB 并对热点限流。
- 分布式锁：若用于同步采集，`SET key token NX PX TTL`，释放验证 token；锁**不代替数据库事务和唯一键**。对正式业务核销，以 MySQL `SELECT ... FOR UPDATE` 为准。
- 消息消费幂等：Redis SETNX 可用作快速挡板，但必须在 `idempotency_record`/`inbox_event` DB 唯一键最终保证。
- 用户授权与敏感数据：缓存最小必要字段，允许需要及时撤销的身份权限短 TTL；患者/客户聊天原文禁止随意缓存。

## Redis 监控
used_memory、evicted_keys、keyspace_hits/misses、connected_clients、blocked_clients、latency p95、主从延迟、连接池等待。出现 `evicted_keys` 上升时先确认 TTL/大 Key，禁止靠加大内存无分析地解决。

## 注意事项
- JWT/Cookie 会话失效与组织权限版本核对；当管理员回收某人查看聊天/导出的权限，应可主动吊销 token。
- 缓存内的客户权益显示可能滞后，关键“确认核销”必须从 DB 再读余额。
- 缓存键使用组织命名空间/环境名；客户端不允许 `KEYS *`；批量清理用 SCAN 且受保护。
- Redis Lua/锁脚本有专门测试覆盖释放别人锁、超时和竞争。

## 官方参考
https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/ 与 https://redis.io/docs/latest/develop/reference/eviction/
