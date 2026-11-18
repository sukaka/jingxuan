UPDATE `crm_institution_settlement_config`
SET `version` = 1
WHERE `version` = 0;

ALTER TABLE `crm_institution_settlement_config`
  MODIFY COLUMN `version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本，首次正式版本从1开始',
  ADD CONSTRAINT `chk_institution_settlement_config_version` CHECK (`version` >= 1);
