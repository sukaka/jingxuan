-- 回滚 V55__wecom_archive_scope_and_recall_repair.sql
-- 注：H-BUG-11 的 DIRECT 撤回事件 conversation_id 数据修复为纠偏 DML，回滚不恢复错误数据；
-- 回滚 uk_wecom_message_2 前需确保同一 (tenant_id, corp_id, archive_seq) 在多 scope 下无重复序号。

ALTER TABLE `crm_wecom_message`
  DROP INDEX `uk_wecom_message_2`,
  DROP COLUMN `archive_scope`,
  ADD UNIQUE KEY `uk_wecom_message_2` (`tenant_id`, `corp_id`, `archive_seq`);

ALTER TABLE `crm_wecom_callback_event`
  DROP INDEX `idx_wecom_callback_lease`,
  DROP COLUMN `lease_until`;

ALTER TABLE `crm_wecom_contact_sync_run`
  DROP INDEX `idx_wecom_sync_run_lease`,
  DROP COLUMN `lease_until`;
