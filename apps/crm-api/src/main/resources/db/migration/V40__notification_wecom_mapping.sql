ALTER TABLE `crm_wecom_staff_account`
  ADD COLUMN `message_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许接收企微应用通知；独立于归档同步 enabled' AFTER `enabled`,
  ADD COLUMN `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '通知映射维护乐观锁版本' AFTER `message_enabled`,
  ADD UNIQUE KEY `uk_wecom_staff_employee` (`tenant_id`,`corp_id`,`staff_user_id`);
