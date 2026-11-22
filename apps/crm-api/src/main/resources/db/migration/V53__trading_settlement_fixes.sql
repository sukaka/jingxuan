-- M-BE-06: 允许同一机构同一月结期间为迟到核销创建后续补充批次；
-- 通过虚拟生成列保留 PER_REDEMPTION 周期唯一性，并保留 crm_settlement_line(tenant_id, redemption_id) 全局唯一约束。
ALTER TABLE `crm_settlement_batch`
  ADD COLUMN `per_redemption_period_key` VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN `settlement_type` = 'PER_REDEMPTION' THEN `period_key` ELSE NULL END) VIRTUAL
    COMMENT '仅PER_REDEMPTION保留周期唯一性，允许MONTHLY同期间创建后续批次' AFTER `period_key`,
  DROP INDEX `uk_settlement_batch_period`,
  ADD KEY `idx_settlement_batch_period` (`tenant_id`, `institution_id`, `settlement_type`, `period_key`),
  ADD UNIQUE KEY `uk_settlement_batch_per_redemption` (`tenant_id`, `institution_id`, `per_redemption_period_key`);
