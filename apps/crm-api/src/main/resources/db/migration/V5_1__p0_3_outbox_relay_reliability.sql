-- P0-3 Outbox 原子抢占、崩溃回收与有界退避；事件 ID 容纳最长 128 字符业务幂等键及前缀。
ALTER TABLE `crm_outbox_event`
  MODIFY COLUMN `event_id` VARCHAR(192) NOT NULL COMMENT '事件ID（支持确定性业务键）',
  MODIFY COLUMN `status` VARCHAR(32) NOT NULL COMMENT 'NEW/CLAIMED/SENT/FAILED',
  ADD COLUMN `next_retry_at` DATETIME(3) NULL COMMENT '失败后下次可重试时间；NULL表示立即可试' AFTER `last_error`,
  ADD COLUMN `claim_owner` VARCHAR(128) NULL COMMENT '当前relay实例唯一标识' AFTER `next_retry_at`,
  ADD COLUMN `claim_expires_at` DATETIME(3) NULL COMMENT '抢占过期时间，过期允许崩溃回收' AFTER `claim_owner`,
  ADD KEY `idx_outbox_relay_1` (`tenant_id`,`status`,`next_retry_at`,`claim_expires_at`,`id`);

ALTER TABLE `crm_inbox_event`
  MODIFY COLUMN `event_id` VARCHAR(192) NOT NULL COMMENT '事件ID（与Outbox一致，供未来消费者幂等）';
