-- 回滚 V53__trading_settlement_fixes.sql（需先确保无同机构同月结周期多批次数据）
ALTER TABLE `crm_settlement_batch`
  DROP INDEX `uk_settlement_batch_per_redemption`,
  DROP INDEX `idx_settlement_batch_period`,
  DROP COLUMN `per_redemption_period_key`,
  ADD UNIQUE KEY `uk_settlement_batch_period` (`tenant_id`, `institution_id`, `settlement_type`, `period_key`);
