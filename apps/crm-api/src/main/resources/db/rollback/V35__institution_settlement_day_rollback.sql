-- 回滚前确认没有仍需保留的新结算日快照；删除列会丢失这些快照，生产执行前必须备份。
ALTER TABLE `crm_settlement_batch`
  DROP CHECK `chk_settlement_batch_per_redemption_day`,
  DROP CHECK `chk_settlement_batch_day`,
  DROP COLUMN `settlement_date`,
  DROP COLUMN `settlement_day_source`,
  DROP COLUMN `settlement_day`;

DROP TABLE IF EXISTS `crm_institution_settlement_config`;

ALTER TABLE `crm_institution`
  DROP CHECK `chk_institution_settlement_day`,
  DROP COLUMN `settlement_day`;
