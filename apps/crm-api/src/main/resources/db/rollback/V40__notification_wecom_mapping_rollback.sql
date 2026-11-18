ALTER TABLE `crm_wecom_staff_account`
  DROP KEY `uk_wecom_staff_employee`,
  DROP COLUMN `version`,
  DROP COLUMN `message_enabled`;
