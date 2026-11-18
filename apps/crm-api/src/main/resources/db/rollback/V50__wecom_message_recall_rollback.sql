DROP TABLE IF EXISTS `crm_wecom_recall_event`;

ALTER TABLE `crm_wecom_message`
  DROP COLUMN `recall_message_id`,
  DROP COLUMN `recalled_at`;
