-- 回滚 V52__platform_security_and_indexes.sql

ALTER TABLE `crm_wecom_staff_account`
  MODIFY COLUMN `message_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许接收企微应用通知；独立于归档同步 enabled';

ALTER TABLE `crm_wecom_media_asset`
  DROP INDEX `idx_wecom_media_status_claim`;

ALTER TABLE `crm_knowledge_sync_job`
  DROP INDEX `idx_knowledge_job_status_claim`;

ALTER TABLE `crm_export_job`
  DROP INDEX `idx_export_job_status_lease`;

ALTER TABLE `crm_ai_job`
  DROP INDEX `idx_ai_job_status_claim`;

ALTER TABLE `crm_outbox_event`
  DROP INDEX `idx_outbox_relay_status`;

ALTER TABLE `crm_idempotency_record`
  DROP INDEX `uk_idempotency_reco_1`,
  DROP COLUMN `actor_user_id`,
  ADD UNIQUE KEY `uk_idempotency_reco_1` (`tenant_id`, `scope_code`, `idempotency_key`);

ALTER TABLE `sys_login_session`
  DROP INDEX `idx_login_session_expires_at`,
  DROP INDEX `idx_login_session_token_hash`;

DELETE rp FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id` = rp.`tenant_id` AND p.`id` = rp.`permission_id`
WHERE p.`permission_code` IN ('wecom:mapping:manage', 'followup:policy:manage', 'channel:approve_finance');

DELETE FROM `sys_permission`
WHERE `permission_code` IN ('wecom:mapping:manage', 'followup:policy:manage', 'channel:approve_finance');
