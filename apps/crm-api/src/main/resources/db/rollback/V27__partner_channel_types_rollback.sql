-- 仅在备份并获批准的下线窗口执行。
ALTER TABLE `crm_partner_channel`
  DROP KEY `idx_partner_channel_type_status`,
  DROP CONSTRAINT `chk_partner_channel_type`,
  DROP COLUMN `channel_type`;
