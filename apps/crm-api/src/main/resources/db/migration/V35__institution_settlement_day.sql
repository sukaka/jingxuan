ALTER TABLE `crm_institution`
  ADD COLUMN `settlement_day` VARCHAR(16) NULL COMMENT '机构月结结算日：1-28/LAST_DAY；NULL继承租户默认' AFTER `settlement_type`,
  ADD CONSTRAINT `chk_institution_settlement_day` CHECK (
    `settlement_day` IS NULL OR `settlement_day` = 'LAST_DAY'
    OR `settlement_day` REGEXP '^([1-9]|1[0-9]|2[0-8])$'
  );

CREATE TABLE IF NOT EXISTS `crm_institution_settlement_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离，每租户一条',
  `default_settlement_day` VARCHAR(16) NOT NULL COMMENT '租户默认月结结算日：1-28/LAST_DAY',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '最近修改人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_settlement_config_tenant` (`tenant_id`),
  CONSTRAINT `chk_institution_default_settlement_day` CHECK (
    `default_settlement_day` = 'LAST_DAY'
    OR `default_settlement_day` REGEXP '^([1-9]|1[0-9]|2[0-8])$'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户月结默认结算日；未插入配置时应用内置LAST_DAY';

ALTER TABLE `crm_settlement_batch`
  ADD COLUMN `settlement_day` VARCHAR(16) NULL COMMENT '入批时实际使用的结算日规则快照：1-28/LAST_DAY' AFTER `period_key`,
  ADD COLUMN `settlement_day_source` VARCHAR(16) NULL COMMENT '结算日来源快照：DEFAULT/INSTITUTION' AFTER `settlement_day`,
  ADD COLUMN `settlement_date` DATE NULL COMMENT '按Asia/Shanghai业务日历计算的批次实际结算日期' AFTER `settlement_day_source`,
  ADD CONSTRAINT `chk_settlement_batch_day` CHECK (
    (`settlement_day` IS NULL AND `settlement_day_source` IS NULL AND `settlement_date` IS NULL)
    OR (`settlement_day` IS NOT NULL AND `settlement_day_source` IS NOT NULL AND `settlement_date` IS NOT NULL
      AND (`settlement_day` REGEXP '^([1-9]|1[0-9]|2[0-8])$' OR `settlement_day` = 'LAST_DAY')
      AND `settlement_day_source` IN ('DEFAULT','INSTITUTION'))
  ),
  ADD CONSTRAINT `chk_settlement_batch_per_redemption_day` CHECK (
    `settlement_type` <> 'PER_REDEMPTION'
    OR (`settlement_day` IS NULL AND `settlement_day_source` IS NULL AND `settlement_date` IS NULL)
  );
