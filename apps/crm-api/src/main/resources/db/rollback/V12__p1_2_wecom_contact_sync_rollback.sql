DROP TABLE IF EXISTS `crm_wecom_callback_event`;
DROP TABLE IF EXISTS `crm_wecom_contact_sync_run`;
ALTER TABLE `crm_wecom_contact`
  DROP KEY `idx_wecom_contact_3`, DROP KEY `idx_wecom_contact_4`,
  DROP COLUMN `last_sync_run_id`, DROP COLUMN `follow_status`, DROP COLUMN `tag_snapshot_json`, DROP COLUMN `follow_created_at`,
  DROP COLUMN `channel_state`, DROP COLUMN `add_way`, DROP COLUMN `description`, DROP COLUMN `remark`,
  DROP COLUMN `unionid`, DROP COLUMN `gender`, DROP COLUMN `contact_type`, DROP COLUMN `avatar`, DROP COLUMN `name`;
DROP TABLE IF EXISTS `crm_wecom_staff_account`;
