-- 仅用于开发应急回滚；先备份并停止全部 relay。
-- 执行前必须确认 Outbox/Inbox 没有超过 64 字符的 event_id；MySQL 会拒绝截断超长值。
UPDATE `crm_outbox_event` SET `status`='NEW' WHERE `status`='CLAIMED';

ALTER TABLE `crm_outbox_event`
  DROP KEY `idx_outbox_relay_1`,
  DROP COLUMN `next_retry_at`,
  DROP COLUMN `claim_owner`,
  DROP COLUMN `claim_expires_at`,
  MODIFY COLUMN `event_id` VARCHAR(64) NOT NULL COMMENT 'UUID事件ID',
  MODIFY COLUMN `status` VARCHAR(32) NOT NULL COMMENT 'NEW/SENT/FAILED';

ALTER TABLE `crm_inbox_event`
  MODIFY COLUMN `event_id` VARCHAR(64) NOT NULL COMMENT 'UUID事件ID';
