ALTER TABLE `crm_quick_reply_usage`
  DROP FOREIGN KEY `fk_quick_reply_usage_ai_suggestion`,
  DROP KEY `idx_quick_reply_usage_ai_suggestion`,
  DROP COLUMN `ai_suggestion_id`;

DROP TABLE IF EXISTS `crm_ai_reply_suggestion`;

UPDATE `crm_quick_reply_template` SET `ai_enabled`=0
WHERE `code` IN ('PROJECT_REMAINING','APPOINTMENT_CONFIRM','FOLLOWUP_GREETING');
